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
    private val onDownloadClick: (Song) -> Unit
) : RecyclerView.Adapter<SongAdapter.SongViewHolder>() {

    class SongViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivCover: ImageView = view.findViewById(R.id.ivCover)
        val tvTitle: TextView = view.findViewById(R.id.tvSongTitle)
        val tvArtist: TextView = view.findViewById(R.id.tvSongArtist)
        val btnLike: ImageButton = view.findViewById(R.id.btnLike)
        val btnDislike: ImageButton = view.findViewById(R.id.btnDislike)
        val btnDownload: ImageButton = view.findViewById(R.id.btnDownload)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_song, parent, false)
        return SongViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        val song = songs[position]
        holder.tvTitle.text = toTitleCase(song.title)
        holder.tvArtist.text = toTitleCase(song.artist) ?: "Artista desconocido"

        // Like
        holder.btnLike.setImageResource(
            if (song.liked) R.drawable.ic_like_on else R.drawable.ic_like_off
        )
        holder.btnLike.setOnClickListener { onLikeClick(song, holder.bindingAdapterPosition) }



        // Dislike
        holder.btnDislike.setOnClickListener { onDislikeClick(song, holder.bindingAdapterPosition) }

        // Download
        val alreadyDownloaded = downloadHelper.isDownloaded(song.id)
        holder.btnDownload.setImageResource(
            if (alreadyDownloaded) R.drawable.ic_downloaded else R.drawable.ic_download
        )
        holder.btnDownload.setOnClickListener {
            if (!downloadHelper.isDownloaded(song.id)) {
                onDownloadClick(song)
            } else {
                // Opcional: mostrar mensaje de que ya está descargada
                // Toast.makeText(holder.itemView.context, "Ya descargada", Toast.LENGTH_SHORT).show()
            }
        }

        // Cover
        if (song.hasCover) {
            Glide.with(holder.itemView.context)
                .load("$serverBaseUrl/cover/${song.id}")
                .centerCrop()
                .into(holder.ivCover)
        } else {
            holder.ivCover.setImageDrawable(null)
        }

        // Click en el ítem
        holder.itemView.setOnClickListener { onSongClick(song, holder.bindingAdapterPosition) }
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