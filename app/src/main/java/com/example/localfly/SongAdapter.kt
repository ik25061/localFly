package com.example.localfly

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
import com.example.localfly.network.Song
import java.util.Locale

class SongAdapter(
    private val songs: MutableList<Song>,
    private val serverBaseUrl: String,
    private val downloadHelper: DownloadManagerHelper,
    private val onSongClick: (Song, Int) -> Unit,
    private val onLikeClick: (Song, Int) -> Unit,
    private val onDislikeClick: (Song, Int) -> Unit,
    private val onDownloadClick: (Song) -> Unit,
    private val onDeleteClick: ((Song, Int) -> Unit)? = null,
    private val onPlayNextClick: ((Song) -> Unit)? = null,
    private val onPlaylistAddClick: ((Song) -> Unit)? = null,
    private var playingSongId: String? = null
) : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

    class SongViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvIndex: TextView = view.findViewById(R.id.tvSongIndex)
        val ivCover: ImageView = view.findViewById(R.id.ivCover)
        val tvTitle: TextView = view.findViewById(R.id.tvSongTitle)
        val tvArtist: TextView = view.findViewById(R.id.tvSongArtist)
        val btnLike: ImageButton = view.findViewById(R.id.btnLike)
        val btnDislike: ImageButton = view.findViewById(R.id.btnDislike)
        val btnSongMenu: ImageButton = view.findViewById(R.id.btnSongMenu)
        val tvDuration: TextView = view.findViewById(R.id.tvDuration)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_song, parent, false)
        return SongViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        val song = songs[position]
        holder.tvIndex.text = (position + 1).toString()
        
        // Highlight current song
        if (song.id == playingSongId) {
            holder.tvIndex.setTextColor(android.graphics.Color.parseColor("#1DB954"))
            holder.tvTitle.setTextColor(android.graphics.Color.parseColor("#1DB954"))
        } else {
            holder.tvIndex.setTextColor(android.graphics.Color.parseColor("#888888"))
            holder.tvTitle.setTextColor(android.graphics.Color.parseColor("#FFFFFF"))
        }

        holder.tvTitle.text = song.title
        holder.tvArtist.text = song.artist ?: "Artista desconocido"
        holder.tvDuration.text = formatDuration(song.duration)

        // Acciones visibles
        holder.btnLike.setImageResource(
            if (song.liked) R.drawable.ic_like_on else R.drawable.ic_like_off
        )
        holder.btnLike.setOnClickListener { onLikeClick(song, holder.bindingAdapterPosition) }
        holder.btnDislike.setOnClickListener { onDislikeClick(song, holder.bindingAdapterPosition) }

        // Menú (hamburguesa) con las acciones anidadas
        holder.btnSongMenu.setOnClickListener { showSongMenu(holder, song) }

        // Cover: el servidor sirve la imagen en /cover/{id} (misma ruta que la web)
        Glide.with(holder.itemView.context)
            .load("$serverBaseUrl/cover/${song.id}")
            .placeholder(R.drawable.ic_music_placeholder)
            .error(R.drawable.ic_music_placeholder)
            .centerCrop()
            .into(holder.ivCover)

        holder.itemView.setOnClickListener { onSongClick(song, holder.bindingAdapterPosition) }
    }

    private fun showSongMenu(holder: SongViewHolder, song: Song) {
        val popup = PopupMenu(holder.itemView.context, holder.btnSongMenu)
        if (onDeleteClick != null) {
            popup.menu.add(0, MENU_DELETE, 0, "Eliminar")
        }
        popup.menu.add(0, MENU_ADD_LIST, 1, "Añadir a lista...")
        popup.menu.add(0, MENU_ADD_PLAYLIST, 2, "Añadir al final de la lista de reproducción")
        popup.menu.add(0, MENU_PLAY_NEXT, 3, "Reproducir siguiente")
        popup.menu.add(
            0,
            MENU_DOWNLOAD,
            4,
            if (downloadHelper.isDownloaded(song.id)) "Quitar descarga" else "Descargar"
        )

        popup.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                MENU_DELETE -> onDeleteClick?.invoke(song, holder.bindingAdapterPosition)
                MENU_ADD_LIST -> {
                    val activity = holder.itemView.context as? androidx.appcompat.app.AppCompatActivity
                    activity?.let {
                        val dialog = com.example.localfly.fragments.PlaylistSelectionDialogFragment.newInstance(song.id)
                        dialog.show(it.supportFragmentManager, "playlist_selection")
                    }
                }
                MENU_ADD_PLAYLIST -> onPlaylistAddClick?.invoke(song)
                MENU_PLAY_NEXT -> onPlayNextClick?.invoke(song)
                MENU_DOWNLOAD -> onDownloadClick(song)
            }
            true
        }
        popup.show()
    }

    private fun formatDuration(durationSeconds: Double?): String {
        if (durationSeconds == null) return "--:--"
        val totalSeconds = durationSeconds.toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    override fun getItemCount(): Int = songs.size

    fun updateSongs(newSongs: List<Song>) {
        songs.clear()
        songs.addAll(newSongs)
        notifyDataSetChanged()
    }

    fun addSongs(newSongs: List<Song>) {
        val startPos = songs.size
        songs.addAll(newSongs)
        notifyItemRangeInserted(startPos, newSongs.size)
    }

    fun setPlayingSongId(id: String?) {
        playingSongId = id
        notifyDataSetChanged()
    }

    fun currentSongs(): List<Song> = songs.toList()

    fun updateSongAt(position: Int, song: Song) {
        if (position !in songs.indices) return
        songs[position] = song
        notifyItemChanged(position)
    }

    fun removeAt(position: Int) {
        if (position !in songs.indices) return
        songs.removeAt(position)
        notifyItemRemoved(position)
    }

    fun refreshDownloadStates() {
        notifyDataSetChanged()
    }

    companion object {
        const val MENU_DELETE = 1
        const val MENU_ADD_LIST = 2
        const val MENU_ADD_PLAYLIST = 3
        const val MENU_PLAY_NEXT = 4
        const val MENU_DOWNLOAD = 5
    }
}
