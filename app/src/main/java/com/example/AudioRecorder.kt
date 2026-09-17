package com.example

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class AudioRecorder(
    private val onAudioChunk: (ByteArray) -> Unit
) {
    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    @SuppressLint("MissingPermission")
    suspend fun start() = withContext(Dispatchers.IO) {
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        audioRecord?.startRecording()
        isRecording = true

        val buffer = ByteArray(2048) // Process in chunks
        while (isActive && isRecording) {
            val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
            if (read > 0) {
                val chunk = buffer.copyOf(read)
                onAudioChunk(chunk)
            }
        }

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    fun stop() {
        isRecording = false
    }
}
