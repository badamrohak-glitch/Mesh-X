
package com.meshx.bridge.handlers

import android.content.Context
import com.meshx.background.MeshForegroundService
import com.meshx.ble.BleScanner
import com.meshx.bridge.BridgeCommandException
import com.meshx.bridge.BridgeCommandHandler
import com.meshx.bridge.BridgeErrorCode
import com.meshx.bridge.BridgeRequest
import org.json.JSONObject

class BackgroundHandler(
    private val context: Context,
    override val commandType: String,
) : BridgeCommandHandler {

    override suspend fun handle(request: BridgeRequest): JSONObject {
        val appCtx = context.applicationContext
        return when (commandType) {
            "START_BACKGROUND_SERVICE" -> {
                val caps = BleScanner.getCapabilities()

                if (!caps.optBoolean("bleSupported", false)) {
                    throw BridgeCommandException(
                        code = BridgeErrorCode.INTERNAL_ERROR,
                        message = "Bluetooth Low Energy is not supported on this device.",
                    )
                }

                if (!caps.optBoolean("permissionsGranted", false)) {
                    throw BridgeCommandException(
                        code = BridgeErrorCode.INVALID_PAYLOAD,
                        message = "Bluetooth permissions must be granted before starting background discovery.",
                    )
                }

                if (!caps.optBoolean("bluetoothEnabled", false)) {
                    throw BridgeCommandException(
                        code = BridgeErrorCode.INVALID_PAYLOAD,
                        message = "Please enable Bluetooth before starting background discovery.",
                    )
                }

                try {
                    MeshForegroundService.start(appCtx)
                } catch (error: SecurityException) {
                    throw BridgeCommandException(
                        code = BridgeErrorCode.INTERNAL_ERROR,
                        message = "Android denied permission to start the background service. Check the required Bluetooth and foreground service permissions.",
                    )
                } catch (error: IllegalStateException) {
                    throw BridgeCommandException(
                        code = BridgeErrorCode.INTERNAL_ERROR,
                        message = "Android currently does not allow the background service to start. Please try again while the app is open.",
                    )
                }

                val status = BleScanner.getCapabilities()

                JSONObject().apply {
                    put("running", MeshForegroundService.isRunning)
                    put("enabled", MeshForegroundService.isBackgroundEnabled(appCtx))
                    put("requested", true)
                    put("scanning", status.optBoolean("scanning", false))
                    put("advertising", status.optBoolean("advertising", false))
                }
            }

            "STOP_BACKGROUND_SERVICE" -> {
                MeshForegroundService.stop(appCtx)

                JSONObject().apply {
                    put("running", false)
                    put("enabled", false)
                    put("scanning", false)
                    put("advertising", false)
                }
            }

            "GET_BACKGROUND_STATUS" -> {
                val status = BleScanner.getCapabilities()

                JSONObject().apply {
                    put("running", MeshForegroundService.isRunning)
                    put("enabled", MeshForegroundService.isBackgroundEnabled(appCtx))
                    put("pendingResume", MeshForegroundService.isPendingResume(appCtx))
                    put("scanning", status.optBoolean("scanning", false))
                    put("advertising", status.optBoolean("advertising", false))
                }
            }

            else -> throw BridgeCommandException(
                code = BridgeErrorCode.UNKNOWN_COMMAND,
                message = "Unsupported background command: $commandType",
            )
        }
    }
}
