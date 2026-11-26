package com.meshx.bridge

import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.modules.core.DeviceEventManagerModule
import org.json.JSONObject

/**
 * Simple singleton used by BridgeHandlers to fire native events that are forwarded to the WebView.
 * The ReactApplicationContext is set once during MeshXBridgeModule initialization.
 */
object BridgeEventEmitter {
    @Volatile private var reactContext: ReactApplicationContext? = null

    fun setReactContext(context: ReactApplicationContext) {
        reactContext = context
    }

    /**
     * Emit an event with the given type and payload.
     * The event is wrapped in a BridgeEvent JSON string and forwarded via React Native's 
     * DeviceEventEmitter (the same channel used for BRIDGE_READY).
     */
    fun emit(type: String, payload: JSONObject) {
        val ctx = reactContext ?: return
        // Build BridgeEvent object using the same format as BridgeContract.EVENT_NAME expects
        val now = System.currentTimeMillis()
        val event = BridgeEvent(
            eventId = "evt_${now}_${System.nanoTime()}",
            type = type,
            payload = payload,
            timestamp = now
        )
        ctx
            .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
            .emit(BridgeContract.EVENT_NAME, event.toJsonString())
    }
}
