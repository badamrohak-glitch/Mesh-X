package com.meshx.bridge.handlers

import com.meshx.ble.BleScanner
import com.meshx.bridge.BridgeCommandException
import com.meshx.bridge.BridgeCommandHandler
import com.meshx.bridge.BridgeErrorCode
import com.meshx.bridge.BridgeRequest
import org.json.JSONObject

class DiscoveryHandler(
    override val commandType: String,
) : BridgeCommandHandler {

    override suspend fun handle(request: BridgeRequest): JSONObject {
        return when (commandType) {
            "GET_DISCOVERY_CAPABILITIES" -> {
                BleScanner.getCapabilities()
            }

            "START_DISCOVERY" -> {
                val lowPower = request.payload.optBoolean("lowPowerMode", false)
                val result = BleScanner.startDiscovery(lowPowerMode = lowPower)
                if (!result.ok) {
                    val code = when (result.errorCode) {
                        "INVALID_PAYLOAD" -> BridgeErrorCode.INVALID_PAYLOAD
                        else -> BridgeErrorCode.INTERNAL_ERROR
                    }
                    throw BridgeCommandException(
                        code = code,
                        message = result.message ?: "Unable to start BLE discovery.",
                    )
                }

                JSONObject().apply {
                    put("started", true)
                    put("advertising", result.advertising)
                    put("scanning", result.scanning)
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

            "CONNECT_PEER" -> {
                val address = request.payload.optString("address").trim()
                if (address.isEmpty()) {
                    throw BridgeCommandException(
                        code = BridgeErrorCode.INVALID_PAYLOAD,
                        message = "Field 'address' is required to connect to a peer.",
                    )
                }
                val initiated = BleScanner.connectPeer(address)
                if (!initiated) {
                    throw BridgeCommandException(
                        code = BridgeErrorCode.INTERNAL_ERROR,
                        message = "Unable to initiate BLE GATT connection to peer.",
                    )
                }
                JSONObject().apply {
                    put("connecting", true)
                    put("address", address)
                }
            }

            "DISCONNECT_PEER" -> {
                val address = request.payload.optString("address").trim()
                if (address.isEmpty()) {
                    throw BridgeCommandException(
                        code = BridgeErrorCode.INVALID_PAYLOAD,
                        message = "Field 'address' is required to disconnect a peer.",
                    )
                }
                val disconnected = BleScanner.disconnectPeer(address)
                JSONObject().apply {
                    put("disconnected", disconnected)
                    put("address", address)
                }
            }

            else -> {
                throw BridgeCommandException(
                    code = BridgeErrorCode.UNKNOWN_COMMAND,
                    message = "Unsupported discovery command: $commandType",
                )
            }
        }
    }
}
