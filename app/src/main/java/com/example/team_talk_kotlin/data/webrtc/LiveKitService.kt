package com.example.team_talk_kotlin.data.webrtc

import android.annotation.SuppressLint
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
import com.google.firebase.database.FirebaseDatabase
import io.livekit.android.RoomOptions
import io.livekit.android.annotations.Beta

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
    private var uId : String = ""
    private var rId : String = ""

//    Connect for the Listener and Speaker
    @SuppressLint("SuspiciousIndentation")
    @OptIn(Beta::class)
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

                val r: Room = LiveKit.create(
                    appContext = context,       // <— applicationContext is now in scope
                    options    = RoomOptions()             // default room options
                )

//                url     = "wss://team-talk-yg0tbukr.livekit.cloud",
                r.connect(
                    url     = "wss://livekit.devsamagri.com",
                    token   = token,
                    options = ConnectOptions(audio = true) // only audio
                )

                if(!isSpeaker)
                {
                    r.setMicrophoneMute(true)
                }

                room = r

                uId = userId

                rId = roomId

                Log.e("<LivekitService.kt>","This is getting called when the start to speak done and Room is : ${room}")

                if (!isSpeaker) {
                  val db= FirebaseDatabase.getInstance().getReference("listeners")
                        .child(roomId)
                        .child(userId)
                        .setValue(true)

                    Log.e("<!Livekit OnConnected!>","Entry is written in the Firebase Database and this is ${db}")
                }

                room?.let { rp ->
                    serviceScope.launch {
                        rp.events.collect { event ->
                            handleRoomEvent(event)
                        }
                    }
                }

//                Publish the audio track if it is a speaker
                if (isSpeaker) {
                    publishAudioTrack()
                    onSpeakingStatusChanged?.invoke(true)
                }

            } catch (e: Exception) {
                Log.e("<LiveKitService>", "Connection error: $e and token is $token")
                onConnectionError?.invoke("Connection failed: ${e.message} and token is ${token}")

            }
        }
    }

//    Room Events Listeners for who is connected or who is disconnected
    private fun handleRoomEvent(event: RoomEvent) {
        when (event) {
            is RoomEvent.ParticipantConnected -> {
                Log.e("<<LiveKitService>>", "Participant connected: ${event.participant.identity}")
            }

            is RoomEvent.ParticipantDisconnected -> {
                Log.e("<<LiveKitService>>", "Participant disconnected: ${event.participant.identity}")

                if (!isSpeaker) {
                    FirebaseDatabase.getInstance().getReference("listeners")
                        .child(rId)
                        .child(uId)
                        .removeValue()
                }

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

                // 👇 Remove from Firebase
                if (!isSpeaker) {
                    FirebaseDatabase.getInstance().getReference("listeners")
                        .child(rId)
                        .child(uId)
                        .removeValue()
                }

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

//    Publishing the Audio track for listening purpose
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

//    Disconnecting from the Room
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

//    Clearing all audio track and disconnecting from the Room
    fun dispose() {
        serviceScope.launch {
            delay(500)
            // DO NOT cancel serviceScope here
//            onSpeakingStatusChanged = null
            onRemoteAudioActive = null
            onConnectionError = null
            onReconnecting = null
            Log.d("LiveKitService", "Service disposed")
        }
    }
}
