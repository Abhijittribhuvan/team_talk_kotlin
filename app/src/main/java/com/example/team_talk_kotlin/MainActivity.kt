package com.example.team_talk_kotlin

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import com.example.team_talk_kotlin.data.auth.AuthService
import com.example.team_talk_kotlin.ui.auth.LoginScreen
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme
import com.example.team_talk_kotlin.ui.home.HomeScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            val phone = AuthService.getSavedPhone(applicationContext)
            val password = AuthService.getSavedPassword(applicationContext)

            if (!phone.isNullOrEmpty() && !password.isNullOrEmpty()) {
                val guard = AuthService.loginGuard(phone, password)
                if (guard != null) {
                    HomeScreen.start(this@MainActivity, guard)
                    finish()
                    return@launch
                }
            }

            setContent {
                MaterialTheme {
                    LoginScreen()
                }
            }
        }
    }
}
