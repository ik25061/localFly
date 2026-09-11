package com.example.localfly.adapters

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.localfly.R

data class PresetGradient(val name: String, val startColor: String, val endColor: String)

class GradientAdapter(
    private val gradients: List<PresetGradient>,
    private val onClick: (PresetGradient) -> Unit
) : RecyclerView.Adapter<GradientAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val viewPreview: View = view.findViewById(R.id.viewGradientPreview)
        val tvName: TextView = view.findViewById(R.id.tvGradientName)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_gradient_card, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = gradients[position]
        holder.tvName.text = item.name
        
        val gd = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(Color.parseColor(item.startColor), Color.parseColor(item.endColor))
        )
        gd.cornerRadius = 12f
        holder.viewPreview.background = gd
        
        holder.itemView.setOnClickListener { onClick(item) }
    }

    override fun getItemCount() = gradients.size
}
