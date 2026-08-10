package com.example.localfly

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.localfly.network.Song
import java.util.Locale
import com.example.localfly.network.HideRequest
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
    private val onPlaylistAddClick: ((Song) -> Unit)? = null
) : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

    class SongViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvIndex: TextView = view.findViewById(R.id.tvSongIndex)
        val ivCover: ImageView = view.findViewById(R.id.ivCover)
        val tvTitle: TextView = view.findViewById(R.id.tvSongTitle)
        val tvArtist: TextView = view.findViewById(R.id.tvSongArtist)
        val btnPlayNext: ImageButton = view.findViewById(R.id.btnPlayNext)
        val btnPlaylistAdd: ImageButton = view.findViewById(R.id.btnPlaylistAdd)
        val btnLike: ImageButton = view.findViewById(R.id.btnLike)
        val btnDislike: ImageButton = view.findViewById(R.id.btnDislike)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
        val btnDownload: ImageButton = view.findViewById(R.id.btnDownload)
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
        holder.tvTitle.text = toTitleCase(song.title)
        holder.tvArtist.text = toTitleCase(song.artist) ?: "Artista desconocido"
        holder.tvDuration.text = formatDuration(song.duration)

        // Like
        holder.btnLike.setImageResource(
            if (song.liked) R.drawable.ic_like_on else R.drawable.ic_like_off
        )
        holder.btnLike.setOnClickListener { onLikeClick(song, holder.bindingAdapterPosition) }

        // Dislike
        holder.btnDislike.setOnClickListener { onDislikeClick(song, holder.bindingAdapterPosition) }

        // Download
        holder.btnDownload.setImageResource(
            if (downloadHelper.isDownloaded(song.id)) R.drawable.ic_downloaded else R.drawable.ic_download
        )
        holder.btnDownload.setOnClickListener { onDownloadClick(song) }

        // Delete
        holder.btnDelete.setOnClickListener { onDeleteClick?.invoke(song, holder.bindingAdapterPosition) }

        // Play Next
        holder.btnPlayNext.setOnClickListener { onPlayNextClick?.invoke(song) }

        // Add to Playlist
        holder.btnPlaylistAdd.setOnClickListener { onPlaylistAddClick?.invoke(song) }

        // Cover
        if (song.hasCover) {
            Glide.with(holder.itemView.context)
                .load("$serverBaseUrl/cover/${song.id}")
                .placeholder(R.drawable.ic_music_placeholder)
                .centerCrop()
                .into(holder.ivCover)
        } else {
            holder.ivCover.setImageResource(R.drawable.ic_music_placeholder)
        }

        // Click en el ítem
        holder.itemView.setOnClickListener { onSongClick(song, holder.bindingAdapterPosition) }
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

    private fun toTitleCase(text: String?): String? {
        if (text.isNullOrBlank()) return text
        return text.lowercase(Locale.getDefault())
            .split(" ")
            .joinToString(" ") { word ->
                if (word.isEmpty()) word
                else word.replaceFirstChar { it.uppercase(Locale.getDefault()) }
            }
    }
}