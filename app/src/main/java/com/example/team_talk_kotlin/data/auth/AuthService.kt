package com.example.team_talk_kotlin.data.auth

import com.example.team_talk_kotlin.data.firebase.FirebaseUtil
import com.example.team_talk_kotlin.data.guard.GuardService
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.*
import kotlin.random.Random
import okhttp3.*
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray


object AuthService {

    private var phoneNumberId = "553313077858045"
    private var bearerId = "tqYr8PooE3HGtJ1LGR3fMP4sQUtcpd1rZARFUuwiSc"
    private var apiCompanyId = "676fbe2232cb3202"
    private var templateName = "gopi_pass_001"
    private val client = OkHttpClient()

    suspend fun loginGuard(phone: String, password: String): Map<String, Any>? {

        val waba = FirebaseUtil.db.child("whatsapp").get().await()
        if(!waba.exists()) return null

        val wb = waba.value as Map<*, *>
        for ((key, value) in wb) {
            val wba = (value as Map<*,*>).mapKeys { it.key.toString() }.mapValues { it.value?: "" }
            phoneNumberId = wba["phoneNumberId"] as String
            bearerId = wba["bearerId"] as String
            apiCompanyId = wba["apiCompanyId"] as String
            templateName = wba["templateName"] as String
        }

        val snapshot = FirebaseUtil.db.child("guards").get().await()
        if (!snapshot.exists()) return null

        val data = snapshot.value as Map<*, *>
        for ((key, value) in data) {
            val guard = (value as Map<*, *>).mapKeys { it.key.toString() }.mapValues { it.value!! }
            val isDeleted = guard["deleted"] == true
            if (!isDeleted && guard["phone"] == phone && guard["password"] == password) {
                return guard + ("id" to key.toString())
            }
        }
        return null
    }

   suspend fun saveCredentials(context: Context, phone: String, password: String) {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("phone", phone)
            .putString("password", password)
            .apply()
    }

   suspend fun getSavedPhone(context: Context): String? {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getString("phone", null)
    }

   suspend fun getSavedPassword(context: Context): String? {
        val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
        return prefs.getString("password", null)
    }


    suspend fun sendOtp(phone: String): String? {
        val guard = GuardService.findGuardByPhone(phone) ?: return "Mobile not registered"
        val uid = guard["id"] as String

        val otp = (Random.nextInt(9000) + 1000).toString()
//        val expiry = Instant.now().plus(5, ChronoUnit.MINUTES).toString()
        val expiry = (System.currentTimeMillis() + 5 * 60 * 1000).toString()

        FirebaseUtil.db.child("guards/$uid").updateChildren(mapOf(
            "otp_token" to otp,
            "otp_expiry" to expiry
        )).await()

        val payload = JSONObject().apply {
            put("phone_number_id", phoneNumberId)
            put("myop_ref_id", "otp_${System.currentTimeMillis()}")
            put("customer_country_code", "91")
            put("customer_number", phone)
            put("data", JSONObject().apply {
                put("type", "template")
                put("context", JSONObject().apply {
                    put("template_name", templateName)
                    put("body", JSONObject().put("otp", otp))
                    put("buttons", JSONArray().apply {
                        put(JSONObject().apply {
                            put("otp", otp)
                            put("index", 0)
                        })
                    })
                })
            })
        }

        val request = Request.Builder()
            .url("https://publicapi.myoperator.co/chat/messages")
            .addHeader("Content-Type", "application/json")
            .addHeader("Authorization", "Bearer $bearerId")
            .addHeader("X-MYOP-COMPANY-ID", apiCompanyId)
            .post(RequestBody.create("application/json".toMediaTypeOrNull(), payload.toString()))
            .build()

//        val response = client.newCall(request).execute()
          val response = withContext(Dispatchers.IO) {
            client.newCall(request).execute()
          }

        return if (response.isSuccessful) null else "OTP send failed (${response.code})"
    }

    suspend fun verifyOtp(phone: String, otp: String): Map<String, Any>? {
        val guard = GuardService.findGuardByPhone(phone) ?: return null
        val token = guard["otp_token"]?.toString()
        val expiryS = guard["otp_expiry"]?.toString()

//        if (token != otp || expiryS == null || Instant.parse(expiryS).isBefore(Instant.now())) {
        if (token != otp || expiryS == null || expiryS.toLong() < System.currentTimeMillis()) {
            return null
        }

        FirebaseUtil.db.child("guards/${guard["id"]}").updateChildren(
            mapOf("otp_token" to null, "otp_expiry" to null)
        ).await()

        return guard
    }

    suspend fun resetPassword(phone: String, otp: String, newPassword: String): String? {
        val guard = verifyOtp(phone, otp) ?: return "Invalid or expired OTP"
        if (newPassword.length < 4) return "Password must be ≥4 characters"

        FirebaseUtil.db.child("guards/${guard["id"]}").updateChildren(
            mapOf("password" to newPassword)
        ).await()
        return null
    }
}
