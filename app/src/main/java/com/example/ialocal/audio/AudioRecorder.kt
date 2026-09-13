package com.example.ialocal.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.ialocal.data.AttachmentType
import com.example.ialocal.data.PendingAttachment
import java.io.File
import java.util.UUID

class AudioRecorder(
    private val context: Context,
) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null

    val isRecording: Boolean get() = recorder != null

    fun start() {
        if (recorder != null) return
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("A permissão de microfone é necessária para gravar áudio.")
        }

        val dir = File(context.filesDir, "audio").apply { mkdirs() }
        require(dir.isDirectory) { "Não foi possível preparar a pasta privada de áudio." }
        val file = File(dir, "audio-${System.currentTimeMillis()}.m4a")

        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        try {
            mediaRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96_000)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            outputFile = file
            recorder = mediaRecorder
        } catch (t: Throwable) {
            runCatching { mediaRecorder.reset() }
            runCatching { mediaRecorder.release() }
            runCatching { file.delete() }
            throw t
        }
    }

    fun stop(): PendingAttachment? {
        val active = recorder ?: return null
        val file = outputFile

        return try {
            active.stop()
            file?.takeIf { it.exists() && it.length() > 0 }?.let {
                PendingAttachment(
                    id = UUID.randomUUID().toString(),
                    type = AttachmentType.AUDIO,
                    fileName = it.name,
                    localPath = it.absolutePath,
                    mimeType = "audio/mp4",
                    sizeBytes = it.length(),
                )
            }
        } catch (_: RuntimeException) {
            file?.delete()
            null
        } finally {
            runCatching { active.reset() }
            runCatching { active.release() }
            recorder = null
            outputFile = null
        }
    }

    fun cancel() {
        val active = recorder
        if (active != null) {
            runCatching { active.stop() }
            runCatching { active.reset() }
            runCatching { active.release() }
        }
        runCatching { outputFile?.delete() }
        recorder = null
        outputFile = null
    }
}
