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
import com.example.localfly.network.ApiConfig
import com.example.localfly.network.Artist
import com.example.localfly.network.Genre
import com.example.localfly.network.Playlist
import com.example.localfly.network.Year
import java.net.URLEncoder

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
        val serverBaseUrl = ApiConfig.BASE_URL

        when (item) {
            is Playlist -> {
                holder.tvTitle.text = item.name
                val count = item.songIds.size
                holder.tvSubtitle.text = if (count == 1) "1 canción" else "$count canciones"
                if (!item.coverId.isNullOrBlank()) {
                    Glide.with(context)
                        .load("$serverBaseUrl/cover/${item.coverId}")
                        .centerCrop()
                        .into(holder.ivCover)
                } else {
                    holder.ivCover.setImageResource(R.drawable.ic_music_placeholder)
                }
            }
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

                // El servidor sirve la foto del artista en /artist-cover/{nombre}
                val encodedName = URLEncoder.encode(item.name, "UTF-8").replace("+", "%20")
                val primary = "$serverBaseUrl/artist-cover/$encodedName"
                val fallback = item.coverId?.let {
                    Glide.with(context).load("$serverBaseUrl/cover/$it").centerCrop()
                } ?: Glide.with(context).load(R.drawable.ic_music_placeholder)

                Glide.with(context)
                    .load(primary)
                    .placeholder(R.drawable.ic_music_placeholder)
                    .error(fallback)
                    .centerCrop()
                    .into(holder.ivCover)
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