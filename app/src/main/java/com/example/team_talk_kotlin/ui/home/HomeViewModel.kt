package com.example.team_talk_kotlin.ui.home

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.compose.ui.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.team_talk_kotlin.data.company.CompanyService
import com.example.team_talk_kotlin.data.firebase.FirebaseUtil
import com.example.team_talk_kotlin.data.group.GroupService
import com.example.team_talk_kotlin.data.guard.GuardService
import com.example.team_talk_kotlin.data.webrtc.LiveKitService
import com.example.team_talk_kotlin.data.model.Company
import com.example.team_talk_kotlin.data.model.Group
import com.example.team_talk_kotlin.data.model.Guard
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import io.livekit.android.room.ConnectionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import com.example.team_talk_kotlin.data.webrtc.BackgroundVoiceService

data class HomeScreenState(
    val groups: List<Group> = emptyList(),
    val selectedGroup: Group? = null,
    val groupMembers: List<Guard> = emptyList(),
    val isSpeaking: Boolean = false,
    val currentSpeakerId: String? = null,
    val activeListeners: Set<String> = emptySet(),
    val companyDetails: Company? = null,
    val licenseStatus: String = "License status: Unknown",
    val licenseColor: Color = Color.Gray,
    val isRemoteAudioActive: Boolean = false,
    val isConnectionInProgress: Boolean = false,
    val isReconnecting: Boolean = false,
    val connectionError: String? = null,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val isDisconnecting: Boolean = false,
)

enum class ConnectionState {
    CONNECTING, CONNECTED, DISCONNECTING, DISCONNECTED, RECONNECTING
}

class HomeViewModel(val guard: Guard, private val context: Context) : ViewModel() {
    private val _state = MutableStateFlow(HomeScreenState())
    val state = _state.asStateFlow()

    private var liveKitService: LiveKitService? = null
    private var speakerCheckJob: Job? = null
    private var speakerRef: com.google.firebase.database.DatabaseReference? = null
    private var speakerListener: ValueEventListener? = null
    private var permissionGranted = false

    init {
        viewModelScope.launch {
            initializeServices()
        }
    }

    private suspend fun initializeServices() = withContext(Dispatchers.IO) {
        try {
            Log.d("HomeViewModel", "Initializing services...")

            // Check microphone permission first
            permissionGranted = isMicrophonePermissionGranted()

            if (permissionGranted) {
                liveKitService = LiveKitService().apply {
                    setupCallbacks(this)
                }
            }

//          Background Service and its related things
            val intent = Intent(context, BackgroundVoiceService::class.java)
            intent.putExtra("guard", guard) // Guard must be Serializable or Parcelable
            ContextCompat.startForegroundService(context, intent)
//

            loadData()
            startLicenseMonitoring()
            Log.d("HomeViewModel", "Services initialized successfully")
        } catch (e: Exception) {
            Log.e("HomeViewModel", "Failed to initialize services: $e", e)
            updateState { copy(connectionError = "Failed to initialize app services") }
        }
    }

    fun onPermissionGranted() {
        viewModelScope.launch {
            permissionGranted = true
            liveKitService = LiveKitService().apply {
                setupCallbacks(this)
            }
        }
    }

