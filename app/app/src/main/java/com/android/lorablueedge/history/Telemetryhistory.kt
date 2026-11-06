package com.android.lorablueedge.history

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/**
 * One persisted reading. The RAW sensor distance is always stored; the
 * converted column/percent are stored too (null while the reservoir depth
 * is not configured). Keeping the raw value means the data set can be
 * recomputed later if the configured depth changes — which matters for
 * training the leak-detection model.
 */
data class HistoryRow(
    val timestamp: Long,
    val nodeId: Int,
    val nodeType: Int,
    val sensorMeters: Double,
    val columnMeters: Double?,
    val percent: Double?,
    val pumpOn: Boolean?,      // Cistern-only
    val batteryPercent: Double,
    val rssiDbm: Double,
    val waterValid: Boolean = true   // false: sensor_m is meaningless, exclude from training
)

/**
 * Long-term telemetry history in plain SQLite (no Room/KSP — those
 * conflict with this project's AGP version, see TelemetryStore).
 *
 * Unlike [com.android.lorablue.chart.TelemetryStore], which only keeps ~1 h
 * for the chart, this keeps [RETENTION_MILLIS] (90 days) of every reading,
 * indexed by (node_id, ts), so consumption rate and hour-of-day patterns
 * can be computed and the data can be exported to train the model.
 *
 * Not thread-confined: SQLiteOpenHelper serialises access, but callers
 * should still do writes off the main thread (LoRaBlueService does).
 */
