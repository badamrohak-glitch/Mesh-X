package com.meshx.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.ParcelUuid
import android.util.Base64
import android.util.Log
import com.meshx.bridge.BridgeEventEmitter
import com.meshx.bridge.handlers.AndroidDeviceIdentityProvider
import com.meshx.storage.MeshLocalStore
import com.meshx.storage.StoredPeer
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages standalone BLE advertising, scanning, GATT identity handshake, and adapter state recovery.
 * Emits DEVICE_FOUND, DEVICE_LOST, DISCOVERY_STATE_CHANGED, BLUETOOTH_STATE_CHANGED, and PEER_CONNECTION_CHANGED.
 */
object BleScanner {

    private const val TAG = "BleScanner"
    private const val SERVICE_UUID = "0000c0de-0000-1000-8000-00805f9b34fb"
    private const val IDENTITY_CHAR_UUID = "0000c0df-0000-1000-8000-00805f9b34fb"
    private const val MESH_PACKET_CHAR_UUID = "0000c0e0-0000-1000-8000-00805f9b34fb"
    private const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"
    private const val MANUFACTURER_ID = 0x1234
    private const val DEVICE_LOST_TIMEOUT_MS = 15_000L

    private lateinit var context: Context
    private var receiverRegistered = false

    private val discoveredDevices = ConcurrentHashMap<String, DeviceRecord>()
    private val activeGattClients = ConcurrentHashMap<String, BluetoothGatt>()
    private val seenPacketIds = ConcurrentHashMap.newKeySet<String>()

    private var scanner: BluetoothLeScanner? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var gattServer: BluetoothGattServer? = null
    private var scanCallback: ScanCallback? = null
    private var advertiseCallback: AdvertiseCallback? = null

    @Volatile
    private var scanningActive = false

    @Volatile
    private var advertisingActive = false
    private var cleanupHandler: Handler? = null

    data class DeviceRecord(
        val address: String,
        var name: String?,
        var rssi: Int,
        var lastSeen: Long,
        var fingerprint: String? = null,
        var deviceId: String? = null,
        var publicKey: String? = null,
        var connectionState: String = "discovered",
    )

