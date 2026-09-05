package com.example.localfly.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.localfly.R
import com.example.localfly.network.Playlist

class PlaylistAdapter(
    private var playlists: List<Playlist>,
    private val onClick: (Playlist) -> Unit,
    private val onDeleteClick: (Playlist) -> Unit
) : RecyclerView.Adapter<PlaylistAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvPlaylistName)
        val tvCount: TextView = view.findViewById(R.id.tvPlaylistCount)
        val btnMenu: ImageButton = view.findViewById(R.id.btnPlaylistMenu)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val playlist = playlists[position]
        holder.tvName.text = playlist.name
        val count = playlist.songIds.size
        val base = if (count == 1) "1 canción" else "$count canciones"
        holder.tvCount.text = if (playlist.id.startsWith("local_")) "$base · sin sincronizar" else base

        holder.itemView.setOnClickListener { onClick(playlist) }
        holder.btnMenu.setOnClickListener {
            val popup = PopupMenu(holder.itemView.context, holder.btnMenu)
            popup.menu.add(0, 1, 0, "Eliminar lista")
            popup.setOnMenuItemClickListener {
                onDeleteClick(playlist)
                true
            }
            popup.show()
        }
    }

    override fun getItemCount(): Int = playlists.size

    fun updatePlaylists(newPlaylists: List<Playlist>) {
        playlists = newPlaylists
        notifyDataSetChanged()
    }
}