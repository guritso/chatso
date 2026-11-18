package com.example.media

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class VoiceRecordingState(
    val isRecording: Boolean = false,
    val elapsedMs: Long = 0L,
    val outputFile: File? = null
)

class VoiceRecorderController(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var recorder: MediaRecorder? = null
    private var timerJob: Job? = null
    private var startedAtMs: Long = 0L

    private val _state = MutableStateFlow(VoiceRecordingState())
    val state: StateFlow<VoiceRecordingState> = _state.asStateFlow()

    fun startRecording(targetFile: File): Boolean {
        stopAndDiscard()
        return try {
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioEncodingBitRate(96_000)
            mr.setAudioSamplingRate(44_100)
            mr.setOutputFile(targetFile.absolutePath)
            mr.prepare()
            mr.start()

            recorder = mr
            startedAtMs = System.currentTimeMillis()
            _state.value = VoiceRecordingState(isRecording = true, elapsedMs = 0L, outputFile = targetFile)
            timerJob = scope.launch(Dispatchers.Main) {
                while (isActive && _state.value.isRecording) {
                    _state.value = _state.value.copy(
                        elapsedMs = System.currentTimeMillis() - startedAtMs
                    )
                    delay(200)
                }
            }
            true
        } catch (_: Exception) {
            stopAndDiscard()
            false
        }
    }

    fun stopAndFinalize(): Pair<File, Long>? {
        val current = _state.value
        if (!current.isRecording || current.outputFile == null) return null
        val duration = (System.currentTimeMillis() - startedAtMs).coerceAtLeast(500L)
        timerJob?.cancel()
        timerJob = null
        return try {
            recorder?.stop()
            recorder?.release()
            recorder = null
            _state.value = VoiceRecordingState()
            if (current.outputFile.exists() && current.outputFile.length() > 0L) {
                current.outputFile to duration
            } else {
                null
            }
        } catch (_: Exception) {
            try {
                recorder?.release()
            } catch (_: Exception) {
            }
            recorder = null
            current.outputFile.delete()
            _state.value = VoiceRecordingState()
            null
        }
    }

    fun stopAndDiscard() {
        timerJob?.cancel()
        timerJob = null
        val outFile = _state.value.outputFile
        try {
            recorder?.stop()
        } catch (_: Exception) {
        }
        try {
            recorder?.release()
        } catch (_: Exception) {
        }
        recorder = null
        outFile?.delete()
        _state.value = VoiceRecordingState()
    }
}

data class VoicePlaybackState(
    val activeMessageId: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L
)

class VoicePlayerController(private val scope: CoroutineScope) {
    private var mediaPlayer: MediaPlayer? = null
    private var progressJob: Job? = null

    private val _state = MutableStateFlow(VoicePlaybackState())
    val state: StateFlow<VoicePlaybackState> = _state.asStateFlow()

    fun togglePlayPause(messageId: String, filePath: String) {
        val current = _state.value
        if (current.activeMessageId == messageId && mediaPlayer != null) {
            val mp = mediaPlayer ?: return
            if (mp.isPlaying) {
                mp.pause()
                _state.value = current.copy(isPlaying = false)
            } else {
                mp.start()
                _state.value = current.copy(isPlaying = true)
                startProgressLoop()
            }
            return
        }

        stop()
        val file = File(filePath)
        if (!file.exists()) return
        try {
            val mp = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
                setOnCompletionListener {
                    stop()
                }
                start()
            }
            mediaPlayer = mp
            _state.value = VoicePlaybackState(
                activeMessageId = messageId,
                isPlaying = true,
                positionMs = 0L,
                durationMs = mp.duration.toLong().coerceAtLeast(1L)
            )
            startProgressLoop()
        } catch (_: Exception) {
            stop()
        }
    }

    private fun startProgressLoop() {
        progressJob?.cancel()
        progressJob = scope.launch(Dispatchers.Main) {
            while (isActive && _state.value.isPlaying) {
                val mp = mediaPlayer ?: break
                val pos = try {
                    mp.currentPosition.toLong()
                } catch (_: Exception) {
                    0L
                }
                _state.value = _state.value.copy(positionMs = pos)
                delay(150)
            }
        }
    }

    fun stop() {
        progressJob?.cancel()
        progressJob = null
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
        _state.value = VoicePlaybackState()
    }
}
