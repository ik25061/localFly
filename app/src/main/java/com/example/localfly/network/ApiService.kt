package com.example.localfly.network

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.HTTP
import retrofit2.http.PATCH
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

    @GET("api/search")
    suspend fun searchSongs(
        @Query("q") query: String
    ): Response<LibraryResponse>

    @GET("api/auth/verify")
    suspend fun verify(@Query("token") token: String?): Response<LoginResponse>

    @POST("api/auth/verify")
    suspend fun verifyToken(@Body request: VerifyTokenRequest): Response<LoginResponse>

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

    @HTTP(method = "DELETE", path = "api/songs", hasBody = true)
    suspend fun deleteSong(
        @Body request: DeleteSongRequest
    ): Response<Unit>

    @GET("api/albums")
    suspend fun getAlbums(
        @Query("userId") userId: String?,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
        @Query("search") search: String? = null,
        @Query("minSongs") minSongs: Int = 0
    ): Response<AlbumsResponse>

    @GET("api/artists")
    suspend fun getArtists(
        @Query("userId") userId: String?,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
        @Query("search") search: String? = null,
        @Query("minSongs") minSongs: Int = 0
    ): Response<ArtistsResponse>

    @POST("api/artists/{id}/hide")
    suspend fun hideArtist(
        @Path("id") artistId: String,
        @Body request: HideArtistRequest
    ): Response<Unit>

    @POST("api/artists/{id}/unhide")
    suspend fun unhideArtist(
        @Path("id") artistId: String,
        @Body request: HideArtistRequest
    ): Response<Unit>

    @GET("api/genres")
    suspend fun getGenres(
        @Query("userId") userId: String?,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0,
        @Query("search") search: String? = null,
        @Query("minSongs") minSongs: Int = 0
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

    // Punto 6: fuente de verdad del servidor para "no me gusta" / ocultas.
    @GET("api/hidden-songs")
    suspend fun getHiddenSongs(
        @Query("userId") userId: String?,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0
    ): Response<LikedSongsResponse>

    @GET("api/albums/{id}/songs")
    suspend fun getAlbumSongs(
        @Path("id") albumId: String,
        @Query("userId") userId: String?,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0
    ): Response<LibraryResponse>

    @GET("api/artists/{id}/songs")
    suspend fun getArtistSongs(
        @Path("id") artistId: String,
        @Query("userId") userId: String?,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0
    ): Response<LibraryResponse>

    @GET("api/genres/{id}/songs")
    suspend fun getGenreSongs(
        @Path("id") genreId: String,
        @Query("userId") userId: String?,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0
    ): Response<LibraryResponse>

    @GET("api/years/{year}/songs")
    suspend fun getYearSongs(
        @Path("year") year: Int,
        @Query("userId") userId: String?,
        @Query("limit") limit: Int = 100,
        @Query("offset") offset: Int = 0
    ): Response<LibraryResponse>

    // --- Playlists ---

    @GET("api/playlists")
    suspend fun getPlayLists(
        @Query("userId") userId: String?
    ): Response<PlaylistsResponse>

    @GET("api/playlists/{id}")
    suspend fun getPlayList(
        @Path("id") playlistId: String
    ): Response<PlaylistResponse>

    @POST("api/playlists")
    suspend fun createPlayList(
        @Body request: CreatePlaylistRequest
    ): Response<PlaylistResponse>

    @PATCH("api/playlists/{id}")
    suspend fun updatePlayList(
        @Path("id") playlistId: String,
        @Body request: UpdatePlaylistRequest
    ): Response<PlaylistResponse>

    @GET("api/playlists/public")
    suspend fun getPublicPlayLists(): Response<PlaylistsResponse>

    @POST("api/playlists/{id}/songs")
    suspend fun addSongToPlayList(
        @Path("id") playlistId: String,
        @Body request: PlaylistSongRequest
    ): Response<PlaylistResponse>

    @POST("api/playlists/{id}/songs/bulk")
    suspend fun addSongsToPlayListBulk(
        @Path("id") playlistId: String,
        @Body request: PlaylistSongsBulkRequest
    ): Response<PlaylistResponse>

    @HTTP(method = "DELETE", path = "api/playlists/{id}/songs", hasBody = true)
    suspend fun removeSongFromPlayList(
        @Path("id") playlistId: String,
        @Body request: PlaylistSongRequest
    ): Response<PlaylistResponse>

    @DELETE("api/playlists/{id}")
    suspend fun deletePlayList(
        @Path("id") playlistId: String
    ): Response<Unit>

    @GET("api/songs/by-ids")
    suspend fun getSongsByIds(
        @Query("ids") ids: String,
        @Query("userId") userId: String?
    ): Response<SongsByIdsResponse>

    // --- Lyrics ---

    @GET("api/lyrics/{id}")
    suspend fun getLyrics(
        @Path("id") songId: String
    ): Response<LyricsResponse>

    data class SaveLyricsFileRequest(val content: String)

    @POST("api/lyrics/{id}/save-file")
    suspend fun saveLyricsFile(
        @Path("id") songId: String,
        @Body request: SaveLyricsFileRequest
    ): Response<Unit>

    // --- Favorite Artists ---

    @GET("api/favorite-artists")
    suspend fun getFavoriteArtists(
        @Query("userId") userId: String?
    ): Response<ArtistsResponse>

    @POST("api/favorite-artists/toggle")
    suspend fun toggleFavoriteArtist(
        @Body request: FavoriteArtistRequest
    ): Response<Unit>

    // --- Config ---

    @GET("api/config/ip")
    suspend fun getIpConfig(): Response<IpConfigResponse>

    @POST("api/rescan")
    suspend fun rescanLibrary(): Response<Unit>

    @POST("api/podcasts/rescan")
    suspend fun rescanPodcasts(): Response<Unit>

    // --- Podcasts ---

    @GET("api/podcasts")
    suspend fun getPodcasts(
        @Query("userId") userId: String?,
        @Query("limit") limit: Int = 20,
        @Query("offset") offset: Int = 0
    ): Response<PodcastsResponse>

    @GET("api/podcasts/{id}")
    suspend fun getPodcast(
        @Path("id") podcastId: String,
        @Query("userId") userId: String?
    ): Response<PodcastResponse>

    @GET("api/moods")
    suspend fun getMoods(): Response<MoodsResponse>

    @POST("api/podcasts/progress")
    suspend fun updateEpisodeProgress(
        @Body request: ProgressUpdateRequest
    ): Response<Unit>

    // --- Radio (emisión en vivo a otros usuarios) ---

    /** Sincroniza las ediciones offline del administrador al reconectar. */
    @POST("api/metadata/sync")
    suspend fun syncMetadata(
        @Body request: MetadataSyncRequest
    ): Response<MetadataSyncResponse>

    /** El host publica qué está sonando y por dónde va (cada ~5 s). */
    @POST("api/radio/publish")
    suspend fun publishRadioState(
        @Body request: RadioPublishRequest
    ): Response<Unit>

    /** Lista de radios activas a las que uno puede unirse. */
    @GET("api/radio/stations")
    suspend fun getRadioStations(): Response<RadioStationsResponse>

    /** Estado actual de la radio de un host (lo consulta cada oyente). */
    @GET("api/radio/status")
    suspend fun getRadioStatus(
        @Query("hostId") hostId: String
    ): Response<RadioStatusResponse>

    /** Registrar al usuario como oyente de una radio. */
    @POST("api/radio/join")
    suspend fun joinRadio(
        @Body request: RadioJoinRequest
    ): Response<Unit>

    /** Dejar de emitir (host) o dejar de escuchar (oyente). */
    @POST("api/radio/leave")
    suspend fun leaveRadio(
        @Body request: RadioJoinRequest
    ): Response<Unit>

    // --- Comentarios ---

    @GET("api/songs/{id}/comments")
    suspend fun getComments(
        @Path("id") songId: String
    ): Response<CommentsResponse>

    @POST("api/comments")
    suspend fun postComment(
        @Body request: PostCommentRequest
    ): Response<Comment>
}