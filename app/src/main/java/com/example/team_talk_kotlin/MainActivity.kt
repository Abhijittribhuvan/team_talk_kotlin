package com.example.team_talk_kotlin

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.example.team_talk_kotlin.data.auth.AuthService
import com.example.team_talk_kotlin.data.model.Guard
import com.example.team_talk_kotlin.ui.auth.LoginScreen
import com.example.team_talk_kotlin.ui.home.HomeScreenActivity
import kotlinx.coroutines.launch
import androidx.compose.material3.MaterialTheme
import androidx.core.content.ContentProviderCompat.requireContext
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.FOREGROUND_SERVICE_MICROPHONE,
            Manifest.permission.POST_NOTIFICATIONS // optional, if targeting Android 13+
        )

        ActivityCompat.requestPermissions(this, permissions, 100)

        lifecycleScope.launch {
            val phone = AuthService.getSavedPhone(applicationContext)
            val password = AuthService.getSavedPassword(applicationContext)

            if (!phone.isNullOrEmpty() && !password.isNullOrEmpty()) {
                val guardData = AuthService.loginGuard(phone, password)
                if (guardData != null) {
                    // Convert Map<String, Any> to Guard
                    val guard = Guard(
                        id = guardData["id"] as? String ?: "",
                        name = guardData["name"] as? String ?: "Unknown",
                        companyId = guardData["company_id"] as? String ?: ""
                    )
                    HomeScreenActivity.start(this@MainActivity, guard)
                    finish()
                    return@launch
                }
            }

            // Call the function to check and request permissions
            requireNeededPermissions {
                // Logic to execute once permissions are granted
                Toast.makeText(this@MainActivity, "Permissions granted!", Toast.LENGTH_SHORT).show()

            }
    
            setContent {
                MaterialTheme {
                    LoginScreen()
                }
            }
        }
    }

    fun ComponentActivity.requireNeededPermissions(onPermissionsGranted: (() -> Unit)? = null) {
        val requestPermissionLauncher =
            registerForActivityResult(
                ActivityResultContracts.RequestMultiplePermissions()
            ) { grants ->
                // Check if any permissions weren't granted.
                for (grant in grants.entries) {
                    if (!grant.value) {
                        Toast.makeText(
                            this,
                            "Missing permission: ${grant.key}",
                            Toast.LENGTH_SHORT
                        )
                            .show()
                    }
                }

                // If all granted, notify if needed.
                if (onPermissionsGranted != null && grants.all { it.value }) {
                    onPermissionsGranted()
                }
            }

        val neededPermissions = listOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            .filter { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_DENIED }
            .toTypedArray()

        if (neededPermissions.isNotEmpty()) {
            requestPermissionLauncher.launch(neededPermissions)
        } else {
            onPermissionsGranted?.invoke()
        }
    }
}