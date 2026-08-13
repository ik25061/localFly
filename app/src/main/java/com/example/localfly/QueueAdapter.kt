package com.example.localfly

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.localfly.network.Song

enum class QueueAction { PLAY_NEXT, ADD_TO_END }

class QueueAdapter(
    private val songs: List<Song>,
    private val onAction: (Song, QueueAction) -> Unit
) : RecyclerView.Adapter<QueueAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvIndex: TextView = view.findViewById(R.id.tvQueueIndex)
        val tvTitle: TextView = view.findViewById(R.id.tvQueueTitle)
        val tvArtist: TextView = view.findViewById(R.id.tvQueueArtist)
        val btnPlayNext: ImageButton = view.findViewById(R.id.btnQueuePlayNext)
        val btnAddToEnd: ImageButton = view.findViewById(R.id.btnQueueAddToEnd)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_queue_song, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val song = songs[position]
        holder.tvIndex.text = (position + 1).toString()
        holder.tvTitle.text = song.title
        holder.tvArtist.text = song.artist ?: "Artista desconocido"

        holder.btnPlayNext.setOnClickListener { onAction(song, QueueAction.PLAY_NEXT) }
        holder.btnAddToEnd.setOnClickListener { onAction(song, QueueAction.ADD_TO_END) }
    }

    override fun getItemCount() = songs.size
}