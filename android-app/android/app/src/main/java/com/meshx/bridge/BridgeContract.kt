package com.meshx.bridge

import org.json.JSONException
import org.json.JSONObject

object BridgeContract {
  const val PROTOCOL_VERSION = 1
  const val EVENT_NAME = "MeshXNativeEvent"
  const val MAX_RAW_MESSAGE_BYTES = 65_536
  const val MAX_REQUEST_ID_LENGTH = 128
  val COMMAND_TYPE_REGEX = Regex("^[A-Z0-9_]{2,64}$")
}

enum class BridgeErrorCode {
  INVALID_JSON,
  INVALID_ENVELOPE,
  UNSUPPORTED_VERSION,
  UNKNOWN_COMMAND,
  INVALID_PAYLOAD,
  INTERNAL_ERROR,
}

data class BridgeRequest(
  val version: Int,
  val requestId: String,
  val type: String,
  val payload: JSONObject,
  val timestamp: Long,
)

data class BridgeError(
  val code: BridgeErrorCode,
  val message: String,
  val details: JSONObject? = null,
) {
  fun toJson(): JSONObject {
    val obj = JSONObject()
    obj.put("code", code.name)
    obj.put("message", message)
    if (details != null) {
      obj.put("details", details)
    }
    return obj
  }
}

data class BridgeResponse(
  val version: Int = BridgeContract.PROTOCOL_VERSION,
  val requestId: String,
  val type: String,
  val ok: Boolean,
  val data: JSONObject? = null,
  val error: BridgeError? = null,
  val timestamp: Long,
) {
  fun toJsonString(): String {
    val obj = JSONObject()
    obj.put("version", version)
    obj.put("kind", "response")
    obj.put("requestId", requestId)
    obj.put("type", type)
    obj.put("ok", ok)
    if (data != null) {
      obj.put("data", data)
    }
    if (error != null) {
      obj.put("error", error.toJson())
    }
    obj.put("timestamp", timestamp)
    return obj.toString()
  }
}

data class BridgeEvent(
  val version: Int = BridgeContract.PROTOCOL_VERSION,
  val eventId: String,
  val type: String,
  val payload: JSONObject,
  val timestamp: Long,
) {
  fun toJsonString(): String {
    val obj = JSONObject()
    obj.put("version", version)
    obj.put("kind", "event")
    obj.put("eventId", eventId)
    obj.put("type", type)
    obj.put("payload", payload)
    obj.put("timestamp", timestamp)
    return obj.toString()
  }
}

sealed class EnvelopeValidationResult {
  data class Valid(val request: BridgeRequest) : EnvelopeValidationResult()
  data class Invalid(
    val requestId: String,
    val type: String,
    val error: BridgeError,
  ) : EnvelopeValidationResult()
}

object BridgeEnvelopeValidator {
  fun validate(rawJson: String): EnvelopeValidationResult {
    if (rawJson.isBlank()) {
      return invalid(
        requestId = "unknown",
        type = "UNKNOWN",
        code = BridgeErrorCode.INVALID_JSON,
        message = "Bridge message must not be empty.",
      )
    }

    if (rawJson.toByteArray(Charsets.UTF_8).size > BridgeContract.MAX_RAW_MESSAGE_BYTES) {
      return invalid(
        requestId = "unknown",
        type = "UNKNOWN",
        code = BridgeErrorCode.INVALID_JSON,
        message = "Bridge message exceeds maximum allowed size (${BridgeContract.MAX_RAW_MESSAGE_BYTES} bytes).",
      )
    }

    val json = try {
      JSONObject(rawJson)
    } catch (e: JSONException) {
      return invalid(
        requestId = "unknown",
        type = "UNKNOWN",
        code = BridgeErrorCode.INVALID_JSON,
        message = "Malformed JSON payload: ${e.message ?: "parse error"}",
      )
    }

    val rawRequestId = (json.opt("requestId") as? String)?.trim()
    val safeRequestId =
      if (!rawRequestId.isNullOrEmpty() && rawRequestId.length <= BridgeContract.MAX_REQUEST_ID_LENGTH) {
        rawRequestId
      } else {
        "unknown"
      }

    val rawType = (json.opt("type") as? String)?.trim()
    val safeType = if (!rawType.isNullOrEmpty()) rawType else "UNKNOWN"

    val versionNum = json.opt("version") as? Number
    if (versionNum == null || versionNum.toDouble() != versionNum.toInt().toDouble()) {
      return invalid(
        requestId = safeRequestId,
        type = safeType,
        code = BridgeErrorCode.INVALID_ENVELOPE,
        message = "Field 'version' must be an integer.",
      )
    }

    val version = versionNum.toInt()
    if (version != BridgeContract.PROTOCOL_VERSION) {
      return invalid(
        requestId = safeRequestId,
        type = safeType,
        code = BridgeErrorCode.UNSUPPORTED_VERSION,
        message = "Unsupported bridge protocol version '$version'. Expected ${BridgeContract.PROTOCOL_VERSION}.",
      )
    }

    val kind = json.opt("kind") as? String
    if (kind != "request") {
      return invalid(
        requestId = safeRequestId,
        type = safeType,
        code = BridgeErrorCode.INVALID_ENVELOPE,
        message = "Field 'kind' must equal 'request'.",
      )
    }

    if (rawRequestId.isNullOrEmpty() || rawRequestId.length > BridgeContract.MAX_REQUEST_ID_LENGTH) {
      return invalid(
        requestId = safeRequestId,
        type = safeType,
        code = BridgeErrorCode.INVALID_ENVELOPE,
        message = "Field 'requestId' must be a non-empty string (1..${BridgeContract.MAX_REQUEST_ID_LENGTH} chars).",
      )
    }

    if (rawType.isNullOrEmpty() || !BridgeContract.COMMAND_TYPE_REGEX.matches(rawType)) {
      return invalid(
        requestId = rawRequestId,
        type = safeType,
        code = BridgeErrorCode.INVALID_ENVELOPE,
        message = "Field 'type' must match ${BridgeContract.COMMAND_TYPE_REGEX.pattern}.",
      )
    }

    val payload = json.opt("payload") as? JSONObject
      ?: return invalid(
        requestId = rawRequestId,
        type = rawType,
        code = BridgeErrorCode.INVALID_ENVELOPE,
        message = "Field 'payload' must be a JSON object.",
      )

    val timestampNum = json.opt("timestamp") as? Number
    if (timestampNum == null || !timestampNum.toDouble().isFinite() || timestampNum.toLong() <= 0L) {
      return invalid(
        requestId = rawRequestId,
        type = rawType,
        code = BridgeErrorCode.INVALID_ENVELOPE,
        message = "Field 'timestamp' must be a positive epoch millisecond number.",
      )
    }

    return EnvelopeValidationResult.Valid(
      BridgeRequest(
        version = version,
        requestId = rawRequestId,
        type = rawType,
        payload = payload,
        timestamp = timestampNum.toLong(),
      ),
    )
  }

  private fun invalid(
    requestId: String,
    type: String,
    code: BridgeErrorCode,
    message: String,
  ): EnvelopeValidationResult.Invalid {
    return EnvelopeValidationResult.Invalid(
      requestId = requestId,
      type = type,
      error = BridgeError(code = code, message = message),
    )
  }
}
