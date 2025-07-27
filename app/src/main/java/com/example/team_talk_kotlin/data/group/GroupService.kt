package com.example.team_talk_kotlin.data.group

import com.example.team_talk_kotlin.data.firebase.FirebaseUtil
import kotlinx.coroutines.tasks.await

object GroupService {
    suspend fun getGroupsForGuard(guardId: String): List<Map<String, Any>> {
        val snapshot = FirebaseUtil.db.child("groups").get().await()
        if (!snapshot.exists()) return emptyList()

        val groups = mutableListOf<Map<String, Any>>()
        val data = snapshot.value as? Map<*, *> ?: return emptyList()

        for ((key, value) in data) {
            val rawMap = value as? Map<*, *> ?: continue

            // Manually build a non-nullable Map<String, Any>
            val group = mutableMapOf<String, Any>()
            for ((k, v) in rawMap) {
                val keyStr = k?.toString() ?: continue
                val nonNullValue = v ?: continue
                group[keyStr] = nonNullValue
            }

            // Handle guard_ids field
            val rawGuardIds = group["guard_ids"]
            val guardIds = when (rawGuardIds) {
                is List<*> -> rawGuardIds.mapNotNull { it?.toString() }
                is String -> rawGuardIds.split(",").map { it.trim() }
                is Map<*, *> -> rawGuardIds.values.map { it.toString() }
                else -> emptyList()
            }

            if (guardIds.contains(guardId)) {
                group["id"] = key.toString()
                groups.add(group)
            }
        }

        return groups
    }


}


