package com.example.aicallassist

import android.util.Log
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.UUID

class SignalingManager(
    private val roomId: String,
    private val senderId: String = UUID.randomUUID().toString()
) {
    interface SignalingListener {
        fun onSignalReceived(signal: SignalingData)
    }

    private var listener: SignalingListener? = null

    data class SignalingData(
        val type: String,
        val senderId: String? = null,
        val sdp: String? = null,
        val candidate: String? = null,
        val sdpMid: String? = null,
        val sdpMLineIndex: Int? = null,
        val text: String? = null,
        val isFinal: Boolean? = null
    )

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val gson = Gson()
    private var webSocket: WebSocket? = null

    private val baseUrl = "https://ntfy.sh/$roomId"

    fun setListener(listener: SignalingListener) {
        this.listener = listener
    }

    fun start() {
        val request = Request.Builder()
            .url("$baseUrl/ws")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("SignalingManager", "Received: $text")
                try {
                    val ntfyMessage = gson.fromJson(text, NtfyMessage::class.java)
                    if (ntfyMessage.event == "message") {
                        val signal = gson.fromJson(ntfyMessage.message, SignalingData::class.java)
                        if (signal.senderId != senderId) {
                            listener?.onSignalReceived(signal)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SignalingManager", "Error parsing message", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("SignalingManager", "WebSocket Failure", t)
            }
        })
    }

    fun sendSignal(signal: SignalingData) {
        val signalWithId = signal.copy(senderId = senderId)
        val json = gson.toJson(signalWithId)
        val request = Request.Builder()
            .url(baseUrl)
            .post(json.toRequestBody("text/plain".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("SignalingManager", "Failed to send signal", e)
            }
            override fun onResponse(call: Call, response: Response) {
                response.close()
            }
        })
    }

    fun stop() {
        webSocket?.close(1000, "Stopped")
        webSocket = null
    }

    private data class NtfyMessage(
        val event: String,
        val message: String? = null
    )
}
