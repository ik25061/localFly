package com.example.localfly.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface ApiService {
    @POST("api/auth/login")
    suspend fun login(@Body request: LoginRequest): Response<LoginResponse>

    @GET("api/library")
    suspend fun getLibrary(
        @Query("userId") userId: String?,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0
    ): Response<LibraryResponse>

    @POST("api/songs/{id}/like")
    suspend fun likeSong(
        @Path("id") songId: String,
        @Body request: LikeRequest
    ): Response<Unit>

    @POST("api/songs/{id}/hide")
    suspend fun hideSong(
        @Path("id") songId: String,
        @Body request: HideRequest
    ): Response<Unit>

    @GET("api/albums")
    suspend fun getAlbums(
        @Query("userId") userId: String?,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
        @Query("search") search: String? = null
    ): Response<AlbumsResponse>

    @GET("api/artists")
    suspend fun getArtists(
        @Query("userId") userId: String?,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
        @Query("search") search: String? = null
    ): Response<ArtistsResponse>

    @GET("api/genres")
    suspend fun getGenres(
        @Query("userId") userId: String?,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
        @Query("search") search: String? = null
    ): Response<GenresResponse>

    @GET("api/years")
    suspend fun getYears(
        @Query("userId") userId: String?,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
        @Query("search") search: String? = null
    ): Response<YearsResponse>

    @GET("api/liked-songs")
    suspend fun getLikedSongs(
        @Query("userId") userId: String?,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0
    ): Response<LikedSongsResponse>

    @GET("api/albums/{id}/songs")
    suspend fun getAlbumSongs(
        @Path("id") albumId: String,
        @Query("userId") userId: String?
    ): Response<LibraryResponse>

    @GET("api/artists/{id}/songs")
    suspend fun getArtistSongs(
        @Path("id") artistId: String,
        @Query("userId") userId: String?
    ): Response<LibraryResponse>

    @GET("api/genres/{id}/songs")
    suspend fun getGenreSongs(
        @Path("id") genreId: String,
        @Query("userId") userId: String?
    ): Response<LibraryResponse>

    @GET("api/years/{year}/songs")
    suspend fun getYearSongs(
        @Path("year") year: Int,
        @Query("userId") userId: String?
    ): Response<LibraryResponse>

}
