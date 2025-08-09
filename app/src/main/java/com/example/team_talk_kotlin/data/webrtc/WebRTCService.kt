package com.example.team_talk_kotlin.data.webrtc

import android.content.Context
import android.media.AudioManager
import android.util.Log
import com.google.firebase.database.*
import kotlinx.coroutines.*
import org.webrtc.*
import java.util.*
import kotlin.collections.HashMap
import kotlinx.coroutines.tasks.await
import com.example.team_talk_kotlin.data.firebase.FirebaseUtil
import java.util.concurrent.atomic.AtomicBoolean
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.app.Service
import android.content.Intent
import android.os.IBinder


class WebRTCService: Service() {
    data class Offer(val sdp: String = "", val type: String = "", val from: String = "")
    data class Answer(val sdp: String = "", val type: String = "", val from: String = "")
    data class IceCandidateModel(
        val candidate: String = "",
        val sdpMid: String? = null,
        val sdpMLineIndex: Int = -1,
        val from: String = ""
    )

    // Thread-safe collections and synchronization
    private val listenerConnections = Collections.synchronizedMap(HashMap<String, PeerConnection>())
    private val subscriptions = Collections.synchronizedMap(HashMap<String, DatabaseReference>())
    private val connectionMutex = Mutex()

    private var speakerPc: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var factory: PeerConnectionFactory? = null
    private var audioManager: AudioManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val db = FirebaseDatabase.getInstance().reference
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Use atomic booleans for thread safety
    private var groupId: String? = null
    private var selfId: String? = null
    private val isDisposed = AtomicBoolean(false)
    private val isConnectionInProgress = AtomicBoolean(false)
    private var isCaller = false
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 3

    // Callbacks - always called on main thread
    var onSpeakerStatusChanged: ((Boolean) -> Unit)? = null
    var onRemoteStreamActive: ((Boolean) -> Unit)? = null
    var onSpeakerChanged: ((String?) -> Unit)? = null
    var onConnectionError: ((String) -> Unit)? = null
    var onReconnecting: ((Boolean) -> Unit)? = null
    var onActiveListenersChanged: ((Set<String>) -> Unit)? = null
    private var initialized = false

