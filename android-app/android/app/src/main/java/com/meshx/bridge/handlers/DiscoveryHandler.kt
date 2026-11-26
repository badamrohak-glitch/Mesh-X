package com.meshx.bridge.handlers

import com.meshx.bridge.BridgeCommandHandler
import com.meshx.bridge.BridgeCommandException
import com.meshx.bridge.BridgeErrorCode
import com.meshx.bridge.BridgeRequest
import com.meshx.ble.BleScanner
import org.json.JSONObject

class DiscoveryHandler(
    override val commandType: String
) : BridgeCommandHandler {

    override suspend fun handle(request: BridgeRequest): JSONObject {
        return when (commandType) {
            "START_DISCOVERY" -> {
                BleScanner.startAdvertising()
                BleScanner.startScanning()
                JSONObject().apply {
                    put("started", true)
                }
            }

            "STOP_DISCOVERY" -> {
                BleScanner.stopScanning()
                BleScanner.stopAdvertising()
                JSONObject().apply {
                    put("stopped", true)
                }
            }

            "GET_NEARBY_DEVICES" -> {
                val devices = BleScanner.getNearbyDevices()
                JSONObject().apply {
                    put("devices", devices)
                }
            }

            else -> {
                throw BridgeCommandException(
                    code = BridgeErrorCode.UNKNOWN_COMMAND,
                    message = "Unsupported discovery command: $commandType"
                )
            }
        }
    }
}
