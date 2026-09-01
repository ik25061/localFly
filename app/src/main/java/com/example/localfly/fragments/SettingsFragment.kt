package com.example.localfly.fragments

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.localfly.LoginActivity
import com.example.localfly.R
import com.example.localfly.network.RescanManager
import com.example.localfly.network.RetrofitClient
import com.example.localfly.network.SessionManager
import com.example.localfly.utils.LocalLogger
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private lateinit var sessionManager: SessionManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sessionManager = SessionManager(requireContext())

        view.findViewById<ImageButton>(R.id.btnBackSettings).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        setupFontSizeSpinner(view)
        setupAppColorSpinner(view)
        setupAdminOptions(view)

        view.findViewById<MaterialButton>(R.id.btnLogout).setOnClickListener {
            sessionManager.clearSession()
            android.content.Intent(requireContext(), LoginActivity::class.java).also {
                it.flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(it)
            }
        }

        view.findViewById<MaterialButton>(R.id.btnViewLog).setOnClickListener {
            showLogDialog()
        }
    }

    private fun showLogDialog() {
        val logFile = java.io.File(requireContext().filesDir, "app_debug_log.txt")
        val content = if (logFile.exists()) logFile.readText() else "No hay registros todavía."
        
        AlertDialog.Builder(requireContext())
            .setTitle("Registro de Depuración")
            .setMessage(content)
            .setPositiveButton("Cerrar", null)
            .setNeutralButton("Borrar Log") { dialog, which ->
                logFile.delete()
                Toast.makeText(requireContext(), "Log borrado", Toast.LENGTH_SHORT).show()
            }
            .show()
    }


    private fun setupFontSizeSpinner(root: View) {
        val spinner = root.findViewById<Spinner>(R.id.spinnerFontSize)
        val options = arrayOf("Extra pequeño", "Normal", "Grande", "Extra grande")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        val currentSize = sessionManager.getTextSize()
        val selection = options.indexOf(currentSize)
        if (selection != -1) spinner.setSelection(selection)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val newSize = options[position]
                if (newSize != sessionManager.getTextSize()) {
                    sessionManager.setTextSize(newSize)
                    requireActivity().finish()
                    startActivity(requireActivity().intent)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupAppColorSpinner(root: View) {
        val spinner = root.findViewById<Spinner>(R.id.spinnerAppColor)
        val options = arrayOf("Verde", "Azul", "Rojo", "Púrpura")
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, options)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        val currentColor = sessionManager.getAppColor()
        val selection = options.indexOf(currentColor)
        if (selection != -1) spinner.setSelection(selection)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, position: Int, id: Long) {
                val newColor = options[position]
                if (newColor != sessionManager.getAppColor()) {
                    sessionManager.setAppColor(newColor)
                    requireActivity().finish()
                    startActivity(requireActivity().intent)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupAdminOptions(root: View) {
        val adminLayout = root.findViewById<LinearLayout>(R.id.layoutAdminOptions)
        val btnRescan = root.findViewById<MaterialButton>(R.id.btnRescanLibrary)

        if (sessionManager.isAdmin()) {
            adminLayout.visibility = View.VISIBLE
        } else {
            adminLayout.visibility = View.GONE
        }

        btnRescan.setOnClickListener {
            RescanManager.triggerRescan(viewLifecycleOwner.lifecycleScope)
        }
    }
}
