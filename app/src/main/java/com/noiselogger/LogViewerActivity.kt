package com.noiselogger

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class LogViewerActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var btnExport: Button
    private lateinit var btnRecordings: Button

    private lateinit var recordingManager: RecordingManager
    private var showingLogs = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log_viewer)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "Noise Logs"

        recordingManager = RecordingManager(this)

        initViews()
        setupClickListeners()
        loadLogs()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    private fun initViews() {
        recyclerView = findViewById(R.id.recyclerView)
        tvEmpty = findViewById(R.id.tvEmpty)
        btnExport = findViewById(R.id.btnExport)
        btnRecordings = findViewById(R.id.btnRecordings)

        recyclerView.layoutManager = LinearLayoutManager(this)
    }

    private fun setupClickListeners() {
        btnExport.setOnClickListener {
            if (showingLogs) {
                exportLog()
            } else {
                shareAllRecordings()
            }
        }

        btnRecordings.setOnClickListener {
            if (showingLogs) {
                loadRecordings()
                btnRecordings.text = "Show Logs"
                supportActionBar?.title = "Recordings"
                btnExport.text = "Share All"
            } else {
                loadLogs()
                btnRecordings.text = "Show Recordings"
                supportActionBar?.title = "Noise Logs"
                btnExport.text = getString(R.string.export_log)
            }
            showingLogs = !showingLogs
        }
    }

    private fun loadLogs() {
        val entries = recordingManager.getLogEntries(500)

        if (entries.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = "No log entries yet.\nStart recording to collect data."
            recyclerView.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            recyclerView.adapter = LogAdapter(entries)
        }
    }

    private fun loadRecordings() {
        val recordings = recordingManager.getRecordings()

        if (recordings.isEmpty()) {
            tvEmpty.visibility = View.VISIBLE
            tvEmpty.text = "No recordings yet.\nStart recording to create audio files."
            recyclerView.visibility = View.GONE
        } else {
            tvEmpty.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE
            recyclerView.adapter = RecordingAdapter(
                recordings,
                onPlayClick = { recording -> playRecording(recording) },
                onShareClick = { recording -> shareRecording(recording) }
            )
        }
    }

    private fun exportLog() {
        val logFile = recordingManager.getLogFile()
        if (!logFile.exists()) {
            return
        }

        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                logFile
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(intent, "Export Log"))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun playRecording(recording: RecordingInfo) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                recording.file
            )

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "audio/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun shareRecording(recording: RecordingInfo) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                recording.file
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Noise Recording: ${recording.name}")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(intent, "Share Recording"))
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to share recording", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareAllRecordings() {
        val recordings = recordingManager.getRecordings()
        if (recordings.isEmpty()) {
            Toast.makeText(this, "No recordings to share", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uris = ArrayList<android.net.Uri>()
            for (recording in recordings) {
                val uri = FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    recording.file
                )
                uris.add(uri)
            }

            // Also include the log file
            val logFile = recordingManager.getLogFile()
            if (logFile.exists()) {
                val logUri = FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    logFile
                )
                uris.add(logUri)
            }

            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                putExtra(Intent.EXTRA_SUBJECT, "Noise Logger Recordings")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            startActivity(Intent.createChooser(intent, "Share All Recordings"))
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to share recordings", Toast.LENGTH_SHORT).show()
        }
    }

    // Log entry adapter
    inner class LogAdapter(private val entries: List<LogEntry>) :
        RecyclerView.Adapter<LogAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvTimestamp: TextView = view.findViewById(R.id.tvTimestamp)
            val tvDbLevel: TextView = view.findViewById(R.id.tvDbLevel)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_log_entry, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = entries[position]
            holder.tvTimestamp.text = entry.timestamp
            holder.tvDbLevel.text = "${"%.1f".format(entry.dbLevel)} dB"

            // Color based on level
            val colorRes = when {
                entry.dbLevel < 50 -> R.color.level_low
                entry.dbLevel < 70 -> R.color.level_medium
                entry.dbLevel < 85 -> R.color.level_high
                else -> R.color.level_extreme
            }
            holder.tvDbLevel.setTextColor(getColor(colorRes))
        }

        override fun getItemCount() = entries.size
    }

    // Recording adapter
    inner class RecordingAdapter(
        private val recordings: List<RecordingInfo>,
        private val onPlayClick: (RecordingInfo) -> Unit,
        private val onShareClick: (RecordingInfo) -> Unit
    ) : RecyclerView.Adapter<RecordingAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val tvName: TextView = view.findViewById(R.id.tvName)
            val tvInfo: TextView = view.findViewById(R.id.tvInfo)
            val btnShare: ImageButton = view.findViewById(R.id.btnShare)
            val layoutInfo: View = view.findViewById(R.id.layoutInfo)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_recording, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val recording = recordings[position]
            holder.tvName.text = recording.name
            holder.tvInfo.text = "${recording.size} - ${recording.date}"

            holder.layoutInfo.setOnClickListener {
                onPlayClick(recording)
            }

            holder.btnShare.setOnClickListener {
                onShareClick(recording)
            }
        }

        override fun getItemCount() = recordings.size
    }
}
