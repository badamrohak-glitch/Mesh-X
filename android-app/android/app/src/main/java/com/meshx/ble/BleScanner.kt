package com.meshx.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.ParcelUuid
import android.util.Log
import com.meshx.bridge.BridgeEventEmitter
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages BLE advertising and scanning for MESH-X devices.
 * Emits DEVICE_FOUND and DEVICE_LOST events.
 */
object BleScanner {

    private const val TAG = "BleScanner"
    private const val SERVICE_UUID = "0000c0de-0000-1000-8000-00805f9b34fb"
    private const val DEVICE_LOST_TIMEOUT_MS = 15_000L

    private lateinit var context: Context

    private val discoveredDevices =
        ConcurrentHashMap<String, DeviceRecord>()

    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var scanCallback: ScanCallback? = null
    private var advertiseCallback: AdvertiseCallback? = null

    private var cleanupHandler: Handler? = null

    data class DeviceRecord(
        val address: String,
        var name: String?,
        var rssi: Int,
        var lastSeen: Long
    )

    private val cleanupRunnable = object : Runnable {
        override fun run() {
            val cutoff = System.currentTimeMillis() - DEVICE_LOST_TIMEOUT_MS

            for ((address, record) in discoveredDevices) {
                if (record.lastSeen < cutoff) {
                    if (discoveredDevices.remove(address, record)) {
                        emitDeviceLost(record)
                    }
                }
            }

            cleanupHandler?.postDelayed(
                this,
                DEVICE_LOST_TIMEOUT_MS / 3
            )
        }
    }

    fun initialize(appContext: Context) {
        context = appContext.applicationContext
    }

    private fun getBluetoothAdapter(): BluetoothAdapter? {
        if (!::context.isInitialized) {
            Log.e(TAG, "BLE scanner has not been initialized")
            return null
        }

        val manager =
            context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

        return manager?.adapter
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising(deviceName: String = "MeshXDevice") {
        try {
            val bluetoothAdapter = getBluetoothAdapter() ?: return

            if (!bluetoothAdapter.isEnabled) {
                Log.w(TAG, "Bluetooth disabled - cannot start advertising")
                return
            }

            advertiser = bluetoothAdapter.bluetoothLeAdvertiser

            if (advertiser == null) {
                Log.w(TAG, "BLE advertising is not supported")
                return
            }

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(true)
                .build()

            val data = AdvertiseData.Builder()
                .addServiceUuid(
                    ParcelUuid(UUID.fromString(SERVICE_UUID))
                )
                .addManufacturerData(0x1234, deviceName.toByteArray())
                .setIncludeDeviceName(true)
                .build()

            advertiseCallback = object : AdvertiseCallback() {
                override fun onStartSuccess(
                    settingsInEffect: AdvertiseSettings
                ) {
                    Log.i(TAG, "BLE advertising started")
                }

                override fun onStartFailure(errorCode: Int) {
                    Log.e(TAG, "BLE advertising failed: $errorCode")
                }
            }

            advertiser?.startAdvertising(
                settings,
                data,
                advertiseCallback
            )
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing Bluetooth permission for advertising", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unable to start BLE advertising", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
            Log.i(TAG, "BLE advertising stopped")
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing Bluetooth permission to stop advertising", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE advertising", e)
        }

        advertiseCallback = null
    }

