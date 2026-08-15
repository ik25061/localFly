package com.example.localfly.adapters

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.localfly.R

data class LyricLine(val timeMs: Long, val content: String)

class LyricsAdapter(private val lines: List<LyricLine>) : RecyclerView.Adapter<LyricsAdapter.ViewHolder>() {

    private var activePosition = -1

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvLine: TextView = view.findViewById(R.id.tvLyricLine)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_lyric_line, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val line = lines[position]
        holder.tvLine.text = line.content
        
        if (position == activePosition) {
            holder.tvLine.setTextColor(Color.WHITE)
            holder.tvLine.alpha = 1.0f
            holder.tvLine.scaleX = 1.05f
            holder.tvLine.scaleY = 1.05f
        } else {
            holder.tvLine.setTextColor(Color.parseColor("#80FFFFFF"))
            holder.tvLine.alpha = 0.6f
            holder.tvLine.scaleX = 1.0f
            holder.tvLine.scaleY = 1.0f
        }
    }

    override fun getItemCount() = lines.size

    fun updateActiveLine(currentTimeMs: Long): Int {
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