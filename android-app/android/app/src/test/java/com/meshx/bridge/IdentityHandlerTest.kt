package com.meshx.bridge

import com.meshx.bridge.handlers.AndroidDeviceIdentityProvider
import com.meshx.bridge.handlers.DeviceIdentity
import com.meshx.bridge.handlers.DeviceIdentityProvider
import com.meshx.bridge.handlers.IdentityHandler
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

class IdentityHandlerTest {

  private class InMemoryPersistentIdentityProvider : DeviceIdentityProvider {
    private var cached: DeviceIdentity? = null

    override fun getOrCreateIdentity(): DeviceIdentity {
      cached?.let { return it }

      val kpg = KeyPairGenerator.getInstance("EC")
      kpg.initialize(ECGenParameterSpec(AndroidDeviceIdentityProvider.EC_CURVE_NAME))
      val keyPair = kpg.generateKeyPair()
      val publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.public.encoded)

      val created = DeviceIdentity(
        deviceId = UUID.randomUUID().toString(),
        publicKey = publicKeyBase64,
        algorithm = AndroidDeviceIdentityProvider.KEY_ALGORITHM,
      )
      cached = created
      return created
    }
  }

  @Test
  fun getIdentityIsRegisteredAndReturnsValidP256PublicKeyWithoutPrivateKey() = runTest {
    val provider = InMemoryPersistentIdentityProvider()
    val dispatcher = BridgeDispatcher(
      handlers = listOf(IdentityHandler(provider)),
      clock = { 3000L },
    )

    assertTrue(dispatcher.registeredCommands.contains("GET_IDENTITY"))

    val req = """{"version":1,"kind":"request","requestId":"req-id-1","type":"GET_IDENTITY","payload":{},"timestamp":2500}"""
    val rawResponse = dispatcher.dispatch(req)
    val res = JSONObject(rawResponse)

    assertTrue(res.getBoolean("ok"))
    assertEquals("req-id-1", res.getString("requestId"))
    assertEquals("GET_IDENTITY", res.getString("type"))

    val data = res.getJSONObject("data")
    assertEquals("EC", data.getString("algorithm"))
    assertFalse(data.has("privateKey"))
    assertFalse(rawResponse.contains("PRIVATE KEY"))

    val deviceId = data.getString("deviceId")
    assertNotNull(UUID.fromString(deviceId))

    val encodedBytes = Base64.getDecoder().decode(data.getString("publicKey"))
    val decodedKey = KeyFactory.getInstance("EC")
      .generatePublic(X509EncodedKeySpec(encodedBytes)) as ECPublicKey
    assertEquals(256, decodedKey.params.curve.field.fieldSize)
  }

  @Test
  fun identityPersistsAcrossRepeatedDispatchesAndHandlerInstances() = runTest {
    val sharedStore = InMemoryPersistentIdentityProvider()
    val firstDispatcher = BridgeDispatcher(
      handlers = listOf(IdentityHandler(sharedStore)),
      clock = { 1000L },
    )
    val secondDispatcher = BridgeDispatcher(
      handlers = listOf(IdentityHandler(sharedStore)),
      clock = { 2000L },
    )

    val req1 = """{"version":1,"kind":"request","requestId":"req-id-a","type":"GET_IDENTITY","payload":{},"timestamp":100}"""
    val req2 = """{"version":1,"kind":"request","requestId":"req-id-b","type":"GET_IDENTITY","payload":{},"timestamp":200}"""

    val res1 = JSONObject(firstDispatcher.dispatch(req1)).getJSONObject("data")
    val res2 = JSONObject(secondDispatcher.dispatch(req2)).getJSONObject("data")

    assertEquals(res1.getString("deviceId"), res2.getString("deviceId"))
    assertEquals(res1.getString("publicKey"), res2.getString("publicKey"))
    assertEquals(res1.getString("algorithm"), res2.getString("algorithm"))
  }

  @Test
  fun providerFailureReturnsStructuredBridgeError() = runTest {
    val failingProvider = object : DeviceIdentityProvider {
      override fun getOrCreateIdentity(): DeviceIdentity {
        throw BridgeCommandException(
          code = BridgeErrorCode.INTERNAL_ERROR,
          message = "Android Keystore failed to initialize or read the device key pair.",
        )
      }
    }
    val dispatcher = BridgeDispatcher(
      handlers = listOf(IdentityHandler(failingProvider)),
      clock = { 3000L },
    )

    val req = """{"version":1,"kind":"request","requestId":"req-err-1","type":"GET_IDENTITY","payload":{},"timestamp":100}"""
    val res = JSONObject(dispatcher.dispatch(req))

    assertFalse(res.getBoolean("ok"))
    assertEquals("INTERNAL_ERROR", res.getJSONObject("error").getString("code"))
    assertEquals(
      "Android Keystore failed to initialize or read the device key pair.",
      res.getJSONObject("error").getString("message"),
    )
  }

  @Test
  fun blankIdentityFieldsAreRejectedSafely() = runTest {
    val blankProvider = object : DeviceIdentityProvider {
      override fun getOrCreateIdentity(): DeviceIdentity {
        return DeviceIdentity(deviceId = "", publicKey = "   ", algorithm = "EC")
      }
    }
    val dispatcher = BridgeDispatcher(
      handlers = listOf(IdentityHandler(blankProvider)),
      clock = { 3000L },
    )

    val req = """{"version":1,"kind":"request","requestId":"req-err-2","type":"GET_IDENTITY","payload":{},"timestamp":100}"""
    val res = JSONObject(dispatcher.dispatch(req))

    assertFalse(res.getBoolean("ok"))
    assertEquals("INTERNAL_ERROR", res.getJSONObject("error").getString("code"))
  }
}
