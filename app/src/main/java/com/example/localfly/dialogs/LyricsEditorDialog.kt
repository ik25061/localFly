package com.example.localfly.dialogs

// Editor de la letra de una canción.
//
// Funciona igual con o sin servidor:
//  1. Precarga lo que ya existe en el teléfono (versión propia del usuario o
//     .lrc guardado) y, si no hay nada, lo pide al servidor.
//  2. Al guardar, la letra se escribe en el almacenamiento de la app (se ve al
//     instante y sigue disponible offline) y se deja en cola para el servidor.
//  3. Si el servidor está disponible en ese momento se sube ya; si no, la
//     subida se hace automáticamente al recuperar la conexión, de modo que la
//     letra del servidor queda actualizada con la versión del usuario.

import android.content.Context
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.example.localfly.R
import com.example.localfly.network.ApiService
import com.example.localfly.network.RetrofitClient
import com.example.localfly.network.ServerReachability
import com.example.localfly.network.SessionManager
import com.example.localfly.network.Song
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object LyricsEditorDialog {

    fun show(context: Context, song: Song, onSaved: (() -> Unit)? = null) {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_edit_lyrics, null)

        val sessionManager = SessionManager(context)
        val etLyrics = view.findViewById<EditText>(R.id.etManualLyrics)
        val tvStatus = view.findViewById<TextView>(R.id.tvLyricsEditorStatus)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSaveManualLyrics)

        tvStatus.text = "Se guarda en este teléfono y se sincroniza con el servidor."

        // 1. Precarga local (funciona sin conexión)
        val localContent = readLocalLyrics(context, song.id)
        if (!localContent.isNullOrBlank()) etLyrics.setText(localContent)

        // 2. Si no había nada propio, traer la letra del servidor (si responde)
        CoroutineScope(Dispatchers.Main).launch {
            if (!localContent.isNullOrBlank()) return@launch
            val remote = withContext(Dispatchers.IO) {
                try {
                    val resp = RetrofitClient.api.getLyrics(song.id)
                    if (resp.isSuccessful) resp.body()?.lyrics else null
                } catch (e: Exception) { null }
            }
            if (!remote.isNullOrBlank() && etLyrics.text.isNullOrBlank()) {
                etLyrics.setText(remote)
            }
        }

        btnSave.setOnClickListener {
            val content = etLyrics.text.toString().trim()
            if (content.isEmpty()) {
                Toast.makeText(context, "La letra está vacía", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            saveLyricsLocally(context, song.id, content)
            sessionManager.addPendingLyricsUpload(song.id, content)
            btnSave.isEnabled = false
            tvStatus.text = "Guardada en el teléfono. Sincronizando con el servidor…"

            CoroutineScope(Dispatchers.Main).launch {
                val uploaded = withContext(Dispatchers.IO) {
                    if (!ServerReachability.isServerReachable()) return@withContext false
                    try {
                        val resp = RetrofitClient.api.saveLyricsFile(
                            song.id, ApiService.SaveLyricsFileRequest(content)
                        )
                        resp.isSuccessful
                    } catch (e: Exception) { false }
                }
                btnSave.isEnabled = true
                if (uploaded) {
                    sessionManager.removePendingLyricsUpload(song.id)
                    Toast.makeText(context, "Letra actualizada en el servidor", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        context,
                        "Letra guardada en el teléfono: se actualizará en el servidor al recuperar la conexión",
                        Toast.LENGTH_LONG
                    ).show()
                }
                onSaved?.invoke()
                dialog.dismiss()
            }
        }

        dialog.setContentView(view)
        dialog.show()
    }

    /** Letra propia del usuario guardada en el teléfono (o null si no hay). */
    private fun readLocalLyrics(context: Context, songId: String): String? {
        val sessionManager = SessionManager(context)
        sessionManager.getPendingLyricsUploads()[songId]?.takeIf { it.isNotBlank() }?.let { return it }
        return try {
            val lyricsFile = File(context.filesDir, "lyrics/$songId.lrc")
            if (lyricsFile.exists()) lyricsFile.readText().takeIf { it.isNotBlank() } else null
        } catch (e: Exception) { null }
    }

    /** Escribe la letra en el almacenamiento de la app para uso sin conexión. */
    private fun saveLyricsLocally(context: Context, songId: String, content: String) {
        try {
            val lyricsDir = File(context.filesDir, "lyrics")
            if (!lyricsDir.exists()) lyricsDir.mkdirs()
            File(lyricsDir, "$songId.lrc").writeText(content)

            // El reproductor también lee downloads/<id>.lrc: se mantiene al día
            // para que la letra mostrada sea siempre la versión del usuario.
            val downloadsDir = File(context.filesDir, "downloads")
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            File(downloadsDir, "$songId.lrc").writeText(content)
        } catch (e: Exception) { }
    }
}
