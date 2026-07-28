package com.example.localfly.network

data class Song(
    val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val year: Int?,
    val duration: Double?,
    val liked: Boolean,
    val hasCover: Boolean
)

data class LibraryResponse(
    val songs: List<Song>,
    val pagination: Pagination?
)

data class Pagination(
    val offset: Int,
    val limit: Int,
    val total: Int,
    val hasMore: Boolean
)

data class LikeRequest(
    val userId: String?,
    val liked: Boolean
)

data class HideRequest(
    val userId: String?
)




// Para respuestas paginadas de estos endpoints
data class PaginatedResponse<T>(
    val items: List<T>,
    val pagination: Pagination
)

// --- Nuevos modelos para álbumes, artistas, géneros y años ---

data class Album(
    val id: String,
    val name: String,
    val artist: String?,
    val coverId: String?,
    val songCount: Int
)

data class Artist(
    val id: String,
    val name: String,
    val coverId: String?,
    val songCount: Int
)

data class Genre(
    val id: String,
    val name: String,
    val coverId: String?,
    val songCount: Int
)

data class Year(
    val year: Int,
    val coverId: String?,
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