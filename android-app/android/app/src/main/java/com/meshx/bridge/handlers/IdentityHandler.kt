package com.meshx.bridge.handlers

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.meshx.bridge.BridgeCommandException
import com.meshx.bridge.BridgeCommandHandler
import com.meshx.bridge.BridgeErrorCode
import com.meshx.bridge.BridgeRequest
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID

data class DeviceIdentity(
    val deviceId: String,
    val publicKey: String,
    val algorithm: String,
)

/**
 * Abstraction for loading or creating the persistent device identity and P-256 public key.
 * Keeping this behind an interface allows JVM unit tests to verify handler behavior without Android Keystore.
 */
interface DeviceIdentityProvider {
    fun getOrCreateIdentity(): DeviceIdentity
}

/**
 * Persists a random device UUID in private SharedPreferences and generates a non-exportable
 * EC P-256 (secp256r1) key pair inside Android Keystore. The private key never leaves Keystore.
 */
class AndroidDeviceIdentityProvider(
    private val context: Context,
) : DeviceIdentityProvider {

    private val lock = Any()

    private val prefs: SharedPreferences by lazy {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun getOrCreateIdentity(): DeviceIdentity = synchronized(lock) {
        val deviceId = getOrCreateDeviceId()
        val publicKey = getOrCreatePublicKeyBase64()
        return DeviceIdentity(
            deviceId = deviceId,
            publicKey = publicKey,
            algorithm = KEY_ALGORITHM,
        )
    }

    private fun getOrCreateDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)?.trim()
        if (!existing.isNullOrEmpty()) {
            return existing
        }

        val generated = UUID.randomUUID().toString()
        val committed = prefs.edit().putString(KEY_DEVICE_ID, generated).commit()
        if (!committed) {
            throw BridgeCommandException(
                code = BridgeErrorCode.INTERNAL_ERROR,
                message = "Failed to persist device identity.",
            )
        }
        return generated
    }

    private fun getOrCreatePublicKeyBase64(): String {
        return try {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
            ks.load(null)

            if (!ks.containsAlias(KEY_ALIAS)) {
                val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE)
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                    .setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE_NAME))
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setUserAuthenticationRequired(false)
                    .build()
                kpg.initialize(spec)
                kpg.generateKeyPair()
            }

            // Confirm the private key handle exists in Keystore without ever exporting or returning it.
            val privateKeyHandle = ks.getKey(KEY_ALIAS, null) as? PrivateKey
                ?: throw BridgeCommandException(
                    code = BridgeErrorCode.INTERNAL_ERROR,
                    message = "Device private key is missing from Android Keystore.",
                )
            check(privateKeyHandle.algorithm.isNotBlank())

            val publicKey = ks.getCertificate(KEY_ALIAS)?.publicKey
                ?: throw BridgeCommandException(
                    code = BridgeErrorCode.INTERNAL_ERROR,
                    message = "Device public key certificate is missing from Android Keystore.",
                )

            val encoded = publicKey.encoded
            if (encoded == null || encoded.isEmpty()) {
                throw BridgeCommandException(
                    code = BridgeErrorCode.INTERNAL_ERROR,
                    message = "Device public key could not be encoded.",
                )
            }

            Base64.encodeToString(encoded, Base64.NO_WRAP)
        } catch (e: BridgeCommandException) {
            throw e
        } catch (_: GeneralSecurityException) {
            throw BridgeCommandException(
                code = BridgeErrorCode.INTERNAL_ERROR,
                message = "Android Keystore failed to initialize or read the device key pair.",
            )
        } catch (_: IllegalStateException) {
            throw BridgeCommandException(
                code = BridgeErrorCode.INTERNAL_ERROR,
                message = "Device cryptographic identity is in an invalid state.",
            )
        }
    }

    companion object {
        const val PREFS_NAME = "meshx_identity"
        const val KEY_DEVICE_ID = "device_id"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "meshx_device_key"
        const val KEY_ALGORITHM = "EC"
        const val EC_CURVE_NAME = "secp256r1"
    }
}

/**
 * Handles device identity commands.
 *
 * Commands:
 *   - GET_IDENTITY : returns JSON with a persistent deviceId, the public key (Base64), and algorithm.
 */
class IdentityHandler(
    private val provider: DeviceIdentityProvider,
) : BridgeCommandHandler {

    constructor(context: Context) : this(AndroidDeviceIdentityProvider(context.applicationContext))

    override val commandType: String = "GET_IDENTITY"

    override suspend fun handle(request: BridgeRequest): JSONObject {
        val identity = provider.getOrCreateIdentity()
        if (identity.deviceId.isBlank() || identity.publicKey.isBlank() || identity.algorithm.isBlank()) {
            throw BridgeCommandException(
                code = BridgeErrorCode.INTERNAL_ERROR,
                message = "Resolved device identity is incomplete.",
            )
        }

        return JSONObject().apply {
            put("deviceId", identity.deviceId)
            put("publicKey", identity.publicKey)
            put("algorithm", identity.algorithm)
        }
    }
}
