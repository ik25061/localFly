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
    private val onLineClick: (LyricLine) -> Unit
) : RecyclerView.Adapter<LyricsAdapter.ViewHolder>() {

    private var activePosition = -1

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvLine: TextView = view.findViewById(R.id.tvLyricLine)
        val tvTranslation: TextView = view.findViewById(R.id.tvLyricTranslation)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_lyric_line, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val line = lines[position]
        holder.tvLine.text = line.content

        if (!line.translation.isNullOrBlank()) {
            holder.tvTranslation.text = line.translation
            holder.tvTranslation.visibility = View.VISIBLE
        } else {
            holder.tvTranslation.visibility = View.GONE
        }
        
        // Evitar que la primera letra se oculte al escalar
        holder.tvLine.pivotX = 0f
        holder.tvLine.post {
            if (position < lines.size) {
                holder.tvLine.pivotY = holder.tvLine.height / 2f
            }
        }
        
        if (position == activePosition) {
            holder.tvLine.setTextColor(Color.WHITE)
            holder.tvLine.alpha = 1.0f
            holder.tvLine.animate()
                .scaleX(1.08f)
                .scaleY(1.08f)
                .setDuration(250)
                .start()
        } else {
            holder.tvLine.setTextColor(Color.parseColor("#80FFFFFF"))
            holder.tvLine.alpha = 0.6f
            holder.tvLine.animate()
                .scaleX(1.0f)
                .scaleY(1.0f)
                .setDuration(250)
                .start()
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