    private fun setupCallbacks(service: LiveKitService) {
        service.onSpeakingStatusChanged = { isActive ->
            updateState {
                copy(
                    isSpeaking = isActive,
                    isConnectionInProgress = false,
                    connectionState = if (isActive) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED
                )
            }
        }

        service.onRemoteAudioActive = { isActive ->
            updateState { copy(isRemoteAudioActive = isActive) }
        }

        service.onConnectionError = { error ->
            updateState {
                copy(
                    isConnectionInProgress = false,
                    connectionError = error
                )
            }
        }

        service.onReconnecting = { isReconnecting ->
            updateState { copy(
                isReconnecting = isReconnecting,
                connectionState = if (isReconnecting) ConnectionState.RECONNECTING else ConnectionState.CONNECTED
            ) }
        }
    }

//    Company Data Loading
    private suspend fun loadData() {
        try {
            // Load company
            CompanyService.getCompanyById(guard.companyId)?.let { companyMap ->
                val company = Company(
                    id = companyMap["id"] as? String ?: "",
                    name = companyMap["name"] as? String ?: "Unknown Company",
                    amcEnd = companyMap["amc_end"] as? String
                )
                updateState { copy(companyDetails = company) }
                validateLicense(company)
            }

            // Load groups
            val groups = GroupService.getGroupsForGuard(guard.id).map {
                Group(
                    id = it["id"] as String,
                    name = it["name"] as String,
                    guardIds = (it["guard_ids"] as? List<*>)?.mapNotNull { id -> id?.toString() } ?: emptyList()
                )
            }

            updateState { copy(groups = groups) }

            // Start periodic speaker check
            if (groups.isNotEmpty()) {
                startSpeakerCheck(groups)
            }
        } catch (e: Exception) {
            Log.e("HomeViewModel", "Error loading data: ${e.message}", e)
            updateState { copy(connectionError = "Failed to load app data") }
        }
    }

//    Check the speaker and auto selects the Group
    private fun startSpeakerCheck(groups: List<Group>) {
        speakerCheckJob?.cancel()
        speakerCheckJob = viewModelScope.launch(Dispatchers.IO) {
            while (coroutineContext.isActive) {
                try {
                    for (group in groups) {
                        val speakerId = getCurrentSpeaker(group.id)
                        if (speakerId != null && speakerId != guard.id) {
                            if (state.value.selectedGroup?.id != group.id) {
                                onGroupSelected(group)
                            }
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e("HomeViewModel", "Speaker check error: $e")
                }
                delay(3000)
            }
        }
    }

//    Get the Current Speaker
    private suspend fun getCurrentSpeaker(groupId: String): String? {
        return try {
            val snapshot = FirebaseUtil.db.child("calls/$groupId/speaker_id").get().await()
            snapshot.getValue(String::class.java)
        } catch (e: Exception) {
            null
        }
    }

//    Select the group and auto connect as a listener or speaker
    fun onGroupSelected(group: Group) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Remove previous Firebase listener
                speakerListener?.let { speakerRef?.removeEventListener(it) }

                // Load group members
                val guardsData = GuardService.getGuardsByIds(group.guardIds)
                val guards = guardsData.map {
                    Guard(
                        id = it["id"] as? String ?: "",
                        name = it["name"] as? String ?: "Unknown",
                        companyId = it["company_id"] as? String ?: ""
                    )
                }

                // Set up Firebase listener for speaker_id
                speakerRef = FirebaseUtil.db.child("calls/${group.id}/speaker_id")
                speakerListener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        updateState { copy(currentSpeakerId = snapshot.getValue(String::class.java)) }

                        viewModelScope.launch {
                            val speakerId = getCurrentSpeaker(group.id)
                            if (speakerId != null && speakerId != guard.id) {
                                Log.e("<<AutoListener>>","This is working for the Speaker also, and the speaker id is ${speakerId} and guard id is ${guard.id}")
                                connectAsListener(group.id)
                            }
                        }
                    }

                    override fun onCancelled(error: DatabaseError) {}
                }
                speakerRef?.addValueEventListener(speakerListener!!)

                updateState {
                    copy(
                        selectedGroup = group,
                        groupMembers = guards,
                        isSpeaking = false,
                        currentSpeakerId = null,
                        isRemoteAudioActive = false,
                        isConnectionInProgress = false,
                        isReconnecting = false,
                        connectionError = null
                    )
                }

                // Check if there's an active speaker and connect as listener
                val speakerId = getCurrentSpeaker(group.id)
                updateState { copy(currentSpeakerId = speakerId) }

                observeListeners(group.id)
//
//                if (speakerId != guard.id) {
//                    connectAsListener(group.id)
//                }
//

