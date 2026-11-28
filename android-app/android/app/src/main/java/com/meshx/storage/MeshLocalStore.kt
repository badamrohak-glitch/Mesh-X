package com.meshx.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

data class StoredPeer(
    val deviceId: String,
    val publicKey: String,
    val fingerprint: String,
    val displayName: String?,
    val bleAddress: String?,
    val lastSeen: Long,
    val trusted: Boolean,
)

data class LocalStorageStats(
    val peerCount: Int,
    val messageCount: Int,
    val relayQueueCount: Int,
)

/**
 * Offline-first SQLite store for verified peers, local messages, and store-and-forward relay packets.
 * Uses Android's built-in SQLiteOpenHelper with zero external dependencies.
 */
class MeshLocalStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE peers (
                device_id TEXT PRIMARY KEY,
                public_key TEXT NOT NULL,
                fingerprint TEXT NOT NULL,
                display_name TEXT,
                ble_address TEXT,
                last_seen INTEGER NOT NULL,
                trusted INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE messages (
                message_id TEXT PRIMARY KEY,
                peer_device_id TEXT NOT NULL,
                direction TEXT NOT NULL,
                body TEXT NOT NULL,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE relay_queue (
                packet_id TEXT PRIMARY KEY,
                target_device_id TEXT NOT NULL,
                payload TEXT NOT NULL,
                ttl INTEGER NOT NULL,
                hop_count INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future schema migrations will be handled incrementally without dropping user data.
    }

    fun upsertPeer(peer: StoredPeer) {
        val values = ContentValues().apply {
            put("device_id", peer.deviceId)
            put("public_key", peer.publicKey)
            put("fingerprint", peer.fingerprint)
            put("display_name", peer.displayName)
            put("ble_address", peer.bleAddress)
            put("last_seen", peer.lastSeen)
            put("trusted", if (peer.trusted) 1 else 0)
        }
        writableDatabase.insertWithOnConflict(
            "peers",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun listPeers(): List<StoredPeer> {
        val result = mutableListOf<StoredPeer>()
        readableDatabase.rawQuery(
            "SELECT device_id, public_key, fingerprint, display_name, ble_address, last_seen, trusted FROM peers ORDER BY last_seen DESC",
            null,
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    StoredPeer(
                        deviceId = cursor.getString(0),
                        publicKey = cursor.getString(1),
                        fingerprint = cursor.getString(2),
                        displayName = cursor.getString(3),
                        bleAddress = cursor.getString(4),
                        lastSeen = cursor.getLong(5),
                        trusted = cursor.getInt(6) == 1,
                    ),
                )
            }
        }
        return result
    }

    fun saveMessage(
        messageId: String,
        peerDeviceId: String,
        direction: String,
        body: String,
        status: String,
        createdAt: Long,
    ) {
        val values = ContentValues().apply {
            put("message_id", messageId)
            put("peer_device_id", peerDeviceId)
            put("direction", direction)
            put("body", body)
            put("status", status)
            put("created_at", createdAt)
        }
        writableDatabase.insertWithOnConflict(
            "messages",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun enqueueRelayPacket(
        packetId: String,
        targetDeviceId: String,
        payload: String,
        ttl: Int,
        hopCount: Int,
        createdAt: Long,
        expiresAt: Long,
    ) {
        val values = ContentValues().apply {
            put("packet_id", packetId)
            put("target_device_id", targetDeviceId)
            put("payload", payload)
            put("ttl", ttl)
            put("hop_count", hopCount)
            put("created_at", createdAt)
            put("expires_at", expiresAt)
        }
        writableDatabase.insertWithOnConflict(
            "relay_queue",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun pruneExpiredRelayPackets(nowMs: Long = System.currentTimeMillis()): Int {
        return writableDatabase.delete(
            "relay_queue",
            "expires_at <= ?",
            arrayOf(nowMs.toString()),
        )
    }

    fun getStats(): LocalStorageStats {
        val db = readableDatabase
        return LocalStorageStats(
            peerCount = countRows(db, "peers"),
            messageCount = countRows(db, "messages"),
            relayQueueCount = countRows(db, "relay_queue"),
        )
    }

    fun getPeersJson(): JSONArray {
        val arr = JSONArray()
        for (peer in listPeers()) {
            arr.put(
                JSONObject().apply {
                    put("deviceId", peer.deviceId)
                    put("publicKey", peer.publicKey)
                    put("fingerprint", peer.fingerprint)
                    put("displayName", peer.displayName)
                    put("bleAddress", peer.bleAddress)
                    put("lastSeen", peer.lastSeen)
                    put("trusted", peer.trusted)
                },
            )
        }
        return arr
    }

    private fun countRows(db: SQLiteDatabase, table: String): Int {
        db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            return if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }
    }

    companion object {
        const val DATABASE_NAME = "meshx_local.db"
        const val DATABASE_VERSION = 1

        @Volatile
        private var instance: MeshLocalStore? = null

        fun getInstance(context: Context): MeshLocalStore {
            return instance ?: synchronized(this) {
                instance ?: MeshLocalStore(context.applicationContext).also { instance = it }
            }
        }
    }
}
