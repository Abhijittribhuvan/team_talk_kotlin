package com.example.team_talk_kotlin.data.company

import com.example.team_talk_kotlin.data.firebase.FirebaseUtil
import kotlinx.coroutines.tasks.await

object CompanyService {
    suspend fun getCompanyById(companyId: String): Map<String, Any>? {
        val snap = FirebaseUtil.db.child("companies/$companyId").get().await()
        if (!snap.exists()) return null

        val raw = snap.value as? Map<*, *> ?: return null

        val data = mutableMapOf<String, Any>()
        for ((k, v) in raw) {
            val keyStr = k?.toString() ?: continue
            val valueNonNull = v ?: continue
            data[keyStr] = valueNonNull
        }

        data["id"] = companyId
        return data
    }
}

