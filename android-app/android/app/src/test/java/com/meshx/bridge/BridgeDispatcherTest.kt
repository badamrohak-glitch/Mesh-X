package com.meshx.bridge

import com.meshx.bridge.handlers.NativeRuntimeInfo
import com.meshx.bridge.handlers.PingHandler
import com.meshx.bridge.handlers.RuntimeInfoHandler
import com.meshx.bridge.handlers.RuntimeInfoProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeDispatcherTest {

  private fun createDispatcher(): BridgeDispatcher {
    lateinit var dispatcher: BridgeDispatcher
    val fakeInfo = object : RuntimeInfoProvider {
      override fun readRuntimeInfo() = NativeRuntimeInfo(
        protocolVersion = 1,
        platform = "android",
        manufacturer = "Google",
        model = "Pixel 8",
        androidRelease = "15",
        sdkInt = 35,
        appId = "com.meshx",
        appVersionName = "1.0",
        appVersionCode = 1L,
        isDebugBuild = true,
      )
    }
    dispatcher = BridgeDispatcher(
      handlers = listOf(
        PingHandler(clock = { 2000L }, uptimeProvider = { 5000L }),
        RuntimeInfoHandler(
          provider = fakeInfo,
          availableCommandsProvider = { dispatcher.registeredCommands },
        ),
      ),
      clock = { 2000L },
    )
    return dispatcher
  }

  @Test
  fun pingHandlerReturnsPongAndEcho() = runTest {
    val dispatcher = createDispatcher()
    val req = """{"version":1,"kind":"request","requestId":"req-1","type":"BRIDGE_PING","payload":{"echo":"hello"},"timestamp":1000}"""

    val res = JSONObject(dispatcher.dispatch(req))
    assertTrue(res.getBoolean("ok"))
    assertEquals("req-1", res.getString("requestId"))
    assertEquals("BRIDGE_PING", res.getString("type"))
    val data = res.getJSONObject("data")
    assertTrue(data.getBoolean("pong"))
    assertEquals("hello", data.getString("echo"))
    assertEquals(1000L, data.getLong("receivedAtMs"))
    assertEquals(2000L, data.getLong("nativeTimestampMs"))
    assertEquals(5000L, data.getLong("uptimeMs"))
  }

  @Test
  fun runtimeInfoHandlerReturnsDeviceMetadata() = runTest {
    val dispatcher = createDispatcher()
    val req = """{"version":1,"kind":"request","requestId":"req-2","type":"GET_RUNTIME_INFO","payload":{},"timestamp":1500}"""

    val res = JSONObject(dispatcher.dispatch(req))
    assertTrue(res.getBoolean("ok"))
    val data = res.getJSONObject("data")
    assertEquals("android", data.getString("platform"))
    assertEquals("Google", data.getString("manufacturer"))
    assertEquals("Pixel 8", data.getString("model"))
    assertEquals(35, data.getInt("sdkInt"))
    assertEquals(2, data.getJSONArray("availableCommands").length())
  }

  @Test
  fun invalidJsonReturnsStructuredError() = runTest {
    val dispatcher = createDispatcher()
    val res = JSONObject(dispatcher.dispatch("{not-json"))
    assertFalse(res.getBoolean("ok"))
    assertEquals("INVALID_JSON", res.getJSONObject("error").getString("code"))
  }

  @Test
  fun unsupportedVersionReturnsStructuredError() = runTest {
    val dispatcher = createDispatcher()
    val req = """{"version":99,"kind":"request","requestId":"req-3","type":"BRIDGE_PING","payload":{},"timestamp":1000}"""
    val res = JSONObject(dispatcher.dispatch(req))
    assertFalse(res.getBoolean("ok"))
    assertEquals("req-3", res.getString("requestId"))
    assertEquals("UNSUPPORTED_VERSION", res.getJSONObject("error").getString("code"))
  }

  @Test
  fun unknownCommandReturnsStructuredError() = runTest {
    val dispatcher = createDispatcher()
    val req = """{"version":1,"kind":"request","requestId":"req-4","type":"READ_ALL_CONTACTS","payload":{},"timestamp":1000}"""
    val res = JSONObject(dispatcher.dispatch(req))
    assertFalse(res.getBoolean("ok"))
    assertEquals("UNKNOWN_COMMAND", res.getJSONObject("error").getString("code"))
  }

  @Test
  fun invalidEchoTypeReturnsInvalidPayload() = runTest {
    val dispatcher = createDispatcher()
    val req = """{"version":1,"kind":"request","requestId":"req-5","type":"BRIDGE_PING","payload":{"echo":12345},"timestamp":1000}"""
    val res = JSONObject(dispatcher.dispatch(req))
    assertFalse(res.getBoolean("ok"))
    assertEquals("INVALID_PAYLOAD", res.getJSONObject("error").getString("code"))
  }

  @Test
  fun missingRequestIdReturnsInvalidEnvelope() = runTest {
    val dispatcher = createDispatcher()
    val req = """{"version":1,"kind":"request","type":"BRIDGE_PING","payload":{},"timestamp":1000}"""
    val res = JSONObject(dispatcher.dispatch(req))
    assertFalse(res.getBoolean("ok"))
    assertEquals("INVALID_ENVELOPE", res.getJSONObject("error").getString("code"))
  }
}
