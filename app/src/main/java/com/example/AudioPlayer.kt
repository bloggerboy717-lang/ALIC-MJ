package com.example

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import java.util.concurrent.ConcurrentLinkedQueue

class AudioPlayer {
    private var audioTrack: AudioTrack? = null
    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()
    private var isPlaying = false
    private var playerThread: Thread? = null

    init {
        val sampleRate = 24000 // Gemini returns 24kHz audio
        val channelConfig = AudioFormat.CHANNEL_OUT_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 4

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelConfig)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    fun playAudioChunk(pcmData: ByteArray) {
        audioQueue.add(pcmData)
        if (!isPlaying) {
            startPlaybackLoop()
        }
    }

    private fun startPlaybackLoop() {
        isPlaying = true
        audioTrack?.play()
        playerThread = Thread {
            try {
                while (isPlaying) {
                    val chunk = audioQueue.poll()
                    if (chunk != null) {
                        audioTrack?.write(chunk, 0, chunk.size)
                    } else {
                        Thread.sleep(10)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }.apply { start() }
    }

    fun stop() {
        isPlaying = false
        audioQueue.clear()
        audioTrack?.stop()
        audioTrack?.flush()
        playerThread?.join()
    }

    fun release() {
        stop()
        audioTrack?.release()
        audioTrack = null
    }
}
