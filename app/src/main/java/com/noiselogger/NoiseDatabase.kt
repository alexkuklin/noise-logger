package com.noiselogger

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class NoiseDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "noise_logger.db"
        private const val DATABASE_VERSION = 1

        // Sessions table
        const val TABLE_SESSIONS = "sessions"
        const val COL_SESSION_ID = "id"
        const val COL_SESSION_START = "start_time"
        const val COL_SESSION_END = "end_time"
        const val COL_SESSION_LATITUDE = "latitude"
        const val COL_SESSION_LONGITUDE = "longitude"
        const val COL_SESSION_ALTITUDE = "altitude"

        // Readings table
        const val TABLE_READINGS = "readings"
        const val COL_READING_ID = "id"
        const val COL_READING_SESSION_ID = "session_id"
        const val COL_READING_TIMESTAMP = "timestamp"
        const val COL_READING_DB_LEVEL = "db_level"
        const val COL_READING_RECORDING_FILE = "recording_file"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_SESSIONS (
                $COL_SESSION_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_SESSION_START INTEGER NOT NULL,
                $COL_SESSION_END INTEGER,
                $COL_SESSION_LATITUDE REAL,
                $COL_SESSION_LONGITUDE REAL,
                $COL_SESSION_ALTITUDE REAL
            )
        """)

        db.execSQL("""
            CREATE TABLE $TABLE_READINGS (
                $COL_READING_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_READING_SESSION_ID INTEGER NOT NULL,
                $COL_READING_TIMESTAMP INTEGER NOT NULL,
                $COL_READING_DB_LEVEL REAL NOT NULL,
                $COL_READING_RECORDING_FILE TEXT,
                FOREIGN KEY ($COL_READING_SESSION_ID) REFERENCES $TABLE_SESSIONS($COL_SESSION_ID)
            )
        """)

        // Index for faster range queries
        db.execSQL("CREATE INDEX idx_readings_timestamp ON $TABLE_READINGS($COL_READING_TIMESTAMP)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // For now, just recreate tables
        db.execSQL("DROP TABLE IF EXISTS $TABLE_READINGS")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SESSIONS")
        onCreate(db)
    }

    fun startSession(location: LocationData?): Long {
        val values = ContentValues().apply {
            put(COL_SESSION_START, System.currentTimeMillis())
            location?.let {
                put(COL_SESSION_LATITUDE, it.latitude)
                put(COL_SESSION_LONGITUDE, it.longitude)
                put(COL_SESSION_ALTITUDE, it.altitude)
            }
        }
        return writableDatabase.insert(TABLE_SESSIONS, null, values)
    }

    fun endSession(sessionId: Long) {
        val values = ContentValues().apply {
            put(COL_SESSION_END, System.currentTimeMillis())
        }
        writableDatabase.update(TABLE_SESSIONS, values, "$COL_SESSION_ID = ?", arrayOf(sessionId.toString()))
    }

    fun logReading(sessionId: Long, dbLevel: Double, recordingFile: String?) {
        val values = ContentValues().apply {
            put(COL_READING_SESSION_ID, sessionId)
            put(COL_READING_TIMESTAMP, System.currentTimeMillis())
            put(COL_READING_DB_LEVEL, dbLevel)
            put(COL_READING_RECORDING_FILE, recordingFile ?: "")
        }
        writableDatabase.insert(TABLE_READINGS, null, values)
    }

    data class NoiseReading(
        val timestamp: Long,
        val dbLevel: Double,
        val recordingFile: String
    )

    fun getReadingsInRange(fromTime: Long, toTime: Long): List<NoiseReading> {
        val readings = mutableListOf<NoiseReading>()
        val cursor = readableDatabase.query(
            TABLE_READINGS,
            arrayOf(COL_READING_TIMESTAMP, COL_READING_DB_LEVEL, COL_READING_RECORDING_FILE),
            "$COL_READING_TIMESTAMP BETWEEN ? AND ?",
            arrayOf(fromTime.toString(), toTime.toString()),
            null, null,
            COL_READING_TIMESTAMP
        )

        cursor.use {
            while (it.moveToNext()) {
                readings.add(NoiseReading(
                    timestamp = it.getLong(0),
                    dbLevel = it.getDouble(1),
                    recordingFile = it.getString(2) ?: ""
                ))
            }
        }
        return readings
    }

    fun getRecentReadings(limit: Int): List<NoiseReading> {
        val readings = mutableListOf<NoiseReading>()
        val cursor = readableDatabase.query(
            TABLE_READINGS,
            arrayOf(COL_READING_TIMESTAMP, COL_READING_DB_LEVEL, COL_READING_RECORDING_FILE),
            null, null, null, null,
            "$COL_READING_TIMESTAMP DESC",
            limit.toString()
        )

        cursor.use {
            while (it.moveToNext()) {
                readings.add(NoiseReading(
                    timestamp = it.getLong(0),
                    dbLevel = it.getDouble(1),
                    recordingFile = it.getString(2) ?: ""
                ))
            }
        }
        return readings.reversed() // Return in chronological order
    }

    data class SessionInfo(
        val id: Long,
        val startTime: Long,
        val endTime: Long?,
        val latitude: Double?,
        val longitude: Double?,
        val altitude: Double?
    )

    fun getAllSessions(): List<SessionInfo> {
        val sessions = mutableListOf<SessionInfo>()
        val cursor = readableDatabase.query(
            TABLE_SESSIONS,
            null, null, null, null, null,
            "$COL_SESSION_START DESC"
        )

        cursor.use {
            while (it.moveToNext()) {
                sessions.add(SessionInfo(
                    id = it.getLong(it.getColumnIndexOrThrow(COL_SESSION_ID)),
                    startTime = it.getLong(it.getColumnIndexOrThrow(COL_SESSION_START)),
                    endTime = if (it.isNull(it.getColumnIndexOrThrow(COL_SESSION_END))) null
                              else it.getLong(it.getColumnIndexOrThrow(COL_SESSION_END)),
                    latitude = if (it.isNull(it.getColumnIndexOrThrow(COL_SESSION_LATITUDE))) null
                              else it.getDouble(it.getColumnIndexOrThrow(COL_SESSION_LATITUDE)),
                    longitude = if (it.isNull(it.getColumnIndexOrThrow(COL_SESSION_LONGITUDE))) null
                               else it.getDouble(it.getColumnIndexOrThrow(COL_SESSION_LONGITUDE)),
                    altitude = if (it.isNull(it.getColumnIndexOrThrow(COL_SESSION_ALTITUDE))) null
                              else it.getDouble(it.getColumnIndexOrThrow(COL_SESSION_ALTITUDE))
                ))
            }
        }
        return sessions
    }

    fun hasData(): Boolean {
        val cursor = readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_READINGS", null)
        cursor.use {
            return it.moveToFirst() && it.getInt(0) > 0
        }
    }
}
