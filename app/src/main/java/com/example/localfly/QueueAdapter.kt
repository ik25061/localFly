package com.example.localfly

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.localfly.network.Song

/**
 * Adaptador de la cola de reproducción REAL (no una vista de solo lectura).
 * Soporta arrastrar para reordenar (mango de hamburguesa) y deslizar para
 * eliminar, mediante ItemTouchHelper configurado en NowPlayingActivity.
 */
class QueueAdapter(
    private val songs: MutableList<Song>,
    private val onDragHandleTouch: (RecyclerView.ViewHolder) -> Unit,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onRemove: (position: Int) -> Unit
) : RecyclerView.Adapter<QueueAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvIndex: TextView = view.findViewById(R.id.tvQueueIndex)
        val tvTitle: TextView = view.findViewById(R.id.tvQueueTitle)
        val tvArtist: TextView = view.findViewById(R.id.tvQueueArtist)
        val ivDragHandle: ImageView = view.findViewById(R.id.ivQueueDragHandle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_queue_song, parent, false)
        return ViewHolder(view)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val song = songs[position]
        holder.tvIndex.text = (position + 1).toString()
        holder.tvTitle.text = song.title
        holder.tvArtist.text = song.artist ?: "Artista desconocido"

        holder.ivDragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                onDragHandleTouch(holder)
            }
            false
        }
    }

    override fun getItemCount() = songs.size

    /** Llamado por el ItemTouchHelper mientras se arrastra, para animar el intercambio visual. */
    fun onItemMove(fromPosition: Int, toPosition: Int) {
        val song = songs.removeAt(fromPosition)
        songs.add(toPosition, song)
        notifyItemMoved(fromPosition, toPosition)
    }

    /** Llamado por el ItemTouchHelper cuando se suelta tras arrastrar: confirma el cambio real. */
    fun confirmMove(fromPosition: Int, toPosition: Int) {
        onMove(fromPosition, toPosition)
    }

    /** Llamado por el ItemTouchHelper al terminar un swipe. */
    fun onItemDismiss(position: Int) {
        songs.removeAt(position)
        notifyItemRemoved(position)
        // Reindexar los números de fila visibles debajo del eliminado
        notifyItemRangeChanged(position, songs.size - position)
        onRemove(position)
    }

    fun updateSongs(newSongs: List<Song>) {
        songs.clear()
        songs.addAll(newSongs)
        notifyDataSetChanged()
    }
}