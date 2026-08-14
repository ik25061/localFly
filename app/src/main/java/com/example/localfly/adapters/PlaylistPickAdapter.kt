package com.example.localfly.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.localfly.R
import com.example.localfly.network.Playlist

/**
 * Lista de playlists dentro del bottom sheet "Añadir a lista".
 * Muestra un check mientras se está añadiendo la canción a esa lista concreta.
 */
class PlaylistPickAdapter(
    private var playlists: List<Playlist>,
    private val onPick: (Playlist) -> Unit
) : RecyclerView.Adapter<PlaylistPickAdapter.ViewHolder>() {

    private var addingId: String? = null

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvPlaylistPickName)
        val ivCheck: ImageView = view.findViewById(R.id.ivPlaylistPickCheck)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playlist_pick, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val playlist = playlists[position]
        holder.tvName.text = playlist.name

        if (playlist.id == addingId) {
            holder.ivCheck.setImageResource(android.R.drawable.checkbox_on_background)
        } else {
            holder.ivCheck.setImageResource(android.R.drawable.ic_menu_add)
        }

        holder.itemView.setOnClickListener {
            if (addingId == null) onPick(playlist)
        }
    }

    override fun getItemCount(): Int = playlists.size

    fun updatePlaylists(newPlaylists: List<Playlist>) {
        playlists = newPlaylists
        notifyDataSetChanged()
    }

    /** Marca (o desmarca con null) una playlist como "añadiendo" para dar feedback visual. */
    fun setAdding(playlistId: String?) {
        addingId = playlistId
        notifyDataSetChanged()
    }
}
