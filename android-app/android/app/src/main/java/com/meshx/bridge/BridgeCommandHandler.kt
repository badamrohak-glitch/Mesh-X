package com.meshx.bridge

import org.json.JSONObject

class BridgeCommandException(
  val code: BridgeErrorCode,
  override val message: String,
  val details: JSONObject? = null,
) : Exception(message)

interface BridgeCommandHandler {
  val commandType: String
  suspend fun handle(request: BridgeRequest): JSONObject
}

class BridgeDispatcher(
  handlers: List<BridgeCommandHandler>,
  private val clock: () -> Long = { System.currentTimeMillis() },
) {
  private val handlerMap: Map<String, BridgeCommandHandler> =
    handlers.associateBy { it.commandType }

  val registeredCommands: List<String>
    get() = handlerMap.keys.sorted()

  suspend fun dispatch(rawJson: String): String {
    val validation = BridgeEnvelopeValidator.validate(rawJson)
    if (validation is EnvelopeValidationResult.Invalid) {
      return BridgeResponse(
        requestId = validation.requestId,
        type = validation.type,
        ok = false,
        error = validation.error,
        timestamp = clock(),
      ).toJsonString()
    }

    val request = (validation as EnvelopeValidationResult.Valid).request
    val handler = handlerMap[request.type]
      ?: return BridgeResponse(
        requestId = request.requestId,
        type = request.type,
        ok = false,
        error = BridgeError(
          code = BridgeErrorCode.UNKNOWN_COMMAND,
          message = "Command '${request.type}' is not registered on this bridge.",
        ),
        timestamp = clock(),
      ).toJsonString()

    return try {
      val data = handler.handle(request)
      BridgeResponse(
        requestId = request.requestId,
        type = request.type,
        ok = true,
        data = data,
        timestamp = clock(),
      ).toJsonString()
    } catch (e: BridgeCommandException) {
      BridgeResponse(
        requestId = request.requestId,
        type = request.type,
        ok = false,
        error = BridgeError(
          code = e.code,
          message = e.message,
          details = e.details,
        ),
        timestamp = clock(),
      ).toJsonString()
    } catch (e: Throwable) {
      BridgeResponse(
        requestId = request.requestId,
        type = request.type,
        ok = false,
        error = BridgeError(
          code = BridgeErrorCode.INTERNAL_ERROR,
          message = e.message ?: "Unexpected native error while handling '${request.type}'.",
        ),
        timestamp = clock(),
      ).toJsonString()
    }
  }
}
