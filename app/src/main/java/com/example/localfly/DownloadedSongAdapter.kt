package com.example.localfly

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import java.util.Locale

class DownloadedSongAdapter(
    private val items: MutableList<DownloadedSong>,
    private val serverBaseUrl: String,
    private val onItemClick: (DownloadedSong) -> Unit,
    private val onDeleteClick: (DownloadedSong) -> Unit
) : RecyclerView.Adapter<DownloadedSongAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvIndex: TextView = view.findViewById(R.id.tvIndex)
        val ivCover: ImageView = view.findViewById(R.id.ivCover)
        val tvTitle: TextView = view.findViewById(R.id.tvDownloadedTitle)
        val tvArtist: TextView = view.findViewById(R.id.tvDownloadedArtist)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
        val ivLyricsIndicator: ImageView = view.findViewById(R.id.ivLyricsIndicator)
        val tvDuration: TextView = view.findViewById(R.id.tvDuration)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_downloaded_song, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvIndex.text = (position + 1).toString()
        holder.tvTitle.text = toTitleCase(item.title)
        holder.tvArtist.text = toTitleCase(item.artist) ?: "Artista desconocido"
        holder.tvDuration.text = formatDuration(item.duration)

        // Lyrics Indicator
        holder.ivLyricsIndicator.visibility = if (item.hasLyrics) View.VISIBLE else View.GONE

        // Carga de portada con fallback inteligente
        val artistEncoded = java.net.URLEncoder.encode(item.artist ?: "", "UTF-8").replace("+", "%20")
        val artistImageUrl = "$serverBaseUrl/artist-cover/$artistEncoded"

        Glide.with(holder.itemView.context)
            .load("$serverBaseUrl/cover/${item.id}")
            .placeholder(R.drawable.ic_music_placeholder)
            .error(
                Glide.with(holder.itemView.context)
                    .load(artistImageUrl)
                    .placeholder(R.drawable.ic_music_placeholder)
                    .error(R.drawable.ic_music_placeholder)
                    .centerCrop()
            )
            .centerCrop()
            .into(holder.ivCover)

        holder.itemView.setOnClickListener { onItemClick(item) }
        holder.btnDelete.setOnClickListener { onDeleteClick(item) }
    }

    private fun formatDuration(durationSeconds: Double?): String {
        if (durationSeconds == null) return "--:--"
        val totalSeconds = durationSeconds.toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<DownloadedSong>) {
        items.clear()
        items.addAll(newItems)
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
