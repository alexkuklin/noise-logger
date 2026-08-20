package com.noiselogger

import android.content.Context
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*

class RecordingManager(private val context: Context) {

    companion object {
        private const val RECORDINGS_DIR = "NoiseLogger/recordings"
        private const val EXPORTS_DIR = "NoiseLogger/exports"
        private const val CHUNK_DURATION_MS = 30 * 60 * 1000L // 30 minutes
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private var currentRecordingFile: File? = null
    private var currentRecordingStartTime: Long = 0
    private var sessionLocation: LocationData? = null
    private var currentSessionId: Long = -1

    private val database: NoiseDatabase by lazy { NoiseDatabase(context) }

    fun getRecordingsDir(): File {
        val dir = File(context.getExternalFilesDir(null), RECORDINGS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getExportsDir(): File {
        val dir = File(context.getExternalFilesDir(null), EXPORTS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun createNewRecordingFile(): File {
        val timestamp = dateFormat.format(Date())
        val filename = "recording_$timestamp.m4a"
        currentRecordingFile = File(getRecordingsDir(), filename)
        currentRecordingStartTime = System.currentTimeMillis()
        return currentRecordingFile!!
    }

    fun shouldStartNewChunk(): Boolean {
        if (currentRecordingStartTime == 0L) return true
        return System.currentTimeMillis() - currentRecordingStartTime >= CHUNK_DURATION_MS
    }

    fun getCurrentRecordingFilename(): String {
        return currentRecordingFile?.name ?: ""
    }

    fun startSession(location: LocationData? = null) {
        sessionLocation = location
        currentSessionId = database.startSession(location)
    }

    fun endSession() {
        if (currentSessionId >= 0) {
            database.endSession(currentSessionId)
        }
        currentSessionId = -1
    }

    fun getSessionLocation(): LocationData? = sessionLocation

    fun logNoiseLevel(dbLevel: Double) {
        if (currentSessionId >= 0) {
            database.logReading(currentSessionId, dbLevel, getCurrentRecordingFilename())
        }
    }

    fun getReadingsInRange(fromTime: Long, toTime: Long): List<NoiseDatabase.NoiseReading> {
        return database.getReadingsInRange(fromTime, toTime)
    }

    fun getRecordings(): List<RecordingInfo> {
        val dir = getRecordingsDir()
        return dir.listFiles()
            ?.filter { it.extension == "m4a" }
            ?.sortedByDescending { it.lastModified() }
            ?.map { file ->
                RecordingInfo(
                    file = file,
                    name = file.name,
                    size = formatFileSize(file.length()),
                    date = timestampFormat.format(Date(file.lastModified()))
                )
            }
            ?: emptyList()
    }

    fun getLogEntries(limit: Int = 100): List<LogEntry> {
        return database.getRecentReadings(limit).map { reading ->
            LogEntry(
                timestamp = timestampFormat.format(Date(reading.timestamp)),
                dbLevel = reading.dbLevel,
                recordingFile = reading.recordingFile
            )
        }.reversed()
    }

    fun hasData(): Boolean {
        return database.hasData()
    }

    /**
     * Export data in a time range to CSV file
     */
    fun exportToCsv(fromTime: Long, toTime: Long): File? {
        val readings = database.getReadingsInRange(fromTime, toTime)
        if (readings.isEmpty()) return null

        val sessions = database.getAllSessions()
        val relevantSession = sessions.find {
            it.startTime <= fromTime && (it.endTime == null || it.endTime >= toTime)
        }

        val exportTimestamp = dateFormat.format(Date())
        val exportFile = File(getExportsDir(), "noise_export_$exportTimestamp.csv")

        PrintWriter(FileWriter(exportFile), true).use { writer ->
            writer.println("# Noise Logger Export")
            writer.println("# Exported: ${timestampFormat.format(Date())}")
            writer.println("# Range: ${timestampFormat.format(Date(fromTime))} to ${timestampFormat.format(Date(toTime))}")
            if (relevantSession != null && relevantSession.latitude != null) {
                writer.println("# Location: ${relevantSession.latitude}, ${relevantSession.longitude}, ${relevantSession.altitude}m")
            }
            writer.println("#")
            writer.println("timestamp,db_level,recording_file")

            for (reading in readings) {
                val timestamp = timestampFormat.format(Date(reading.timestamp))
                // Use Locale.US to ensure dot as decimal separator
                writer.println("$timestamp,${String.format(Locale.US, "%.1f", reading.dbLevel)},${reading.recordingFile}")
            }
        }

        return exportFile
    }

    /**
     * Export all data to CSV
     */
    fun exportAllToCsv(): File? {
        val readings = database.getRecentReadings(Int.MAX_VALUE)
        if (readings.isEmpty()) return null

        val sessions = database.getAllSessions()

        val exportTimestamp = dateFormat.format(Date())
        val exportFile = File(getExportsDir(), "noise_export_all_$exportTimestamp.csv")

        PrintWriter(FileWriter(exportFile), true).use { writer ->
            writer.println("# Noise Logger Full Export")
            writer.println("# Exported: ${timestampFormat.format(Date())}")
            writer.println("# Total readings: ${readings.size}")
            writer.println("# Total sessions: ${sessions.size}")
            writer.println("#")
            writer.println("timestamp,db_level,recording_file")

            for (reading in readings) {
                val timestamp = timestampFormat.format(Date(reading.timestamp))
                writer.println("$timestamp,${String.format(Locale.US, "%.1f", reading.dbLevel)},${reading.recordingFile}")
            }
        }

        return exportFile
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${String.format(Locale.US, "%.1f", bytes / (1024.0 * 1024.0))} MB"
        }
    }

    fun reset() {
        currentRecordingFile = null
        currentRecordingStartTime = 0
    }
}

data class RecordingInfo(
    val file: File,
    val name: String,
    val size: String,
    val date: String
)

data class LogEntry(
    val timestamp: String,
    val dbLevel: Double,
    val recordingFile: String
)
