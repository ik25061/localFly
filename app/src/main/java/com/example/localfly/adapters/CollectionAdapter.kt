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
import com.example.localfly.network.Year
import java.net.URLEncoder

class CollectionAdapter(
    private var items: List<Any>,
    private val onItemClick: (Any) -> Unit
) : RecyclerView.Adapter<CollectionAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivCover: ImageView = view.findViewById(R.id.ivGridCover)
        val tvTitle: TextView = view.findViewById(R.id.tvGridTitle)
        val tvSubtitle: TextView = view.findViewById(R.id.tvGridSubtitle)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_grid_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        val context = holder.itemView.context
        val serverBaseUrl = ApiConfig.BASE_URL

        when (item) {
            is Album -> {
                holder.tvTitle.text = item.name
                holder.tvSubtitle.text = item.artist ?: "Álbum"
                val encodedName = URLEncoder.encode("album - ${item.name}.jpg", "UTF-8").replace("+", "%20")
                val coverUrl = "$serverBaseUrl/resources/$encodedName"
                
                Glide.with(context).load(coverUrl).placeholder(R.drawable.ic_music_placeholder).error(
                    Glide.with(context).load("$serverBaseUrl/cover/${item.coverId}").centerCrop()
                ).centerCrop().into(holder.ivCover)
            }
            is Artist -> {
                holder.tvTitle.text = item.name
                holder.tvSubtitle.text = "${item.songCount} canciones"
                val encodedName = URLEncoder.encode("artist - ${item.name}.jpg", "UTF-8").replace("+", "%20")
                val coverUrl = "$serverBaseUrl/resources/$encodedName"
                
                Glide.with(context).load(coverUrl).placeholder(R.drawable.ic_music_placeholder).error(
                    Glide.with(context).load("$serverBaseUrl/cover/${item.coverId}").centerCrop()
                ).centerCrop().into(holder.ivCover)
            }
            is Genre -> {
                holder.tvTitle.text = item.name
                holder.tvSubtitle.text = "${item.songCount} canciones"
                Glide.with(context).load("$serverBaseUrl/cover/${item.coverId}").placeholder(R.drawable.ic_music_placeholder).centerCrop().into(holder.ivCover)
            }
            is Year -> {
                holder.tvTitle.text = item.year.toString()
                holder.tvSubtitle.text = "${item.songCount} canciones"
                Glide.with(context).load("$serverBaseUrl/cover/${item.coverId}").placeholder(R.drawable.ic_music_placeholder).centerCrop().into(holder.ivCover)
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