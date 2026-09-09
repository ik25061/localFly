package com.example.localfly.fragments

import android.app.AlertDialog
import android.net.Uri
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
import androidx.activity.result.contract.ActivityResultContracts
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
    private var selectedBackgroundImageUri: Uri? = null

    private val pickBackgroundImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        selectedBackgroundImageUri = uri
        sessionManager.setBackgroundMode("image")
        sessionManager.setBackgroundImageUri(uri.toString())
        Toast.makeText(requireContext(), "Fondo de imagen guardado", Toast.LENGTH_SHORT).show()
        requireActivity().recreate()
    }

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
        setupFontFamilySpinner(view)
        setupAppColorSpinner(view)
        setupBackgroundAppearance(view)
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
        val adapter = ArrayAdapter(requireContext(), R.layout.spinner_item_selected, options)
        adapter.setDropDownViewResource(R.layout.spinner_item_dropdown)
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
        val adapter = ArrayAdapter(requireContext(), R.layout.spinner_item_selected, options)
        adapter.setDropDownViewResource(R.layout.spinner_item_dropdown)
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

    private fun setupFontFamilySpinner(root: View) {
        val spinner = root.findViewById<Spinner>(R.id.spinnerFontFamily)
        val options = arrayOf("Default", "Serif", "Monospace")
        val adapter = ArrayAdapter(requireContext(), R.layout.spinner_item_selected, options)
        adapter.setDropDownViewResource(R.layout.spinner_item_dropdown)
        spinner.adapter = adapter

        val current = sessionManager.getFontFamily()
        val selection = options.indexOf(current)
        if (selection != -1) spinner.setSelection(selection)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selected = options[position]
                if (selected != sessionManager.getFontFamily()) {
                    sessionManager.setFontFamily(selected)
                    requireActivity().recreate()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupBackgroundAppearance(root: View) {
        val spinner = root.findViewById<Spinner>(R.id.spinnerBackgroundMode)
        val options = arrayOf("Sólido", "Degradado", "Imagen")
        val adapter = ArrayAdapter(requireContext(), R.layout.spinner_item_selected, options)
        adapter.setDropDownViewResource(R.layout.spinner_item_dropdown)
        spinner.adapter = adapter

        val currentMode = sessionManager.getBackgroundMode()
        val currentIndex = when (currentMode.lowercase()) {
            "gradient" -> 1
            "image" -> 2
            else -> 0
        }
        spinner.setSelection(currentIndex)

        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                when (options[position]) {
                    "Sólido" -> sessionManager.setBackgroundMode("solid")
                    "Degradado" -> sessionManager.setBackgroundMode("gradient")
                    "Imagen" -> sessionManager.setBackgroundMode("image")
                }
                requireActivity().recreate()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        root.findViewById<MaterialButton>(R.id.btnPickBackgroundImage).setOnClickListener {
            pickBackgroundImage.launch("image/*")
        }

        root.findViewById<MaterialButton>(R.id.btnApplyPresetGradient).setOnClickListener {
            val presets = arrayOf(
                "Magenta / Morado",
                "Azul / Cyan",
                "Naranja / Rosa",
                "Verde / Azul"
            )
            val values = arrayOf(
                "#4A148C" to "#F06292",
                "#0D47A1" to "#26C6DA",
                "#FF6F00" to "#EC407A",
                "#1B5E20" to "#29B6F6"
            )
            val index = arrayOf(0, 1, 2, 3)
            AlertDialog.Builder(requireContext())
                .setTitle("Selecciona un degradado")
                .setItems(presets) { _, which ->
                    val (start, end) = values[which]
                    sessionManager.setBackgroundMode("gradient")
                    sessionManager.setBackgroundGradientStart(start)
                    sessionManager.setBackgroundGradientEnd(end)
                    requireActivity().recreate()
                }
                .show()
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

        root.findViewById<MaterialButton>(R.id.btnOpenAdminPanel).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, AdminPanelFragment())
                .addToBackStack(null)
                .commit()
        }
    }
}
