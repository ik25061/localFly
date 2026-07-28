package com.example.localfly.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.localfly.R
import com.example.localfly.network.Song

class LikedSongsAdapter(
    private var songs: MutableList<Song>,
    private val onLikeClick: (Song) -> Unit,
    private val onDislikeClick: (Song) -> Unit,
    private val onItemClick: (Song) -> Unit
) : RecyclerView.Adapter<LikedSongsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvSongTitle)
        val tvArtist: TextView = view.findViewById(R.id.tvSongArtist)
        val btnLike: ImageButton = view.findViewById(R.id.btnLike)
        val btnDislike: ImageButton = view.findViewById(R.id.btnDislike)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_song, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val song = songs[position]
        holder.tvTitle.text = song.title
        holder.tvArtist.text = song.artist ?: "Artista desconocido"
        holder.btnLike.setImageResource(
            if (song.liked) android.R.drawable.btn_star_big_on
            else android.R.drawable.btn_star_big_off
        )
        holder.btnLike.setOnClickListener { onLikeClick(song) }
        holder.btnDislike.setOnClickListener { onDislikeClick(song) }
        holder.itemView.setOnClickListener { onItemClick(song) }
    }

    override fun getItemCount() = songs.size

    fun updateSongs(newSongs: List<Song>) {
        songs.clear()
        songs.addAll(newSongs)
        notifyDataSetChanged()
    }
}