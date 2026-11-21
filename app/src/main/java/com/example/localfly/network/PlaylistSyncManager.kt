package com.example.localfly.network

// Archivo nuevo: app/src/main/java/com/example/localfly/network/PlaylistSyncManager.kt
//
// Sube al servidor lo que se hizo offline en playlists: listas creadas sin
// conexión (con sus canciones) y canciones añadidas offline a listas que ya
// existían en el servidor. Se llama desde MainActivity justo cuando se
// detecta que el servidor volvió a estar disponible (mismo punto donde ya
// se llama a playbackService?.flushPendingLyricsUploads()).

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object PlaylistSyncManager {

    // El sync puede dispararse desde MainActivity, NowPlayingActivity y
    // PlaybackService casi a la vez; con el Mutex evitamos correr dos syncs
    // en paralelo (que podría duplicar listas si ambos hacen createPlayList
    // del mismo pendiente a la vez).
    private val syncMutex = Mutex()

    suspend fun sync(sessionManager: SessionManager) = syncMutex.withLock {
        performSync(sessionManager)
    }

    private suspend fun performSync(sessionManager: SessionManager) {
        val userId = sessionManager.getUserId()

        // 1) Listas creadas sin conexión. Si ya se creó en el servidor en un
        // ciclo anterior, reutilizar serverId y continuar con sus canciones.
        for (creation in sessionManager.getPendingPlaylistCreations()) {
            try {
                var realPlaylist = if (!creation.serverId.isNullOrBlank()) {
                    val existingResp = RetrofitClient.api.getPlayList(creation.serverId)
                    when {
                        existingResp.isSuccessful -> existingResp.body()?.playlist
                        existingResp.code() == 404 -> null
                        else -> continue
                    }
                } else {
                    null
                }

                if (realPlaylist == null) {
                    val createResp = RetrofitClient.api.createPlayList(
                        CreatePlaylistRequest(creation.name, creation.description, userId, creation.isPublic)
                    )
                    if (!createResp.isSuccessful) continue
                    realPlaylist = createResp.body()?.playlist ?: continue

                    // Guardar el id antes de subir canciones. Si se pierde la
                    // conexión después, el próximo ciclo no crea otra lista.
                    sessionManager.updatePendingPlaylistCreation(
                        creation.localId,
                        creation.copy(serverId = realPlaylist.id)
                    )
                }

                val targetPlaylist = realPlaylist
                var allAdded = true
                if (creation.songIds.isNotEmpty()) {
                    try {
                        val bulkResp = RetrofitClient.api.addSongsToPlayListBulk(
                            targetPlaylist.id,
                            PlaylistSongsBulkRequest(creation.songIds)
                        )
                        if (!bulkResp.isSuccessful) {
                            // Fallback para servidores que aún no soportan bulk.
                            for (songId in creation.songIds) {
                                val addResp = RetrofitClient.api.addSongToPlayList(
                                    targetPlaylist.id,
                                    PlaylistSongRequest(songId)
                                )
                                if (!addResp.isSuccessful) allAdded = false
                            }
                        }
                    } catch (e: Exception) {
                        // Reintentar elemento a elemento; INSERT OR IGNORE en el
                        // servidor hace que repetir los ya añadidos sea seguro.
                        for (songId in creation.songIds) {
                            try {
                                val addResp = RetrofitClient.api.addSongToPlayList(
                                    targetPlaylist.id,
                                    PlaylistSongRequest(songId)
                                )
                                if (!addResp.isSuccessful) allAdded = false
                            } catch (_: Exception) {
                                allAdded = false
                            }
                        }
                    }
                }

                if (allAdded) {
                    sessionManager.removePendingPlaylistCreation(creation.localId)
                    val cache = sessionManager.getPlaylistsCache().toMutableList()
                    val idx = cache.indexOfFirst { it.id == creation.localId }
                    if (idx >= 0) cache[idx] = targetPlaylist else cache.add(targetPlaylist)
                    sessionManager.savePlaylistsCache(cache)
                }
            } catch (e: Exception) {
                // Sin conexión a mitad del proceso: conservar serverId y reintentar.
            }
        }

        // 2) Canciones añadidas offline a listas que ya existían en el servidor.
        for (pendingAdd in sessionManager.getPendingPlaylistSongAdds()) {
            try {
                val resp = RetrofitClient.api.addSongToPlayList(pendingAdd.playlistId, PlaylistSongRequest(pendingAdd.songId))
                if (resp.isSuccessful) {
                    sessionManager.removePendingPlaylistSongAdd(pendingAdd.playlistId, pendingAdd.songId)
                }
            } catch (e: Exception) {
                // Reintentar en el próximo ciclo.
            }
        }
    }
}
