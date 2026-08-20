package com.noiselogger

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.abs
import kotlin.math.log10

class NoiseLevelMonitor {

    companion object {
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        // Reference amplitude for dB calculation
        // This is calibrated for typical smartphone microphones
        private const val REFERENCE_AMPLITUDE = 1.0

        // dB range for display (approximate values for smartphone mics)
        const val MIN_DB = 20.0
        const val MAX_DB = 100.0
    }

    private var audioRecord: AudioRecord? = null
    private var isMonitoring = false
    private var monitorThread: Thread? = null
    private var currentDbLevel = 0.0

    var onNoiseLevelChanged: ((Double) -> Unit)? = null

    fun start() {
        if (isMonitoring) return

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )

        if (bufferSize == AudioRecord.ERROR || bufferSize == AudioRecord.ERROR_BAD_VALUE) {
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord?.release()
                audioRecord = null
                return
            }

            audioRecord?.startRecording()
            isMonitoring = true

            monitorThread = Thread {
                val buffer = ShortArray(bufferSize / 2)

                try {
                    while (isMonitoring) {
                        val readSize = audioRecord?.read(buffer, 0, buffer.size) ?: 0

                        if (readSize > 0) {
                            val amplitude = calculateAmplitude(buffer, readSize)
                            currentDbLevel = calculateDb(amplitude)
                            onNoiseLevelChanged?.invoke(currentDbLevel)
                        }

                        // Small sleep to avoid excessive CPU usage
                        Thread.sleep(100)
                    }
                } catch (e: InterruptedException) {
                    // Thread interrupted during stop, this is expected
                }
            }.apply { start() }

        } catch (e: SecurityException) {
            // Permission not granted
            e.printStackTrace()
        }
    }

    fun stop() {
        isMonitoring = false
        monitorThread?.interrupt()
        monitorThread = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    fun getCurrentDbLevel(): Double = currentDbLevel

    private fun calculateAmplitude(buffer: ShortArray, readSize: Int): Double {
        var sum = 0L
        for (i in 0 until readSize) {
            sum += abs(buffer[i].toInt())
        }
        return sum.toDouble() / readSize
    }

    private fun calculateDb(amplitude: Double): Double {
        if (amplitude <= 0) return MIN_DB

        // Convert amplitude to decibels
        // Using a reference that gives reasonable values for smartphone mics
        val db = 20 * log10(amplitude / REFERENCE_AMPLITUDE)

        // Clamp to reasonable range
        return db.coerceIn(MIN_DB, MAX_DB)
    }

    fun isRunning(): Boolean = isMonitoring
}
