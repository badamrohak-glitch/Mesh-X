package com.meshx.bridge.handlers

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.meshx.bridge.BridgeCommandHandler
import com.meshx.bridge.BridgeCommandException
import com.meshx.bridge.BridgeErrorCode
import com.meshx.bridge.BridgeRequest
import com.meshx.bridge.BridgeResponse
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.UUID

/**
 * Handles device identity commands.
 *
 * Commands:
 *   - GET_IDENTITY : returns JSON with a persistent deviceId, the public key (Base64), and algorithm.
 */
class IdentityHandler(private val context: Context) : BridgeCommandHandler {
    override val commandType: String = "GET_IDENTITY"

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences("meshx_identity", Context.MODE_PRIVATE)
    }

    private fun getDeviceId(): String {
        var id = prefs.getString("device_id", null)
        if (id == null) {
            id = UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
        }
        return id
    }

    private fun getKeyPair(): java.security.KeyPair {
        val alias = "meshx_device_key"
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        if (!ks.containsAlias(alias)) {
            // Generate EC P-256 key pair
            val kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
            val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                .setUserAuthenticationRequired(false)
                .build()
            kpg.initialize(spec)
            kpg.generateKeyPair()
        }
        val priv = ks.getKey(alias, null) as java.security.PrivateKey
        val pub = ks.getCertificate(alias).publicKey
        return java.security.KeyPair(pub, priv)
    }

    private fun getPublicKeyBase64(): String {
        val kp = getKeyPair()
        val encoded = kp.public.encoded
        return Base64.encodeToString(encoded, Base64.NO_WRAP)
    }

    override suspend fun handle(request: BridgeRequest): JSONObject {
        // No payload needed for identity request
        val response = JSONObject()
        response.put("deviceId", getDeviceId())
        response.put("publicKey", getPublicKeyBase64())
        response.put("algorithm", "EC")
        return response
    }
}
