package com.example.localfly.fragments

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.localfly.R
import com.example.localfly.dialogs.NewGenreDialog
import com.example.localfly.network.CustomGenre
import com.example.localfly.network.SongAdminStore
import com.google.android.material.button.MaterialButton

/** Gestor de géneros del administrador: crea, abre y elimina géneros locales. */
class GenreManagerFragment : Fragment() {

    private lateinit var layoutGenres: LinearLayout
    private lateinit var tvInfo: TextView
    private lateinit var tvNoGenres: TextView

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_genre_manager, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        layoutGenres = view.findViewById(R.id.layoutGenres)
        tvInfo = view.findViewById(R.id.tvGenresInfo)
        tvNoGenres = view.findViewById(R.id.tvNoGenres)

        view.findViewById<ImageButton>(R.id.btnBackGenres).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        view.findViewById<MaterialButton>(R.id.btnNewGenre).setOnClickListener {
            NewGenreDialog.show(requireContext(), null) { refresh() }
        }

        refresh()
    }

    private fun refresh() {
        layoutGenres.removeAllViews()
        val genres = SongAdminStore.getGenres()
        tvInfo.text = if (genres.size == 1) "1 género creado" else "${genres.size} géneros creados"
        tvNoGenres.visibility = if (genres.isEmpty()) View.VISIBLE else View.GONE
        for (genre in genres) {
            layoutGenres.addView(buildRow(genre))
        }
    }

    private fun buildRow(genre: CustomGenre): View {
        val row = LayoutInflater.from(requireContext())
            .inflate(R.layout.item_custom_genre, null)

        row.findViewById<TextView>(R.id.tvGenreName).text = genre.name

        val meta = if (genre.songIds.size == 1) "1 canción" else "${genre.songIds.size} canciones"
        row.findViewById<TextView>(R.id.tvGenreMeta).text =
            if (genre.description.isNullOrBlank()) meta else "$meta · ${genre.description}"

        row.findViewById<MaterialButton>(R.id.btnOpenGenre).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, GenreDetailFragment.newInstance(genre.id))
                .addToBackStack(null)
                .commit()
        }

        row.findViewById<MaterialButton>(R.id.btnDeleteGenre).setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("Eliminar género")
                .setMessage("¿Eliminar \"${genre.name}\"? Las canciones se conservarán en la biblioteca; solo se borra el género (${genre.songIds.size} asignadas).")
                .setPositiveButton("Eliminar") { _, _ ->
                    SongAdminStore.deleteGenre(genre.id)
                    refresh()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        return row
    }
}