package com.example.localfly.adapters

import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.localfly.DownloadManagerHelper
import com.example.localfly.R
import com.example.localfly.network.ApiConfig
import com.example.localfly.utils.CoverPlaceholder
import com.example.localfly.network.Song

class LikedSongsAdapter(
    private var songs: MutableList<Song>,
    private val downloadHelper: DownloadManagerHelper,
    private val onLikeClick: (Song) -> Unit,
    private val onDislikeClick: (Song) -> Unit,
    private val onItemClick: (Song) -> Unit,
    private val onDownloadClick: (Song) -> Unit,
    private val onPlayNextClick: ((Song) -> Unit)? = null,
    private val onAddToQueueClick: ((Song) -> Unit)? = null,
    private val onDeleteClick: ((Song) -> Unit)? = null,
    private val onAddToPlaylistClick: ((Song) -> Unit)? = null
) : RecyclerView.Adapter<LikedSongsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvIndex: TextView = view.findViewById(R.id.tvSongIndex)
        val ivCover: ImageView = view.findViewById(R.id.ivCover)
        val tvTitle: TextView = view.findViewById(R.id.tvSongTitle)
        val tvArtist: TextView = view.findViewById(R.id.tvSongArtist)
        val btnLike: ImageButton = view.findViewById(R.id.btnLike)
        val btnDislike: ImageButton = view.findViewById(R.id.btnDislike)
        val btnSongMenu: ImageButton = view.findViewById(R.id.btnSongMenu)
        val ivLyricsIndicator: ImageView = view.findViewById(R.id.ivLyricsIndicator)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_song, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val song = songs[position]
        val context = holder.itemView.context
        val serverBaseUrl = ApiConfig.BASE_URL

        holder.tvIndex.text = (position + 1).toString()
        holder.tvTitle.text = song.title
        holder.tvArtist.text = song.artist ?: "Artista desconocido"

        // Lyrics Indicator
        holder.ivLyricsIndicator.visibility = if (song.hasLyrics) View.VISIBLE else View.GONE

        // Acciones visibles
        holder.btnLike.setImageResource(
            if (song.liked) R.drawable.ic_like_on else R.drawable.ic_like_off
        )
        holder.btnLike.setOnClickListener { onLikeClick(song) }
        holder.btnDislike.setOnClickListener { onDislikeClick(song) }

        // Menú (hamburguesa) con las acciones anidadas
        holder.btnSongMenu.setOnClickListener { showSongMenu(holder, song) }

        // Carga de portada con fallback inteligente
        val artistEncoded = java.net.URLEncoder.encode(song.artist ?: "", "UTF-8").replace("+", "%20")
        val artistImageUrl = "$serverBaseUrl/artist-cover/$artistEncoded"
val seed = song.id

        Glide.with(context)
            .load("$serverBaseUrl/cover/${song.id}")
            .placeholder(CoverPlaceholder.drawable(seed))
            .error(
                Glide.with(context)
                    .load(artistImageUrl)
                    .placeholder(CoverPlaceholder.drawable(seed))
                    .error(CoverPlaceholder.drawable(seed))
                    .centerCrop()
            )
            .centerCrop()
            .into(holder.ivCover)

        holder.itemView.setOnClickListener { onItemClick(song) }
    }

    private fun showSongMenu(holder: ViewHolder, song: Song) {
        val popup = PopupMenu(holder.itemView.context, holder.btnSongMenu)
        if (onDeleteClick != null) {
            popup.menu.add(0, MENU_DELETE, 0, "Eliminar")
        }
        popup.menu.add(0, MENU_ADD_TO_QUEUE, 1, "Añadir al final de la cola")
        popup.menu.add(0, MENU_PLAY_NEXT, 2, "Reproducir siguiente")
        if (onAddToPlaylistClick != null) {
            popup.menu.add(0, MENU_ADD_PLAYLIST, 3, "Añadir a una lista")
        }
        popup.menu.add(
            0,
            MENU_DOWNLOAD,
            4,
            if (downloadHelper.isDownloaded(song.id)) "Quitar descarga" else "Descargar"
        )

        popup.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                MENU_DELETE -> onDeleteClick?.invoke(song)
                MENU_ADD_TO_QUEUE -> onAddToQueueClick?.invoke(song)
                MENU_ADD_PLAYLIST -> onAddToPlaylistClick?.invoke(song)
                MENU_PLAY_NEXT -> onPlayNextClick?.invoke(song)
                MENU_DOWNLOAD -> onDownloadClick(song)
            }
            true
        }
        popup.show()
    }

    override fun getItemCount() = songs.size

    fun updateSongs(newSongs: List<Song>) {
        songs.clear()
        songs.addAll(newSongs)
        notifyDataSetChanged()
    }

    fun refreshDownloadStates() {
        notifyDataSetChanged()
    }

    /** Devuelve el índice de una canción en la lista, o -1 si no está. */
    fun indexOf(songId: String): Int = songs.indexOfFirst { it.id == songId }

    /** Actualiza una canción en una posición concreta. */
    fun updateSongAt(position: Int, song: Song) {
        if (position in songs.indices) {
            songs[position] = song
            notifyItemChanged(position)
        }
    }

    /** Elimina una canción de la lista (acción "Eliminar" del menú). */
    fun removeSongById(songId: String) {
        val index = songs.indexOfFirst { it.id == songId }
        if (index != -1) {
            songs.removeAt(index)
            notifyItemRemoved(index)
        }
    }

    companion object {
        const val MENU_DELETE = 1
        const val MENU_ADD_TO_QUEUE = 2
        const val MENU_PLAY_NEXT = 3
        const val MENU_DOWNLOAD = 4
        const val MENU_ADD_PLAYLIST = 5
    }
}