                if (speakerId != null && speakerId != guard.id) {
                    Log.d("<<AutoListener>>","This is working for the Speaker also, and the speaker id is ${speakerId} and guard id is ${guard.id}")
                    connectAsListener(group.id)
                }
            } catch (e: Exception) {
                updateState { copy(connectionError = "Failed to select group: ${e.message}") }
            }
        }
    }

    fun observeListeners(groupId: String) {
        val database = FirebaseDatabase.getInstance().getReference("listeners").child(groupId)

        database.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val onlineUserIds = snapshot.children.mapNotNull { it.key }.toSet()
                updateState { copy(activeListeners = onlineUserIds) }
                Log.e("<<Firebase Listener>>", "Online users: $onlineUserIds")
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("Firebase", "Failed to read listeners: ${error.message}")
            }
        })
    }


    fun handleTapToSpeak() {
        val currentState = state.value
        val group = currentState.selectedGroup ?: return

        if (!permissionGranted) {
            updateState { copy(connectionError = "Microphone permission required") }
            return
        }

        if (currentState.isConnectionInProgress) {
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
//                updateState { copy(isConnectionInProgress = true, connectionError = null) }

                if (currentState.isSpeaking) {
                    updateState { copy(isSpeaking = false) }
                    stopSpeaking(group.id)
                    Log.d("HomeViewModel","The function stop to Speak is working")
                } else {
                    Log.d("HomeViewModel","The function handle to speak is working and id is ${group.id}")
                    updateState { copy(isSpeaking = true, isConnectionInProgress = false) }
                    startSpeaking(group.id)
                }
            } catch (e: Exception) {
                updateState {
                    copy(
                        isConnectionInProgress = false,
                        connectionError = "Failed to start speaking: ${e.message}"
                    )
                }
            }
        }
    }

    private suspend fun startSpeaking(groupId: String) {
        // Check if someone else is speaking
        val currentSpeaker = getCurrentSpeaker(groupId)
        Log.d("StartSpeaking", "I am working 1 and current speaker is ${currentSpeaker}")
        if (currentSpeaker != null && currentSpeaker != guard.id) {
            updateState {
                copy(
                    isConnectionInProgress = false,
                    connectionError = "Another guard is speaking"
                )
            }
            return
        }

        // Set speaker in Firebase
        FirebaseUtil.db.child("calls/$groupId/speaker_id").setValue(guard.id).await()

        Log.d("StartSpeaking", "I am working 2 and call is written in the databse")

        // Generate token and connect

        val token = generateToken(groupId+"", guard.id+"", "speaker")

        Log.d("StartSpeaking", "I am not working the after token generation function gets failes")

        liveKitService?.disconnect()

        liveKitService?.connect(
            context = context,
            token = token,
            roomId = groupId,
            userId = guard.id,
            isSpeaker = true
        )

        Log.d("StartSpeaking", "I am not working the tokengeneration function gets failes")
    }

    private suspend fun connectAsListener(groupId: String) {
        liveKitService?.disconnect()

        val token = generateToken(groupId+"", guard.id+"", "listener")
        liveKitService?.connect(
            context = context,
            token = token,
            roomId = groupId,
            userId = guard.id,
            isSpeaker = false
        )
        Log.d("ConnectAsListener", "ConnectAsListener is getting called and token is $token")
    }

    private suspend fun stopSpeaking(groupId: String) {
        try{
            updateState {
                copy(
                    isSpeaking = false,
                    isConnectionInProgress = false
                )
            }
            liveKitService?.disconnect()
            delay(200)
//            liveKitService?.dispose()

            FirebaseUtil.db.child("calls/$groupId/speaker_id").removeValue().await()
        } catch (e: Exception){
            Log.e("StopSpeaking", "Error stopping speech: ${e.message}")
            updateState {
                copy(
                    connectionError = "Failed to stop speaking: ${e.message}",
                    isSpeaking = false,
                    isConnectionInProgress = false
                )
            }
        }

    }


    suspend fun generateToken(roomName: String, participantName: String, role: String): String = withContext(Dispatchers.IO) {
        val client = OkHttpClient()

        // Sanitize inputs
        val encodedRoom = URLEncoder.encode(roomName, "UTF-8")
        val encodedUser = URLEncoder.encode(participantName, "UTF-8")

        val json = JSONObject().apply {
            put("roomName", encodedRoom)
            put("participantName", encodedUser)
            put("role", role)
        }

//        val json = JSONObject().apply {
//            put("roomName", roomName)
//            put("participantName", participantName)
//            put("role", role)
//        }

        Log.d("GenerateToken", " I am working in the generate token1 ")

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = json.toString().toRequestBody(mediaType)
 
        val request = Request.Builder()
            .url("https://pure.devsamagri.com/livekit-token.php")
            .post(body)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) throw IOException("Unexpected code $response")

        val responseBody = response.body?.string() ?: throw IOException("Empty response")

        Log.d("GenerateToken", " I am working in the generate token2 and token is ${responseBody}")

        val jsonObject = JSONObject(responseBody)

        Log.d("GenerateToken", " I am working in the generate token3 ")

        return@withContext jsonObject.getString("token")
    }



    fun clearError() {
        updateState { copy(connectionError = null) }
    }

    private fun startLicenseMonitoring() {
        viewModelScope.launch(Dispatchers.IO) {
            state.value.companyDetails?.let { validateLicense(it) }
        }
    }

    private fun validateLicense(company: Company) {
        val amcEnd = company.amcEnd ?: return
        val now = Date()
        val endDate = try {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(amcEnd)
        } catch (e: Exception) {
            null
        } ?: return

        val formattedDate = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(endDate)

        updateState {
            if (now.after(endDate)) {
                copy(
                    licenseStatus = "License EXPIRED on $formattedDate",
                    licenseColor = Color.Red
                )
            } else {
                copy(
                    licenseStatus = "License valid until $formattedDate",
                    licenseColor = Color.Green
                )
            }
        }
    }

    private fun isMicrophonePermissionGranted(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun updateState(update: HomeScreenState.() -> HomeScreenState) {
//        _state.update { it.update() }
        _state.update {
            val newState = it.update()
            Log.d("StateUpdate", "State updated: $newState")
            newState
        }
    }

    override fun onCleared() {
        speakerCheckJob?.cancel()
        speakerListener?.let { speakerRef?.removeEventListener(it) }
        liveKitService?.dispose()
        super.onCleared()
    }
}

class HomeViewModelFactory(private val guard: Guard, private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return HomeViewModel(guard, context) as T
    }
}