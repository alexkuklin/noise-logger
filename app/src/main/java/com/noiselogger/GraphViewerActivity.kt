package com.noiselogger

import android.graphics.Color
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.LimitLine
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import com.github.mikephil.charting.highlight.Highlight
import com.github.mikephil.charting.listener.OnChartValueSelectedListener
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class GraphViewerActivity : AppCompatActivity(), OnChartValueSelectedListener {

    private lateinit var chart: LineChart
    private lateinit var tvInfo: TextView
    private lateinit var tvPlaybackTime: TextView
    private lateinit var btnPlayPause: Button
    private lateinit var btnZoomIn: Button
    private lateinit var btnZoomOut: Button
    private lateinit var btnResetZoom: Button

    private lateinit var recordingManager: RecordingManager

    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false
    private var currentLogFile: File? = null

    private val handler = Handler(Looper.getMainLooper())
    private var playbackRunnable: Runnable? = null

    // Data structures
    private var logEntries = mutableListOf<GraphLogEntry>()
    private var recordingFiles = mutableMapOf<String, File>()
    private var sessionStartTime: Long = 0

    // Playback marker
    private var playbackMarker: LimitLine? = null

    data class GraphLogEntry(
        val timestamp: Long,
        val dbLevel: Double,
        val recordingFile: String,
        val offsetInFile: Long // milliseconds from start of this recording file
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_graph_viewer)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Noise Graph"

        recordingManager = RecordingManager(this)

        initViews()
        setupChart()
        loadData()
        setupClickListeners()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
    }

    private fun initViews() {
        chart = findViewById(R.id.chart)
        tvInfo = findViewById(R.id.tvInfo)
        tvPlaybackTime = findViewById(R.id.tvPlaybackTime)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnZoomIn = findViewById(R.id.btnZoomIn)
        btnZoomOut = findViewById(R.id.btnZoomOut)
        btnResetZoom = findViewById(R.id.btnResetZoom)
    }

    private fun setupChart() {
        chart.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            setDrawGridBackground(false)
            setOnChartValueSelectedListener(this@GraphViewerActivity)

            // X axis (time)
            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(true)
                granularity = 60f // 1 minute minimum
                valueFormatter = TimeAxisFormatter()
            }

            // Y axis (dB)
            axisLeft.apply {
                setDrawGridLines(true)
                axisMinimum = 20f
                axisMaximum = 100f
            }
            axisRight.isEnabled = false

            // Legend
            legend.isEnabled = true

            // No data text
            setNoDataText("Loading noise data...")
        }
    }

    private fun loadData() {
        val logFile = recordingManager.getLogFile()
        if (!logFile.exists()) {
            tvInfo.text = "No log data available"
            return
        }

        currentLogFile = logFile
        logEntries.clear()

        // Parse log file
        val lines = logFile.readLines()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        var currentRecordingFile = ""
        var currentRecordingStartTime = 0L

        // Load recording files
        recordingManager.getRecordings().forEach { recording ->
            recordingFiles[recording.name] = recording.file
        }

        for (line in lines) {
            if (line.startsWith("#") || line.startsWith("timestamp") || line.isBlank()) {
                continue
            }

            val parts = line.split(",")
            if (parts.size >= 2) {
                try {
                    val timestamp = dateFormat.parse(parts[0])?.time ?: continue
                    val dbLevel = parts[1].toDoubleOrNull() ?: continue
                    val recordingFile = parts.getOrElse(5) { "" }

                    if (sessionStartTime == 0L) {
                        sessionStartTime = timestamp
                    }

                    // Track recording file changes to calculate offset
                    if (recordingFile != currentRecordingFile) {
                        currentRecordingFile = recordingFile
                        currentRecordingStartTime = timestamp
                    }

                    val offsetInFile = timestamp - currentRecordingStartTime

                    logEntries.add(
                        GraphLogEntry(
                            timestamp = timestamp,
                            dbLevel = dbLevel,
                            recordingFile = recordingFile,
                            offsetInFile = offsetInFile
                        )
                    )
                } catch (e: Exception) {
                    // Skip malformed lines
                }
            }
        }

        if (logEntries.isEmpty()) {
            tvInfo.text = "No data points found"
            return
        }

        displayChart()
        updateInfo()
    }

    private fun displayChart() {
        val entries = logEntries.mapIndexed { index, entry ->
            // X = seconds from session start
            val secondsFromStart = (entry.timestamp - sessionStartTime) / 1000f
            Entry(secondsFromStart, entry.dbLevel.toFloat())
        }

        val dataSet = LineDataSet(entries, "Noise Level (dB)").apply {
            color = ContextCompat.getColor(this@GraphViewerActivity, R.color.level_medium)
            setDrawCircles(false)
            lineWidth = 1.5f
            setDrawValues(false)
            setDrawFilled(true)
            fillColor = ContextCompat.getColor(this@GraphViewerActivity, R.color.level_medium)
            fillAlpha = 50
            mode = LineDataSet.Mode.LINEAR

            // Highlight
            highLightColor = Color.RED
            highlightLineWidth = 2f
        }

        chart.data = LineData(dataSet)
        chart.invalidate()

        // Add threshold lines
        addThresholdLines()
    }

    private fun addThresholdLines() {
        val leftAxis = chart.axisLeft

        // Clear existing limit lines
        leftAxis.removeAllLimitLines()

        // Add noise level thresholds
        val moderateLine = LimitLine(50f, "Moderate").apply {
            lineColor = ContextCompat.getColor(this@GraphViewerActivity, R.color.level_low)
            lineWidth = 1f
            enableDashedLine(10f, 10f, 0f)
        }

        val loudLine = LimitLine(70f, "Loud").apply {
            lineColor = ContextCompat.getColor(this@GraphViewerActivity, R.color.level_medium)
            lineWidth = 1f
            enableDashedLine(10f, 10f, 0f)
        }

        val veryLoudLine = LimitLine(85f, "Very Loud").apply {
            lineColor = ContextCompat.getColor(this@GraphViewerActivity, R.color.level_high)
            lineWidth = 1f
            enableDashedLine(10f, 10f, 0f)
        }

        leftAxis.addLimitLine(moderateLine)
        leftAxis.addLimitLine(loudLine)
        leftAxis.addLimitLine(veryLoudLine)
    }

    private fun updateInfo() {
        if (logEntries.isEmpty()) return

        val duration = (logEntries.last().timestamp - sessionStartTime) / 1000
        val maxDb = logEntries.maxOf { it.dbLevel }
        val avgDb = logEntries.map { it.dbLevel }.average()

        tvInfo.text = "Duration: ${formatDuration(duration * 1000)} | " +
                "Max: ${"%.0f".format(maxDb)} dB | " +
                "Avg: ${"%.0f".format(avgDb)} dB"
    }

    private fun setupClickListeners() {
        btnPlayPause.setOnClickListener {
            if (isPlaying) {
                pausePlayback()
            } else {
                // If nothing selected, start from beginning
                val highlight = chart.highlighted?.firstOrNull()
                if (highlight != null) {
                    playFromPosition(highlight.x)
                } else if (logEntries.isNotEmpty()) {
                    playFromPosition(0f)
                }
            }
        }

        btnZoomIn.setOnClickListener {
            chart.zoom(1.5f, 1f, chart.width / 2f, chart.height / 2f)
        }

        btnZoomOut.setOnClickListener {
            chart.zoom(0.67f, 1f, chart.width / 2f, chart.height / 2f)
        }

        btnResetZoom.setOnClickListener {
            chart.fitScreen()
        }
    }

    override fun onValueSelected(e: Entry?, h: Highlight?) {
        if (e == null) return

        val secondsFromStart = e.x.toLong()
        val entryIndex = logEntries.indexOfFirst {
            (it.timestamp - sessionStartTime) / 1000 == secondsFromStart
        }

        if (entryIndex >= 0) {
            val entry = logEntries[entryIndex]
            val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US)
                .format(Date(entry.timestamp))

            tvPlaybackTime.text = "$timeStr - ${"%.1f".format(entry.dbLevel)} dB"

            // If playing, seek to this position
            if (isPlaying) {
                playFromPosition(e.x)
            }
        }
    }

    override fun onNothingSelected() {
        tvPlaybackTime.text = "Tap on graph to select point"
    }

    private fun playFromPosition(secondsFromStart: Float) {
        stopPlayback()

        // Find the entry at this position
        val targetTime = sessionStartTime + (secondsFromStart * 1000).toLong()
        val entry = logEntries.minByOrNull {
            kotlin.math.abs(it.timestamp - targetTime)
        } ?: return

        val recordingFile = recordingFiles[entry.recordingFile]
        if (recordingFile == null || !recordingFile.exists()) {
            Toast.makeText(this, "Recording file not found", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            mediaPlayer = MediaPlayer().apply {
                setDataSource(recordingFile.absolutePath)
                prepare()
                seekTo(entry.offsetInFile.toInt())
                start()
            }

            isPlaying = true
            btnPlayPause.text = "Pause"

            // Start playback position updates
            startPlaybackUpdates(secondsFromStart)

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to play audio", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startPlaybackUpdates(startSeconds: Float) {
        playbackRunnable = object : Runnable {
            var currentSeconds = startSeconds

            override fun run() {
                if (isPlaying && mediaPlayer != null) {
                    // Update marker position
                    updatePlaybackMarker(currentSeconds)

                    // Update time display
                    val currentTime = sessionStartTime + (currentSeconds * 1000).toLong()
                    val entry = logEntries.minByOrNull {
                        kotlin.math.abs(it.timestamp - currentTime)
                    }
                    if (entry != null) {
                        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US)
                            .format(Date(entry.timestamp))
                        tvPlaybackTime.text = "$timeStr - ${"%.1f".format(entry.dbLevel)} dB"
                    }

                    currentSeconds += 0.5f // Update every 500ms
                    handler.postDelayed(this, 500)
                }
            }
        }
        handler.post(playbackRunnable!!)

        // Set completion listener
        mediaPlayer?.setOnCompletionListener {
            stopPlayback()
        }
    }

    private fun updatePlaybackMarker(seconds: Float) {
        val xAxis = chart.xAxis

        // Remove old marker
        playbackMarker?.let { xAxis.removeLimitLine(it) }

        // Add new marker
        playbackMarker = LimitLine(seconds).apply {
            lineColor = Color.RED
            lineWidth = 2f
        }
        xAxis.addLimitLine(playbackMarker)
        chart.invalidate()
    }

    private fun pausePlayback() {
        mediaPlayer?.pause()
        isPlaying = false
        btnPlayPause.text = "Play"
        playbackRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun stopPlayback() {
        mediaPlayer?.apply {
            if (isPlaying) {
                stop()
            }
            release()
        }
        mediaPlayer = null
        isPlaying = false
        btnPlayPause.text = "Play"
        playbackRunnable?.let { handler.removeCallbacks(it) }

        // Remove playback marker
        playbackMarker?.let { chart.xAxis.removeLimitLine(it) }
        playbackMarker = null
        chart.invalidate()
    }

    private fun formatDuration(millis: Long): String {
        val seconds = (millis / 1000) % 60
        val minutes = (millis / (1000 * 60)) % 60
        val hours = millis / (1000 * 60 * 60)
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    // X-axis formatter to show time
    inner class TimeAxisFormatter : ValueFormatter() {
        override fun getFormattedValue(value: Float): String {
            val totalSeconds = value.toLong()
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            return "%d:%02d".format(minutes, seconds)
        }
    }
}
