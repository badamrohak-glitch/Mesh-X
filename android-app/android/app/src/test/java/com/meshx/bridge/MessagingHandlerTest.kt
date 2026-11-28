
package com.meshx.bridge.handlers

import android.content.Context
import com.meshx.bridge.BridgeContract
import com.meshx.bridge.BridgeRequest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MessagingHandlerTest {

    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Test
    fun sendAndRetrieveMessage() = runTest {
        val peerId = "peer-${UUID.randomUUID()}"

        val sendHandler = MessagingHandler(context, "SEND_MESSAGE")

        val sendRequest = BridgeRequest(
            version = BridgeContract.PROTOCOL_VERSION,
            requestId = "req-send-1",
            type = "SEND_MESSAGE",
            payload = org.json.JSONObject().apply {
                put("peerDeviceId", peerId)
                put("body", "Hello MeshX!")
            },
            timestamp = System.currentTimeMillis(),
        )

        val sendResponse = sendHandler.handle(sendRequest)
        val messageId = sendResponse.getString("messageId")

        assertTrue(messageId.isNotBlank())

        val getHandler = MessagingHandler(context, "GET_MESSAGES")

        val getRequest = BridgeRequest(
            version = BridgeContract.PROTOCOL_VERSION,
            requestId = "req-get-1",
            type = "GET_MESSAGES",
            payload = org.json.JSONObject().apply {
                put("peerDeviceId", peerId)
            },
            timestamp = System.currentTimeMillis(),
        )

        val getResponse = getHandler.handle(getRequest)
        val messages = getResponse.getJSONArray("messages")

        assertEquals(1, messages.length())

        val message = messages.getJSONObject(0)
        assertEquals(messageId, message.getString("messageId"))
        assertEquals("Hello MeshX!", message.getString("body"))
        assertEquals("outgoing", message.getString("direction"))
        assertEquals("sent", message.getString("status"))
    }
}
