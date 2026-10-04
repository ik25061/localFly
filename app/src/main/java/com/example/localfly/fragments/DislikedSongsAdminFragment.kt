package com.example.localfly.fragments

import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.example.localfly.DownloadManagerHelper
import com.example.localfly.MainActivity
import com.example.localfly.R
import com.example.localfly.network.DeleteSongRequest
import com.example.localfly.network.DislikedSong
import com.example.localfly.network.RetrofitClient
import com.example.localfly.network.SessionManager
import com.example.localfly.network.SongAdminStore
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pestaña "Canciones que no me gustan" del administrador. Combina el registro
 * local (SongAdminStore) con lo que el SERVIDOR tiene marcado como oculto
 * (GET /api/hidden-songs): cualquier canción oculta en el servidor que no
 * estuviera ya en el registro local se "adopta" automáticamente la primera
 * vez que se abre esta pantalla, para que ambas fuentes queden sincronizadas.
 */
class DislikedSongsAdminFragment : Fragment() {

    private lateinit var layoutDisliked: LinearLayout
    private lateinit var tvCount: TextView
    private lateinit var tvNoDisliked: TextView
    private lateinit var sessionManager: SessionManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_disliked_admin, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sessionManager = SessionManager(requireContext())

        layoutDisliked = view.findViewById(R.id.layoutDisliked)
        tvCount = view.findViewById(R.id.tvDislikedCount)
        tvNoDisliked = view.findViewById(R.id.tvNoDisliked)

        view.findViewById<ImageButton>(R.id.btnBackDisliked).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        if (parentFragment is SettingsFragment) {
            view.findViewById<View>(R.id.btnBackDisliked).visibility = View.GONE
            view.background = ColorDrawable(Color.TRANSPARENT)
        }

        view.findViewById<MaterialButton>(R.id.btnClearDisliked).setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Vaciar lista local")
                .setMessage("Se quitarán todas las canciones de esta lista local. No se borrará ningún archivo del disco.")
                .setPositiveButton("Vaciar") { _, _ ->
                    SongAdminStore.clearDislikedSongs()
                    refresh()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        refresh()
        syncFromServerThenRefresh()
    }

    override fun onResume() {
        super.onResume()
        // Al volver a la pantalla (p. ej. tras eliminar desde el reproductor),
        // releer el registro local para no mostrar canciones ya borradas.
        if (::layoutDisliked.isInitialized) refresh()
    }

