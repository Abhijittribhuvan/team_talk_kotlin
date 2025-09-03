package com.example.team_talk_kotlin.ui.auth

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
//import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.team_talk_kotlin.data.auth.AuthService
import com.example.team_talk_kotlin.data.model.Guard
import com.example.team_talk_kotlin.ui.home.HomeScreenActivity
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class OtpLoginActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OtpLoginScreen()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OtpLoginScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val firebaseService = remember { AuthService }

    var phone by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var otpSent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Sign in via OTP") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(24.dp)
        ) {
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("Phone") },
//                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (otpSent) {
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = otp,
                    onValueChange = { otp = it },
                    label = { Text("Enter OTP") },
//                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = {
                    scope.launch {
                        if (!otpSent) {
                            if (phone.length != 10) {
                                error = "Enter valid 10-digit phone number"
                                return@launch
                            }
                            val err = firebaseService.sendOtp(phone)
                            if (err == null) {
                                otpSent = true
                                error = null
                            } else {
                                error = err
                            }
                        } else {
                            if (otp.length != 4) {
                                error = "Enter 4-digit OTP"
                                return@launch
                            }
                            val guard = firebaseService.verifyOtp(phone, otp)
                            if (guard != null) {
                                val prefs = context.getSharedPreferences("login_prefs", Context.MODE_PRIVATE)
                                with(prefs.edit()) {
                                    putString("phone", phone)
                                    putString("password", guard["password"] as? String ?: "")
                                    apply()
                                }
                                Log.d("{{{OTPActivity}}}","Phone No and Password :$phone and ${guard["password"]}")
                                val guardId = guard["id"] as? String
                                val token = FirebaseMessaging.getInstance().token.await()
                                guardId?.let {
                                    FirebaseDatabase.getInstance().reference
                                        .child("user_tokens/$it")
                                        .setValue(token)
                                }
//
                                val guard = Guard(
                                    id = guard["id"] as? String ?: "",
                                    name = guard["name"] as? String ?: "Unknown",
                                    companyId = guard["company_id"] as? String ?: ""
                                )
                                HomeScreenActivity.start(context, guard)
                            } else {
                                error = "Invalid or expired OTP"
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (otpSent) "Verify OTP" else "Send OTP")
            }

            error?.let {
                Spacer(modifier = Modifier.height(20.dp))
                Text(it, color = Color.Red)
            }
        }
    }
}
