package com.example.localfly.dialogs

import android.content.Context
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import com.example.localfly.R
import com.example.localfly.network.CustomGenre
import com.example.localfly.network.SongAdminStore
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton

/** Diálogo para crear (o renombrar) un género personalizado del administrador. */
object NewGenreDialog {

    /**
     * @param existing si no es null, el diálogo se usa para renombrar/editar
     *                 ese género conservando sus canciones.
     */
    fun show(context: Context, existing: CustomGenre? = null, onSaved: (CustomGenre) -> Unit) {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context)
            .inflate(R.layout.dialog_new_genre, null)

        val isRename = existing != null

        view.findViewById<android.widget.TextView>(R.id.tvNewGenreTitle).text =
            if (isRename) "Renombrar género" else "Nuevo género"

        val etName = view.findViewById<EditText>(R.id.etGenreName)
        val etDescription = view.findViewById<EditText>(R.id.etGenreDescription)
        if (isRename && existing != null) {
            etName.setText(existing.name)
            if (existing.description != null && existing.description!!.isNotEmpty()) {
                etDescription.setText(existing.description!!)
            }
        }

        view.findViewById<ImageButton>(R.id.btnCloseNewGenre).setOnClickListener { dialog.dismiss() }
        view.findViewById<MaterialButton>(R.id.btnCancelNewGenre).setOnClickListener { dialog.dismiss() }

        view.findViewById<MaterialButton>(R.id.btnCreateGenre).setOnClickListener {
            val name = etName.text.toString().trim()
            val description = etDescription.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(context, "Escribe un nombre para el género", Toast.LENGTH_SHORT).show()
            } else {
                val genre = if (isRename && existing != null) {
                    // Conserva id, canciones y fecha de creación.
                    existing.copy(
                        name = name,
                        description = if (description.isEmpty()) null else description
                    )
                } else {
                    val now = System.currentTimeMillis()
                    CustomGenre(
                        id = "g_$now",
                        name = name,
                        description = if (description.isEmpty()) null else description,
                        songIds = emptyList(),
                        createdAtMs = now
                    )
                }
                SongAdminStore.saveGenre(genre)
                Toast.makeText(
                    context,
                    if (isRename) "Género actualizado" else "Género \"" + name + "\" creado",
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
                onSaved(genre)
            }
        }

        dialog.setContentView(view)
        dialog.show()
    }
}