    /**
     * Start scanning for nearby MESH-X devices.
     */
    @SuppressLint("MissingPermission")
    fun startScanning() {
        try {
            val bluetoothAdapter = getBluetoothAdapter() ?: return

            if (!bluetoothAdapter.isEnabled) {
                Log.w(TAG, "Bluetooth disabled - cannot start scanning")
                return
            }

            scanner = bluetoothAdapter.bluetoothLeScanner

            if (scanner == null) {
                Log.w(TAG, "BLE scanning is not supported")
                return
            }

            if (scanCallback != null) {
                Log.i(TAG, "BLE scanning is already active")
                return
            }

            val callback = object : ScanCallback() {

                override fun onScanResult(
                    callbackType: Int,
                    result: ScanResult
                ) {
                    handleScanResult(result)
                }

                override fun onBatchScanResults(
                    results: List<ScanResult>
                ) {
                    for (result in results) {
                        handleScanResult(result)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.e(TAG, "BLE scan failed: $errorCode")
                }
            }

            scanCallback = callback

            val filters = listOf(
                android.bluetooth.le.ScanFilter.Builder()
                    .setServiceUuid(
                        ParcelUuid(UUID.fromString(SERVICE_UUID))
                    )
                    .build()
            )

            val settings =
                android.bluetooth.le.ScanSettings.Builder()
                    .setScanMode(
                        android.bluetooth.le.ScanSettings.SCAN_MODE_LOW_LATENCY
                    )
                    .build()

            scanner?.startScan(filters, settings, callback)

            scheduleLostDeviceCheck()

            Log.i(TAG, "BLE scanning started")

        } catch (e: SecurityException) {
            scanCallback = null
            Log.e(TAG, "Missing Bluetooth permission for scanning", e)
        } catch (e: Exception) {
            scanCallback = null
            Log.e(TAG, "Unable to start BLE scanning", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        try {
            scanCallback?.let { callback ->
                scanner?.stopScan(callback)
            }

            Log.i(TAG, "BLE scanning stopped")

        } catch (e: SecurityException) {
            Log.e(TAG, "Missing Bluetooth permission to stop scanning", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE scanning", e)
        }

        scanCallback = null

        cleanupHandler?.removeCallbacks(cleanupRunnable)
        cleanupHandler = null
    }

    /**
     * Returns the currently discovered devices.
     */
    fun getNearbyDevices(): JSONArray {
        val array = JSONArray()

        for (record in discoveredDevices.values) {
            array.put(
                JSONObject().apply {
                    put("address", record.address)
                    put("name", record.name)
                    put("rssi", record.rssi)
                    put("lastSeen", record.lastSeen)
                }
            )
        }

        return array
    }

    private fun handleScanResult(result: ScanResult) {
        try {
            val device = result.device
            val address = device.address ?: return
            val name = device.name ?: "Unknown"
            val rssi = result.rssi
            val now = System.currentTimeMillis()

            val existing = discoveredDevices[address]

            if (existing == null) {
                val record = DeviceRecord(
                    address = address,
                    name = name,
                    rssi = rssi,
                    lastSeen = now
                )

                discoveredDevices[address] = record
                emitDeviceFound(record)

                Log.i(TAG, "MESH-X device discovered: $name")

            } else {
                existing.name = name
                existing.rssi = rssi
                existing.lastSeen = now
            }

        } catch (e: SecurityException) {
            Log.e(TAG, "Bluetooth permission error while reading device", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing BLE scan result", e)
        }
    }

    private fun emitDeviceFound(record: DeviceRecord) {
        val payload = JSONObject().apply {
            put("address", record.address)
            put("name", record.name)
            put("rssi", record.rssi)
            put("timestamp", record.lastSeen)
        }

        BridgeEventEmitter.emit("DEVICE_FOUND", payload)
    }

    private fun emitDeviceLost(record: DeviceRecord) {
        val payload = JSONObject().apply {
            put("address", record.address)
            put("name", record.name)
            put("lastSeen", record.lastSeen)
        }

        BridgeEventEmitter.emit("DEVICE_LOST", payload)
    }

    private fun scheduleLostDeviceCheck() {
        if (cleanupHandler == null) {
            cleanupHandler = Handler(context.mainLooper)
        }

        cleanupHandler?.removeCallbacks(cleanupRunnable)

        cleanupHandler?.postDelayed(
            cleanupRunnable,
            DEVICE_LOST_TIMEOUT_MS / 3
        )
    }
}
