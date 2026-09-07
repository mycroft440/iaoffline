package com.example.ialocal.audio

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OnDeviceAudioTranscriber(
    private val context: Context,
    private val decoder: AudioDecoder = AudioDecoder(),
) {
    suspend fun transcribe(file: File): String {
        val audio = withContext(Dispatchers.IO) { decoder.decodeToPcm16(file) }
        return withContext(Dispatchers.Main.immediate) {
            require(SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
                "Este aparelho não possui reconhecimento de voz on-device disponível."
            }
            recognize(audio)
        }
    }

    private suspend fun recognize(audio: DecodedAudio): String = suspendCancellableCoroutine { continuation ->
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        val pipe = ParcelFileDescriptor.createPipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        val segments = mutableListOf<String>()
        val finished = AtomicBoolean(false)

        fun finish(text: String?, error: Throwable? = null) {
            if (!finished.compareAndSet(false, true)) return
            runCatching { readSide.close() }
            runCatching { writeSide.close() }
            runCatching { recognizer.destroy() }
            if (!continuation.isActive) return
            if (error != null) continuation.resumeWithException(error)
            else {
                val clean = text.orEmpty().trim()
                if (clean.isBlank()) continuation.resumeWithException(IllegalStateException("Nenhuma fala foi reconhecida no áudio."))
                else continuation.resume(clean)
            }
        }

        fun resultText(bundle: Bundle): String? =
            bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()

        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) = Unit
            override fun onEvent(eventType: Int, params: Bundle?) = Unit

            override fun onError(error: Int) {
                finish(null, IllegalStateException(errorMessage(error)))
            }

            override fun onResults(results: Bundle) {
                resultText(results)?.takeIf { it.isNotBlank() }?.let(segments::add)
                finish(segments.distinct().joinToString(" "))
            }

            override fun onSegmentResults(segmentResults: Bundle) {
                resultText(segmentResults)?.takeIf { it.isNotBlank() }?.let(segments::add)
            }

            override fun onEndOfSegmentedSession() {
                finish(segments.joinToString(" "))
            }
        })

        continuation.invokeOnCancellation {
            if (finished.compareAndSet(false, true)) {
                runCatching { recognizer.cancel() }
                runCatching { recognizer.destroy() }
                runCatching { readSide.close() }
                runCatching { writeSide.close() }
            }
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readSide)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, audio.channelCount)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, audio.sampleRate)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }

        try {
            recognizer.startListening(intent)
        } catch (t: Throwable) {
            finish(null, t)
            return@suspendCancellableCoroutine
        }
        thread(name = "audio-transcription-pipe", isDaemon = true) {
            runCatching {
                FileOutputStream(writeSide.fileDescriptor).use { output ->
                    output.write(audio.pcm16)
                    output.flush()
                }
            }
            runCatching { writeSide.close() }
        }
    }

    private fun errorMessage(code: Int): String = when (code) {
        SpeechRecognizer.ERROR_AUDIO -> "Falha ao fornecer o áudio ao reconhecedor."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permissão de áudio insuficiente."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "Idioma não suportado pelo reconhecedor local."
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "O modelo de voz deste idioma não está instalado no aparelho."
        SpeechRecognizer.ERROR_NO_MATCH -> "Não foi possível reconhecer fala nesse áudio."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "O reconhecedor de voz está ocupado. Tente novamente."
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "O serviço local de reconhecimento foi desconectado."
        else -> "Falha na transcrição local de áudio (código $code)."
    }
}
