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