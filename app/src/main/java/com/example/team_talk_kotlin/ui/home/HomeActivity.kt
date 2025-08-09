package com.example.team_talk_kotlin.ui.home

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import com.example.team_talk_kotlin.data.model.Guard
import com.example.team_talk_kotlin.ui.home.HomeScreenActivity // replace with your theme

class HomeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Guard is passed as a serializable extra
        val guard = intent.getSerializableExtra("guard") as? HashMap<String, Any> ?: return

//        setContent {
//            MaterialTheme {
//                HomeScreen.Content(guard)
//            }
//        }
        val guard2 = Guard(
            id = guard["id"] as? String ?: "",
            name = guard["name"] as? String ?: "Unknown",
            companyId = guard["company_id"] as? String ?: ""
        )
        HomeScreenActivity.start(this, guard2)
    }
}
