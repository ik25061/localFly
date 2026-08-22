package com.example.localfly.network

import com.google.gson.annotations.SerializedName

data class Song(
    val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val year: Int?,
    val duration: Double?,
    val liked: Boolean,
    val hasCover: Boolean,
    @SerializedName("hasLyrics")
    val hasLyrics: Boolean = false
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
    val coverId: String? = null
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
    val userId: String?
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

// --- Lyrics ---

data class LyricsResponse(
    val lyrics: String?,
    val source: String? = null
)

// --- Config ---

data class IpConfigResponse(
    val ip: String
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