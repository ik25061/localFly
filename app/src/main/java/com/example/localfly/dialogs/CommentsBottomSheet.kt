package com.example.localfly.dialogs

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.localfly.R
import com.example.localfly.network.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import java.util.*

class CommentsBottomSheet(
    private val songId: String,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val sessionManager: SessionManager
) : BottomSheetDialogFragment() {

    private lateinit var rvComments: RecyclerView
    private lateinit var adapter: CommentsAdapter
    private lateinit var etInput: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var rbRating: RatingBar
    private lateinit var tvStats: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.bottom_sheet_comments, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvComments = view.findViewById(R.id.rvComments)
        etInput = view.findViewById(R.id.etCommentInput)
        btnSend = view.findViewById(R.id.btnSendComment)
        rbRating = view.findViewById(R.id.rbUserRating)
        tvStats = view.findViewById(R.id.tvCommentStats)

        view.findViewById<ImageButton>(R.id.btnCloseComments).setOnClickListener { dismiss() }

        adapter = CommentsAdapter()
        rvComments.layoutManager = LinearLayoutManager(requireContext())
        rvComments.adapter = adapter

        loadComments()

        btnSend.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener
            
            val rating = rbRating.rating.toInt()
            postComment(text, rating)
        }
    }

    private fun loadComments() {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.api.getComments(songId)
                if (resp.isSuccessful && resp.body() != null) {
                    val body = resp.body()!!
                    adapter.updateItems(body.comments)
                    tvStats.text = "${body.totalCount} opiniones de la comunidad · ⭐ ${String.format("%.1f", body.averageRating)}"
                }
            } catch (e: Exception) { }
        }
    }

    private fun postComment(text: String, rating: Int) {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.api.postComment(
                    PostCommentRequest(
                        userId = sessionManager.getUserId(),
                        songId = songId,
                        text = text,
                        rating = rating
                    )
                )
                if (resp.isSuccessful) {
                    etInput.text.clear()
                    rbRating.rating = 0f
                    loadComments()
                    Toast.makeText(requireContext(), "Comentario enviado", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error al enviar comentario", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        fun show(context: Context, fragmentManager: FragmentManager, songId: String, scope: LifecycleCoroutineScope, session: SessionManager) {
            val sheet = CommentsBottomSheet(songId, scope, session)
            sheet.show(fragmentManager, "CommentsBottomSheet")
        }
    }
}

class CommentsAdapter : RecyclerView.Adapter<CommentsAdapter.ViewHolder>() {
    private var items = emptyList<Comment>()

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvUser: TextView = view.findViewById(R.id.tvCommentUsername)
        val tvInitial: TextView = view.findViewById(R.id.tvCommentUserInitial)
        val avatar: View = view.findViewById(R.id.ivCommentUserAvatar)
        val tvText: TextView = view.findViewById(R.id.tvCommentText)
        val tvDate: TextView = view.findViewById(R.id.tvCommentDate)
        val rb: RatingBar = view.findViewById(R.id.rbCommentRating)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_comment, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvUser.text = item.username
        holder.tvText.text = item.text
        holder.rb.rating = item.rating.toFloat()
        
        val initial = item.username.take(1).uppercase()
        holder.tvInitial.text = initial
        
        val colors = listOf("#E91E63", "#9C27B0", "#673AB7", "#3F51B5", "#2196F3", "#009688", "#4CAF50", "#FF9800", "#FF5722")
        val colorIndex = Math.abs(item.username.hashCode()) % colors.size
        holder.avatar.backgroundTintList = ColorStateList.valueOf(Color.parseColor(colors[colorIndex]))

        // Formatear fecha simple (ej. hace 2h)
        holder.tvDate.text = "reciente" // Implementación real requeriría parsing de ISO date
    }

    override fun getItemCount() = items.size

    fun updateItems(newItems: List<Comment>) {
        items = newItems
        notifyDataSetChanged()
    }
}
