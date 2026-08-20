package com.noiselogger

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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
import java.util.concurrent.Executors

class GraphViewerActivity : AppCompatActivity(), OnChartValueSelectedListener {

    private lateinit var chart: LineChart
    private lateinit var btnFromDate: Button
    private lateinit var btnToDate: Button
    private lateinit var btnApplyRange: Button
    private lateinit var tvInfo: TextView
    private lateinit var tvPlaybackTime: TextView
    private lateinit var btnPlayPause: Button
    private lateinit var btnZoomIn: Button
    private lateinit var btnZoomOut: Button
    private lateinit var btnResetZoom: Button

    private lateinit var recordingManager: RecordingManager

    private var mediaPlayer: MediaPlayer? = null
    private var isPlaying = false
    private var isPrepared = false

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var playbackRunnable: Runnable? = null

    // Date range
    private var fromDate: Calendar = Calendar.getInstance()
    private var toDate: Calendar = Calendar.getInstance()
    private val dateTimeFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.US)

    // Data from SQLite
    private var readings = listOf<NoiseDatabase.NoiseReading>()
    private var recordingFiles = mutableMapOf<String, File>()
    private var rangeStartTime: Long = 0

    // Playback marker
    private var playbackMarker: LimitLine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_graph_viewer)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Noise Graph"

        recordingManager = RecordingManager(this)

        // Set default range: last 24 hours
        toDate = Calendar.getInstance()
        fromDate = Calendar.getInstance().apply {
            add(Calendar.HOUR_OF_DAY, -24)
        }

        initViews()
        setupChart()
        loadRecordingFiles()
        updateDateButtons()
        loadDataForRange()
        setupClickListeners()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPlayback()
        executor.shutdown()
    }

    private fun initViews() {
        chart = findViewById(R.id.chart)
        btnFromDate = findViewById(R.id.btnFromDate)
        btnToDate = findViewById(R.id.btnToDate)
        btnApplyRange = findViewById(R.id.btnApplyRange)
        tvInfo = findViewById(R.id.tvInfo)
        tvPlaybackTime = findViewById(R.id.tvPlaybackTime)
        btnPlayPause = findViewById(R.id.btnPlayPause)
        btnZoomIn = findViewById(R.id.btnZoomIn)
        btnZoomOut = findViewById(R.id.btnZoomOut)
        btnResetZoom = findViewById(R.id.btnResetZoom)
    }

    private fun loadRecordingFiles() {
        recordingManager.getRecordings().forEach { recording ->
            recordingFiles[recording.name] = recording.file
        }
    }

    private fun updateDateButtons() {
        btnFromDate.text = dateTimeFormat.format(fromDate.time)
        btnToDate.text = dateTimeFormat.format(toDate.time)
    }

    private fun showDateTimePicker(calendar: Calendar, onSet: (Calendar) -> Unit) {
        val currentYear = calendar.get(Calendar.YEAR)
        val currentMonth = calendar.get(Calendar.MONTH)
        val currentDay = calendar.get(Calendar.DAY_OF_MONTH)
        val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
        val currentMinute = calendar.get(Calendar.MINUTE)

        DatePickerDialog(this, { _, year, month, day ->
            TimePickerDialog(this, { _, hour, minute ->
                val newCal = Calendar.getInstance().apply {
                    set(year, month, day, hour, minute, 0)
                    set(Calendar.MILLISECOND, 0)
                }
                onSet(newCal)
            }, currentHour, currentMinute, true).show()
        }, currentYear, currentMonth, currentDay).show()
    }

    private fun setupClickListeners() {
        btnFromDate.setOnClickListener {
            showDateTimePicker(fromDate) { cal ->
                fromDate = cal
                updateDateButtons()
            }
        }

        btnToDate.setOnClickListener {
            showDateTimePicker(toDate) { cal ->
                toDate = cal
                updateDateButtons()
            }
        }

        btnApplyRange.setOnClickListener {
            if (fromDate.timeInMillis >= toDate.timeInMillis) {
                Toast.makeText(this, "Start must be before end", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            loadDataForRange()
        }

        btnPlayPause.setOnClickListener {
            if (isPlaying) {
                pausePlayback()
            } else {
                val highlight = chart.highlighted?.firstOrNull()
                val startPos = highlight?.x ?: 0f
                playFromPosition(startPos)
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

    private fun setupChart() {
        chart.apply {
            description.isEnabled = false
            setTouchEnabled(true)
            isDragEnabled = true
            setScaleEnabled(true)
            setPinchZoom(true)
            setDrawGridBackground(false)
            setOnChartValueSelectedListener(this@GraphViewerActivity)

            xAxis.apply {
                position = XAxis.XAxisPosition.BOTTOM
                setDrawGridLines(true)
                granularity = 60f
                valueFormatter = TimeAxisFormatter()
            }

            axisLeft.apply {
                setDrawGridLines(true)
                axisMinimum = 20f
                axisMaximum = 100f
            }
            axisRight.isEnabled = false
            legend.isEnabled = true
            setNoDataText("Select a time range and tap Apply")
        }
    }

    private fun loadDataForRange() {
        stopPlayback()
        rangeStartTime = fromDate.timeInMillis

        val fromTime = fromDate.timeInMillis
        val toTime = toDate.timeInMillis

        // Query SQLite database
        readings = recordingManager.getReadingsInRange(fromTime, toTime)

        if (readings.isEmpty()) {
            tvInfo.text = "No data in selected range"
            displayEmptyChart()
            return
        }

        displayChart()
        updateInfo()
    }

    private fun displayEmptyChart() {
        // Show empty chart with full selected range
        val rangeSeconds = (toDate.timeInMillis - fromDate.timeInMillis) / 1000f
        chart.xAxis.apply {
            axisMinimum = 0f
            axisMaximum = rangeSeconds
        }

        val emptyDataSet = LineDataSet(emptyList(), "Noise Level (dB)").apply {
            color = ContextCompat.getColor(this@GraphViewerActivity, R.color.level_medium)
        }

        chart.data = LineData(emptyDataSet)
        chart.fitScreen()
        chart.invalidate()
        addThresholdLines()
    }

    private fun displayChart() {
        val entries = readings.map { reading ->
            val secondsFromStart = (reading.timestamp - rangeStartTime) / 1000f
            Entry(secondsFromStart, reading.dbLevel.toFloat())
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
            highLightColor = Color.RED
            highlightLineWidth = 2f
        }

        // Set X axis to show full selected range
        val rangeSeconds = (toDate.timeInMillis - fromDate.timeInMillis) / 1000f
        chart.xAxis.apply {
            axisMinimum = 0f
            axisMaximum = rangeSeconds
        }

        chart.data = LineData(dataSet)
        chart.fitScreen()
        chart.invalidate()
        addThresholdLines()
    }

    private fun addThresholdLines() {
        val leftAxis = chart.axisLeft
        leftAxis.removeAllLimitLines()

        listOf(
            Triple(50f, "Moderate", R.color.level_low),
            Triple(70f, "Loud", R.color.level_medium),
            Triple(85f, "Very Loud", R.color.level_high)
        ).forEach { (value, label, colorRes) ->
            leftAxis.addLimitLine(LimitLine(value, label).apply {
                lineColor = ContextCompat.getColor(this@GraphViewerActivity, colorRes)
                lineWidth = 1f
                enableDashedLine(10f, 10f, 0f)
                textSize = 10f
            })
        }
    }

    private fun updateInfo() {
        if (readings.isEmpty()) return

        val duration = (readings.last().timestamp - readings.first().timestamp) / 1000
        val maxDb = readings.maxOf { it.dbLevel }
        val avgDb = readings.map { it.dbLevel }.average()
        val peakCount = countPeaks(readings, 70.0)

        tvInfo.text = "Points: ${readings.size} | " +
                "Max: ${"%.0f".format(maxDb)} dB | " +
                "Avg: ${"%.0f".format(avgDb)} dB | " +
                "Peaks >70dB: $peakCount"
    }

    private fun countPeaks(entries: List<NoiseDatabase.NoiseReading>, threshold: Double): Int {
        var count = 0
        var wasAbove = false
        for (entry in entries) {
            val isAbove = entry.dbLevel >= threshold
            if (isAbove && !wasAbove) count++
            wasAbove = isAbove
        }
        return count
    }

    override fun onValueSelected(e: Entry?, h: Highlight?) {
        if (e == null) return

        val timestamp = rangeStartTime + (e.x * 1000).toLong()
        val reading = readings.minByOrNull {
            kotlin.math.abs(it.timestamp - timestamp)
        }

        if (reading != null) {
            val timeStr = SimpleDateFormat("MMM dd HH:mm:ss", Locale.US).format(Date(reading.timestamp))
            tvPlaybackTime.text = "$timeStr - ${"%.1f".format(reading.dbLevel)} dB"
        }
    }

    override fun onNothingSelected() {
        tvPlaybackTime.text = "Tap graph to select, then Play"
    }

    private fun playFromPosition(secondsFromStart: Float) {
        stopPlayback()

        if (readings.isEmpty()) {
            Toast.makeText(this, "No data to play", Toast.LENGTH_SHORT).show()
            return
        }

        val targetTime = rangeStartTime + (secondsFromStart * 1000).toLong()
        val reading = readings.minByOrNull {
            kotlin.math.abs(it.timestamp - targetTime)
        } ?: return

        val recordingFileName = reading.recordingFile
        if (recordingFileName.isEmpty()) {
            Toast.makeText(this, "No recording file for this point", Toast.LENGTH_SHORT).show()
            return
        }

        val recordingFile = recordingFiles[recordingFileName]
        if (recordingFile == null || !recordingFile.exists()) {
            Toast.makeText(this, "Recording not found: $recordingFileName", Toast.LENGTH_SHORT).show()
            return
        }

        // Calculate offset: find the first reading with this recording file
        val recordingStartReading = readings.firstOrNull { it.recordingFile == recordingFileName }
        val offsetInFile = if (recordingStartReading != null) {
            reading.timestamp - recordingStartReading.timestamp
        } else {
            0L
        }

        btnPlayPause.isEnabled = false
        btnPlayPause.text = "..."

        executor.execute {
            try {
                val player = MediaPlayer().apply {
                    setDataSource(recordingFile.absolutePath)
                    prepare()
                    seekTo(offsetInFile.toInt().coerceAtLeast(0))
                }

                handler.post {
                    mediaPlayer = player
                    isPrepared = true
                    player.start()
                    isPlaying = true
                    btnPlayPause.isEnabled = true
                    btnPlayPause.text = "Pause"
                    startPlaybackUpdates(secondsFromStart)

                    player.setOnCompletionListener {
                        handler.post { stopPlayback() }
                    }

                    player.setOnErrorListener { _, _, _ ->
                        handler.post {
                            Toast.makeText(this, "Playback error", Toast.LENGTH_SHORT).show()
                            stopPlayback()
                        }
                        true
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    btnPlayPause.isEnabled = true
                    btnPlayPause.text = "Play"
                    Toast.makeText(this, "Failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startPlaybackUpdates(startSeconds: Float) {
        var currentSeconds = startSeconds

        playbackRunnable = object : Runnable {
            override fun run() {
                if (isPlaying && mediaPlayer != null && isPrepared) {
                    updatePlaybackMarker(currentSeconds)

                    val timestamp = rangeStartTime + (currentSeconds * 1000).toLong()
                    val reading = readings.minByOrNull {
                        kotlin.math.abs(it.timestamp - timestamp)
                    }
                    if (reading != null) {
                        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(reading.timestamp))
                        tvPlaybackTime.text = "$timeStr - ${"%.1f".format(reading.dbLevel)} dB"
                    }

                    currentSeconds += 0.5f
                    handler.postDelayed(this, 500)
                }
            }
        }
        handler.post(playbackRunnable!!)
    }

    private fun updatePlaybackMarker(seconds: Float) {
        val xAxis = chart.xAxis
        playbackMarker?.let { xAxis.removeLimitLine(it) }

        playbackMarker = LimitLine(seconds).apply {
            lineColor = Color.RED
            lineWidth = 2f
        }
        xAxis.addLimitLine(playbackMarker)
        chart.invalidate()
    }

    private fun pausePlayback() {
        try {
            mediaPlayer?.pause()
        } catch (e: Exception) {}
        isPlaying = false
        btnPlayPause.text = "Play"
        playbackRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun stopPlayback() {
        playbackRunnable?.let { handler.removeCallbacks(it) }
        playbackRunnable = null

        try {
            mediaPlayer?.apply {
                if (isPrepared) stop()
                release()
            }
        } catch (e: Exception) {}

        mediaPlayer = null
        isPrepared = false
        isPlaying = false
        btnPlayPause.text = "Play"
        btnPlayPause.isEnabled = true

        playbackMarker?.let { chart.xAxis.removeLimitLine(it) }
        playbackMarker = null
        chart.invalidate()
    }

    inner class TimeAxisFormatter : ValueFormatter() {
        override fun getFormattedValue(value: Float): String {
            val timestamp = rangeStartTime + (value * 1000).toLong()
            return SimpleDateFormat("HH:mm", Locale.US).format(Date(timestamp))
        }
    }
}