    data class StartResult(
        val ok: Boolean,
        val errorCode: String? = null,
        val message: String? = null,
        val advertising: Boolean = false,
        val scanning: Boolean = false,
    )

    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) {
                return
            }
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            when (state) {
                BluetoothAdapter.STATE_TURNING_OFF,
                BluetoothAdapter.STATE_OFF -> {
                    stopScanning()
                    stopAdvertising()
                    closeAllGattClients()
                    BridgeEventEmitter.emit(
                        "BLUETOOTH_STATE_CHANGED",
                        JSONObject().apply { put("enabled", false) },
                    )
                    emitDiscoveryState("bluetooth_off", "Bluetooth was turned off.")
                }

                BluetoothAdapter.STATE_ON -> {
                    BridgeEventEmitter.emit(
                        "BLUETOOTH_STATE_CHANGED",
                        JSONObject().apply { put("enabled", true) },
                    )
                }
            }
        }
    }

    private val cleanupRunnable = object : Runnable {
        override fun run() {
            val cutoff = System.currentTimeMillis() - DEVICE_LOST_TIMEOUT_MS

            for ((address, record) in discoveredDevices) {
                if (record.lastSeen < cutoff && record.connectionState != "connected") {
                    if (discoveredDevices.remove(address, record)) {
                        emitDeviceLost(record)
                    }
                }
            }

            cleanupHandler?.postDelayed(this, DEVICE_LOST_TIMEOUT_MS / 3)
        }
    }

    fun initialize(appContext: Context) {
        context = appContext.applicationContext
        if (!receiverRegistered) {
            val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(bluetoothStateReceiver, filter)
            }
            receiverRegistered = true
        }
    }

    private fun getBluetoothManager(): BluetoothManager? {
        if (!::context.isInitialized) {
            Log.e(TAG, "BLE scanner has not been initialized")
            return null
        }
        return context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    }

    private fun getBluetoothAdapter(): BluetoothAdapter? = getBluetoothManager()?.adapter

    fun hasBlePermissions(): Boolean {
        if (!::context.isInitialized) {
            return false
        }
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE,
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return required.all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun isLocationEnabled(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || !::context.isInitialized) {
            return true
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }

    fun getCapabilities(): JSONObject {
        val bleSupported = ::context.isInitialized &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val adapter = getBluetoothAdapter()
        val bluetoothEnabled = adapter?.isEnabled == true
        val advertisingSupported = bluetoothEnabled && adapter?.bluetoothLeAdvertiser != null
        val permissionsGranted = hasBlePermissions()
        val locationRequired = Build.VERSION.SDK_INT < Build.VERSION_CODES.S
        val locationEnabled = isLocationEnabled()

        return JSONObject().apply {
            put("bleSupported", bleSupported)
            put("bluetoothEnabled", bluetoothEnabled)
            put("advertisingSupported", advertisingSupported)
            put("permissionsGranted", permissionsGranted)
            put("locationRequired", locationRequired)
            put("locationEnabled", locationEnabled)
            put("discoveryActive", scanningActive)
            put("scanning", scanningActive)
            put("advertising", advertisingActive)
        }
    }

    fun startDiscovery(lowPowerMode: Boolean = false): StartResult {
        if (!::context.isInitialized) {
            return StartResult(ok = false, errorCode = "INTERNAL_ERROR", message = "BLE scanner is not initialized.")
        }
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            return StartResult(ok = false, errorCode = "INTERNAL_ERROR", message = "Bluetooth Low Energy is not supported on this device.")
        }
        if (!hasBlePermissions()) {
            return StartResult(ok = false, errorCode = "INVALID_PAYLOAD", message = "Required Bluetooth permissions have not been granted.")
        }
        val adapter = getBluetoothAdapter()
        if (adapter == null || !adapter.isEnabled) {
            return StartResult(ok = false, errorCode = "INVALID_PAYLOAD", message = "Bluetooth is turned off. Please enable Bluetooth.")
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !isLocationEnabled()) {
            return StartResult(ok = false, errorCode = "INVALID_PAYLOAD", message = "Location services must be enabled for BLE scanning on Android 11 and below.")
        }

        val advertisingStarted = startAdvertising()
        val scanningStarted = startScanning(lowPowerMode = lowPowerMode)
        if (!scanningStarted) {
            return StartResult(
                ok = false,
                errorCode = "INTERNAL_ERROR",
                message = "Unable to start BLE scanner.",
                advertising = advertisingStarted,
                scanning = false,
            )
        }

        emitDiscoveryState("active", null)
        return StartResult(
            ok = true,
            advertising = advertisingStarted,
            scanning = true,
        )
    }

    @SuppressLint("MissingPermission")
    fun startAdvertising(): Boolean {
        try {
            if (!hasBlePermissions()) {
                return false
            }
            val bluetoothAdapter = getBluetoothAdapter() ?: return false
            if (!bluetoothAdapter.isEnabled) {
                Log.w(TAG, "Bluetooth disabled - cannot start advertising")
                return false
            }


            advertiser = bluetoothAdapter.bluetoothLeAdvertiser
            if (advertiser == null) {
                Log.w(TAG, "BLE advertising is not supported")
                return false
            }
            startGattServer()

            if (advertiseCallback != null) {
                return true
            }

            val fingerprintBytes = getLocalIdentityFingerprintBytes()

            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .build()

            val data = AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid(UUID.fromString(SERVICE_UUID)))
                .addManufacturerData(MANUFACTURER_ID, fingerprintBytes)
                .setIncludeDeviceName(false)
                .build()

            advertiseCallback = object : AdvertiseCallback() {
                override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                    advertisingActive = true
                    Log.i(TAG, "BLE advertising started")
                    emitDiscoveryState("advertising_active", null)
                }

                override fun onStartFailure(errorCode: Int) {
                    advertisingActive = false
                    advertiseCallback = null
                    Log.e(TAG, "BLE advertising failed: $errorCode")
                    emitDiscoveryState(
                        "advertising_error",
                        "BLE advertising failed with code $errorCode",
                    )
                }
            }

            advertiser?.startAdvertising(settings, data, advertiseCallback)
            return true
        } catch (e: SecurityException) {
            advertisingActive = false
            advertiseCallback = null
            Log.e(TAG, "Missing Bluetooth permission for advertising", e)
            return false
        } catch (e: Exception) {
            advertisingActive = false
            advertiseCallback = null
            Log.e(TAG, "Unable to start BLE advertising", e)
            return false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopAdvertising() {
        try {
            advertiseCallback?.let { cb ->
                advertiser?.stopAdvertising(cb)
                Log.i(TAG, "BLE advertising stopped")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing Bluetooth permission to stop advertising", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE advertising", e)
        }

        advertiseCallback = null
        advertisingActive = false
        stopGattServer()
    }

    @SuppressLint("MissingPermission")
    fun startScanning(lowPowerMode: Boolean = false): Boolean {
        try {
            if (!hasBlePermissions()) {
                return false
            }
            val bluetoothAdapter = getBluetoothAdapter() ?: return false
            if (!bluetoothAdapter.isEnabled) {
                Log.w(TAG, "Bluetooth disabled - cannot start scanning")
                return false
            }

            scanner = bluetoothAdapter.bluetoothLeScanner
            if (scanner == null) {
                Log.w(TAG, "BLE scanning is not supported")
                return false
            }

            if (scanCallback != null) {
                Log.i(TAG, "BLE scanning is already active")
                return true
            }

            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    handleScanResult(result)
                }

                override fun onBatchScanResults(results: List<ScanResult>) {
                    for (result in results) {
                        handleScanResult(result)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    scanningActive = false
                    scanCallback = null
                    Log.e(TAG, "BLE scan failed: $errorCode")
                    emitDiscoveryState("error", "BLE scan failed with code $errorCode")
                }
            }

            scanCallback = callback

            val filters = listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(UUID.fromString(SERVICE_UUID)))
                    .build(),
            )

            val scanMode = if (lowPowerMode) {
                ScanSettings.SCAN_MODE_BALANCED
            } else {
                ScanSettings.SCAN_MODE_LOW_LATENCY
            }

            val settings = ScanSettings.Builder()
                .setScanMode(scanMode)
                .build()

            scanner?.startScan(filters, settings, callback)
            scanningActive = true
            emitDiscoveryState("scanning_active", null)
            scheduleLostDeviceCheck()
            Log.i(TAG, "BLE scanning started")
            return true
        } catch (e: SecurityException) {
            scanningActive = false
            scanCallback = null
            Log.e(TAG, "Missing Bluetooth permission for scanning", e)
            return false
        } catch (e: Exception) {
            scanningActive = false
            scanCallback = null
            Log.e(TAG, "Unable to start BLE scanning", e)
            return false
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScanning() {
        try {
            scanCallback?.let { callback ->
                scanner?.stopScan(callback)
                Log.i(TAG, "BLE scanning stopped")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Missing Bluetooth permission to stop scanning", e)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping BLE scanning", e)
        }

        scanCallback = null
        scanningActive = false
        cleanupHandler?.removeCallbacks(cleanupRunnable)
        cleanupHandler = null
        emitDiscoveryState("stopped", null)
    }

    @SuppressLint("MissingPermission")
    fun connectPeer(address: String): Boolean {
        if (!hasBlePermissions()) {
            return false
        }
        val adapter = getBluetoothAdapter() ?: return false
        if (!adapter.isEnabled) {
            return false
        }

        val device = try {
            adapter.getRemoteDevice(address)
        } catch (_: IllegalArgumentException) {
            return false
        }

        val record = discoveredDevices[address]
        if (record != null) {
            record.connectionState = "connecting"
            emitPeerConnectionChanged(record, null)
        }

        val gattCallback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS && newState != BluetoothProfile.STATE_CONNECTED) {
                    activeGattClients.remove(address)
                    gatt.close()
                    discoveredDevices[address]?.let {
                        it.connectionState = "disconnected"
                        emitPeerConnectionChanged(it, "GATT connection failed ($status)")
                    }
                    return
                }

                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    discoveredDevices[address]?.let {
                        it.connectionState = "connected"
                        emitPeerConnectionChanged(it, null)
                    }
                    gatt.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    activeGattClients.remove(address)
                    gatt.close()
                    discoveredDevices[address]?.let {
                        it.connectionState = "disconnected"
                        emitPeerConnectionChanged(it, null)
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    return
                }
                val service = gatt.getService(UUID.fromString(SERVICE_UUID)) ?: return
                val identityChar = service.getCharacteristic(UUID.fromString(IDENTITY_CHAR_UUID))
                if (identityChar != null) {
                    @Suppress("DEPRECATION")
                    gatt.readCharacteristic(identityChar)
                }

                // Subscribe to incoming mesh packet notifications from this peer
                val packetChar = service.getCharacteristic(UUID.fromString(MESH_PACKET_CHAR_UUID))
                if (packetChar != null) {
                    gatt.setCharacteristicNotification(packetChar, true)
                    val cccd = packetChar.getDescriptor(UUID.fromString(CCCD_UUID))
                    if (cccd != null) {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        gatt.writeDescriptor(cccd)
                    }
                }

                // Drain store-and-forward queue to this newly connected peer
                drainRelayQueue()
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    @Suppress("DEPRECATION")
                    val bytes = characteristic.value ?: return
                    handlePeerIdentityPayload(address, bytes)
                }
            }

            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int,
            ) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    handlePeerIdentityPayload(address, value)
                }
            }

            @Suppress("DEPRECATION")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (characteristic.uuid == UUID.fromString(MESH_PACKET_CHAR_UUID)) {
                    @Suppress("DEPRECATION")
                    val value = characteristic.value ?: return
                    handleIncomingMeshPacket(gatt.device.address, value)
                }
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                if (characteristic.uuid == UUID.fromString(MESH_PACKET_CHAR_UUID)) {
                    handleIncomingMeshPacket(gatt.device.address, value)
                }
            }
        }

        val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, false, gattCallback)
        } ?: return false

        activeGattClients[address] = gatt
        return true
    }

    @SuppressLint("MissingPermission")
    fun disconnectPeer(address: String): Boolean {
        val gatt = activeGattClients.remove(address) ?: return false
        try {
            gatt.disconnect()
            gatt.close()
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission error disconnecting GATT", e)
        }
        discoveredDevices[address]?.let {
            it.connectionState = "disconnected"
            emitPeerConnectionChanged(it, null)
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun closeAllGattClients() {
        for ((address, gatt) in activeGattClients) {
            try {
                gatt.disconnect()
                gatt.close()
            } catch (_: Exception) {
            }
            discoveredDevices[address]?.let {
                it.connectionState = "disconnected"
                emitPeerConnectionChanged(it, "Bluetooth turned off")
            }
        }
        activeGattClients.clear()
    }

    @SuppressLint("MissingPermission")
    private fun startGattServer() {
        if (gattServer != null || !hasBlePermissions()) {
            return
        }
        val manager = getBluetoothManager() ?: return
        val callback = object : BluetoothGattServerCallback() {
            override fun onCharacteristicReadRequest(
                device: BluetoothDevice,
                requestId: Int,
                offset: Int,
                characteristic: BluetoothGattCharacteristic,
            ) {
                if (characteristic.uuid != UUID.fromString(IDENTITY_CHAR_UUID)) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                    return
                }

                val payloadBytes = try {
                    val id = AndroidDeviceIdentityProvider(context).getOrCreateIdentity()
                    JSONObject().apply {
                        put("deviceId", id.deviceId)
                        put("publicKey", id.publicKey)
                        put("algorithm", id.algorithm)
                    }.toString().toByteArray(Charsets.UTF_8)
                } catch (_: Exception) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                    return
                }

                if (offset >= payloadBytes.size) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, ByteArray(0))
                    return
                }

                val slice = payloadBytes.copyOfRange(offset, payloadBytes.size)
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
            }

            override fun onCharacteristicWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                characteristic: BluetoothGattCharacteristic,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                if (characteristic.uuid == UUID.fromString(MESH_PACKET_CHAR_UUID)) {
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                    }
                    handleIncomingMeshPacket(device.address, value)
                } else {
                    if (responseNeeded) {
                        gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                    }
                }
            }

            override fun onDescriptorWriteRequest(
                device: BluetoothDevice,
                requestId: Int,
                descriptor: BluetoothGattDescriptor,
                preparedWrite: Boolean,
                responseNeeded: Boolean,
                offset: Int,
                value: ByteArray,
            ) {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                }
            }
        }

        val server = manager.openGattServer(context, callback) ?: return
        val service = BluetoothGattService(
            UUID.fromString(SERVICE_UUID),
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        val identityChar = BluetoothGattCharacteristic(
            UUID.fromString(IDENTITY_CHAR_UUID),
            BluetoothGattCharacteristic.PROPERTY_READ,
            BluetoothGattCharacteristic.PERMISSION_READ,
        )
        service.addCharacteristic(identityChar)

        val packetChar = BluetoothGattCharacteristic(
            UUID.fromString(MESH_PACKET_CHAR_UUID),
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val cccd = BluetoothGattDescriptor(
            UUID.fromString(CCCD_UUID),
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
        )
        packetChar.addDescriptor(cccd)
        service.addCharacteristic(packetChar)

        server.addService(service)
        gattServer = server
    }

    @SuppressLint("MissingPermission")
    private fun stopGattServer() {
        try {
            gattServer?.close()
        } catch (_: Exception) {
        }
        gattServer = null
    }

    /**
     * Sends a mesh message to a target peer or broadcasts to the entire mesh.
     */
    @SuppressLint("MissingPermission")
    fun sendMeshMessage(peerDeviceId: String, messageId: String, body: String): Boolean {
        val myId = try {
            AndroidDeviceIdentityProvider(context).getOrCreateIdentity().deviceId
        } catch (_: Exception) {
            "local-mesh-device"
        }

        val packet = JSONObject().apply {
            put("packetId", messageId)
            put("sourceDeviceId", myId)
            put("targetDeviceId", peerDeviceId)
            put("type", "MESSAGE")
            put("payload", body)
            put("hopCount", 0)
            put("maxHops", 4)
            put("timestamp", System.currentTimeMillis())
        }

        seenPacketIds.add(messageId)
        return transmitMeshPacket(packet, excludeAddress = null)
    }

    /**
     * Transmits a mesh packet to all directly connected GATT peers (clients and server devices).
     * If no peers are currently reachable, stores in SQLite relay_queue for store-and-forward relay.
     */
    @SuppressLint("MissingPermission")
    fun transmitMeshPacket(packet: JSONObject, excludeAddress: String?): Boolean {
        val packetBytes = packet.toString().toByteArray(Charsets.UTF_8)
        var sentAny = false

        // 1. Send to all connected GATT client devices
        for ((address, gatt) in activeGattClients) {
            if (address == excludeAddress) continue
            try {
                val service = gatt.getService(UUID.fromString(SERVICE_UUID))
                val char = service?.getCharacteristic(UUID.fromString(MESH_PACKET_CHAR_UUID))
                if (char != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeCharacteristic(char, packetBytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    } else {
                        @Suppress("DEPRECATION")
                        char.value = packetBytes
                        @Suppress("DEPRECATION")
                        gatt.writeCharacteristic(char)
                    }
                    sentAny = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to write mesh packet to GATT client $address", e)
            }
        }

        // 2. Notify connected server devices if GATT server is active
        gattServer?.let { server ->
            val service = server.getService(UUID.fromString(SERVICE_UUID))
            val char = service?.getCharacteristic(UUID.fromString(MESH_PACKET_CHAR_UUID))
            if (char != null) {
                val manager = getBluetoothManager()
                val connectedDevices = manager?.getConnectedDevices(BluetoothProfile.GATT) ?: emptyList()
                for (dev in connectedDevices) {
                    if (dev.address == excludeAddress) continue
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            server.notifyCharacteristicChanged(dev, char, false, packetBytes)
                        } else {
                            @Suppress("DEPRECATION")
                            char.value = packetBytes
                            @Suppress("DEPRECATION")
                            server.notifyCharacteristicChanged(dev, char, false)
                        }
                        sentAny = true
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to notify GATT server device ${dev.address}", e)
                    }
                }
            }
        }

        // Store-and-forward queueing if unreached
        if (!sentAny && ::context.isInitialized) {
            try {
                MeshLocalStore.getInstance(context).enqueueRelayPacket(
                    packetId = packet.optString("packetId", "pkt_${UUID.randomUUID()}"),
                    targetDeviceId = packet.optString("targetDeviceId", "*"),
                    payload = packet.toString(),
                    ttl = packet.optInt("maxHops", 4),
                    hopCount = packet.optInt("hopCount", 0),
                    createdAt = packet.optLong("timestamp", System.currentTimeMillis()),
                    expiresAt = System.currentTimeMillis() + 86_400_000L,
                )
            } catch (_: Exception) {}
        }

        return sentAny
    }

    /**
     * Handles an incoming mesh packet: delivers locally if targeted, and forwards/relays
     * across the mesh if hops remain. Prevents infinite relay loops using seen packet deduplication.
     */
    private fun handleIncomingMeshPacket(fromAddress: String, bytes: ByteArray) {
        try {
            val packet = JSONObject(String(bytes, Charsets.UTF_8))
            val packetId = packet.optString("packetId").trim()
            if (packetId.isEmpty() || !seenPacketIds.add(packetId)) {
                return // Deduplicated / already processed
            }

            val sourceDeviceId = packet.optString("sourceDeviceId")
            val targetDeviceId = packet.optString("targetDeviceId")
            val type = packet.optString("type", "MESSAGE")
            val payload = packet.optString("payload")
            val hopCount = packet.optInt("hopCount", 0)
            val maxHops = packet.optInt("maxHops", 4)
            val timestamp = packet.optLong("timestamp", System.currentTimeMillis())

            val myId = try {
                AndroidDeviceIdentityProvider(context).getOrCreateIdentity().deviceId
            } catch (_: Exception) { "" }

            val isForMe = targetDeviceId == myId || targetDeviceId == "*" || targetDeviceId == "broadcast" || targetDeviceId.isEmpty()

            if (isForMe && type == "MESSAGE") {
                if (::context.isInitialized) {
                    MeshLocalStore.getInstance(context).saveMessage(
                        messageId = packetId,
                        peerDeviceId = sourceDeviceId,
                        direction = "incoming",
                        body = payload,
                        status = "received",
                        createdAt = timestamp,
                    )
                }

                BridgeEventEmitter.emit(
                    "MESSAGE_RECEIVED",
                    JSONObject().apply {
                        put("messageId", packetId)
                        put("peerDeviceId", sourceDeviceId)
                        put("body", payload)
                        put("timestamp", timestamp)
                        put("hopCount", hopCount)
                    },
                )
            }

            // Multi-person mesh relaying: forward to all OTHER connected peers if hop limit not reached
            if (hopCount < maxHops && (targetDeviceId != myId || targetDeviceId == "*")) {
                packet.put("hopCount", hopCount + 1)
                transmitMeshPacket(packet, excludeAddress = fromAddress)

                BridgeEventEmitter.emit(
                    "PACKET_RELAYED",
                    JSONObject().apply {
                        put("packetId", packetId)
                        put("sourceDeviceId", sourceDeviceId)
                        put("targetDeviceId", targetDeviceId)
                        put("hopCount", hopCount + 1)
                        put("timestamp", System.currentTimeMillis())
                    },
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error handling incoming mesh packet", e)
        }
    }

    /**
     * Drains pending relay packets from SQLite store to directly connected peers.
     */
    private fun drainRelayQueue() {
        if (!::context.isInitialized) return
        try {
            val store = MeshLocalStore.getInstance(context)
            val db = store.readableDatabase
            val cursor = db.rawQuery("SELECT packet_id, payload FROM relay_queue LIMIT 10", null)
            val toDelete = mutableListOf<String>()
            cursor.use { cur ->
                while (cur.moveToNext()) {
                    val pid = cur.getString(0)
                    val payloadJson = cur.getString(1)
                    try {
                        val pkt = JSONObject(payloadJson)
                        val sent = transmitMeshPacket(pkt, excludeAddress = null)
                        if (sent) toDelete.add(pid)
                    } catch (_: Exception) {}
                }
            }
            val wdb = store.writableDatabase
            for (pid in toDelete) {
                wdb.delete("relay_queue", "packet_id = ?", arrayOf(pid))
            }
        } catch (_: Exception) {}
    }

    private fun handlePeerIdentityPayload(address: String, bytes: ByteArray) {
        try {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            val peerDeviceId = json.optString("deviceId").trim()
            val peerPublicKey = json.optString("publicKey").trim()
            if (peerDeviceId.isEmpty() || peerPublicKey.isEmpty()) {
                return
            }

            val fingerprint = formatPublicKeyFingerprint(peerPublicKey)
            val now = System.currentTimeMillis()
            val record = discoveredDevices[address]
            if (record != null) {
                record.deviceId = peerDeviceId
                record.publicKey = peerPublicKey
                record.fingerprint = fingerprint
                record.lastSeen = now
                emitPeerConnectionChanged(record, null)
            }

            MeshLocalStore.getInstance(context).upsertPeer(
                StoredPeer(
                    deviceId = peerDeviceId,
                    publicKey = peerPublicKey,
                    fingerprint = fingerprint,
                    displayName = record?.name,
                    bleAddress = address,
                    lastSeen = now,
                    trusted = true,
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse peer GATT identity payload", e)
        }
    }

    private fun getLocalIdentityFingerprintBytes(): ByteArray {
        return try {
            val id = AndroidDeviceIdentityProvider(context).getOrCreateIdentity()
            val decoded = Base64.decode(id.publicKey, Base64.NO_WRAP)
            val digest = MessageDigest.getInstance("SHA-256").digest(decoded)
            digest.copyOfRange(0, 4)
        } catch (_: Exception) {
            byteArrayOf(0x4D, 0x58, 0x00, 0x01)
        }
    }

    private fun formatPublicKeyFingerprint(publicKeyBase64: String): String {
        return try {
            val decoded = Base64.decode(publicKeyBase64, Base64.NO_WRAP)
            val digest = MessageDigest.getInstance("SHA-256").digest(decoded)
            val hex = digest.take(4).joinToString("") { "%02X".format(it) }
            "MX-${hex.substring(0, 4)}-${hex.substring(4, 8)}"
        } catch (_: Exception) {
            "MX-PEER"
        }
    }

    fun getNearbyDevices(): JSONArray {
        val array = JSONArray()
        for (record in discoveredDevices.values) {
            array.put(
                JSONObject().apply {
                    put("address", record.address)
                    put("name", record.name)
                    put("rssi", record.rssi)
                    put("lastSeen", record.lastSeen)
                    put("fingerprint", record.fingerprint)
                    put("deviceId", record.deviceId)
                    put("publicKey", record.publicKey)
                    put("connectionState", record.connectionState)
                },
            )
        }
        return array
    }

    @SuppressLint("MissingPermission")
    private fun handleScanResult(result: ScanResult) {
        try {
            val device = result.device
            val address = device.address ?: return
            val mfgBytes = result.scanRecord?.getManufacturerSpecificData(MANUFACTURER_ID)
            val mfgFingerprint = if (mfgBytes != null && mfgBytes.size >= 4) {
                "MX-%02X%02X-%02X%02X".format(mfgBytes[0], mfgBytes[1], mfgBytes[2], mfgBytes[3])
            } else {
                null
            }
            val name = device.name ?: mfgFingerprint ?: "MESH-X Peer"
            val rssi = result.rssi
            val now = System.currentTimeMillis()

            val existing = discoveredDevices[address]
            if (existing == null) {
                val record = DeviceRecord(
                    address = address,
                    name = name,
                    rssi = rssi,
                    lastSeen = now,
                    fingerprint = mfgFingerprint,
                )
                discoveredDevices[address] = record
                emitDeviceFound(record)
            } else {
                existing.name = name
                existing.rssi = rssi
                existing.lastSeen = now
                if (mfgFingerprint != null && existing.fingerprint == null) {
                    existing.fingerprint = mfgFingerprint
                }
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
            put("lastSeen", record.lastSeen)
            put("fingerprint", record.fingerprint)
            put("connectionState", record.connectionState)
        }
        BridgeEventEmitter.emit("DEVICE_FOUND", payload)
    }

    private fun emitDeviceLost(record: DeviceRecord) {
        val payload = JSONObject().apply {
            put("address", record.address)
            put("name", record.name)
            put("lastSeen", record.lastSeen)
            put("fingerprint", record.fingerprint)
        }
        BridgeEventEmitter.emit("DEVICE_LOST", payload)
    }

    private fun emitPeerConnectionChanged(record: DeviceRecord, error: String?) {
        val payload = JSONObject().apply {
            put("address", record.address)
            put("name", record.name)
            put("connectionState", record.connectionState)
            put("fingerprint", record.fingerprint)
            put("deviceId", record.deviceId)
            put("publicKey", record.publicKey)
            if (error != null) {
                put("error", error)
            }
        }
        BridgeEventEmitter.emit("PEER_CONNECTION_CHANGED", payload)
    }

    private fun emitDiscoveryState(state: String, reason: String?) {
        val payload = JSONObject().apply {
            put("state", state)
            put("scanning", scanningActive)
            put("advertising", advertisingActive)
            if (reason != null) {
                put("reason", reason)
            }
        }
        BridgeEventEmitter.emit("DISCOVERY_STATE_CHANGED", payload)
    }

    private fun scheduleLostDeviceCheck() {
        if (cleanupHandler == null && ::context.isInitialized) {
            cleanupHandler = Handler(context.mainLooper)
        }
        cleanupHandler?.removeCallbacks(cleanupRunnable)
        cleanupHandler?.postDelayed(cleanupRunnable, DEVICE_LOST_TIMEOUT_MS / 3)
    }
}
