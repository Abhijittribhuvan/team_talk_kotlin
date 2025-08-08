package com.example.team_talk_kotlin.data.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase

object FirebaseUtil {
    val db = FirebaseDatabase.getInstance().reference
}

//object FirebaseUtil {
//    val db: DatabaseReference
//        get() {
//            val user = FirebaseAuth.getInstance().currentUser
//            if (user != null) {
//                return FirebaseDatabase.getInstance().reference
//            } else {
//                throw IllegalStateException("User not authenticated.")
//            }
//        }
//}
