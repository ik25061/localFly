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

    fun setAutoDeleteEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("auto_delete_on_finish", enabled).apply()
    }

    fun isAutoDeleteEnabled(): Boolean = prefs.getBoolean("auto_delete_on_finish", false)

    // --- Soporte Offline para Like/Dislike ---

    fun addPendingLike(songId: String, liked: Boolean) {
        val pending = getPendingLikes().toMutableMap()
        pending[songId] = liked
        savePendingLikes(pending)
    }

    fun getPendingLikes(): Map<String, Boolean> {
        val json = prefs.getString("pending_likes", "{}") ?: "{}"
        val type = object : com.google.gson.reflect.TypeToken<Map<String, Boolean>>() {}.type
        return try {
            com.google.gson.Gson().fromJson(json, type)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    private fun savePendingLikes(likes: Map<String, Boolean>) {
        val json = com.google.gson.Gson().toJson(likes)
        prefs.edit().putString("pending_likes", json).apply()
    }

    fun removePendingLike(songId: String) {
        val pending = getPendingLikes().toMutableMap()
        pending.remove(songId)
        savePendingLikes(pending)
    }

    fun addPendingDislike(songId: String) {
        val pending = getPendingDislikes().toMutableSet()
        pending.add(songId)
        savePendingDislikes(pending)
    }

    fun getPendingDislikes(): Set<String> {
        val json = prefs.getString("pending_dislikes", "[]") ?: "[]"
        val type = object : com.google.gson.reflect.TypeToken<Set<String>>() {}.type
        return try {
            com.google.gson.Gson().fromJson(json, type)
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun savePendingDislikes(dislikes: Set<String>) {
        val json = com.google.gson.Gson().toJson(dislikes)
        prefs.edit().putString("pending_dislikes", json).apply()
    }

    fun removePendingDislike(songId: String) {
        val pending = getPendingDislikes().toMutableSet()
        pending.remove(songId)
        savePendingDislikes(pending)
    }

    fun saveFavoriteArtists(artistIds: Set<String>) {
        val json = com.google.gson.Gson().toJson(artistIds)
        prefs.edit().putString("favorite_artists", json).apply()
    }

    fun getFavoriteArtists(): Set<String> {
        val json = prefs.getString("favorite_artists", "[]") ?: "[]"
        val type = object : com.google.gson.reflect.TypeToken<Set<String>>() {}.type
        return try {
            com.google.gson.Gson().fromJson(json, type)
        } catch (e: Exception) {
            emptySet()
        }
    }
}