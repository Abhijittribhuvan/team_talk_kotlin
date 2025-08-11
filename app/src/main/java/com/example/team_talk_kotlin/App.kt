package com.example.team_talk_kotlin

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)

        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser == null) {
            auth.signInAnonymously()
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Log.d("App", "Anonymous sign-in successful: ${auth.currentUser?.uid}")
                    } else {
                        Log.e("App", "Anonymous sign-in failed", task.exception)
                    }
                }
        } else {
            Log.d("App", "Already signed in: ${auth.currentUser?.uid}")
        }

        // Optional: Keep data synced offline
        FirebaseDatabase.getInstance().setPersistenceEnabled(true)

    }
}
