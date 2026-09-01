package com.example.localfly.network

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SessionManager(context: Context) {

    private val prefs: SharedPreferences

    init {
        val appContext = context.applicationContext
        // Crear las prefs cifradas de forma segura. En algunos dispositivos/emuladores
        // el Keystore de Android falla al generar la clave (GeneralSecurityException /
        // IOException), lo que provocaba un crash al arrancar la app. Si eso ocurre,
        // usamos SharedPreferences normales como respaldo para no romper el inicio.
        prefs = try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                appContext,
                "localfly_session",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback: no cifradas. El usuario simplemente tendrá que volver a iniciar sesión.
            appContext.getSharedPreferences("localfly_session_plain", Context.MODE_PRIVATE)
        }
    }

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

    fun setCrossfadeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("crossfade_enabled", enabled).apply()
    }

    fun isCrossfadeEnabled(): Boolean = prefs.getBoolean("crossfade_enabled", false)

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

    fun addPendingLyricsUpload(songId: String, content: String) {
        val pending = getPendingLyricsUploads().toMutableMap()
        pending[songId] = content
        savePendingLyricsUploads(pending)
    }

    fun getPendingLyricsUploads(): Map<String, String> {
        val json = prefs.getString("pending_lyrics_uploads", null) ?: return emptyMap()
        return try {
            val type = object : com.google.gson.reflect.TypeToken<Map<String, String>>() {}.type
            com.google.gson.Gson().fromJson(json, type) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun removePendingLyricsUpload(songId: String) {
        val pending = getPendingLyricsUploads().toMutableMap()
        pending.remove(songId)
        savePendingLyricsUploads(pending)
    }

    private fun savePendingLyricsUploads(map: Map<String, String>) {
        prefs.edit().putString("pending_lyrics_uploads", com.google.gson.Gson().toJson(map)).apply()
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

    // --- Ajustes de App ---

    fun getTextSize(): String = prefs.getString("app_text_size", "Normal") ?: "Normal"
    fun setTextSize(size: String) = prefs.edit().putString("app_text_size", size).apply()

    fun getAppColor(): String = prefs.getString("app_color", "Green") ?: "Green"
    fun setAppColor(color: String) = prefs.edit().putString("app_color", color).apply()

    fun isAdmin(): Boolean = getUsername()?.equals("Rafael", ignoreCase = true) == true
}