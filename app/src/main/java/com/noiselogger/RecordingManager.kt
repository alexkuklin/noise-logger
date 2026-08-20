package com.noiselogger

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*

class RecordingManager(private val context: Context) {

    companion object {
        private const val RECORDINGS_DIR = "NoiseLogger/recordings"
        private const val LOGS_DIR = "NoiseLogger/logs"
        private const val LOG_FILENAME = "noise_log.csv"
        private const val CHUNK_DURATION_MS = 30 * 60 * 1000L // 30 minutes
    }

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
    private val timestampFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private var currentRecordingFile: File? = null
    private var currentRecordingStartTime: Long = 0
    private var logWriter: PrintWriter? = null
    private var sessionLocation: LocationData? = null

    fun getRecordingsDir(): File {
        val dir = File(context.getExternalFilesDir(null), RECORDINGS_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getLogsDir(): File {
        val dir = File(context.getExternalFilesDir(null), LOGS_DIR)
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

    fun initializeLogFile(location: LocationData? = null) {
        sessionLocation = location

        // Create a new session log file with timestamp
        val sessionTimestamp = dateFormat.format(Date())
        val logFile = File(getLogsDir(), "noise_log_$sessionTimestamp.csv")

        logWriter = PrintWriter(FileWriter(logFile, false), true)

        // Write header with location info
        logWriter?.println("# Noise Logger Session")
        logWriter?.println("# Started: ${timestampFormat.format(Date())}")
        if (location != null) {
            logWriter?.println(location.toHeaderString())
        } else {
            logWriter?.println("# Location: not available")
        }
        logWriter?.println("#")
        logWriter?.println("timestamp,db_level,latitude,longitude,altitude,recording_file")
    }

    fun getSessionLocation(): LocationData? = sessionLocation

    fun logNoiseLevel(dbLevel: Double) {
        val timestamp = timestampFormat.format(Date())
        val recordingFile = getCurrentRecordingFilename()
        val locationStr = sessionLocation?.toCsvString() ?: ",,"
        logWriter?.println("$timestamp,${"%.1f".format(dbLevel)},$locationStr,$recordingFile")
    }

    fun closeLogFile() {
        logWriter?.close()
        logWriter = null
    }

    fun getLogFile(): File {
        // Return the most recent log file
        val logsDir = getLogsDir()
        return logsDir.listFiles()
            ?.filter { it.name.startsWith("noise_log_") && it.extension == "csv" }
            ?.maxByOrNull { it.lastModified() }
            ?: File(logsDir, "noise_log.csv")
    }

    fun getAllLogFiles(): List<File> {
        return getLogsDir().listFiles()
            ?.filter { it.name.startsWith("noise_log_") && it.extension == "csv" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
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
        val logFile = getLogFile()
        if (!logFile.exists()) return emptyList()

        return logFile.readLines()
            .filter { !it.startsWith("#") && it.isNotBlank() && !it.startsWith("timestamp") }
            .takeLast(limit)
            .reversed()
            .mapNotNull { line ->
                val parts = line.split(",")
                if (parts.size >= 2) {
                    LogEntry(
                        timestamp = parts[0],
                        dbLevel = parts[1].toDoubleOrNull() ?: 0.0,
                        recordingFile = parts.getOrElse(5) { "" }
                    )
                } else null
            }
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
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
