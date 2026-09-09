package com.example.localfly.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.localfly.R
import com.example.localfly.network.Song
import com.example.localfly.network.SongAdminStore
import com.google.android.material.button.MaterialButton

/**
 * Lista de canciones de la biblioteca para asignarlas a un género: cada fila
 * muestra el título/artista y un botón "Añadir"/"Quitar" según si la canción
 * ya pertenece al género.
 */
class GenrePickAdapter(
    private var songs: MutableList<Song>,
    private val isSelected: (Song) -> Boolean,
    private val onToggle: (Song) -> Unit
) : RecyclerView.Adapter<GenrePickAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvPickTitle)
        val tvArtist: TextView = view.findViewById(R.id.tvPickArtist)
        val btnAdd: MaterialButton = view.findViewById(R.id.btnPickAdd)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_genre_pick, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val song = SongAdminStore.applyTo(songs[position])
        holder.tvTitle.text = song.title
        holder.tvArtist.text = song.artist ?: "Artista desconocido"

        val selected = isSelected(song)
        holder.btnAdd.text = if (selected) "Quitar" else "Añadir"
        holder.btnAdd.setOnClickListener { onToggle(song) }
        holder.itemView.setOnClickListener { onToggle(song) }
    }

    override fun getItemCount() = songs.size

    fun updateSongs(newSongs: List<Song>) {
        songs.clear()
        songs.addAll(newSongs)
        notifyDataSetChanged()
    }
}