package com.noiselogger

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaRecorder
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import java.io.File

class AudioRecorderService : Service() {

    companion object {
        const val CHANNEL_ID = "noise_logger_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.noiselogger.START"
        const val ACTION_STOP = "com.noiselogger.STOP"

        private const val LOG_INTERVAL_MS = 1000L // Log every second
    }

    private val binder = LocalBinder()

    private var mediaRecorder: MediaRecorder? = null
    private var noiseLevelMonitor: NoiseLevelMonitor? = null
    private var recordingManager: RecordingManager? = null
    private var locationHelper: LocationHelper? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var pendingLocationCallback: (() -> Unit)? = null

    private var isRecording = false
    private var recordingStartTime = 0L

    private val handler = Handler(Looper.getMainLooper())
    private var logRunnable: Runnable? = null

    var onDbLevelChanged: ((Double) -> Unit)? = null
    var onRecordingStateChanged: ((Boolean) -> Unit)? = null

    inner class LocalBinder : Binder() {
        fun getService(): AudioRecorderService = this@AudioRecorderService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        recordingManager = RecordingManager(this)
        noiseLevelMonitor = NoiseLevelMonitor()
        locationHelper = LocationHelper(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopRecording()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRecording()
        super.onDestroy()
    }

    fun startRecording() {
        if (isRecording) return

        try {
            // Acquire wake lock to keep CPU running
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "NoiseLogger::RecordingWakeLock"
            ).apply {
                acquire(10 * 60 * 60 * 1000L) // 10 hours max
            }

            // Start foreground service
            startForeground(NOTIFICATION_ID, createNotification())

            // Get location first, then start recording
            locationHelper?.getCurrentLocation { location ->
                handler.post {
                    startRecordingWithLocation(location)
                }
            } ?: startRecordingWithLocation(null)

        } catch (e: Exception) {
            e.printStackTrace()
            stopRecording()
        }
    }

    private fun startRecordingWithLocation(location: LocationData?) {
        try {
            // Start session in database with location
            recordingManager?.startSession(location)
            startNewRecordingChunk()

            // Start noise level monitoring
            noiseLevelMonitor?.onNoiseLevelChanged = { dbLevel ->
                onDbLevelChanged?.invoke(dbLevel)
            }
            noiseLevelMonitor?.start()

            // Start periodic logging
            startLogging()

            isRecording = true
            recordingStartTime = System.currentTimeMillis()
            onRecordingStateChanged?.invoke(true)

        } catch (e: Exception) {
            e.printStackTrace()
            stopRecording()
        }
    }

    fun stopRecording() {
        if (!isRecording) return

        try {
            // Stop logging
            stopLogging()

            // Stop noise monitoring
            noiseLevelMonitor?.stop()

            // Stop media recorder
            mediaRecorder?.apply {
                stop()
                release()
            }
            mediaRecorder = null

            // End session in database
            recordingManager?.endSession()
            recordingManager?.reset()

            // Release wake lock
            wakeLock?.release()
            wakeLock = null

            isRecording = false
            onRecordingStateChanged?.invoke(false)

            // Stop foreground service
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun startNewRecordingChunk() {
        // Stop current recorder if exists
        mediaRecorder?.apply {
            try {
                stop()
                release()
            } catch (e: Exception) {
                // Ignore
            }
        }

        // Create new recording file
        val outputFile = recordingManager?.createNewRecordingFile() ?: return

        // Setup new recorder
        mediaRecorder = createMediaRecorder(outputFile).apply {
            prepare()
            start()
        }
    }

    @Suppress("DEPRECATION")
    private fun createMediaRecorder(outputFile: File): MediaRecorder {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            MediaRecorder()
        }.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128000)
            setAudioSamplingRate(44100)
            setOutputFile(outputFile.absolutePath)
        }
    }

    private fun startLogging() {
        logRunnable = object : Runnable {
            override fun run() {
                if (isRecording) {
                    // Check if we need to start a new chunk
                    if (recordingManager?.shouldStartNewChunk() == true) {
                        startNewRecordingChunk()
                    }

                    // Log current noise level
                    val dbLevel = noiseLevelMonitor?.getCurrentDbLevel() ?: 0.0
                    recordingManager?.logNoiseLevel(dbLevel)

                    // Update notification
                    updateNotification(dbLevel)

                    handler.postDelayed(this, LOG_INTERVAL_MS)
                }
            }
        }
        handler.post(logRunnable!!)
    }

    private fun stopLogging() {
        logRunnable?.let { handler.removeCallbacks(it) }
        logRunnable = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Noise Logger Recording",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when noise logging is active"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(dbLevel: Double = 0.0): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, AudioRecorderService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val duration = if (recordingStartTime > 0) {
            formatDuration(System.currentTimeMillis() - recordingStartTime)
        } else {
            "00:00:00"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording Noise")
            .setContentText("Level: ${"%.0f".format(dbLevel)} dB | Duration: $duration")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(dbLevel: Double) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(dbLevel))
    }

    private fun formatDuration(millis: Long): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        val hours = millis / (1000 * 60 * 60)
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }

    fun isRecording(): Boolean = isRecording

    fun getRecordingDuration(): Long {
        return if (recordingStartTime > 0) {
            System.currentTimeMillis() - recordingStartTime
        } else 0
    }

    fun getCurrentDbLevel(): Double = noiseLevelMonitor?.getCurrentDbLevel() ?: 0.0
}
