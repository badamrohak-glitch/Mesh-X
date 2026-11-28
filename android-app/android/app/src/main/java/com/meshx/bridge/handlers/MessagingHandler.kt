package com.meshx.bridge.handlers

import com.meshx.ble.BleScanner
import com.meshx.storage.MeshLocalStore

import com.meshx.bridge.BridgeCommandException
import com.meshx.bridge.BridgeErrorCode
import com.meshx.bridge.BridgeRequest
import com.meshx.bridge.BridgeCommandHandler
import com.meshx.bridge.BridgeEventEmitter

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Handles simple peer‑to‑peer messaging storage.
 *
 * Commands:
 *   - SEND_MESSAGE   { peerDeviceId: String, body: String }
 *   - GET_MESSAGES   { peerDeviceId: String }
 */
class MessagingHandler(
    private val context: android.content.Context,
    private val command: String,
) : BridgeCommandHandler {
    override val commandType: String = command
    private val store = MeshLocalStore.getInstance(context)
    private val mutex = Mutex()

    override suspend fun handle(request: BridgeRequest): JSONObject {
        val payload = request.payload
        return when (request.type) {
            "SEND_MESSAGE" -> handleSendMessage(request.requestId, payload)
            "GET_MESSAGES" -> handleGetMessages(request.requestId, payload)
            else -> throw BridgeCommandException(
                code = BridgeErrorCode.UNKNOWN_COMMAND,
                message = "Command '${request.type}' not supported by MessagingHandler."
            )
        }
    }

    private suspend fun handleSendMessage(requestId: String, payload: JSONObject): JSONObject {
        val peerId = payload.optString("peerDeviceId").trim()
        val body = payload.optString("body").trim()
        if (peerId.isEmpty() || body.isEmpty()) {
            throw BridgeCommandException(
                code = BridgeErrorCode.INVALID_PAYLOAD,
                message = "Both 'peerDeviceId' and 'body' must be non‑empty strings."
            )
        }
        // Generate a stable message ID
        val messageId = "msg_${UUID.randomUUID()}"
        val timestamp = System.currentTimeMillis()
        // Store the message atomically
        mutex.withLock {
            store.saveMessage(
                messageId = messageId,
                peerDeviceId = peerId,
                direction = "outgoing",
                body = body,
                status = "sent",
                createdAt = timestamp,
            )
        }
        // Transmit over Bluetooth LE mesh
        try {
            BleScanner.sendMeshMessage(peerId, messageId, body)
        } catch (_: Throwable) {
            // Non-fatal if Bluetooth is temporarily unavailable
        }
        // Emit a native event so the UI can react in real time
        BridgeEventEmitter.emit(
            type = "MESSAGE_SENT",
            payload = JSONObject().apply {
                put("messageId", messageId)
                put("peerDeviceId", peerId)
                put("body", body)
                put("timestamp", timestamp)
            }
        )
        return JSONObject().apply {
            put("messageId", messageId)
            put("timestamp", timestamp)
        }
    }

    private suspend fun handleGetMessages(requestId: String, payload: JSONObject): JSONObject {
        val peerId = payload.optString("peerDeviceId").trim()
        if (peerId.isEmpty()) {
            throw BridgeCommandException(
                code = BridgeErrorCode.INVALID_PAYLOAD,
                message = "'peerDeviceId' must be a non‑empty string."
            )
        }
        val db = store.readableDatabase
        val cursor = db.rawQuery(
            "SELECT message_id, direction, body, status, created_at FROM messages WHERE peer_device_id = ? ORDER BY created_at ASC",
            arrayOf(peerId)
        )
        val arr = JSONArray()
        cursor.use { cur ->
            while (cur.moveToNext()) {
                val obj = JSONObject().apply {
                    put("messageId", cur.getString(0))
                    put("direction", cur.getString(1))
                    put("body", cur.getString(2))
                    put("status", cur.getString(3))
                    put("timestamp", cur.getLong(4))
                }
                arr.put(obj)
            }
        }
        return JSONObject().apply { put("messages", arr) }
    }
}
