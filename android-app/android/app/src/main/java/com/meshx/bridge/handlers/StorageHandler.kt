package com.meshx.bridge.handlers

import android.content.Context
import com.meshx.bridge.BridgeCommandHandler
import com.meshx.bridge.BridgeRequest
import com.meshx.storage.MeshLocalStore
import org.json.JSONObject

class StorageHandler(
    private val context: Context,
) : BridgeCommandHandler {
    override val commandType: String = "GET_LOCAL_STORAGE_STATS"

    override suspend fun handle(request: BridgeRequest): JSONObject {
        val store = MeshLocalStore.getInstance(context.applicationContext)
        val stats = store.getStats()
        return JSONObject().apply {
            put("peerCount", stats.peerCount)
            put("messageCount", stats.messageCount)
            put("relayQueueCount", stats.relayQueueCount)
            put("peers", store.getPeersJson())
        }
    }
}
