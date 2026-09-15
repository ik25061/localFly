package com.example.localfly.network

import com.google.gson.annotations.SerializedName

data class Song(
    val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val year: Int?,
    val duration: Double?,
    val bpm: Double?,
    @SerializedName("key_name")
    val key: String?,
    val liked: Boolean,
    val hasCover: Boolean,
    @SerializedName("hasLyrics")
    val hasLyrics: Boolean = false,
    @SerializedName("is_episode")
    val isEpisode: Boolean = false,
    @SerializedName("last_position_ms")
    var lastPositionMs: Long = 0L,
    @SerializedName("subtitle_url")
    val subtitleUrl: String? = null,
    val genre: List<String>? = emptyList(),
    val moods: List<Mood>? = emptyList()
)

data class Mood(
    val id: Int,
    val name: String,
    val color: String?
)

data class LibraryResponse(
    val songs: List<Song>,
    val pagination: Pagination?
)

data class Pagination(
    val offset: Int,
    val limit: Int,
    val total: Int,
    @SerializedName("hasMore")
    val hasMore: Boolean
)

data class LikeRequest(
    val userId: String?,
    val liked: Boolean
)

data class HideRequest(
    val userId: String?
)

// --- Nuevos modelos para álbumes, artistas, géneros y años ---

data class Album(
    val id: String,
    val name: String,
    val artist: String?,
    @SerializedName("cover_id")
    val coverId: String?,
    @SerializedName("song_count")
    val songCount: Int
)

data class Artist(
    val id: String,
    val name: String,
    @SerializedName("cover_id")
    val coverId: String?,
    @SerializedName("song_count")
    val songCount: Int
)

data class Genre(
    val id: String,
    val name: String,
    @SerializedName("cover_id")
    val coverId: String?,
    @SerializedName("song_count")
    val songCount: Int
)

data class Year(
    val year: Int,
    @SerializedName("cover_id")
    val coverId: String?,
    @SerializedName("song_count")
    val songCount: Int
)

// Respuestas de la API

data class AlbumsResponse(
    val items: List<Album>,
    val pagination: Pagination?
)

data class ArtistsResponse(
    val items: List<Artist>,
    val pagination: Pagination?
)

data class GenresResponse(
    val items: List<Genre>,
    val pagination: Pagination?
)

data class YearsResponse(
    val items: List<Year>,
    val pagination: Pagination?
)

data class LikedSongsResponse(
    val songs: List<Song>,
    val pagination: Pagination?
)

// --- Playlists ---

data class Playlist(
    val id: String,
    val name: String,
    val description: String?,
    @SerializedName("songIds")
    val songIds: List<String> = emptyList(),
    @SerializedName("cover_id")
    val coverId: String? = null,
    @SerializedName("is_public")
    val isPublic: Boolean = false
)

data class PlaylistsResponse(
    val playlists: List<Playlist>
)

data class PlaylistResponse(
    val playlist: Playlist
)

data class CreatePlaylistRequest(
    val name: String,
    val description: String?,
    val userId: String?,
    @SerializedName("is_public")
    val isPublic: Boolean = false
)

/** Edición de una lista existente (nombre/descripción/visibilidad). */
data class UpdatePlaylistRequest(
    val name: String? = null,
    val description: String? = null,
    val userId: String? = null,
    @SerializedName("is_public")
    val isPublic: Any? = null // Permite enviar Boolean o Int
)

data class PlaylistSongRequest(
    val songId: String
)

data class PlaylistSongsBulkRequest(
    val songIds: List<String>
)

data class DeleteSongRequest(
    val id: String,
    val userId: String?
)

data class SongsByIdsResponse(
    val songs: List<Song>
)

// --- Rescan Status ---

data class RescanProgress(
    val phase: String = "idle",
    val pct: Int = 0,
    val processed: Int = 0,
    val total: Int = 0,
    val message: String = "",
    val totalSongsLibrary: Int = 0,
    val durationSec: Int = 0
)

data class RescanStreamEvent(
    val type: String,
    val phase: String? = null,
    val pct: Int? = null,
    val processed: Int? = null,
    val total: Int? = null,
    val message: String? = null,
    val totalSongsLibrary: Int? = null,
    val durationSec: Int? = null
)

// --- Lyrics ---

data class SyncedLyricLine(
    val time: Double, // segundos, con decimales — no milisegundos
    val text: String
)

data class LyricsResponse(
    val lyrics: String?,
    @SerializedName("syncedLines")
    val syncedLines: List<SyncedLyricLine>? = null,
    val source: String? = null
)

// --- LRCLIB (fuente pública directa, sin pasar por el servidor) ---

data class LrclibResult(
    val id: Long? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val syncedLyrics: String? = null,
    val plainLyrics: String? = null,
    val instrumental: Boolean = false
)

// --- Config ---

data class MoodsResponse(
    val moods: List<Mood>
)

// --- Comentarios y Valoraciones ---

data class Comment(
    val id: String,
    val songId: String,
    val userId: String,
    val username: String,
    val text: String,
    val rating: Int, // 1-5 estrellas
    val createdAt: String
)

data class CommentsResponse(
    val comments: List<Comment>,
    val averageRating: Double,
    val totalCount: Int
)

data class PostCommentRequest(
    val userId: String?,
    val songId: String,
    val text: String,
    val rating: Int
)

data class IpConfigResponse(
    val ip: String
)

// --- Sincronización de metadatos offline (admin) ---

data class MetadataSyncRequest(
    val userId: String?,
    val edits: List<SongAdminEditPayload>,
    val removals: List<String> = emptyList()
)

data class SongAdminEditPayload(
    val songId: String,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val moods: List<String> = emptyList(),
    val originalTitle: String? = null
)

data class MetadataSyncConflict(
    val songId: String,
    val reason: String,
    val serverTitle: String? = null
)

data class MetadataSyncResponse(
    val success: Boolean = false,
    val applied: List<String> = emptyList(),
    val conflicts: List<MetadataSyncConflict> = emptyList()
)

// --- Favorite Artists ---
data class FavoriteArtistRequest(
    val userId: String?,
    val artistId: String,
    val liked: Boolean
)

// --- Hidden Artists ---
data class HideArtistRequest(
    val userId: String?
)