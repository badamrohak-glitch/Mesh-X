package com.meshx.bridge

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.meshx.background.MeshMaintenanceJobService
import com.meshx.ble.BleScanner
import com.meshx.bridge.handlers.AndroidDeviceIdentityProvider
import com.meshx.bridge.handlers.AndroidRuntimeInfoProvider
import com.meshx.bridge.handlers.BackgroundHandler
import com.meshx.bridge.handlers.DiscoveryHandler
import com.meshx.bridge.handlers.IdentityHandler
import com.meshx.bridge.handlers.PingHandler
import com.meshx.bridge.handlers.RuntimeInfoHandler
import com.meshx.bridge.handlers.StorageHandler
import com.meshx.bridge.handlers.MessagingHandler

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

class MeshXBridgeModule(
    private val reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {

    private val moduleScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val eventCounter = AtomicLong(0L)

    private lateinit var dispatcher: BridgeDispatcher

    init {
        BridgeEventEmitter.setReactContext(reactContext)
        BleScanner.initialize(reactContext)
        MeshMaintenanceJobService.schedule(reactContext.applicationContext)

        val infoProvider =
            AndroidRuntimeInfoProvider(reactContext.applicationContext)

        val handlers: List<BridgeCommandHandler> = listOf(
            PingHandler(),
            RuntimeInfoHandler(
                provider = infoProvider,
                availableCommandsProvider = { dispatcher.registeredCommands },
            ),
            DiscoveryHandler("GET_DISCOVERY_CAPABILITIES"),
            DiscoveryHandler("START_DISCOVERY"),
            DiscoveryHandler("STOP_DISCOVERY"),
            DiscoveryHandler("GET_NEARBY_DEVICES"),
            DiscoveryHandler("CONNECT_PEER"),
            DiscoveryHandler("DISCONNECT_PEER"),
            BackgroundHandler(reactContext.applicationContext, "START_BACKGROUND_SERVICE"),
            BackgroundHandler(reactContext.applicationContext, "STOP_BACKGROUND_SERVICE"),
            BackgroundHandler(reactContext.applicationContext, "GET_BACKGROUND_STATUS"),
            StorageHandler(reactContext.applicationContext),
            IdentityHandler(AndroidDeviceIdentityProvider(reactContext.applicationContext)),
            MessagingHandler(reactContext.applicationContext, "SEND_MESSAGE"),
            MessagingHandler(reactContext.applicationContext, "GET_MESSAGES"),
        )

        dispatcher = BridgeDispatcher(handlers)
    }

    override fun getName(): String = NAME

    @ReactMethod
    fun dispatchRequest(rawJson: String, promise: Promise) {
        moduleScope.launch {
            try {
                val responseJson = dispatcher.dispatch(rawJson)
                promise.resolve(responseJson)
            } catch (t: Throwable) {
                val fallback = BridgeResponse(
                    requestId = "unknown",
                    type = "UNKNOWN",
                    ok = false,
                    error = BridgeError(
                        code = BridgeErrorCode.INTERNAL_ERROR,
                        message = t.message ?: "Unexpected native bridge failure.",
                    ),
                    timestamp = System.currentTimeMillis(),
                ).toJsonString()

                promise.resolve(fallback)
            }
        }
    }

    @ReactMethod
    fun notifyWebViewLoaded() {
        val payload = JSONObject().apply {
            put("protocolVersion", BridgeContract.PROTOCOL_VERSION)
            put("platform", "android")
            put(
                "availableCommands",
                org.json.JSONArray(dispatcher.registeredCommands),
            )
        }

        emitNativeEvent("BRIDGE_READY", payload)
    }

    fun emitNativeEvent(type: String, payload: JSONObject) {
        if (!reactContext.hasActiveReactInstance()) {
            return
        }

        val now = System.currentTimeMillis()

        val event = BridgeEvent(
            eventId = "evt_${now}_${eventCounter.incrementAndGet()}",
            type = type,
            payload = payload,
            timestamp = now,
        )

        reactContext
            .getJSModule(
                DeviceEventManagerModule.RCTDeviceEventEmitter::class.java,
            )
            .emit(BridgeContract.EVENT_NAME, event.toJsonString())
    }

    override fun invalidate() {
        super.invalidate()
        moduleScope.cancel()
    }

    companion object {
        const val NAME = "MeshXBridge"
    }
}
