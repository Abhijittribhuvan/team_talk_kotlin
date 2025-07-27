package com.example.team_talk_kotlin.ui.home

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class HomeScreen : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val guardName = intent.getStringExtra("guard_name") ?: "User"

        setContent {
            Surface(modifier = Modifier.fillMaxSize()) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Text(text = "Welcome, $guardName!", fontSize = 24.sp)
                }
            }
        }
    }

    companion object {
        fun start(context: Context, guard: Map<String, Any>) {
            val intent = Intent(context, HomeScreen::class.java)
            intent.putExtra("guard_name", guard["name"].toString())
            context.startActivity(intent)
        }
    }
}
