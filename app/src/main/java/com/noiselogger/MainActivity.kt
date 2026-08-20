package com.noiselogger

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
    }

    private lateinit var btnStartStop: Button
    private lateinit var btnViewLogs: Button
    private lateinit var tvDbLevel: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvDuration: TextView
    private lateinit var progressDb: ProgressBar

    private var recorderService: AudioRecorderService? = null
    private var isBound = false

    private val handler = Handler(Looper.getMainLooper())
    private var updateRunnable: Runnable? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as AudioRecorderService.LocalBinder
            recorderService = binder.getService()
            isBound = true

            recorderService?.onDbLevelChanged = { dbLevel ->
                runOnUiThread {
                    updateDbDisplay(dbLevel)
                }
            }

            recorderService?.onRecordingStateChanged = { isRecording ->
                runOnUiThread {
                    updateUI(isRecording)
                }
            }

            updateUI(recorderService?.isRecording() == true)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recorderService = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        setupClickListeners()
    }

    override fun onStart() {
        super.onStart()
        bindToService()
        startDurationUpdates()
    }

    override fun onStop() {
        super.onStop()
        stopDurationUpdates()
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun initViews() {
        btnStartStop = findViewById(R.id.btnStartStop)
        btnViewLogs = findViewById(R.id.btnViewLogs)
        tvDbLevel = findViewById(R.id.tvDbLevel)
        tvStatus = findViewById(R.id.tvStatus)
        tvDuration = findViewById(R.id.tvDuration)
        progressDb = findViewById(R.id.progressDb)

        progressDb.max = 100
    }

    private fun setupClickListeners() {
        btnStartStop.setOnClickListener {
            if (recorderService?.isRecording() == true) {
                stopRecording()
            } else {
                checkPermissionsAndStart()
            }
        }

        btnViewLogs.setOnClickListener {
            startActivity(Intent(this, LogViewerActivity::class.java))
        }
    }

    private fun bindToService() {
        Intent(this, AudioRecorderService::class.java).also { intent ->
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        }
    }

    private fun checkPermissionsAndStart() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequest.isEmpty()) {
            startRecording()
        } else {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            // Check if audio permission is granted (either just now or previously)
            val audioGranted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

            if (audioGranted) {
                // Location is optional - just notify if not available
                val locationGranted = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                if (!locationGranted) {
                    Toast.makeText(
                        this,
                        "Location not available - GPS coordinates won't be recorded",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                startRecording()
            } else {
                Toast.makeText(
                    this,
                    "Microphone permission is required for recording",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun startRecording() {
        val intent = Intent(this, AudioRecorderService::class.java).apply {
            action = AudioRecorderService.ACTION_START
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        // Rebind to get callbacks
        if (!isBound) {
            bindToService()
        }
    }

    private fun stopRecording() {
        recorderService?.stopRecording()
    }

    private fun updateUI(isRecording: Boolean) {
        if (isRecording) {
            btnStartStop.text = getString(R.string.stop_recording)
            btnStartStop.setBackgroundColor(ContextCompat.getColor(this, R.color.stop_button))
            tvStatus.text = getString(R.string.status_recording)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.recording))
        } else {
            btnStartStop.text = getString(R.string.start_recording)
            btnStartStop.setBackgroundColor(ContextCompat.getColor(this, R.color.start_button))
            tvStatus.text = getString(R.string.status_stopped)
            tvStatus.setTextColor(ContextCompat.getColor(this, R.color.stopped))
            tvDuration.text = "00:00:00"
            updateDbDisplay(0.0)
        }
    }

    private fun updateDbDisplay(dbLevel: Double) {
        tvDbLevel.text = "${"%.0f".format(dbLevel)} dB"

        // Map dB to progress (20-100 dB range)
        val progress = ((dbLevel - NoiseLevelMonitor.MIN_DB) /
                (NoiseLevelMonitor.MAX_DB - NoiseLevelMonitor.MIN_DB) * 100).toInt()
            .coerceIn(0, 100)
        progressDb.progress = progress

        // Color based on noise level
        val color = when {
            dbLevel < 50 -> R.color.level_low
            dbLevel < 70 -> R.color.level_medium
            dbLevel < 85 -> R.color.level_high
            else -> R.color.level_extreme
        }
        tvDbLevel.setTextColor(ContextCompat.getColor(this, color))
    }

    private fun startDurationUpdates() {
        updateRunnable = object : Runnable {
            override fun run() {
                recorderService?.let { service ->
                    if (service.isRecording()) {
                        val duration = service.getRecordingDuration()
                        tvDuration.text = formatDuration(duration)
                    }
                }
                handler.postDelayed(this, 1000)
            }
        }
        handler.post(updateRunnable!!)
    }

    private fun stopDurationUpdates() {
        updateRunnable?.let { handler.removeCallbacks(it) }
        updateRunnable = null
    }

    private fun formatDuration(millis: Long): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        val hours = millis / (1000 * 60 * 60)
        return "%02d:%02d:%02d".format(hours, minutes, seconds)
    }
}
