package com.example.localfly.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.Toast
import com.example.localfly.R
import com.example.localfly.network.ApiService
import com.example.localfly.network.RetrofitClient
import com.example.localfly.network.Song
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object LyricsEditorDialog {

    fun show(context: Context, song: Song) {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_edit_lyrics, null)
        
        val etLyrics = view.findViewById<EditText>(R.id.etManualLyrics)
        val btnSave = view.findViewById<MaterialButton>(R.id.btnSaveManualLyrics)
        
        // Cargar letras actuales
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val resp = RetrofitClient.api.getLyrics(song.id)
                if (resp.isSuccessful) {
                    etLyrics.setText(resp.body()?.lyrics ?: "")
                }
            } catch (e: Exception) { }
        }

        btnSave.setOnClickListener {
            val content = etLyrics.text.toString().trim()
            if (content.isEmpty()) return@setOnClickListener
            
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val resp = RetrofitClient.api.saveLyricsFile(song.id, ApiService.SaveLyricsFileRequest(content))
                    if (resp.isSuccessful) {
                        Toast.makeText(context, "Letra guardada en el servidor", Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    } else {
                        Toast.makeText(context, "Error al guardar: ${resp.code()}", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(context, "Error de red", Toast.LENGTH_SHORT).show()
                }
            }
        }

        dialog.setContentView(view)
        dialog.show()
    }
}
