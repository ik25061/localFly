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
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch

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
    private lateinit var tvRatingLabel: TextView
    private lateinit var tvStats: TextView
    private lateinit var llReplyingTo: View
    private lateinit var tvReplyingTo: TextView
    private lateinit var btnCancelReply: ImageButton

    /** Comentario al que se está respondiendo (modo respuesta activo). */
    private var replyTo: Comment? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.bottom_sheet_comments, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rvComments = view.findViewById(R.id.rvComments)
        etInput = view.findViewById(R.id.etCommentInput)
        btnSend = view.findViewById(R.id.btnSendComment)
        rbRating = view.findViewById(R.id.rbUserRating)
        tvRatingLabel = view.findViewById(R.id.tvRatingLabel)
        tvStats = view.findViewById(R.id.tvCommentStats)
        llReplyingTo = view.findViewById(R.id.llReplyingTo)
        tvReplyingTo = view.findViewById(R.id.tvReplyingTo)
        btnCancelReply = view.findViewById(R.id.btnCancelReply)

        view.findViewById<ImageButton>(R.id.btnCloseComments).setOnClickListener { dismiss() }

        adapter = CommentsAdapter(sessionManager, lifecycleScope)
        adapter.onReplyClickListener = { comment -> setReplyTarget(comment) }
        rvComments.layoutManager = LinearLayoutManager(requireContext())
        rvComments.adapter = adapter

        loadComments()

        btnSend.setOnClickListener {
            val text = etInput.text.toString().trim()
            if (text.isEmpty()) return@setOnClickListener

            val target = replyTo
            if (target != null) {
                postReply(text, target)
            } else {
                val rating = rbRating.rating.toInt()
                postComment(text, rating)
            }
        }

        btnCancelReply.setOnClickListener { clearReplyTarget() }
    }

    private fun loadComments() {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.api.getComments(songId, sessionManager.getUserId())
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
                if (!ServerReachability.isOnline) {
                    sessionManager.addPendingComment(songId, text, rating)
                    etInput.text.clear()
                    rbRating.rating = 0f
                    Toast.makeText(requireContext(), "Comentario guardado (se subirá al reconectar)", Toast.LENGTH_SHORT).show()
                    dismiss()
                    return@launch
                }

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
                // Fallback offline ante error de red inesperado
                sessionManager.addPendingComment(songId, text, rating)
                Toast.makeText(requireContext(), "Error de red: comentario guardado localmente", Toast.LENGTH_SHORT).show()
                dismiss()
            }
        }
    }

    /** Envía una respuesta a otro comentario (sin rating: las respuestas
     *  no puntúan la canción). */
    private fun postReply(text: String, parent: Comment) {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.api.postComment(
                    PostCommentRequest(
                        userId = sessionManager.getUserId(),
                        songId = songId,
                        text = text,
                        rating = 0,
                        parentId = parent.id
                    )
                )
                if (resp.isSuccessful) {
                    etInput.text.clear()
                    clearReplyTarget()
                    loadComments()
                    Toast.makeText(requireContext(), "Respuesta enviada", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "Error al enviar respuesta", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Activa el modo "responder a un comentario". */
    private fun setReplyTarget(comment: Comment) {
        replyTo = comment
        tvReplyingTo.text = "Respondiendo a ${comment.username}"
        llReplyingTo.visibility = View.VISIBLE
        tvRatingLabel.visibility = View.GONE
        rbRating.visibility = View.GONE
        etInput.hint = "Escribe tu respuesta..."
        etInput.requestFocus()
    }

    /** Cancela el modo respuesta y restaura la vista de comentario nuevo. */
    private fun clearReplyTarget() {
        replyTo = null
        llReplyingTo.visibility = View.GONE
        tvRatingLabel.visibility = View.VISIBLE
        rbRating.visibility = View.VISIBLE
        etInput.hint = "Escribe tu opinión..."
    }

    companion object {
        fun show(context: Context, fragmentManager: FragmentManager, songId: String, scope: LifecycleCoroutineScope, session: SessionManager) {
            val sheet = CommentsBottomSheet(songId, scope, session)
            sheet.show(fragmentManager, "CommentsBottomSheet")
        }
    }
}

/**
 * Adapter de comentarios: muestra los comentarios principales con sus
 * respuestas anidadas debajo (un nivel), botón "Responder" y "Me gusta".
 */
class CommentsAdapter(
    private val sessionManager: SessionManager,
    private val lifecycleScope: LifecycleCoroutineScope
) : RecyclerView.Adapter<CommentsAdapter.CommentViewHolder>() {

    /** Fila plana para el RecyclerView: comentario principal o respuesta. */
    private class Row(val comment: Comment, val isReply: Boolean)

    private var topLevel = emptyList<Comment>()
    private var rows = emptyList<Row>()

    /** Invocado al pulsar "Responder" sobre un comentario principal. */
    var onReplyClickListener: ((Comment) -> Unit)? = null

    class CommentViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvUser: TextView = view.findViewById(R.id.tvCommentUsername)
        val tvInitial: TextView = view.findViewById(R.id.tvCommentUserInitial)
        val avatar: View = view.findViewById(R.id.ivCommentUserAvatar)
        val tvText: TextView = view.findViewById(R.id.tvCommentText)
        val tvDate: TextView = view.findViewById(R.id.tvCommentDate)
        val rb: RatingBar = view.findViewById(R.id.rbCommentRating)
        val btnLike: ImageButton = view.findViewById(R.id.btnCommentLike)
        val tvLikes: TextView = view.findViewById(R.id.tvCommentLikes)
        val btnReply: TextView = view.findViewById(R.id.btnCommentReply)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CommentViewHolder {
        val layout = if (viewType == TYPE_REPLY) R.layout.item_comment_reply else R.layout.item_comment
        val view = LayoutInflater.from(parent.context).inflate(layout, parent, false)
        return CommentViewHolder(view)
    }

    override fun getItemViewType(position: Int): Int =
        if (rows[position].isReply) TYPE_REPLY else TYPE_COMMENT

    override fun onBindViewHolder(holder: CommentViewHolder, position: Int) {
        val row = rows[position]
        val item = row.comment
        holder.tvUser.text = item.username
        holder.tvText.text = item.text

        // Las respuestas no llevan estrellas; los comentarios solo las
        // muestran si puntúan la canción.
        holder.rb.rating = item.rating.toFloat()
        holder.rb.visibility = if (!row.isReply && item.rating > 0) View.VISIBLE else View.GONE

        val initial = item.username.take(1).uppercase()
        holder.tvInitial.text = initial

        val colors = listOf("#E91E63", "#9C27B0", "#673AB7", "#3F51B5", "#2196F3", "#009688", "#4CAF50", "#FF9800", "#FF5722")
        val colorIndex = Math.abs(item.username.hashCode()) % colors.size
        holder.avatar.backgroundTintList = ColorStateList.valueOf(Color.parseColor(colors[colorIndex]))

        // Formatear fecha simple (ej. hace 2h)
        holder.tvDate.text = "reciente" // Implementación real requeriría parsing de ISO date

        // "Me gusta"
        holder.btnLike.setImageResource(
            if (item.likedByMe) R.drawable.ic_like_on else R.drawable.ic_like_off
        )
        holder.tvLikes.text = if (item.likesCount > 0) item.likesCount.toString() else ""
        holder.btnLike.setOnClickListener { toggleLike(item, holder.itemView.context) }

        // "Responder": solo en comentarios principales (las respuestas no
        // se anidan a más niveles).
        holder.btnReply.visibility = if (row.isReply) View.GONE else View.VISIBLE
        holder.btnReply.setOnClickListener { onReplyClickListener?.invoke(item) }
    }

    override fun getItemCount() = rows.size

    /** Actualiza la lista de comentarios y reconstruye las filas planas. */
    fun updateItems(newItems: List<Comment>) {
        topLevel = newItems
        rebuild()
    }

    /** Da/quita el "me gusta" del usuario actual a un comentario o respuesta. */
    private fun toggleLike(comment: Comment, context: Context) {
        val userId = sessionManager.getUserId() ?: return
        val newLiked = !comment.likedByMe

        // Actualización optimista mientras llega la respuesta del servidor.
        updateLikeState(comment.id, newLiked)

        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.api.toggleCommentLike(
                    comment.id,
                    CommentLikeRequest(userId)
                )
                if (resp.isSuccessful && resp.body() != null) {
                    val body = resp.body()!!
                    updateLikeState(comment.id, body.liked, body.likesCount)
                } else {
                    // Revertir al estado original si el servidor rechazó.
                    updateLikeState(comment.id, comment.likedByMe)
                    Toast.makeText(context, "No se pudo registrar tu me gusta", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                updateLikeState(comment.id, comment.likedByMe)
                Toast.makeText(context, "Error de conexión", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Actualiza el estado de like de un comentario (o de una de sus
     *  respuestas) en el modelo y refresca la lista. */
    private fun updateLikeState(commentId: String, liked: Boolean, likesCount: Int? = null) {
        var changed = false

        topLevel = topLevel.map { c ->
            when {
                c.id == commentId -> {
                    changed = true
                    c.copy(
                        likedByMe = liked,
                        likesCount = likesCount ?: c.likesCount + if (liked) 1 else -1
                    )
                }
                c.replies.any { it.id == commentId } -> {
                    changed = true
                    c.copy(replies = c.replies.map { r ->
                        if (r.id == commentId) {
                            r.copy(
                                likedByMe = liked,
                                likesCount = likesCount ?: r.likesCount + if (liked) 1 else -1
                            )
                        } else r
                    })
                }
                else -> c
            }
        }

        if (changed) rebuild()
    }

    /** Convierte la lista jerárquica en filas planas para el RecyclerView. */
    private fun rebuild() {
        val newRows = mutableListOf<Row>()
        for (comment in topLevel) {
            newRows.add(Row(comment, isReply = false))
            for (reply in comment.replies) {
                newRows.add(Row(reply, isReply = true))
            }
        }
        rows = newRows
        notifyDataSetChanged()
    }

    companion object {
        private const val TYPE_COMMENT = 0
        private const val TYPE_REPLY = 1
    }
}
