package com.example.team_talk_kotlin.ui.auth

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.team_talk_kotlin.data.auth.AuthService
import kotlinx.coroutines.launch
//import androidx.compose.ui.text.input.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api


class ForgotPasswordActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ForgotPasswordScreen(onSuccess = { finish() })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ForgotPasswordScreen(onSuccess: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var phone by remember { mutableStateOf("") }
    var otp by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var otpSent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Reset Password") })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("Phone") },
//                keyboardOptions = androidx.compose.ui.text.input.KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (otpSent) {
                Spacer(modifier = Modifier.height(20.dp))
                OutlinedTextField(
                    value = otp,
                    onValueChange = { otp = it },
                    label = { Text("OTP") },
//                    keyboardOptions = androidx.compose.ui.text.input.KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(20.dp))
                OutlinedTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    label = { Text("New Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(30.dp))
            Button(onClick = {
                scope.launch {
                    if (!otpSent) {
                        if (phone.length != 10) {
                            error = "Enter valid phone number"
                            return@launch
                        }
                        val err = AuthService.sendOtp(phone)
                        if (err == null) {
                            otpSent = true
                        } else {
                            error = err
                        }
                    } else {
                        if (otp.length != 4 || newPassword.length < 4) {
                            error = "OTP must be 4 digits and password at least 4 characters"
                            return@launch
                        }
                        val err = AuthService.resetPassword(phone, otp, newPassword)
                        if (err == null) {
                            onSuccess()
                        } else {
                            error = err
                        }
                    }
                }
            }) {
                Text(if (otpSent) "Reset Password" else "Send OTP")
            }

            error?.let {
                Spacer(modifier = Modifier.height(20.dp))
                Text(it, color = Color.Red)
            }
        }
    }
}