class TelemetryHistory(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "lorablue_history.db"
        private const val DB_VERSION = 2
        private const val TABLE = "telemetry"

        const val RETENTION_MILLIS = 90L * 24 * 60 * 60 * 1000
        private const val PURGE_EVERY_N_INSERTS = 500
    }

    private var insertsSincePurge = 0

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE (
                _id        INTEGER PRIMARY KEY AUTOINCREMENT,
                ts         INTEGER NOT NULL,
                node_id    INTEGER NOT NULL,
                node_type  INTEGER NOT NULL,
                sensor_m   REAL    NOT NULL,
                column_m   REAL,
                percent    REAL,
                pump       INTEGER,
                battery    REAL    NOT NULL,
                rssi       REAL    NOT NULL,
                water_ok   INTEGER NOT NULL DEFAULT 1
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_telemetry_node_ts ON $TABLE (node_id, ts)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Existing rows were all stored as valid readings.
            db.execSQL("ALTER TABLE $TABLE ADD COLUMN water_ok INTEGER NOT NULL DEFAULT 1")
        }
    }

    fun insert(row: HistoryRow) {
        val values = ContentValues().apply {
            put("ts", row.timestamp)
            put("node_id", row.nodeId)
            put("node_type", row.nodeType)
            put("sensor_m", row.sensorMeters)
            if (row.columnMeters != null) put("column_m", row.columnMeters) else putNull("column_m")
            if (row.percent != null) put("percent", row.percent) else putNull("percent")
            if (row.pumpOn != null) put("pump", if (row.pumpOn) 1 else 0) else putNull("pump")
            put("battery", row.batteryPercent)
            put("rssi", row.rssiDbm)
            put("water_ok", if (row.waterValid) 1 else 0)
        }
        writableDatabase.insert(TABLE, null, values)

        if (++insertsSincePurge >= PURGE_EVERY_N_INSERTS) {
            insertsSincePurge = 0
            purgeOlderThan(System.currentTimeMillis() - RETENTION_MILLIS)
        }
    }

    /** Readings of [nodeId] at or after [sinceMillis], oldest first. */
    fun query(nodeId: Int, sinceMillis: Long): List<HistoryRow> {
        readableDatabase.query(
            TABLE, null,
            "node_id = ? AND ts >= ?",
            arrayOf(nodeId.toString(), sinceMillis.toString()),
            null, null, "ts ASC"
        ).use { cursor ->
            val rows = ArrayList<HistoryRow>(cursor.count)
            while (cursor.moveToNext()) rows.add(cursor.toRow())
            return rows
        }
    }

    override fun onConfigure(db: SQLiteDatabase) {
        // Write-ahead logging lets the export read while the service keeps writing.
        db.enableWriteAheadLogging()
    }

    /** Distinct (nodeId, nodeType) pairs present in the history. */
    fun nodes(): List<Pair<Int, Int>> {
        readableDatabase.rawQuery(
            "SELECT DISTINCT node_id, node_type FROM $TABLE ORDER BY node_id", null
        ).use { cursor ->
            val out = ArrayList<Pair<Int, Int>>()
            while (cursor.moveToNext()) out.add(cursor.getInt(0) to cursor.getInt(1))
            return out
        }
    }

    /** First and last timestamp of [nodeId], or null when it has no rows. */
    fun timeRange(nodeId: Int): Pair<Long, Long>? {
        readableDatabase.rawQuery(
            "SELECT MIN(ts), MAX(ts) FROM $TABLE WHERE node_id = ?",
            arrayOf(nodeId.toString())
        ).use { cursor ->
            if (!cursor.moveToFirst() || cursor.isNull(0)) return null
            return cursor.getLong(0) to cursor.getLong(1)
        }
    }

    /** Readings of [nodeId] with fromMillis <= ts < toMillis, oldest first. */
    fun queryRange(nodeId: Int, fromMillis: Long, toMillis: Long): List<HistoryRow> {
        readableDatabase.query(
            TABLE, null,
            "node_id = ? AND ts >= ? AND ts < ?",
            arrayOf(nodeId.toString(), fromMillis.toString(), toMillis.toString()),
            null, null, "ts ASC"
        ).use { cursor ->
            val rows = ArrayList<HistoryRow>(cursor.count)
            while (cursor.moveToNext()) rows.add(cursor.toRow())
            return rows
        }
    }

    fun count(): Long = DatabaseUtils.queryNumEntries(readableDatabase, TABLE)

    fun purgeOlderThan(cutoffMillis: Long): Int =
        writableDatabase.delete(TABLE, "ts < ?", arrayOf(cutoffMillis.toString()))

    /**
     * Writes the whole history as CSV (one row per reading, oldest first)
     * for offline training in Python. Returns the number of rows written.
     * Empty cells mean "not applicable / not configured".
     */
    fun exportCsv(file: File): Int {
        var written = 0
        file.bufferedWriter().use { out ->
            out.write("timestamp_ms,node_id,node_type,sensor_m,column_m,percent,pump,battery,rssi,water_ok\n")
            readableDatabase.query(TABLE, null, null, null, null, null, "ts ASC").use { cursor ->
                while (cursor.moveToNext()) {
                    val r = cursor.toRow()
                    out.write(
                        listOf(
                            r.timestamp, r.nodeId, r.nodeType, r.sensorMeters,
                            r.columnMeters ?: "", r.percent ?: "",
                            r.pumpOn?.let { if (it) 1 else 0 } ?: "",
                            r.batteryPercent, r.rssiDbm, if (r.waterValid) 1 else 0
                        ).joinToString(",") + "\n"
                    )
                    written++
                }
            }
        }
        return written
    }

    private fun Cursor.toRow(): HistoryRow {
        fun doubleOrNull(col: String): Double? {
            val i = getColumnIndexOrThrow(col)
            return if (isNull(i)) null else getDouble(i)
        }
        val pumpIndex = getColumnIndexOrThrow("pump")
        return HistoryRow(
            timestamp = getLong(getColumnIndexOrThrow("ts")),
            nodeId = getInt(getColumnIndexOrThrow("node_id")),
            nodeType = getInt(getColumnIndexOrThrow("node_type")),
            sensorMeters = getDouble(getColumnIndexOrThrow("sensor_m")),
            columnMeters = doubleOrNull("column_m"),
            percent = doubleOrNull("percent"),
            pumpOn = if (isNull(pumpIndex)) null else getInt(pumpIndex) == 1,
            batteryPercent = getDouble(getColumnIndexOrThrow("battery")),
            rssiDbm = getDouble(getColumnIndexOrThrow("rssi")),
            waterValid = getInt(getColumnIndexOrThrow("water_ok")) == 1
        )
    }
}