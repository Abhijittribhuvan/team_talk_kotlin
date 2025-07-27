package com.example.team_talk_kotlin.data.firebase

import com.google.firebase.database.FirebaseDatabase

object FirebaseUtil {
    val db = FirebaseDatabase.getInstance().reference
}
