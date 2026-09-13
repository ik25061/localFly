package com.example.localfly.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.localfly.R

data class LyricLine(
    val timeMs: Long,
    val content: String,
    val translation: String? = null
)

class LyricsAdapter(
    private val lines: List<LyricLine>,
    private val isInlineMode: Boolean = false,
    private val onLineClick: (LyricLine) -> Unit
) : RecyclerView.Adapter<LyricsAdapter.ViewHolder>() {

    private var activePosition = -1

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvLine: TextView = view.findViewById(R.id.tvLyricLine)
        val tvTranslation: TextView? = view.findViewById(R.id.tvLyricTranslation)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val layout = if (isInlineMode) R.layout.item_lyric_inline else R.layout.item_lyric_line
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val line = lines[position]
        holder.tvLine.text = line.content

        if (holder.tvTranslation != null) {
            if (!line.translation.isNullOrBlank()) {
                holder.tvTranslation.text = line.translation
                holder.tvTranslation.visibility = View.VISIBLE
            } else {
                holder.tvTranslation.visibility = View.GONE
            }
        }
        
        // Evitar que la primera letra se oculte al escalar si no es centrado
        if (!isInlineMode) {
            holder.tvLine.pivotX = 0f
        }
        
        holder.tvLine.post {
            if (position < lines.size) {
                holder.tvLine.pivotY = holder.tvLine.height / 2f
            }
        }
        
        if (position == activePosition) {
            holder.tvLine.setTextColor(Color.WHITE)
            holder.tvLine.alpha = 1.0f
            holder.tvLine.scaleX = if (isInlineMode) 1.05f else 1.08f
            holder.tvLine.scaleY = if (isInlineMode) 1.05f else 1.08f
        } else {
            holder.tvLine.setTextColor(if (isInlineMode) Color.parseColor("#B3FFFFFF") else Color.parseColor("#80FFFFFF"))
            holder.tvLine.alpha = if (isInlineMode) 0.8f else 0.6f
            holder.tvLine.scaleX = 1.0f
            holder.tvLine.scaleY = 1.0f
        }

        holder.itemView.setOnClickListener {
            onLineClick(line)
        }
    }

    override fun getItemCount() = lines.size

    fun updateActiveLine(currentTimeMs: Long): Int {
        // Si no hay tiempos (letras en modo plano), no hacemos scroll automático
        if (lines.all { it.timeMs == 0L }) return -1

        var newPosition = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= currentTimeMs) {
                newPosition = i
            } else {
                break
            }
        }

        if (newPosition != activePosition) {
            val old = activePosition
            activePosition = newPosition
            if (old != -1) notifyItemChanged(old)
            if (activePosition != -1) notifyItemChanged(activePosition)
        }
        return activePosition
    }
}