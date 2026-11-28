package com.meshx.bridge

import com.meshx.bridge.handlers.DiscoveryHandler
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryHandlerTest {

  @Test
  fun connectPeerRequiresAddress() = runTest {
    val dispatcher = BridgeDispatcher(
      handlers = listOf(DiscoveryHandler("CONNECT_PEER")),
      clock = { 1000L },
    )

    val req = """{"version":1,"kind":"request","requestId":"req-conn-1","type":"CONNECT_PEER","payload":{},"timestamp":500}"""
    val res = JSONObject(dispatcher.dispatch(req))

    assertFalse(res.getBoolean("ok"))
    assertEquals("INVALID_PAYLOAD", res.getJSONObject("error").getString("code"))
  }

  @Test
  fun disconnectPeerRequiresAddress() = runTest {
    val dispatcher = BridgeDispatcher(
      handlers = listOf(DiscoveryHandler("DISCONNECT_PEER")),
      clock = { 1000L },
    )

    val req = """{"version":1,"kind":"request","requestId":"req-disc-1","type":"DISCONNECT_PEER","payload":{"address":"  "},"timestamp":500}"""
    val res = JSONObject(dispatcher.dispatch(req))

    assertFalse(res.getBoolean("ok"))
    assertEquals("INVALID_PAYLOAD", res.getJSONObject("error").getString("code"))
  }

  @Test
  fun discoveryCommandsAreRegisteredInDispatcher() {
    val dispatcher = BridgeDispatcher(
      handlers = listOf(
        DiscoveryHandler("GET_DISCOVERY_CAPABILITIES"),
        DiscoveryHandler("START_DISCOVERY"),
        DiscoveryHandler("STOP_DISCOVERY"),
        DiscoveryHandler("GET_NEARBY_DEVICES"),
        DiscoveryHandler("CONNECT_PEER"),
        DiscoveryHandler("DISCONNECT_PEER"),
      ),
    )

    assertTrue(dispatcher.registeredCommands.contains("GET_DISCOVERY_CAPABILITIES"))
    assertTrue(dispatcher.registeredCommands.contains("CONNECT_PEER"))
    assertTrue(dispatcher.registeredCommands.contains("DISCONNECT_PEER"))
  }
}
