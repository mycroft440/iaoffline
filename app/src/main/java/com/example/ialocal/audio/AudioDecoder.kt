package com.example.ialocal.audio

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

class AudioDecoder {
    fun decodeToPcm16(file: File): DecodedAudio {
        require(file.isFile) { "Arquivo de áudio não encontrado." }
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalArgumentException("O arquivo não contém uma faixa de áudio compatível.")
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalArgumentException("Formato de áudio desconhecido.")
            val decoder = MediaCodec.createDecoderByType(mime)
            try {
                decoder.configure(inputFormat, null, null, 0)
                decoder.start()
                return drain(extractor, decoder)
            } finally {
                runCatching { decoder.stop() }
                decoder.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun drain(extractor: MediaExtractor, codec: MediaCodec): DecodedAudio {
        var inputDone = false
        var outputDone = false
        var sampleRate = 16_000
        var channels = 1
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        val output = ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()

        while (!outputDone) {
            if (!inputDone) {
                val inputIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                if (inputIndex >= 0) {
                    val inputBuffer = codec.getInputBuffer(inputIndex)
                        ?: throw IllegalStateException("Decoder de áudio sem buffer de entrada.")
                    inputBuffer.clear()
                    val sampleSize = extractor.readSampleData(inputBuffer, 0)
                    if (sampleSize < 0) {
                        codec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }

            when (val outputIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)) {
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = codec.outputFormat
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    pcmEncoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    } else AudioFormat.ENCODING_PCM_16BIT
                    require(pcmEncoding == AudioFormat.ENCODING_PCM_16BIT) {
                        "O decoder retornou PCM não suportado ($pcmEncoding)."
                    }
                }
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                else -> if (outputIndex >= 0) {
                    val buffer: ByteBuffer = codec.getOutputBuffer(outputIndex)
                        ?: throw IllegalStateException("Decoder de áudio sem buffer de saída.")
                    if (info.size > 0) {
                        require(output.size() + info.size <= MAX_PCM_BYTES) {
                            "Áudio longo demais para transcrição nesta versão."
                        }
                        val chunk = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        buffer.get(chunk)
                        output.write(chunk)
                    }
                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(outputIndex, false)
                }
            }
        }
        require(output.size() > 0) { "Não foi possível decodificar o áudio." }
        return DecodedAudio(output.toByteArray(), sampleRate, channels)
    }

    companion object {
        private const val TIMEOUT_US = 10_000L
        private const val MAX_PCM_BYTES = 64 * 1024 * 1024
    }
}
