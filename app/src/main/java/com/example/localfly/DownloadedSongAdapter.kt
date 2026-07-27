package com.example.localfly

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.util.Locale

class DownloadedSongAdapter(
    private val items: MutableList<DownloadedSong>,
    private val onItemClick: (DownloadedSong) -> Unit,
    private val onDeleteClick: (DownloadedSong) -> Unit
) : RecyclerView.Adapter<DownloadedSongAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvDownloadedTitle)
        val tvArtist: TextView = view.findViewById(R.id.tvDownloadedArtist)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_downloaded_song, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvTitle.text = toTitleCase(item.title)
        holder.tvArtist.text = toTitleCase(item.artist) ?: "Artista desconocido"
        holder.itemView.setOnClickListener { onItemClick(item) }
        holder.btnDelete.setOnClickListener { onDeleteClick(item) }
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