    /** Trae lo que el servidor tiene oculto y adopta en el registro local
     *  cualquier canción que faltara, sin duplicar las que ya están. */
    private fun syncFromServerThenRefresh() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.getHiddenSongs(sessionManager.getUserId(), limit = 200)
                if (response.isSuccessful) {
                    val serverHidden = response.body()?.songs ?: emptyList()
                    val localIds = SongAdminStore.getDislikedSongs().map { it.songId }.toSet()
                    var addedAny = false
                    serverHidden.forEach { song ->
                        // El borrado en el servidor puede tardar en reflejarse:
                        // jamás readoptar una canción eliminada del disco.
                        if (song.id !in localIds && !SongAdminStore.isDeleted(song.id)) {
                            SongAdminStore.recordDislikedSong(song)
                            addedAny = true
                        }
                    }
                    if (addedAny && isAdded) refresh()
                }
            } catch (e: Exception) {
                // Sin conexión: se muestra igualmente lo que ya hay en local.
            }
        }
    }

    private fun refresh() {
        layoutDisliked.removeAllViews()
        val songs = SongAdminStore.getDislikedSongs()
        // La lista se divide en DOS grupos independientes, cada uno con su
        // encabezado:
        //   1. REVISADAS: el admin ya las escuchó y pulsó "Dejar" (kept = true).
        //   2. SIN REVISAR: siguen pendientes, la "lista de eliminar después"
        //      (kept = false); son las que forman la cola de revisión.
        val reviewed = songs.filter { it.kept }
        val unreviewed = songs.filter { !it.kept }
        tvCount.text = buildString {
            append(if (songs.size == 1) "1 canción" else "${songs.size} canciones")
            append(" · ${reviewed.size} dejadas · ${unreviewed.size} sin revisar")
        }
        tvNoDisliked.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE

        // ---- Grupo 1: revisadas y dejadas (verde) ----
        if (reviewed.isNotEmpty() && isAdded) {
            layoutDisliked.addView(
                buildSectionHeader("Revisadas · ya las dejaste (${reviewed.size})", "#6BCB77")
            )
            for (song in reviewed) layoutDisliked.addView(buildRow(song))
        }

        // ---- Grupo 2: sin revisar, para eliminar después (rojo) ----
        if (unreviewed.isNotEmpty() && isAdded) {
            layoutDisliked.addView(
                buildSectionHeader("Sin revisar · para eliminar después (${unreviewed.size})", "#FF6B6B")
            )
            for (song in unreviewed) layoutDisliked.addView(buildRow(song))
        }
    }

    /**
     * Encabezado visual de cada grupo de la lista de "No me gusta": un título
     * con el color del grupo y una línea inferior del mismo color para separar
     * claramente las dos secciones.
     */
    private fun buildSectionHeader(text: String, accentHex: String): View {
        val container = LinearLayout(requireContext())
        container.orientation = LinearLayout.VERTICAL

        val header = TextView(requireContext())
        header.text = text
        header.setTextColor(Color.parseColor(accentHex))
        header.textSize = 13f
        header.setTypeface(null, android.graphics.Typeface.BOLD)
        header.setPadding(dp(6), dp(14), dp(6), dp(6))
        container.addView(header)

        val divider = View(requireContext())
        val dividerParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1)
        )
        dividerParams.bottomMargin = dp(4)
        divider.layoutParams = dividerParams
        divider.setBackgroundColor(Color.parseColor(accentHex))
        container.addView(divider)

        container.layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        return container
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun buildRow(song: DislikedSong): View {
        val row = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_disliked_admin, null)

        row.findViewById<TextView>(R.id.tvDlSongTitle).text = song.title

        val parts = mutableListOf<String>()
        song.artist?.let { parts.add(it) }
        song.album?.let { parts.add(it) }
        song.year?.let { parts.add(it.toString()) }
        row.findViewById<TextView>(R.id.tvDlSongArtist).text =
            if (parts.isEmpty()) "Artista desconocido" else parts.joinToString(" · ")

        val card = row.findViewById<com.google.android.material.card.MaterialCardView>(R.id.cardDisliked)
        val tvStatus = row.findViewById<TextView>(R.id.tvDlStatus)
        val btnKeep = row.findViewById<MaterialButton>(R.id.btnKeepSong)

        if (song.kept) {
            // Recuadro apagado + aviso: ya la revisó y decidió no borrarla.
            card.setCardBackgroundColor(Color.parseColor("#16211A"))
            card.strokeColor = Color.parseColor("#2E5237")
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = "✓ Dejada: ya la revisaste y no se eliminará (fuera de la cola de revisión)"
            tvStatus.setTextColor(Color.parseColor("#6BCB77"))
            btnKeep.text = "Volver a revisar"
            btnKeep.setTextColor(Color.parseColor("#A8A8AF"))
            btnKeep.strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#55555C"))
        } else {
            // Recuadro RELLENO EN ROJO OSCURO: pendiente de revisar.
            card.setCardBackgroundColor(Color.parseColor("#3B0F14"))
            card.strokeColor = Color.parseColor("#C0392B")
            tvStatus.visibility = View.VISIBLE
            tvStatus.text = "● Pendiente de revisar: ponla en la cola para decidir si la borras o la dejas"
            tvStatus.setTextColor(Color.parseColor("#FF6B6B"))
            btnKeep.text = "Dejar"
            btnKeep.setTextColor(Color.parseColor("#6BCB77"))
            btnKeep.strokeColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#6BCB77"))
        }

        btnKeep.setOnClickListener {
            val nowKept = !song.kept
            if (nowKept) {
                SongAdminStore.markDislikedSongKept(song.songId)
            } else {
                SongAdminStore.markDislikedSongPending(song.songId)
            }
            // Si la canción está sonando, rearmar la cola de revisión del servicio.
            (requireActivity() as? MainActivity)?.playbackService?.refreshDislikedReviewQueue()
            refresh()
            Toast.makeText(
                requireContext(),
                if (nowKept) "Dejada: ya la revisaste, no se eliminará" else "Vuelta a poner en revisión",
                Toast.LENGTH_SHORT
            ).show()
        }

        row.findViewById<MaterialButton>(R.id.btnRemoveFromList).setOnClickListener {
            SongAdminStore.removeDislikedSong(song.songId)
            (requireActivity() as? MainActivity)?.playbackService?.refreshDislikedReviewQueue()
            refresh()
            Toast.makeText(requireContext(), "Quitada de la lista local", Toast.LENGTH_SHORT).show()
        }

        row.findViewById<MaterialButton>(R.id.btnDeleteFromDisk).setOnClickListener {
            confirmDeleteFromDisk(song)
        }

        row.findViewById<MaterialButton>(R.id.btnPlayDisliked).setOnClickListener {
            playSong(song)
        }

        return row
    }

    @OptIn(UnstableApi::class)
    private fun playSong(disliked: DislikedSong) {
        // La canción se eliminó del disco: ya no se puede reproducir aunque
        // el servidor la siga devolviendo un rato en /by-ids.
        if (SongAdminStore.isDeleted(disliked.songId)) {
            SongAdminStore.removeDislikedSong(disliked.songId)
            if (isAdded) {
                refresh()
                Toast.makeText(requireContext(), "Esa canción ya se eliminó del disco", Toast.LENGTH_SHORT).show()
            }
            return
        }
        val activity = requireActivity() as? MainActivity
        if (activity?.playbackService == null) {
            Toast.makeText(requireContext(), "Servicio de música no disponible", Toast.LENGTH_SHORT).show()
            return
        }

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Obtener detalles completos de la canción desde el servidor para poder reproducirla
                val response = RetrofitClient.api.getSongsByIds(disliked.songId, sessionManager.getUserId())
                if (response.isSuccessful && response.body() != null) {
                    val songs = response.body()!!.songs
                    if (songs.isNotEmpty()) {
                        val fullSong = songs[0]
                        // Si el servidor ya no la tiene pero aún la lista como
                        // oculta, limpiarla de la lista en vez de reproducirla.
                        if (SongAdminStore.isDeleted(fullSong.id)) {
                            SongAdminStore.removeDislikedSong(fullSong.id)
                            if (isAdded) {
                                refresh()
                                Toast.makeText(requireContext(), "Esa canción ya se eliminó del disco", Toast.LENGTH_SHORT).show()
                            }
                            return@launch
                        }
                        activity.playbackService?.playDislikedReview(fullSong)
                        Toast.makeText(requireContext(), "Escuchando: ${fullSong.title}", Toast.LENGTH_SHORT).show()
                    } else {
                        // El servidor ya no devuelve la canción (borrada): no
                        // ofrecer reproducirla, quitarla de la lista local.
                        SongAdminStore.markSongDeleted(disliked.songId)
                        activity.playbackService?.purgeDeletedSong(disliked.songId)
                        if (isAdded) {
                            refresh()
                            Toast.makeText(requireContext(), "Esa canción ya no existe: se quitó de la lista", Toast.LENGTH_SHORT).show()
                        }
                    }
                } else {
                    Log.e("DislikedAdmin", "Error server: ${response.code()} ${response.errorBody()?.string()}")
                    Toast.makeText(requireContext(), "Error del servidor al cargar audio", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e("DislikedAdmin", "Error playback", e)
                Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDeleteFromDisk(song: DislikedSong) {
        AlertDialog.Builder(requireContext())
            .setTitle("Eliminar del disco")
            .setMessage("¿Eliminar \"${song.title}\" por completo del disco? Se borrará el archivo del servidor y no se podrá recuperar.")
            .setPositiveButton("Eliminar") { _, _ -> deleteFromDisk(song) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    @OptIn(UnstableApi::class)
    private fun deleteFromDisk(song: DislikedSong) {
        val activity = requireActivity() as? MainActivity
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.deleteSong(
                    DeleteSongRequest(id = song.songId, userId = sessionManager.getUserId())
                )
                if (response.isSuccessful) {
                    // Marcar como eliminada ANTES de rearmar nada: así ni la
                    // sincronización con /hidden-songs ni la cola de revisión
                    // pueden readoptarla mientras el servidor termina de
                    // reflejar el borrado.
                    SongAdminStore.markSongDeleted(song.songId)
                    sessionManager.removePendingDislike(song.songId)

                    // Limpieza local completa: audio, letra (ambas copias),
                    // instrumental de karaoke y subida de letra pendiente,
                    // haya o no descarga registrada.
                    val helper = DownloadManagerHelper.getInstance(requireContext())
                    withContext(Dispatchers.IO) { helper.removeLocalArtifacts(song.songId) }
                    // Sacarla de TODA la cola (incluida la que está sonando).
                    activity?.playbackService?.purgeDeletedSong(song.songId)
                    if (isAdded) {
                        Toast.makeText(requireContext(), "Canción eliminada del disco y saltada", Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                } else if (isAdded) {
                    Toast.makeText(requireContext(), "No se pudo eliminar del disco", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                if (isAdded) {
                    Toast.makeText(requireContext(), "Sin conexión: no se pudo eliminar", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
