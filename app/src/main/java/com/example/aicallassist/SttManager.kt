package com.example.aicallassist

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.IOException
import java.nio.ByteBuffer

class SttManager(
    private val context: Context,
    private val listener: SttListener
) {
    interface SttListener {
        fun onResult(text: String)
        fun onPartialResult(text: String)
        fun onError(e: Exception)
    }

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var isInitialized = false
    private var currentSampleRate: Float = -1f
    
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Dedicated thread for Vosk processing to avoid blocking WebRTC audio thread
    private val workerThread = HandlerThread("VoskWorker").apply { start() }
    private val workerHandler = Handler(workerThread.looper)

    // For local Mic STT fallback
    private var audioRecord: AudioRecord? = null
    private var isRecording = false

    fun initModel(modelPath: String = "model") {
        Log.d("STT_DEBUG", "Starting model unpack from assets/$modelPath")
        StorageService.unpack(context, modelPath, "vosk-model",
            { model: Model ->
                this.model = model
                Log.d("STT_DEBUG", "Vosk model loaded successfully")
            },
            { e: IOException -> 
                Log.e("STT_DEBUG", "Vosk model load FAILED", e)
                mainHandler.post { listener.onError(e) }
            }
        )
    }

    fun startMicSTT() {
        if (isRecording) return
        val model = this.model ?: return
        
        val sampleRate = 16000
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("STT_DEBUG", "Failed to initialize AudioRecord")
                return
            }

            recognizer?.close()
            recognizer = Recognizer(model, sampleRate.toFloat())
            currentSampleRate = sampleRate.toFloat()
            isInitialized = true

            audioRecord?.startRecording()
            isRecording = true
            Log.d("STT_DEBUG", "Local Mic STT started at $sampleRate Hz")

            workerHandler.post(object : Runnable {
                override fun run() {
                    val buffer = ByteArray(minBufferSize)
                    while (isRecording) {
                        val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                        if (read > 0) {
                            processAudioInternal(buffer, sampleRate, 1, model)
                        }
                    }
                }
            })
        } catch (e: SecurityException) {
            Log.e("STT_DEBUG", "Mic permission missing for local STT", e)
        } catch (e: Exception) {
            Log.e("STT_DEBUG", "Error starting local mic STT", e)
        }
    }

    fun stopMicSTT() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        Log.d("STT_DEBUG", "Local Mic STT stopped")
    }

    fun feedAudio(buffer: ByteBuffer, sampleRate: Int, channels: Int) {
        val model = this.model ?: return
        
        // WebRTC might provide a direct buffer. We must copy it before switching threads.
        val remaining = buffer.remaining()
        if (remaining == 0) return
        
        val pcmCopy = ByteArray(remaining)
        buffer.get(pcmCopy)

        workerHandler.post {
            processAudioInternal(pcmCopy, sampleRate, channels, model)
        }
    }

    private fun processAudioInternal(pcmData: ByteArray, sampleRate: Int, channels: Int, model: Model) {
        // 1. Handle dynamic re-initialization if sample rate changes
        if (currentSampleRate != sampleRate.toFloat()) {
            Log.d("STT_DEBUG", "Initializing recognizer: Rate=$sampleRate Channels=$channels")
            try {
                recognizer?.close()
                // Vosk Recognizer expects 16-bit PCM.
                recognizer = Recognizer(model, sampleRate.toFloat())
                currentSampleRate = sampleRate.toFloat()
                isInitialized = true
                Log.d("STT_DEBUG", "Recognizer ready at $sampleRate Hz")
            } catch (e: Exception) {
                Log.e("STT_DEBUG", "Recognizer init error", e)
                return
            }
        }

        if (!isInitialized || recognizer == null) return

        // 2. Convert to Mono if necessary (Vosk requires mono)
        val processedData = if (channels > 1) {
            convertToMono(pcmData, channels)
        } else {
            pcmData
        }

        // 3. Feed to Vosk
        try {
            if (recognizer!!.acceptWaveForm(processedData, processedData.size)) {
                val resultJson = recognizer!!.result
                val text = parseVoskJson(resultJson, "text")
                if (text.isNotBlank()) {
                    Log.d("VOSK_DEBUG", "Final: $text")
                    mainHandler.post { listener.onResult(text) }
                }
            } else {
                val partialJson = recognizer!!.partialResult
                val partialText = parseVoskJson(partialJson, "partial")
                if (partialText.isNotBlank()) {
                    // Log partials occasionally
                    if (System.currentTimeMillis() % 1000 < 50) {
                        Log.d("VOSK_DEBUG", "Partial: $partialText")
                    }
                    mainHandler.post { listener.onPartialResult(partialText) }
                }
            }
        } catch (e: Exception) {
            Log.e("STT_DEBUG", "Vosk processing error", e)
        }
    }

    private fun convertToMono(data: ByteArray, channels: Int): ByteArray {
        val frameSize = 2 * channels
        val monoSize = data.size / channels
        val monoData = ByteArray(monoSize)
        
        var monoIdx = 0
        for (i in 0 until data.size step frameSize) {
            if (monoIdx + 1 < monoSize) {
                monoData[monoIdx] = data[i]
                monoData[monoIdx + 1] = data[i + 1]
                monoIdx += 2
            }
        }
        return monoData
    }

    private fun parseVoskJson(json: String, key: String): String {
        return try {
            JSONObject(json).optString(key, "")
        } catch (e: Exception) {
            ""
        }
    }

    fun release() {
        Log.d("STT_DEBUG", "Releasing SttManager resources")
        stopMicSTT()
        workerHandler.removeCallbacksAndMessages(null)
        workerThread.quitSafely()
        recognizer?.close()
        recognizer = null
        model = null
        isInitialized = false
    }
}
