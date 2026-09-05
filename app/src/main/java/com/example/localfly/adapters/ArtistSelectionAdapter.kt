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
import com.example.localfly.utils.CoverPlaceholder
import java.net.URLEncoder

class ArtistSelectionAdapter(
    private var artists: List<Artist>,
    private val selectedIds: MutableSet<String>,
    private val onSelectionChanged: () -> Unit
) : RecyclerView.Adapter<ArtistSelectionAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val root: View = view.findViewById(R.id.rootArtistItem)
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
        val isSelected = selectedIds.contains(artist.id)
        holder.checkBox.isChecked = isSelected
        holder.root.isSelected = isSelected

        // El servidor sirve la foto del artista en /artist-cover/{nombre}
        // (misma ruta que usa la versión web y el resto de la app). 
        val seed = artist.name
        val encodedName = URLEncoder.encode(artist.name, "UTF-8").replace("+", "%20")
        val primaryUrl = "$serverBaseUrl/artist-cover/$encodedName"
        if (artist.coverId == null) {
            holder.ivCover.setImageDrawable(CoverPlaceholder.drawable(seed))
        } else {
            val fallback = Glide.with(context)
                .load("$serverBaseUrl/cover/${artist.coverId}")
                .placeholder(CoverPlaceholder.drawable(seed))
                .error(CoverPlaceholder.drawable(seed))
                .centerCrop()
            Glide.with(context)
                .load(primaryUrl)
                .placeholder(CoverPlaceholder.drawable(seed))
                .error(fallback)
                .centerCrop()
                .into(holder.ivCover)
        }
 
        holder.itemView.setOnClickListener {
            if (selectedIds.contains(artist.id)) {

                selectedIds.remove(artist.id)
            } else {
                selectedIds.add(artist.id)
            }
            notifyItemChanged(holder.bindingAdapterPosition)
            onSelectionChanged()
        }
    }
 
    override fun getItemCount() = artists.size
 
    fun updateItems(newArtists: List<Artist>) {
        artists = newArtists
        notifyDataSetChanged()
    }
}