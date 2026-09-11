package com.example.localfly.network

import com.google.gson.annotations.SerializedName

data class Podcast(
    val id: String,
    val title: String,
    val author: String?,
    val description: String?,
    @SerializedName("cover_url")
    val coverUrl: String?,
    @SerializedName("episode_count")
    val episodeCount: Int = 0
)

data class Episode(
    val id: String,
    val podcastId: String,
    val title: String,
    val description: String?,
    val duration: Double?, // seconds
    @SerializedName("audio_url")
    val audioUrl: String,
    @SerializedName("subtitle_url")
    val subtitleUrl: String? = null,
    @SerializedName("last_position_ms")
    var lastPositionMs: Long = 0L,
    @SerializedName("order_index")
    val orderIndex: Int = 0,
    @SerializedName("published_at")
    val publishedAt: String? = null
)

data class PodcastsResponse(
    val podcasts: List<Podcast>
)

data class PodcastResponse(
    val podcast: Podcast,
    val episodes: List<Episode>
)

data class ProgressUpdateRequest(
    val episodeId: String,
    val userId: String?,
    val positionMs: Long
)
