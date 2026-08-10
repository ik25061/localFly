package com.example.localfly.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.localfly.DownloadManagerHelper
import com.example.localfly.R
import com.example.localfly.network.ApiConfig
import com.example.localfly.network.Song

class LikedSongsAdapter(
    private var songs: MutableList<Song>,
    private val downloadHelper: DownloadManagerHelper,
    private val onLikeClick: (Song) -> Unit,
    private val onDislikeClick: (Song) -> Unit,
    private val onItemClick: (Song) -> Unit,
    private val onDownloadClick: (Song) -> Unit,
    private val onPlayNextClick: ((Song) -> Unit)? = null,
    private val onAddToQueueClick: ((Song) -> Unit)? = null
) : RecyclerView.Adapter<LikedSongsAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvIndex: TextView = view.findViewById(R.id.tvSongIndex)
        val ivCover: ImageView = view.findViewById(R.id.ivCover)
        val tvTitle: TextView = view.findViewById(R.id.tvSongTitle)
        val tvArtist: TextView = view.findViewById(R.id.tvSongArtist)
        val btnLike: ImageButton = view.findViewById(R.id.btnLike)
        val btnDislike: ImageButton = view.findViewById(R.id.btnDislike)
        val btnDownload: ImageButton = view.findViewById(R.id.btnDownload)
        val btnPlayNext: ImageButton = view.findViewById(R.id.btnPlayNext)
        val btnPlaylistAdd: ImageButton = view.findViewById(R.id.btnPlaylistAdd)
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

        // Use custom icons
        holder.btnLike.setImageResource(
            if (song.liked) R.drawable.ic_like_on else R.drawable.ic_like_off
        )
        holder.btnLike.setOnClickListener { onLikeClick(song) }
        holder.btnDislike.setOnClickListener { onDislikeClick(song) }

        holder.btnDownload.setImageResource(
            if (downloadHelper.isDownloaded(song.id)) R.drawable.ic_downloaded else R.drawable.ic_download
        )
        holder.btnDownload.setOnClickListener { onDownloadClick(song) }

        holder.btnPlayNext.setOnClickListener { onPlayNextClick?.invoke(song) }
        holder.btnPlaylistAdd.setOnClickListener { onAddToQueueClick?.invoke(song) }

        // Cover Loading
        val coverUrl = if (!song.album.isNullOrBlank()) {
            "$serverBaseUrl/resources/album - ${song.album}.jpg"
        } else {
            "$serverBaseUrl/cover/${song.id}"
        }

        Glide.with(context)
            .load(coverUrl)
            .placeholder(R.drawable.ic_music_placeholder)
            .centerCrop()
            .into(holder.ivCover)

        holder.itemView.setOnClickListener { onItemClick(song) }
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
}
