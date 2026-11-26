package com.meshx.bridge.handlers

import android.os.SystemClock
import com.meshx.bridge.BridgeCommandException
import com.meshx.bridge.BridgeCommandHandler
import com.meshx.bridge.BridgeErrorCode
import com.meshx.bridge.BridgeRequest
import org.json.JSONObject

class PingHandler(
  private val clock: () -> Long = { System.currentTimeMillis() },
  private val uptimeProvider: () -> Long = { SystemClock.elapsedRealtime() },
) : BridgeCommandHandler {
  override val commandType: String = "BRIDGE_PING"

  override suspend fun handle(request: BridgeRequest): JSONObject {
    var echo: String? = null
    if (request.payload.has("echo") && !request.payload.isNull("echo")) {
      val rawEcho = request.payload.opt("echo")
      if (rawEcho !is String) {
        throw BridgeCommandException(
          code = BridgeErrorCode.INVALID_PAYLOAD,
          message = "Field 'echo' must be a string.",
        )
      }
      if (rawEcho.length > MAX_ECHO_LENGTH) {
        throw BridgeCommandException(
          code = BridgeErrorCode.INVALID_PAYLOAD,
          message = "Field 'echo' must be $MAX_ECHO_LENGTH characters or fewer.",
        )
      }
      echo = rawEcho
    }

    return JSONObject().apply {
      put("pong", true)
      if (echo != null) {
        put("echo", echo)
      }
      put("receivedAtMs", request.timestamp)
      put("nativeTimestampMs", clock())
      put("uptimeMs", uptimeProvider())
    }
  }

  companion object {
    const val MAX_ECHO_LENGTH = 256
  }
}
