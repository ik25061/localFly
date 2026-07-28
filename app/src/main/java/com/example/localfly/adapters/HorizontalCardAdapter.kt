package com.example.localfly.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.localfly.R
import com.example.localfly.network.Album
import com.example.localfly.network.Artist
import com.example.localfly.network.Genre
import com.example.localfly.network.Year

class HorizontalCardAdapter(
    private var items: List<Any>,
    private val onItemClick: (Any) -> Unit
) : RecyclerView.Adapter<HorizontalCardAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivCover: ImageView = view.findViewById(R.id.ivCardCover)
        val tvTitle: TextView = view.findViewById(R.id.tvCardTitle)
        val tvSubtitle: TextView = view.findViewById(R.id.tvCardSubtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_horizontal_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        val serverBaseUrl = "http://127.0.0.1:5002" // O usar RetrofitClient.BASE_URL

        when (item) {
            is Album -> {
                holder.tvTitle.text = item.name
                holder.tvSubtitle.text = item.artist ?: "Álbum"
                if (item.coverId != null) {
                    Glide.with(context)
                        .load("$serverBaseUrl/cover/${item.coverId}")
                        .centerCrop()
                        .into(holder.ivCover)
                } else {
                    holder.ivCover.setImageResource(R.drawable.ic_music_placeholder)
                }
            }
            is Artist -> {
                holder.tvTitle.text = item.name
                holder.tvSubtitle.text = "${item.songCount} canciones"
                if (item.coverId != null) {
                    Glide.with(context)
                        .load("$serverBaseUrl/cover/${item.coverId}")
                        .centerCrop()
                        .into(holder.ivCover)
                } else {
                    holder.ivCover.setImageResource(R.drawable.ic_music_placeholder)
                }
            }
            is Genre -> {
                holder.tvTitle.text = item.name
                holder.tvSubtitle.text = "${item.songCount} canciones"
                if (item.coverId != null) {
                    Glide.with(context)
                        .load("$serverBaseUrl/cover/${item.coverId}")
                        .centerCrop()
                        .into(holder.ivCover)
                } else {
                    holder.ivCover.setImageResource(R.drawable.ic_music_placeholder)
                }
            }
            is Year -> {
                holder.tvTitle.text = item.year.toString()
                holder.tvSubtitle.text = "${item.songCount} canciones"
                if (item.coverId != null) {
                    Glide.with(context)
                        .load("$serverBaseUrl/cover/${item.coverId}")
                        .centerCrop()
                        .into(holder.ivCover)
                } else {
                    holder.ivCover.setImageResource(R.drawable.ic_music_placeholder)
                }
            }
        }

        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount() = items.size

    fun updateItems(newItems: List<Any>) {
        items = newItems
        notifyDataSetChanged()
    }
}