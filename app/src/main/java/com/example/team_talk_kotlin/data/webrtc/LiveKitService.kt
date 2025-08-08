package com.example.team_talk_kotlin.data.webrtc

import android.content.Context
import android.util.Log
import io.livekit.android.ConnectOptions
import io.livekit.android.LiveKit
import io.livekit.android.e2ee.E2EEManager
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalAudioTrack
import io.livekit.android.room.track.Track
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import android.media.AudioManager
import androidx.core.content.ContextCompat.getSystemService
import io.livekit.android.RoomOptions
//import io.livekit.android.room.connect


class LiveKitService {
    // Callbacks
    var onSpeakingStatusChanged: ((Boolean) -> Unit)? = null
    var onRemoteAudioActive: ((Boolean) -> Unit)? = null
    var onConnectionError: ((String) -> Unit)? = null
    var onReconnecting: ((Boolean) -> Unit)? = null

    private var room: Room? = null
    private var audioTrack: LocalAudioTrack? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isSpeaker = false

    fun connect(
        context: Context,
        token: String,
        roomId: String,
        userId: String,
        isSpeaker: Boolean
    ) {
        // Prevent multiple connections

        serviceScope.launch {
            try {
//                disconnect()

                this@LiveKitService.isSpeaker = isSpeaker

                val liveKit = LiveKit.create(context)
                val options = ConnectOptions(audio = true)

//                room = liveKit.connect(
//                    url = "wss://team-talk-yg0tbukr.livekit.cloud",
//                    token = token,
//                    options = options
//                )

                val r: Room = LiveKit.create(
                    appContext = context,       // <— applicationContext is now in scope
                    options    = RoomOptions()             // default room options
                )

                // 2) ACTUALLY CONNECT via the suspend extension, which _mutates_ r
                r.connect(
                    url     = "wss://team-talk-yg0tbukr.livekit.cloud",
                    token   = token,
                    options = ConnectOptions(audio = true) // only audio
                )

                // 3) Save your room reference
                room = r

                Log.e("<LivekitService.kt>","This is getting called when the start to speak done and Room is : ${room}")

                room?.let { r ->
                    serviceScope.launch {
                        r.events.collect { event ->
                            handleRoomEvent(event)
                        }
                    }
                }

                if (isSpeaker) {
                    publishAudioTrack()
                    onSpeakingStatusChanged?.invoke(true)
                }

            } catch (e: Exception) {
                Log.e("LiveKitService", "Connection error: $e and token is $token")
                onConnectionError?.invoke("Connection failed: ${e.message} and token is ${token}")

            }
        }
    }

    private fun handleRoomEvent(event: RoomEvent) {
        when (event) {
            is RoomEvent.ParticipantConnected -> {
                Log.d("LiveKitService", "Participant connected: ${event.participant.identity}")
            }

            is RoomEvent.ParticipantDisconnected -> {
                Log.d("LiveKitService", "Participant disconnected: ${event.participant.identity}")
            }

            is RoomEvent.TrackSubscribed -> {
                if (event.track.kind == Track.Kind.AUDIO && !isSpeaker) {
                    onRemoteAudioActive?.invoke(true)
                }
            }

            is RoomEvent.TrackUnsubscribed -> {
                if (event.track.kind == Track.Kind.AUDIO && !isSpeaker) {
                    onRemoteAudioActive?.invoke(false)
                }
            }

            is RoomEvent.Disconnected -> {
                onSpeakingStatusChanged?.invoke(false)
                onRemoteAudioActive?.invoke(false)
                onReconnecting?.invoke(false)
            }

            is RoomEvent.Reconnecting -> onReconnecting?.invoke(true)
            is RoomEvent.Reconnected -> onReconnecting?.invoke(false)

//            is RoomEvent.ConnectionError -> {
//                Log.e("LiveKitService", "Connection failed: ${event.error}")
//                onConnectionError?.invoke("Connection failed: ${event.error.message}")
//            }

            else -> { /* Ignore other events */ }
        }
    }

    private fun publishAudioTrack() {
        serviceScope.launch {
            room?.let { r ->
                try {
                    // Clean up any existing track first
                    audioTrack?.stop()
                    audioTrack?.let { room?.localParticipant?.unpublishTrack(it) }
                    audioTrack = null
                    // Create an audio track using localParticipant
                    val track = r.localParticipant.createAudioTrack(name = "mic")
                    audioTrack = track
                    // Publish it to LiveKit
                    val success = r.localParticipant.publishAudioTrack(track)
                    if (!success) {
                        Log.e("LiveKitService", "Failed to publish audio track")
                    }
                } catch (e: SecurityException) {
                    Log.e("LiveKitService", "Permission missing for audio: $e")
                } catch (e: Exception) {
                    Log.e("LiveKitService", "Failed to publish audio track: $e")
                }
            }
        }
    }

    fun disconnect() {
        serviceScope.launch {
            try {
                // Step 1: Stop and unpublish the audio track
                audioTrack?.let { track ->
                    room?.localParticipant?.unpublishTrack(track)
                    track.stop()
                    audioTrack = null
                }

                // Step 2: Disconnect the room
                room?.disconnect()
                room = null

                // Step 3: Notify callbacks
                onSpeakingStatusChanged?.invoke(false)
                onRemoteAudioActive?.invoke(false)

                dispose();

                Log.d("LiveKitService", "Disconnected successfully ${room}")
            } catch (e: Exception) {
                Log.e("LiveKitService", "Disconnect error: $e")
            }
        }
    }

    fun dispose() {
        serviceScope.launch {
            delay(500)
            // DO NOT cancel serviceScope here
            onSpeakingStatusChanged = null
            onRemoteAudioActive = null
            onConnectionError = null
            onReconnecting = null
            Log.d("LiveKitService", "Service disposed")
        }
    }
}
