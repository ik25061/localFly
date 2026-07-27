package com.example.localfly.network

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SessionManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "localfly_session",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveSession(token: String, userId: String, username: String) {
        prefs.edit()
            .putString("token", token)
            .putString("user_id", userId)
            .putString("username", username)
            .apply()
    }

    fun getToken(): String? = prefs.getString("token", null)

    fun getUsername(): String? = prefs.getString("username", null)
    fun getUserId(): String? = prefs.getString("user_id", null)
    fun clearSession() {
        prefs.edit().clear().apply()
    }

    fun isLoggedIn(): Boolean = getToken() != null
}