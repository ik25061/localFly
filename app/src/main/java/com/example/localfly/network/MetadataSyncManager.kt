package com.example.localfly.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Sincroniza con el servidor las ediciones de metadatos que el administrador
 * hizo sin conexión (SongAdminStore). Se invoca al detectar reconexión:
 * envía el lote con POST /api/metadata/sync y, si el servidor confirma,
 * actualiza los "originales" recordados para que no vuelva a reportar
 * conflicto sobre la misma edición.
 */
object MetadataSyncManager {

    private const val TAG = "MetadataSyncManager"

    suspend fun syncPendingEdits(sessionManager: SessionManager): Boolean = withContext(Dispatchers.IO) {
        val edits = SongAdminStore.getEdits().values.toList()
        if (edits.isEmpty()) return@withContext true

        val payload = edits.map { e ->
            SongAdminEditPayload(
                songId = e.songId,
                title = e.title,
                artist = e.artist,
                album = e.album,
                year = e.year,
                genres = e.genres,
                moods = e.moods,
                originalTitle = e.originalTitle
            )
        }

        try {
            val response = RetrofitClient.api.syncMetadata(
                MetadataSyncRequest(userId = sessionManager.getUserId(), edits = payload)
            )
            if (!response.isSuccessful) return@withContext false
            val body = response.body() ?: return@withContext false

            // Las ediciones aplicadas quedan confirmadas: se actualizan sus
            // "originales" al valor actual del servidor para no reenviarlas.
            val appliedIds = body.applied.toSet()
            for (edit in edits) {
                if (edit.songId in appliedIds) {
                    val current = RetrofitClient.api.getSongsByIds(edit.songId, sessionManager.getUserId())
                        .body()?.songs?.firstOrNull()
                    if (current != null) {
                        SongAdminStore.saveEdit(
                            edit.copy(
                                originalTitle = current.title,
                                originalArtist = current.artist,
                                originalAlbum = current.album,
                                originalYear = current.year
                            )
                        )
                    }
                }
            }
            body.conflicts.forEach { c ->
                Log.w(TAG, "Conflicto de metadatos en ${c.songId}: ${c.reason}")
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "syncPendingEdits falló: ${e.message}")
            false
        }
    }
}
