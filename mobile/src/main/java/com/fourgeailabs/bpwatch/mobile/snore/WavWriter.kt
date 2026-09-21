package com.fourgeailabs.bpwatch.mobile.snore

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes PCM 16-bit mono 16 kHz frames to a WAV file with a proper RIFF
 * header, so the clips play back in MediaPlayer and any audio player.
 */
object WavWriter {
    const val SAMPLE_RATE = 16_000
    const val CHANNELS = 1
    const val BITS_PER_SAMPLE = 16

    fun writePcm16Mono16k(file: File, frames: List<ByteArray>) {
        val dataSize = frames.sumOf { it.size }
        FileOutputStream(file).use { out ->
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray(Charsets.US_ASCII))
            header.putInt(36 + dataSize)
            header.put("WAVE".toByteArray(Charsets.US_ASCII))
            header.put("fmt ".toByteArray(Charsets.US_ASCII))
            header.putInt(16) // PCM format chunk size
            header.putShort(1) // audio format: PCM
            header.putShort(CHANNELS.toShort())
            header.putInt(SAMPLE_RATE)
            header.putInt(SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8) // byte rate
            header.putShort((CHANNELS * BITS_PER_SAMPLE / 8).toShort()) // block align
            header.putShort(BITS_PER_SAMPLE.toShort())
            header.put("data".toByteArray(Charsets.US_ASCII))
            header.putInt(dataSize)
            out.write(header.array())
            for (frame in frames) {
                out.write(frame)
            }
        }
    }
}
