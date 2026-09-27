package com.example.localfly

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.localfly.network.Song
import com.example.localfly.network.SongAdminStore

/**
 * Adaptador de la cola de reproducción REAL (no una vista de solo lectura).
 * Soporta arrastrar para reordenar (mango de hamburguesa) y deslizar para
 * eliminar, mediante ItemTouchHelper configurado en NowPlayingActivity.
 *
 * Además marca visualmente las canciones que están en la lista de "No me
 * gusta" y pendientes de revisar: recuadro en ROJO OSCURO, aviso y botón
 * "Dejar" (ya la revisó y no la eliminará).
 */
class QueueAdapter(
    private val songs: MutableList<Song>,
    private val onDragHandleTouch: (RecyclerView.ViewHolder) -> Unit,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onRemove: (position: Int) -> Unit,
    private val onKeepDisliked: (position: Int) -> Unit = {}
) : RecyclerView.Adapter<QueueAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvIndex: TextView = view.findViewById(R.id.tvQueueIndex)
        val tvTitle: TextView = view.findViewById(R.id.tvQueueTitle)
        val tvArtist: TextView = view.findViewById(R.id.tvQueueArtist)
        val tvDislikeTag: TextView = view.findViewById(R.id.tvQueueDislikeTag)
        val ivDragHandle: ImageView = view.findViewById(R.id.ivQueueDragHandle)
        val btnKeepDislike: ImageButton = view.findViewById(R.id.btnQueueKeepDislike)
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

        // Canciones marcadas como "No me gusta" pendientes de revisar: recuadro
        // relleno en rojo oscuro, aviso y botón "Dejar" (antes oculto).
        val isPendingDislike = SongAdminStore.isPendingDislike(song.id)
        if (isPendingDislike) {
            holder.itemView.background = ColorDrawable(Color.parseColor("#66B3121F"))
            holder.tvDislikeTag.visibility = View.VISIBLE
            holder.btnKeepDislike.visibility = View.VISIBLE
        } else {
            restoreSelectableBackground(holder.itemView)
            holder.tvDislikeTag.visibility = View.GONE
            holder.btnKeepDislike.visibility = View.GONE
        }

        holder.btnKeepDislike.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onKeepDisliked(pos)
        }

        holder.ivDragHandle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                onDragHandleTouch(holder)
            }
            false
        }
    }

    /** Devuelve el fondo pulsable del tema (el que trae el layout) cuando la
     *  canción no está marcada como "No me gusta". */
    private fun restoreSelectableBackground(view: View) {
        val outValue = TypedValue()
        val resolved = view.context.theme.resolveAttribute(
            android.R.attr.selectableItemBackground, outValue, true
        )
        if (resolved && outValue.resourceId != 0) {
            view.setBackgroundResource(outValue.resourceId)
        } else {
            view.background = null
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