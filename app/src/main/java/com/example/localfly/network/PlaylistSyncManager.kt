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

        // 1) Listas creadas sin conexión: crearlas en el servidor y subirles sus canciones.
        for (creation in sessionManager.getPendingPlaylistCreations()) {
            try {
                val createResp = RetrofitClient.api.createPlayList(
                    CreatePlaylistRequest(creation.name, creation.description, userId)
                )
                val realPlaylist = createResp.body()?.playlist
                if (!createResp.isSuccessful || realPlaylist == null) continue

                var allAdded = true
                for (songId in creation.songIds) {
                    val addResp = RetrofitClient.api.addSongToPlayList(realPlaylist.id, PlaylistSongRequest(songId))
                    if (!addResp.isSuccessful) allAdded = false
                }

                if (allAdded) {
                    sessionManager.removePendingPlaylistCreation(creation.localId)

                    // Sustituir el id local por el id real en la caché para que la
                    // próxima vez que se abra "Listas" ya apunte al servidor.
                    val cache = sessionManager.getPlaylistsCache().toMutableList()
                    val idx = cache.indexOfFirst { it.id == creation.localId }
                    if (idx >= 0) cache[idx] = realPlaylist else cache.add(realPlaylist)
                    sessionManager.savePlaylistsCache(cache)
                }
                // Si alguna canción falló, se deja la creación pendiente tal cual:
                // en el próximo ciclo se reintenta desde cero (createPlayList es
                // idempotente en la práctica: como mucho crea una lista vacía
                // duplicada si el nombre se repite, aceptable frente a la
                // complejidad de trackear una creación "a medias").
            } catch (e: Exception) {
                // Sin conexión de nuevo a mitad de sync: se reintenta en el próximo ciclo.
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
