package com.example.team_talk_kotlin.data.guard

import com.example.team_talk_kotlin.data.firebase.FirebaseUtil
import kotlinx.coroutines.tasks.await

object GuardService {
    suspend fun findGuardByPhone(phone: String): Map<String, Any>? {
        val snap = FirebaseUtil.db.child("guards").get().await()
        if (!snap.exists()) return null

        val data = snap.value as? Map<*, *> ?: return null
        for ((key, value) in data) {
            val rawMap = value as? Map<*, *> ?: continue

            // Convert to Map<String, Any> safely (remove null keys/values)
            val g = rawMap.entries
                .mapNotNull { (k, v) ->
                    val keyStr = k?.toString() ?: return@mapNotNull null
                    val valueNonNull = v ?: return@mapNotNull null
                    keyStr to valueNonNull
                }.toMap()

            if (g["phone"] == phone && g["deleted"] != true) {
                return g + ("id" to key.toString())
            }
        }
        return null
    }

    suspend fun getGuardsByIds(ids: List<String>): List<Map<String, Any>> {
        val snap = FirebaseUtil.db.child("guards").get().await()
        if (!snap.exists()) return emptyList()

        val result = mutableListOf<Map<String, Any>>()
        val data = snap.value as? Map<*, *> ?: return emptyList()

        for ((key, value) in data) {
            val id = key.toString()
            if (ids.contains(id)) {
                val rawMap = value as? Map<*, *> ?: continue

                val guard = rawMap.entries
                    .mapNotNull { (k, v) ->
                        val keyStr = k?.toString() ?: return@mapNotNull null
                        val valueNonNull = v ?: return@mapNotNull null
                        keyStr to valueNonNull
                    }.toMap()

                result.add(guard + ("id" to id))
            }
        }
        return result
    }
}
