package com.example.team_talk_kotlin.ui.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.team_talk_kotlin.data.model.Guard

@OptIn(ExperimentalMaterial3Api::class)
class HomeScreenActivity : ComponentActivity() {
    private val viewModel: HomeViewModel by viewModels {
        HomeViewModelFactory(
            guard = intent.getSerializableExtra("guard") as? Guard
                ?: Guard(id = "", name = "Unknown", companyId = ""),
            context = this
        )
    }

//    Binding the HomeViewModel to HomeScreen
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request permissions immediately
        requestMicrophonePermission()

        setContent {
            MaterialTheme {
                HomeScreenContent(viewModel)
            }
        }
    }

//    Request the Microphone Permission
    private fun requestMicrophonePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), MICROPHONE_PERMISSION_REQUEST)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == MICROPHONE_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("HomeScreenActivity", "Microphone permission granted")
                viewModel.onPermissionGranted()
            } else {
                Log.w("HomeScreenActivity", "Microphone permission denied")
            }
        }
    }

//    Activity is getting Destoyed
    override fun onDestroy() {
        Log.d("HomeScreenActivity", "Activity being destroyed")
        super.onDestroy()
    }

    companion object {
        private const val MICROPHONE_PERMISSION_REQUEST = 1001

        fun start(context: Context, guard: Guard) {
            val intent = Intent(context, HomeScreenActivity::class.java)
            intent.putExtra("guard", guard)
            context.startActivity(intent)
        }
    }

    @Composable
    fun HomeScreenContent(viewModel: HomeViewModel) {
        val state by viewModel.state.collectAsState()
        val context = LocalContext.current

        val isCurrentUserSpeaker = state.currentSpeakerId == viewModel.guard.id
        val isAnotherSpeaking = state.currentSpeakerId != null && !isCurrentUserSpeaker
//        val onlineCount = state.activeListeners.size + if (state.currentSpeakerId != null) 1 else 0
        val onlineCount = state.activeListeners.size
        val totalMembers = state.groupMembers.size

        LaunchedEffect(state) {
            Log.d("UIState", "isConnecting: ${state.isConnectionInProgress}, isSpeaking: ${state.isSpeaking}")
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column(horizontalAlignment = Alignment.Start) {
                            Text("Welcome, ${viewModel.guard.name}")
                            state.companyDetails?.let {
                                Text(it.name, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    actions = {
                        if (state.isReconnecting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp).padding(end = 16.dp),
                                color = MaterialTheme.colorScheme.onSurface,
                                strokeWidth = 2.dp
                            )
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .padding(16.dp)
                    .fillMaxSize()
            ) {
                // Error display
                state.connectionError?.let { error ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = "Error",
                                tint = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.size(24.dp)
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            Text(
                                text = error,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )

                            TextButton(
                                onClick = { viewModel.clearError() }
                            ) {
                                Text("Dismiss")
                            }
                        }
                    }
                }

                // Group selection
                if (state.groups.isNotEmpty()) {
                    Column(
                        modifier = Modifier.padding(vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Select Group:", fontWeight = FontWeight.Bold)
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(state.groups) { group ->
                                val isSelected = state.selectedGroup?.id == group.id
                                FilterChip(
                                    onClick = { viewModel.onGroupSelected(group) },
                                    label = { Text(group.name) },
                                    selected = isSelected,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )
                            }
                        }
                    }
                } else {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            "No groups available",
                            modifier = Modifier.padding(16.dp),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Group info
                state.selectedGroup?.let { group ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "Group: ${group.name}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                                Text(
                                    "$onlineCount/$totalMembers online",
                                    color = if (onlineCount > 0) Color.Green else Color.Gray,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Group members
                if (state.groupMembers.isNotEmpty()) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Group Members:", fontWeight = FontWeight.Bold)
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                        ) {
                            LazyColumn(
                                modifier = Modifier.padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(state.groupMembers) { member ->
                                    val memberId = member.id
                                    val memberName = member.name
                                    val isConnected = state.activeListeners.contains(memberId) || state.currentSpeakerId == memberId
                                    val isSpeakingMember = state.currentSpeakerId == memberId
                                    val isCurrentUser = memberId == viewModel.guard.id

                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(
                                            containerColor = when {
                                                isSpeakingMember -> MaterialTheme.colorScheme.primaryContainer
                                                isCurrentUser -> MaterialTheme.colorScheme.secondaryContainer
                                                isConnected -> MaterialTheme.colorScheme.surfaceVariant
                                                else -> MaterialTheme.colorScheme.surface
                                            }
                                        ),
                                        elevation = CardDefaults.cardElevation(defaultElevation = if (isSpeakingMember) 4.dp else 1.dp)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(12.dp)
                                        ) {
                                            Text(
                                                text = if (isCurrentUser) "$memberName (You)" else memberName,
                                                fontWeight = if (isSpeakingMember) FontWeight.Bold else FontWeight.Normal,
                                                modifier = Modifier.weight(1f)
                                            )

                                            Spacer(modifier = Modifier.width(8.dp))

                                            if (isSpeakingMember) {
                                                Icon(
                                                    imageVector = Icons.Default.Mic,
                                                    contentDescription = "Speaking",
                                                    modifier = Modifier.size(20.dp),
                                                    tint = Color.Red
                                                )
                                                Text(
                                                    text = "Speaking",
                                                    color = Color.Red,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    modifier = Modifier.padding(start = 4.dp)
                                                )
                                            } else {
                                                Icon(
                                                    imageVector = if (isConnected) Icons.Default.Headset else Icons.Default.HeadsetOff,
                                                    contentDescription = if (isConnected) "Connected" else "Disconnected",
                                                    modifier = Modifier.size(18.dp),
                                                    tint = if (isConnected) Color.Green else Color.Gray
                                                )
                                                Text(
                                                    text = if (isConnected) "Online" else "Offline",
                                                    color = if (isConnected) Color.Green else Color.Gray,
                                                    fontSize = 12.sp,
                                                    modifier = Modifier.padding(start = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (state.selectedGroup != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            "No group members found",
                            modifier = Modifier.padding(16.dp),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(0.1f))

                // Status indicators
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (state.isRemoteAudioActive) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color.Green.copy(alpha = 0.1f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Headset,
                                    contentDescription = "Receiving audio",
                                    tint = Color.Green,
                                    modifier = Modifier.size(20.dp)
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                Text(
                                    text = "Receiving audio...",
                                    color = Color.Green,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    if (state.isReconnecting) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFFFFA000).copy(alpha = 0.1f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color(0xFFFFA000),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Reconnecting...",
                                    color = Color(0xFFFFA000),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Speak button
                state.selectedGroup?.let {
                    Column {
//                        Button grey or Enable or Disable
                        Button(
                            onClick = { viewModel.handleTapToSpeak() },
                            enabled = !state.isConnectionInProgress && !isAnotherSpeaking,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.isSpeaking) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    Color(0xFF008080)
                                },
                                contentColor = Color.White,
                                disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
//                            Loading / Buffering
                            if (state.isConnectionInProgress) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        color = Color.White,
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(20.dp)
                                    )

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Text(
                                        text = "Connecting...",
                                        color = Color.White
                                    )
                                }
                            }
                            else {
//                                Stop / Speak to Group
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Mic,
                                        contentDescription = null,
                                        modifier = Modifier.size(20.dp)
                                    )

                                    Spacer(modifier = Modifier.width(8.dp))

                                    Text(
                                        text = if (state.isSpeaking) "STOP SPEAKING" else "SPEAK TO GROUP",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // Additional status messages
                        if (state.isSpeaking && state.activeListeners.isEmpty()) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFFFFA000).copy(alpha = 0.1f)
                                )
                            ) {
                                Text(
                                    "⚠️ No listeners connected yet...",
                                    color = Color(0xFFFFA000),
                                    modifier = Modifier.padding(12.dp),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        if (isAnotherSpeaking) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                )
                            ) {
                                Text(
                                    "🔴 Another guard is speaking. Please wait...",
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    modifier = Modifier.padding(12.dp),
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                } ?: run {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            "Please select a group to start speaking",
                            modifier = Modifier.padding(16.dp),
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Footer
                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "Powered by Toorant Communications",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    state.licenseStatus,
                    fontSize = 12.sp,
                    color = state.licenseColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    fontWeight = if (state.licenseColor == Color.Red) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}