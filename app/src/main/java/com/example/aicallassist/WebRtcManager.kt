package com.example.aicallassist

import android.content.Context
import android.util.Log
import org.webrtc.*
import java.nio.ByteBuffer
import java.util.*

class WebRtcManager(
    private val context: Context,
    private val listener: WebRtcListener
) {
    interface WebRtcListener {
        fun onIceCandidate(candidate: IceCandidate)
        fun onLocalDescription(sdp: SessionDescription)
        fun onConnectionStateChange(state: PeerConnection.PeerConnectionState)
        fun onRemoteAudio(audioData: ByteBuffer, sampleRate: Int, channels: Int)
    }

    private var peerConnection: PeerConnection? = null
    private val rootEglBase: EglBase = EglBase.create()
    private val factory: PeerConnectionFactory

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
        )

        val options = PeerConnectionFactory.Options()
        val defaultVideoEncoderFactory = DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, true)
        val defaultVideoDecoderFactory = DefaultVideoDecoderFactory(rootEglBase.eglBaseContext)

        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(defaultVideoEncoderFactory)
            .setVideoDecoderFactory(defaultVideoDecoderFactory)
            .createPeerConnectionFactory()
    }

    fun createPeerConnection() {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()
        )

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers)
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN

        peerConnection = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                listener.onIceCandidate(candidate)
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                listener.onConnectionStateChange(newState)
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {
                val track = receiver?.track()
                Log.d("WEBRTC_AUDIO_DEBUG", "onAddTrack: kind=${track?.kind()} id=${track?.id()} streams=${streams?.size}")
                
                if (track is AudioTrack) {
                    Log.d("WEBRTC_AUDIO_DEBUG", "Detected remote AudioTrack. Attaching AudioSink to ID: ${track.id()}")
                    var frameCount = 0L
                    track.addSink { audioData, bitsPerSample, sampleRate, numberOfChannels, numberOfFrames, _ ->
                        frameCount++
                        // Log format and continuity check every ~5 seconds (approx 100 frames/sec for 10ms frames)
                        if (frameCount % 500 == 0L) {
                            Log.d("WEBRTC_AUDIO_DEBUG", "AudioSink Heartbeat: track=${track.id()} count=$frameCount bits=$bitsPerSample rate=$sampleRate channels=$numberOfChannels frames=$numberOfFrames rem=${audioData.remaining()}")
                        }
                        
                        if (audioData.remaining() > 0) {
                            listener.onRemoteAudio(audioData, sampleRate, numberOfChannels)
                        }
                    }
                }
            }
        })

        // Add local audio track
        val audioSource = factory.createAudioSource(MediaConstraints())
        val localAudioTrack = factory.createAudioTrack("local_audio_track", audioSource)
        peerConnection?.addTrack(localAudioTrack, listOf("local_stream"))
    }

    fun createOffer() {
        val constraints = MediaConstraints()
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        listener.onLocalDescription(sdp)
                    }
                    override fun onSetFailure(s: String?) {}
                    override fun onCreateSuccess(sdp: SessionDescription?) {}
                    override fun onCreateFailure(s: String?) {}
                }, sdp)
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(s: String?) {}
            override fun onCreateFailure(s: String?) {}
        }, constraints)
    }

    fun handleOffer(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                createAnswer()
            }
            override fun onSetFailure(s: String?) {}
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onCreateFailure(s: String?) {}
        }, sdp)
    }

    private fun createAnswer() {
        val constraints = MediaConstraints()
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        listener.onLocalDescription(sdp)
                    }
                    override fun onSetFailure(s: String?) {}
                    override fun onCreateSuccess(sdp: SessionDescription?) {}
                    override fun onCreateFailure(s: String?) {}
                }, sdp)
            }
            override fun onSetSuccess() {}
            override fun onSetFailure(s: String?) {}
            override fun onCreateFailure(s: String?) {}
        }, constraints)
    }

    fun handleAnswer(sdp: SessionDescription) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {}
            override fun onSetFailure(s: String?) {}
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onCreateFailure(s: String?) {}
        }, sdp)
    }

    fun addIceCandidate(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun endCall() {
        peerConnection?.close()
        peerConnection = null
    }
}
