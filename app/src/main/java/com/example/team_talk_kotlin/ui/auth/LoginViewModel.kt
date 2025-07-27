package com.example.team_talk_kotlin.ui.auth

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.team_talk_kotlin.data.auth.AuthService
import com.example.team_talk_kotlin.ui.home.HomeScreen
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// Data class representing UI state
data class LoginUiState(
    val phone: String = "",
    val password: String = "",
    val error: String? = null
)

class LoginViewModel : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state

    private val firebaseService = AuthService

    fun onPhoneChange(phone: String) {
        _state.value = _state.value.copy(phone = phone)
    }

    fun onPasswordChange(password: String) {
        _state.value = _state.value.copy(password = password)
    }

    fun checkLoggedIn(context: Context) {
        viewModelScope.launch {
            val phone = firebaseService.getSavedPhone(context)
            val password = firebaseService.getSavedPassword(context)

            if (!phone.isNullOrEmpty() && !password.isNullOrEmpty()) {
                val guard = firebaseService.loginGuard(phone, password)
                if (guard != null) {
                    saveFcmToken(guard["id"].toString())
                    HomeScreen.start(context, guard)
                }
            }
        }
    }

    fun handleLogin(context: Context) {
        val phone = _state.value.phone.trim()
        val password = _state.value.password.trim()

        if (phone.isEmpty() || password.isEmpty()) {
            _state.value = _state.value.copy(error = "Please enter phone and password")
            return
        }

        viewModelScope.launch {
            val guard = firebaseService.loginGuard(phone, password)
            if (guard != null) {
                firebaseService.saveCredentials(context, phone, password)
                saveFcmToken(guard["id"].toString())
                HomeScreen.start(context, guard)
            } else {
                _state.value = _state.value.copy(error = "Invalid phone or password")
            }
        }
    }

    private fun saveFcmToken(userId: String) {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            FirebaseDatabase.getInstance().getReference("user_tokens/$userId").setValue(token)
        }
    }
}
