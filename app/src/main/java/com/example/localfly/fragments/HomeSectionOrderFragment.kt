package com.example.localfly.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.localfly.R
import com.example.localfly.network.HomeSections
import com.example.localfly.network.SessionManager
import com.google.android.material.button.MaterialButton

/**
 * Pantalla del administrador para reordenar las secciones de la pantalla de
 * Inicio. Cada fila tiene dos botones (▲/▼) que mueven la sección; el orden se
 * guarda al instante en [SessionManager] y [HomeFragment] lo aplica al abrir.
 */
class HomeSectionOrderFragment : Fragment() {

    private lateinit var sessionManager: SessionManager
    private lateinit var listContainer: LinearLayout

    /** Orden de trabajo; se persiste en cada movimiento. */
    private val order = mutableListOf<String>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_home_section_order, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        sessionManager = SessionManager(requireContext())
        listContainer = view.findViewById(R.id.listSectionOrder)

        order.clear()
        order.addAll(sessionManager.getHomeSectionOrder())

        view.findViewById<ImageButton>(R.id.btnBackSectionOrder).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        view.findViewById<MaterialButton>(R.id.btnResetSectionOrder).setOnClickListener {
            order.clear()
            order.addAll(HomeSections.DEFAULT_ORDER)
            sessionManager.setHomeSectionOrder(order)
            renderList()
            Toast.makeText(requireContext(), "Orden restablecido", Toast.LENGTH_SHORT).show()
        }

        renderList()
    }

    private fun renderList() {
        listContainer.removeAllViews()
        order.forEachIndexed { index, key ->
            val row = layoutInflater.inflate(R.layout.item_home_section_order, listContainer, false)

            row.findViewById<TextView>(R.id.tvSectionOrderName).text =
                "${index + 1}. ${HomeSections.title(key)}"

            val btnUp = row.findViewById<MaterialButton>(R.id.btnMoveSectionUp)
            val btnDown = row.findViewById<MaterialButton>(R.id.btnMoveSectionDown)

            // Primera/última sección: la flecha correspondiente se desactiva.
            btnUp.isEnabled = index > 0
            btnUp.alpha = if (index > 0) 1f else 0.3f
            btnUp.setOnClickListener { move(index, -1) }

            btnDown.isEnabled = index < order.size - 1
            btnDown.alpha = if (index < order.size - 1) 1f else 0.3f
            btnDown.setOnClickListener { move(index, 1) }

            listContainer.addView(row)
        }
    }

    private fun move(index: Int, delta: Int) {
        val target = index + delta
        if (target < 0 || target >= order.size) return
        val item = order.removeAt(index)
        order.add(target, item)
        sessionManager.setHomeSectionOrder(order)
        renderList()
    }
}
