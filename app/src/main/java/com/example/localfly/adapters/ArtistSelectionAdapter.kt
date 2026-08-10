package com.example.localfly.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.localfly.R
import com.example.localfly.network.Artist
import com.example.localfly.network.ApiConfig
import java.net.URLEncoder

class ArtistSelectionAdapter(
    private var artists: List<Artist>,
    private val selectedIds: MutableSet<String>,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<ArtistSelectionAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivCover: ImageView = view.findViewById(R.id.ivArtistCoverSelect)
        val tvName: TextView = view.findViewById(R.id.tvArtistNameSelect)
        val checkBox: CheckBox = view.findViewById(R.id.cbArtistSelect)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_artist_selection, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val artist = artists[position]
        val context = holder.itemView.context
        val serverBaseUrl = ApiConfig.BASE_URL

        holder.tvName.text = artist.name
        holder.checkBox.setOnCheckedChangeListener(null)
        holder.checkBox.isChecked = selectedIds.contains(artist.id)

        val encodedName = URLEncoder.encode("artist - ${artist.name}.jpg", "UTF-8").replace("+", "%20")
        val coverUrl = "$serverBaseUrl/resources/$encodedName"

        Glide.with(context)
            .load(coverUrl)
            .placeholder(R.drawable.ic_music_placeholder)
            .error(
                Glide.with(context).load("$serverBaseUrl/cover/${artist.coverId}").centerCrop()
            )
            .centerCrop()
            .into(holder.ivCover)

        holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) selectedIds.add(artist.id)
            else selectedIds.remove(artist.id)
            onSelectionChanged()
        }

        holder.itemView.setOnClickListener {
            holder.checkBox.isChecked = !holder.checkBox.isChecked
        }
    }

    override fun getItemCount() = artists.size

    fun updateItems(newArtists: List<Artist>) {
        artists = newArtists
        notifyDataSetChanged()
    }
}