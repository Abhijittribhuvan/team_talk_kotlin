package com.example.team_talk_kotlin.data.webrtc

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.team_talk_kotlin.R
import com.example.team_talk_kotlin.data.firebase.FirebaseUtil
import com.example.team_talk_kotlin.data.group.GroupService
import com.example.team_talk_kotlin.data.model.Guard
import com.example.team_talk_kotlin.data.model.Group
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder

class BackgroundVoiceService : Service() {

    companion object {
        const val CHANNEL_ID = "voice_service_channel"
        const val NOTIFICATION_ID = 101
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val liveKitService = LiveKitService()

    private lateinit var guard: Guard
    private var groups: List<Group> = emptyList()
    private var currentSpeakerId: String? = null

    private var connectedGroupId: String? = null
    private var isConnecting = false
    private val observedGroups = mutableSetOf<String>()
    private val lastConnectTime = mutableMapOf<String, Long>()


    override fun onCreate() {
        super.onCreate()
        try {
            io.livekit.android.LiveKit.create(applicationContext)
        } catch (e: Throwable) {
            Log.e("MyApp", "LiveKit init failed: ${e.message}", e)
        }

        createNotificationChannel()
    }

    private fun shouldConnectNow(groupId: String, minIntervalMs: Long = 2000L): Boolean {
        val now = System.currentTimeMillis()
        val last = lastConnectTime[groupId] ?: 0L
        if (now - last < minIntervalMs) return false
        lastConnectTime[groupId] = now
        return true
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val g = intent?.getSerializableExtra("guard") as? Guard
        if (g != null) {
            guard = g
            serviceScope.launch { loadGroupsAndStart() }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        liveKitService.disconnect()
    }

    private suspend fun loadGroupsAndStart() {
        try {
            groups = GroupService.getGroupsForGuard(guard.id)
                .filter { (it["guard_ids"] as? List<*>)?.contains(guard.id) == true }
                .map {
                    Group(
                        id = it["id"] as String,
                        name = it["name"] as String,
                        guardIds = (it["guard_ids"] as? List<*>)?.mapNotNull { id -> id?.toString() } ?: emptyList()
                    )
                }

            showNotification()
            startSpeakerCheck()
        } catch (e: Exception) {
            Log.e("BackgroundService", "Error loading groups: ${e.message}")
        }
    }

    /** Show minimal persistent notification */
    private fun showNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Listening for calls")
            .setContentText("Auto-listening in background")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    /** Periodically check for speaker and auto-connect */
    private fun startSpeakerCheck() {
        serviceScope.launch {
            while (isActive) {
                try {
                    for (group in groups) {
                        val speakerId = getCurrentSpeaker(group.id)
                        if (speakerId != null && speakerId != guard.id) {
                            // attach observer once
                            if (!observedGroups.contains(group.id)) {
                                observeSpeaker(group.id)
                                observedGroups.add(group.id)
                            }
                            // only attempt connect if not connected to same group and debounce ok
                            if (connectedGroupId != group.id && shouldConnectNow(group.id)) {
                                connectAsListener(group.id)
                            }
                            // we matched a speaker — no need to check remaining groups in this pass
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e("BackgroundService", "Speaker check error: $e")
                }
                delay(3000)
            }
        }
    }


    /** Firebase: Get the current speaker */
    private suspend fun getCurrentSpeaker(groupId: String): String? {
        return try {
            val snapshot = FirebaseUtil.db.child("calls/$groupId/speaker_id").get().await()
            snapshot.getValue(String::class.java)
        } catch (_: Exception) {
            null
        }
    }

    /** Firebase: Observe changes in speaker */
    private fun observeSpeaker(groupId: String) {
        // If already observed, skip
        if (observedGroups.contains(groupId)) return
        observedGroups.add(groupId)

        FirebaseUtil.db.child("calls/$groupId/speaker_id")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val speaker = snapshot.getValue(String::class.java)
                    currentSpeakerId = speaker
                    if (speaker != null && speaker != guard.id) {
                        // Only connect if not already connected to this group and not currently connecting
                        if (connectedGroupId != groupId && !isConnecting && shouldConnectNow(groupId)) {
                            serviceScope.launch { connectAsListener(groupId) }
                        }
                    } else {
                        // speaker removed or speaker is me -> disconnect if we were listening to this group
                        if (connectedGroupId == groupId) {
                            serviceScope.launch {
                                try {
                                    liveKitService.disconnect()
                                } catch (e: Exception) { /* ignore */ }
                                connectedGroupId = null
                            }
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {}
            })
    }


    /** Audio setup + connect to LiveKit as listener */
    private suspend fun connectAsListener(groupId: String) {
        // guard re-entrancy
        if (connectedGroupId == groupId) {
            Log.d("BackgroundService", "Already connected to $groupId — skipping")
            return
        }
        if (isConnecting) {
            Log.d("BackgroundService", "Already connecting — skipping new request for $groupId")
            return
        }

        isConnecting = true
        try {
            // If connected to another group, request disconnect first
            if (connectedGroupId != null && connectedGroupId != groupId) {
                try {
                    liveKitService.disconnect()
                } catch (e: Exception) {
                    Log.w("BackgroundService", "disconnect() threw: ${e.message}")
                }
                // small delay to let the other side clean up
                delay(300)
                connectedGroupId = null
            }

            // audio setup
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            audioManager.isSpeakerphoneOn = true

            val token = try {
                generateToken(groupId, guard.id, "listener")
            } catch (e: Exception) {
                Log.e("BackgroundService", "Token error: ${e.message}")
                null
            }

            if (token == null) {
                Log.e("BackgroundService", "Token null — cannot connect")
                return
            }

            if (!requestAudioFocus()) {
                Log.e("BackgroundService", "Failed to get audio focus — still attempting connect")
                // We may still try; optionally return early
            }

            // attempt connect
            liveKitService.connect(applicationContext, token, groupId, guard.id, false)
            connectedGroupId = groupId
            Log.d("BackgroundService", "Requested connect to $groupId")
        } finally {
            // small cooldown to prevent immediate re-entry
            delay(200)
            isConnecting = false
        }
    }


    /** Audio focus request */
    private fun requestAudioFocus(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val result = audioManager.requestAudioFocus(
            AudioManager.OnAudioFocusChangeListener { },
            AudioManager.STREAM_VOICE_CALL,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        )
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    /** Token generation HTTP request */
    private suspend fun generateToken(roomName: String, participantName: String, role: String): String {
        val client = OkHttpClient()
        val json = JSONObject().apply {
            put("roomName", URLEncoder.encode(roomName, "UTF-8"))
            put("participantName", URLEncoder.encode(participantName, "UTF-8"))
            put("role", role)
        }
        val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("https://pure.devsamagri.com/livekit-token.php")
            .post(body)
            .build()
        val response = client.newCall(request).execute()
        val resBody = response.body?.string() ?: throw Exception("Empty response")
        return JSONObject(resBody).getString("token")
    }

    /** Foreground service notification channel */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Background Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}
