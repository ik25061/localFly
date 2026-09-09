package com.example.localfly.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.fragment.app.Fragment
import com.example.localfly.R
import com.google.android.material.button.MaterialButton

/** Punto de entrada a las herramientas del administrador. */
class AdminPanelFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_admin_panel, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<ImageButton>(R.id.btnBackAdmin).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        view.findViewById<MaterialButton>(R.id.btnEditSongs).setOnClickListener {
            open(SongEditorFragment())
        }
        view.findViewById<MaterialButton>(R.id.btnConflicts).setOnClickListener {
            open(ConflictReviewFragment())
        }
        view.findViewById<MaterialButton>(R.id.btnDisliked).setOnClickListener {
            open(DislikedSongsAdminFragment())
        }
        view.findViewById<MaterialButton>(R.id.btnGenres).setOnClickListener {
            open(GenreManagerFragment())
        }
    }

    private fun open(fragment: Fragment) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.container, fragment)
            .addToBackStack(null)
            .commit()
    }
}