    private val audioConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "true"))
    }

    fun initialize(context: Context) {
        if (isDisposed.get()) return

        try {
            Log.d("WebRTCService", "Initializing WebRTC...")

            if (!initialized) {
                val options = PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()

                PeerConnectionFactory.initialize(options)
                initialized = true
            }

            val options = PeerConnectionFactory.Options().apply {
                disableEncryption = false
                disableNetworkMonitor = false
            }

            factory = PeerConnectionFactory.builder()
                .setOptions(options)
                .setAudioDeviceModule(null) // Use default
                .createPeerConnectionFactory()

            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            Log.d("WebRTCService", "WebRTC initialized successfully")
        } catch (e: Exception) {
            Log.e("WebRTCService", "Failed to initialize WebRTC: $e", e)
            callOnMainThread { onConnectionError?.invoke("Failed to initialize audio service") }
        }
    }

    private fun callOnMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private suspend fun cleanup() {
        if (isDisposed.get()) return

        connectionMutex.withLock {
            try {
                Log.d("WebRTCService", "Starting cleanup...")

                // Remove Firebase subscriptions first
                synchronized(subscriptions) {
                    subscriptions.forEach { (_, ref) ->
                        try {
                            ref.removeEventListener(object : ValueEventListener {
                                override fun onDataChange(snapshot: DataSnapshot) {}
                                override fun onCancelled(error: DatabaseError) {}
                            })
                        } catch (e: Exception) {
                            Log.e("WebRTCService", "Error removing Firebase listener: $e")
                        }
                    }
                    subscriptions.clear()
                }

                // Close connections on main thread
                withContext(Dispatchers.Main) {
                    // Close listener connections
                    synchronized(listenerConnections) {
                        listenerConnections.values.forEach { pc ->
                            try {
                                if (pc.connectionState() != PeerConnection.PeerConnectionState.CLOSED) {
                                    pc.close()
                                }
                            } catch (e: Exception) {
                                Log.e("WebRTCService", "Error closing listener peer connection: $e")
                            }
                        }

                        listenerConnections.values.forEach { pc ->
                            try {
                                pc.dispose()
                            } catch (e: Exception) {
                                Log.e("WebRTCService", "Error disposing listener peer connection: $e")
                            }
                        }
                        listenerConnections.clear()
                    }

                    // Close and dispose speaker connection
                    speakerPc?.let { pc ->
                        try {
                            if (pc.connectionState() != PeerConnection.PeerConnectionState.CLOSED) {
                                pc.close()
                            }
                            pc.dispose()
                        } catch (e: Exception) {
                            Log.e("WebRTCService", "Error disposing speaker peer connection: $e")
                        }
                    }
                    speakerPc = null

                    // Dispose audio resources
                    audioTrack?.let { track ->
                        try {
                            track.setEnabled(false)
                            track.dispose()
                        } catch (e: Exception) {
                            Log.e("WebRTCService", "Error disposing audio track: $e")
                        }
                    }
                    audioTrack = null

                    audioSource?.dispose()
                    audioSource = null
                }

                isConnectionInProgress.set(false)
                Log.d("WebRTCService", "Cleanup completed successfully")
            } catch (e: Exception) {
                Log.e("WebRTCService", "Cleanup error: $e", e)
            }
        }
    }

    suspend fun stopCall() {
        if (isDisposed.get()) return

        try {
            Log.d("WebRTCService", "Stopping call...")

            // Remove from Firebase first
            groupId?.let { gid ->
                selfId?.let { id ->
                    try {
                        if (isCaller) {
                            db.child("calls/$gid/speaker_id").removeValue().await()
                            db.child("calls/$gid/offers").removeValue().await()
                            db.child("calls/$gid/answers").removeValue().await()
                        } else {
                            db.child("calls/$gid/listeners/$id").removeValue().await()
                            db.child("calls/$gid/answers/$id").removeValue().await()
                        }
                    } catch (e: Exception) {
                        Log.e("WebRTCService", "Error removing from Firebase: $e")
                    }
                }
            }

            cleanup()

            // Reset state
            groupId = null
            selfId = null
            isCaller = false
            reconnectAttempts = 0

            // Notify callbacks on main thread
            callOnMainThread {
                onSpeakerStatusChanged?.invoke(false)
                onRemoteStreamActive?.invoke(false)
                onSpeakerChanged?.invoke(null)
                onReconnecting?.invoke(false)
                onActiveListenersChanged?.invoke(emptySet())
            }

            Log.d("WebRTCService", "Call stopped successfully")
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error in stopCall: $e", e)
        }
    }

    suspend fun startCaller(groupId: String, selfId: String) {
        if (isDisposed.get() || factory == null) {
            Log.w("WebRTCService", "Cannot start caller - invalid state")
            return
        }

        if (!isConnectionInProgress.compareAndSet(false, true)) {
            Log.w("WebRTCService", "Connection already in progress")
            return
        }

        connectionMutex.withLock {
            try {
                Log.d("WebRTCService", "Starting caller for group: $groupId")
                reconnectAttempts = 0

                // Cleanup existing connections first
                cleanup()

                this.isCaller = true
                this.groupId = groupId
                this.selfId = selfId

                val iceServers = listOf(
                    PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                    PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
                )

                val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                    sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                    bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                    rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
                    continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
                    iceConnectionReceivingTimeout = 30000
                    iceBackupCandidatePairPingInterval = 10000
                }

                // Create peer connection on main thread
                speakerPc = withContext(Dispatchers.Main) {
                    factory?.createPeerConnection(rtcConfig, createPeerConnectionObserver(selfId, true))
                } ?: throw IllegalStateException("Failed to create peer connection")

                // Create audio source and track
                audioSource = factory?.createAudioSource(audioConstraints)
                audioTrack = factory?.createAudioTrack("audio_$selfId", audioSource!!)

                // Add track to peer connection on main thread
                withContext(Dispatchers.Main) {
                    speakerPc?.addTrack(audioTrack!!, listOf("stream_$selfId"))
                }

                // Configure audio settings on main thread
                withContext(Dispatchers.Main) {
                    audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
                    audioManager?.isSpeakerphoneOn = true
                }

                // Set speaker in Firebase with disconnect handler
                db.child("calls/$groupId/speaker_id").setValue(selfId).await()
                db.child("calls/$groupId/speaker_id").onDisconnect().removeValue()

                setupCallerSubscriptions(groupId, selfId)

                callOnMainThread {
                    onSpeakerStatusChanged?.invoke(true)
                    onSpeakerChanged?.invoke(selfId)
                }

                isConnectionInProgress.set(false)
                Log.d("WebRTCService", "Caller started successfully")
            } catch (e: Exception) {
                Log.e("WebRTCService", "Caller error: $e", e)
                isConnectionInProgress.set(false)
                callOnMainThread {
                    onConnectionError?.invoke("Failed to start speaking: ${e.message}")
                }
                cleanup()
            }
        }
    }

    private fun createPeerConnectionObserver(id: String, isSpeaker: Boolean) = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            serviceScope.launch {
                try {
                    val candidateMap = hashMapOf(
                        "candidate" to candidate.sdp,
                        "sdpMid" to candidate.sdpMid,
                        "sdpMLineIndex" to candidate.sdpMLineIndex,
                        "from" to id
                    )
                    db.child("calls/$groupId/candidates").push().setValue(candidateMap)
                } catch (e: Exception) {
                    Log.e("WebRTCService", "Error sending ICE candidate: $e")
                }
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            Log.d("WebRTCService", "Connection state changed to: $newState for $id")
            serviceScope.launch {
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        callOnMainThread { onReconnecting?.invoke(false) }
                        reconnectAttempts = 0
                    }
                    PeerConnection.PeerConnectionState.DISCONNECTED,
                    PeerConnection.PeerConnectionState.FAILED -> {
                        callOnMainThread { onReconnecting?.invoke(true) }
                        if (!isDisposed.get() && reconnectAttempts < maxReconnectAttempts) {
                            scheduleReconnect(groupId ?: return@launch, id)
                        }
                    }
                    else -> {}
                }
            }
        }

        override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<MediaStream>) {
            if (!isSpeaker) {
                Log.d("WebRTCService", "Remote audio track added for listener $id")
                callOnMainThread {
                    onRemoteStreamActive?.invoke(true)
                    audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
                    audioManager?.isSpeakerphoneOn = true
                }
            }
        }

        override fun onRemoveTrack(receiver: RtpReceiver) {
            if (!isSpeaker) {
                Log.d("WebRTCService", "Remote audio track removed for listener $id")
                callOnMainThread {
                    onRemoteStreamActive?.invoke(false)
                }
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) {
            Log.d("WebRTCService", "Signaling state changed: $state for $id")
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            Log.d("WebRTCService", "ICE connection state: $state for $id")
        }

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            Log.d("WebRTCService", "ICE gathering state: $state for $id")
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) {
            Log.d("WebRTCService", "ICE connection receiving: $receiving for $id")
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(dataChannel: DataChannel) {}
        override fun onRenegotiationNeeded() {
            Log.d("WebRTCService", "Renegotiation needed for $id")
        }
    }

    private fun scheduleReconnect(groupId: String, selfId: String) {
        if (reconnectAttempts >= maxReconnectAttempts || isDisposed.get()) return
        reconnectAttempts++

        serviceScope.launch {
            delay(reconnectAttempts * 2000L)
            if (!isDisposed.get()) {
                try {
                    if (isCaller) {
                        startCaller(groupId, selfId)
                    } else {
                        startListener(groupId, selfId)
                    }
                } catch (e: Exception) {
                    Log.e("WebRTCService", "Reconnect failed: $e")
                }
            }
        }
    }

    private fun setupCallerSubscriptions(groupId: String, selfId: String) {
        // Listen for new listeners
        val listenersRef = db.child("calls/$groupId/listeners")
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val listenerId = snapshot.key ?: return
                if (listenerId == selfId) return

                serviceScope.launch {
                    val shouldCreate = synchronized(listenerConnections) {
                        !listenerConnections.containsKey(listenerId)
                    }

                    if (shouldCreate) {
                        createListenerConnection(groupId, selfId, listenerId)
                    }
                }
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                val listenerId = snapshot.key ?: return
                serviceScope.launch {
                    val pc = synchronized(listenerConnections) {
                        listenerConnections.remove(listenerId)
                    }

                    pc?.let {
                        withContext(Dispatchers.Main) {
                            try {
                                it.close()
                                it.dispose()
                            } catch (e: Exception) {
                                Log.e("WebRTCService", "Error disposing listener connection: $e")
                            }
                        }
                    }

                    updateActiveListeners()
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e("WebRTCService", "Listeners listener cancelled: ${error.message}")
            }
        }

        listenersRef.addChildEventListener(listener)
        synchronized(subscriptions) {
            subscriptions["listeners"] = listenersRef
        }

        serviceScope.launch {
            updateActiveListeners()
        }
    }

    private suspend fun updateActiveListeners() {
        try {
            val snapshot = db.child("calls/$groupId/listeners").get().await()
            val listeners = snapshot.children.mapNotNull { it.key }.toSet()
            callOnMainThread {
                onActiveListenersChanged?.invoke(listeners)
            }
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error getting listeners: $e")
        }
    }

    private suspend fun createListenerConnection(groupId: String, selfId: String, listenerId: String) {
        try {
            Log.d("WebRTCService", "Creating connection to listener: $listenerId")

            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
            )

            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            }

            val pc = withContext(Dispatchers.Main) {
                factory?.createPeerConnection(rtcConfig, createPeerConnectionObserver(selfId, true))
            } ?: return

            // Add audio track if available
            withContext(Dispatchers.Main) {
                audioTrack?.let { track ->
                    pc.addTrack(track, listOf("stream_$selfId"))
                }
            }

            synchronized(listenerConnections) {
                listenerConnections[listenerId] = pc
            }

            // Create offer on main thread
            withContext(Dispatchers.Main) {
                val mediaConstraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                }

                pc.createOffer(object : SdpObserver {
                    override fun onCreateSuccess(desc: SessionDescription) {
                        pc.setLocalDescription(object : SdpObserver {
                            override fun onSetSuccess() {
                                serviceScope.launch {
                                    try {
                                        db.child("calls/$groupId/offers/$listenerId").setValue(
                                            hashMapOf(
                                                "sdp" to desc.description,
                                                "type" to desc.type.canonicalForm(),
                                                "from" to selfId
                                            )
                                        ).await()
                                        Log.d("WebRTCService", "Offer sent to $listenerId")
                                    } catch (e: Exception) {
                                        Log.e("WebRTCService", "Error sending offer: $e")
                                    }
                                }
                            }
                            override fun onSetFailure(error: String?) {
                                Log.e("WebRTCService", "SetLocalDescription error: $error")
                            }
                            override fun onCreateSuccess(p0: SessionDescription?) {}
                            override fun onCreateFailure(p0: String?) {}
                        }, desc)
                    }
                    override fun onCreateFailure(error: String?) {
                        Log.e("WebRTCService", "CreateOffer error: $error")
                    }
                    override fun onSetSuccess() {}
                    override fun onSetFailure(error: String?) {}
                }, mediaConstraints)
            }

            setupAnswerSubscription(groupId, listenerId, pc)
            setupCandidateSubscription(groupId, pc)
        } catch (e: Exception) {
            Log.e("WebRTCService", "Error creating listener connection: $e", e)
        }
    }

    private fun setupAnswerSubscription(groupId: String, listenerId: String, pc: PeerConnection) {
        val answersRef = db.child("calls/$groupId/answers/$listenerId")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return
                serviceScope.launch {
                    try {
                        val answerData = snapshot.value as? Map<String, Any> ?: return@launch
                        val sdp = answerData["sdp"] as? String ?: return@launch
                        val type = answerData["type"] as? String ?: return@launch

                        val sessionDescription = SessionDescription(
                            SessionDescription.Type.fromCanonicalForm(type),
                            sdp
                        )

                        withContext(Dispatchers.Main) {
                            if (pc.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                                pc.setRemoteDescription(object : SdpObserver {
                                    override fun onSetSuccess() {
                                        Log.d("WebRTCService", "Set remote answer success for $listenerId")
                                    }
                                    override fun onSetFailure(error: String?) {
                                        Log.e("WebRTCService", "SetRemoteDescription error: $error")
                                    }
                                    override fun onCreateSuccess(p0: SessionDescription?) {}
                                    override fun onCreateFailure(p0: String?) {}
                                }, sessionDescription)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("WebRTCService", "Error processing answer: $e")
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e("WebRTCService", "Answer listener cancelled: ${error.message}")
            }
        }
        answersRef.addValueEventListener(listener)
        synchronized(subscriptions) {
            subscriptions["answer_$listenerId"] = answersRef
        }
    }

    private fun setupCandidateSubscription(groupId: String, pc: PeerConnection) {
        val candidatesRef = db.child("calls/$groupId/candidates")
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                serviceScope.launch {
                    try {
                        val candidateData = snapshot.value as? Map<String, Any> ?: return@launch
                        val candidateStr = candidateData["candidate"] as? String ?: return@launch
                        val sdpMid = candidateData["sdpMid"] as? String
                        val sdpMLineIndex = (candidateData["sdpMLineIndex"] as? Long)?.toInt() ?: -1
                        val from = candidateData["from"] as? String ?: return@launch

                        if (from == selfId) return@launch

                        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidateStr)

                        withContext(Dispatchers.Main) {
                            pc.addIceCandidate(iceCandidate)
                        }
                    } catch (e: Exception) {
                        Log.e("WebRTCService", "Error adding ICE candidate: $e")
                    }
                }
            }

            override fun onChildChanged(p0: DataSnapshot, p1: String?) {}
            override fun onChildRemoved(p0: DataSnapshot) {}
            override fun onChildMoved(p0: DataSnapshot, p1: String?) {}
            override fun onCancelled(p0: DatabaseError) {
                Log.e("WebRTCService", "Candidate listener cancelled: ${p0.message}")
            }
        }
        candidatesRef.addChildEventListener(listener)
        synchronized(subscriptions) {
            subscriptions["candidates_${pc.hashCode()}"] = candidatesRef
        }
    }

    suspend fun startListener(groupId: String, selfId: String) {
        if (isDisposed.get() || factory == null) {
            Log.w("WebRTCService", "Cannot start listener - invalid state")
            return
        }

        if (!isConnectionInProgress.compareAndSet(false, true)) {
            Log.w("WebRTCService", "Connection already in progress")
            return
        }

        connectionMutex.withLock {
            try {
                Log.d("WebRTCService", "Starting listener for group: $groupId")
                reconnectAttempts = 0

                cleanup()

                this.isCaller = false
                this.groupId = groupId
                this.selfId = selfId

                setupSpeakerSubscription(groupId, selfId)

                // Get current speaker immediately
                val snapshot = FirebaseUtil.db.child("calls/$groupId/speaker_id").get().await()
                val speakerId = snapshot.getValue(String::class.java)
                if (speakerId != null && speakerId != selfId) {
                    setupListenerConnection(groupId, selfId, speakerId)
                }

                isConnectionInProgress.set(false)
                Log.d("WebRTCService", "Listener started successfully")
            } catch (e: Exception) {
                isConnectionInProgress.set(false)
                Log.e("WebRTCService", "Listener error: $e", e)
                callOnMainThread {
                    onConnectionError?.invoke("Failed to connect as listener: ${e.message}")
                }
            }
        }
    }

    private fun setupSpeakerSubscription(groupId: String, selfId: String) {
        val speakerRef = db.child("calls/$groupId/speaker_id")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val speakerId = snapshot.getValue(String::class.java)
                serviceScope.launch {
                    callOnMainThread {
                        onSpeakerChanged?.invoke(speakerId)
                    }

                    if (speakerId == null || speakerId == selfId) {
                        try {
                            db.child("calls/$groupId/listeners/$selfId").removeValue().await()
                            cleanup()
                            callOnMainThread {
                                onSpeakerStatusChanged?.invoke(false)
                                onRemoteStreamActive?.invoke(false)
                            }
                        } catch (e: Exception) {
                            Log.e("WebRTCService", "Error cleaning up listener: $e")
                        }
                        return@launch
                    }

                    if (speakerId != selfId) {
                        setupListenerConnection(groupId, selfId, speakerId)
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e("WebRTCService", "Speaker listener cancelled: ${error.message}")
            }
        }
        speakerRef.addValueEventListener(listener)
        synchronized(subscriptions) {
            subscriptions["speaker"] = speakerRef
        }

        // Monitor listeners count
        val listenersRef = db.child("calls/$groupId/listeners")
        val listenersListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val listeners = snapshot.children.mapNotNull { it.key }.toSet()
                callOnMainThread {
                    onActiveListenersChanged?.invoke(listeners)
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e("WebRTCService", "Listeners count listener cancelled: ${error.message}")
            }
        }
        listenersRef.addValueEventListener(listenersListener)
        synchronized(subscriptions) {
            subscriptions["listeners_count"] = listenersRef
        }
    }

    private suspend fun setupListenerConnection(groupId: String, selfId: String, speakerId: String) {
        try {
            Log.d("WebRTCService", "Setting up listener connection to speaker: $speakerId")

            val iceServers = listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer()
            )

            val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
                rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            }

            val pc = withContext(Dispatchers.Main) {
                factory?.createPeerConnection(rtcConfig, createPeerConnectionObserver(selfId, false))
            } ?: return

            speakerPc = pc

            // Add to listeners in Firebase
            db.child("calls/$groupId/listeners/$selfId").setValue(true).await()
            db.child("calls/$groupId/listeners/$selfId").onDisconnect().removeValue()

            // Setup offer subscription
            setupListenerOfferSubscription(groupId, selfId, pc)
            setupCandidateSubscription(groupId, pc)

            Log.d("WebRTCService", "Listener connection setup completed")
        } catch (e: Exception) {
            Log.e("WebRTCService", "Listener connection error: $e", e)
        }
    }

    private fun setupListenerOfferSubscription(groupId: String, selfId: String, pc: PeerConnection) {
        val offersRef = db.child("calls/$groupId/offers/$selfId")
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return
                serviceScope.launch {
                    try {
                        val offerData = snapshot.value as? Map<String, Any> ?: return@launch
                        val sdp = offerData["sdp"] as? String ?: return@launch
                        val type = offerData["type"] as? String ?: return@launch

                        val sessionDescription = SessionDescription(
                            SessionDescription.Type.fromCanonicalForm(type),
                            sdp
                        )

                        withContext(Dispatchers.Main) {
                            if (pc.signalingState() == PeerConnection.SignalingState.STABLE) {
                                pc.setRemoteDescription(object : SdpObserver {
                                    override fun onSetSuccess() {
                                        // Create answer
                                        val answerConstraints = MediaConstraints().apply {
                                            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
                                        }

                                        pc.createAnswer(object : SdpObserver {
                                            override fun onCreateSuccess(desc: SessionDescription) {
                                                pc.setLocalDescription(object : SdpObserver {
                                                    override fun onSetSuccess() {
                                                        serviceScope.launch {
                                                            try {
                                                                db.child("calls/$groupId/answers/$selfId").setValue(
                                                                    hashMapOf(
                                                                        "sdp" to desc.description,
                                                                        "type" to desc.type.canonicalForm(),
                                                                        "from" to selfId
                                                                    )
                                                                ).await()
                                                                callOnMainThread {
                                                                    onSpeakerStatusChanged?.invoke(true)
                                                                }
                                                                Log.d("WebRTCService", "Answer sent successfully")
                                                            } catch (e: Exception) {
                                                                Log.e("WebRTCService", "Error sending answer: $e")
                                                            }
                                                        }
                                                    }
                                                    override fun onSetFailure(error: String?) {
                                                        Log.e("WebRTCService", "SetLocalDescription error: $error")
                                                    }
                                                    override fun onCreateSuccess(p0: SessionDescription?) {}
                                                    override fun onCreateFailure(p0: String?) {}
                                                }, desc)
                                            }
                                            override fun onCreateFailure(error: String?) {
                                                Log.e("WebRTCService", "CreateAnswer error: $error")
                                            }
                                            override fun onSetSuccess() {}
                                            override fun onSetFailure(error: String?) {}
                                        }, answerConstraints)
                                    }
                                    override fun onSetFailure(error: String?) {
                                        Log.e("WebRTCService", "SetRemoteDescription error: $error")
                                    }
                                    override fun onCreateSuccess(p0: SessionDescription?) {}
                                    override fun onCreateFailure(p0: String?) {}
                                }, sessionDescription)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("WebRTCService", "Error processing offer: $e")
                    }
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e("WebRTCService", "Offer listener cancelled: ${error.message}")
            }
        }
        offersRef.addValueEventListener(listener)
        synchronized(subscriptions) {
            subscriptions["offer_$selfId"] = offersRef
        }
    }

    fun dispose() {
        if (!isDisposed.compareAndSet(false, true)) return

        Log.d("WebRTCService", "Disposing WebRTC service...")

        serviceScope.launch {
            try {
                stopCall()

                withContext(Dispatchers.Main) {
                    // Reset audio manager
                    audioManager?.mode = AudioManager.MODE_NORMAL
                    audioManager?.isSpeakerphoneOn = false

                    // Dispose factory last
                    factory?.dispose()
                    factory = null
                }

                serviceScope.cancel()
                Log.d("WebRTCService", "WebRTC service disposed successfully")
            } catch (e: Exception) {
                Log.e("WebRTCService", "Error during dispose: $e", e)
            }
        }
    }

    override fun onBind(p0: Intent?): IBinder? {
        TODO("Not yet implemented")
    }
}