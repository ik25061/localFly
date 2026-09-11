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
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.localfly.LoginActivity
import com.example.localfly.MainActivity
import com.example.localfly.R
import com.example.localfly.network.RescanManager
import com.example.localfly.network.RetrofitClient
import com.example.localfly.network.SessionManager
import com.example.localfly.utils.LocalLogger
import com.example.localfly.dialogs.ColorPickerDialog
import com.example.localfly.adapters.GradientAdapter
import com.example.localfly.adapters.PresetGradient
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import android.widget.TextView
import com.google.android.material.materialswitch.MaterialSwitch
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
        setupBackgroundExtraControls(view)
        setupAdminOptions(view)

        val swMix = view.findViewById<MaterialSwitch>(R.id.swMixPodcasts)
        swMix.isChecked = sessionManager.isPodcastMixingEnabled()
        swMix.setOnCheckedChangeListener { _, isChecked ->
            sessionManager.setPodcastMixingEnabled(isChecked)
        }

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
                    requireActivity().recreate()
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
                    requireActivity().recreate()
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

        // Evita recreate() en bucle: setSelection() dispara onItemSelected al crear la vista.
        var initializingBgMode = true
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (initializingBgMode) { initializingBgMode = false; return }
                val newMode = when (options[position]) {
                    "Degradado" -> "gradient"
                    "Imagen" -> "image"
                    else -> "solid"
                }
                if (newMode != sessionManager.getBackgroundMode()) {
                    sessionManager.setBackgroundMode(newMode)
                    requireActivity().recreate()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        root.findViewById<MaterialButton>(R.id.btnPickBackgroundImage).setOnClickListener {
            pickBackgroundImage.launch("image/*")
        }
    }

    private fun setupBackgroundExtraControls(root: View) {
        val btnSolid = root.findViewById<MaterialButton>(R.id.btnPickSolidColor)
        btnSolid.setOnClickListener {
            ColorPickerDialog.show(requireContext(), sessionManager.getBackgroundSolidColor()) { hex, alpha ->
                sessionManager.setBackgroundMode("solid")
                sessionManager.setBackgroundSolidColor(hex)
                sessionManager.setBackgroundAlphaPct(alpha)
                (requireActivity() as? MainActivity)?.applyBackgroundAppearance()
                Toast.makeText(requireContext(), "Fondo sólido guardado", Toast.LENGTH_SHORT).show()
            }
        }

        root.findViewById<MaterialButton>(R.id.btnApplyPresetGradient).setOnClickListener {
            showPresetGradientsDialog()
        }

        val seekAlpha = root.findViewById<SeekBar>(R.id.seekBackgroundAlpha)
        seekAlpha.progress = sessionManager.getBackgroundAlphaPct()
        seekAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                sessionManager.setBackgroundAlphaPct(progress)
                (requireActivity() as? MainActivity)?.applyBackgroundAppearance()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        val seekBlur = root.findViewById<SeekBar>(R.id.seekBackgroundBlur)
        seekBlur.progress = sessionManager.getBackgroundBlur()
        seekBlur.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                sessionManager.setBackgroundBlur(progress)
                (requireActivity() as? MainActivity)?.applyBackgroundAppearance()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    private fun pickCustomGradient() {
        ColorPickerDialog.show(requireContext(), sessionManager.getBackgroundGradientStart()) { startHex, _ ->
            ColorPickerDialog.show(requireContext(), sessionManager.getBackgroundGradientEnd()) { endHex, _ ->
                sessionManager.setBackgroundMode("gradient")
                sessionManager.setBackgroundGradientStart(startHex)
                sessionManager.setBackgroundGradientEnd(endHex)
                (requireActivity() as? MainActivity)?.applyBackgroundAppearance()
                Toast.makeText(requireContext(), "Degradado guardado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showPresetGradientsDialog() {
        val dialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.fragment_playlists, null) // Reusar layout con RV
        
        view.findViewById<View>(R.id.btnAIPlaylist).visibility = View.GONE
        view.findViewById<View>(R.id.btnNewPlaylist).visibility = View.GONE
        view.findViewById<TextView>(android.R.id.text1)?.text = "Selecciona un degradado"
        
        val rv = view.findViewById<RecyclerView>(R.id.rvPlaylists)
        val presets = listOf(
            PresetGradient("Aurora", "#4A148C", "#F06292"),
            PresetGradient("Océano", "#0D47A1", "#26C6DA"),
            PresetGradient("Atardecer", "#FF6F00", "#EC407A"),
            PresetGradient("Bosque", "#1B5E20", "#29B6F6"),
            PresetGradient("Noche", "#121212", "#434343"),
            PresetGradient("Personalizado...", "#000000", "#FFFFFF")
        )
        
        val adapter = GradientAdapter(presets) { item ->
            if (item.name == "Personalizado...") {
                pickCustomGradient()
            } else {
                sessionManager.setBackgroundMode("gradient")
                sessionManager.setBackgroundGradientStart(item.startColor)
                sessionManager.setBackgroundGradientEnd(item.endColor)
                (requireActivity() as? MainActivity)?.applyBackgroundAppearance()
            }
            dialog.dismiss()
        }
        
        rv.layoutManager = GridLayoutManager(requireContext(), 2)
        rv.adapter = adapter
        
        dialog.setContentView(view)
        dialog.show()
    }

    private fun setupAdminOptions(root: View) {
        val adminLayout = root.findViewById<LinearLayout>(R.id.layoutAdminOptions)

        // Ajustes y herramientas disponibles para todos (antes quedaban ocultas
        // si el nombre de usuario no coincidía exactamente con "Rafael").
        adminLayout.visibility = View.VISIBLE

        root.findViewById<MaterialButton>(R.id.btnDislikedSongs).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, DislikedSongsAdminFragment())
                .addToBackStack(null)
                .commit()
        }

        root.findViewById<MaterialButton>(R.id.btnRescanLibrary).setOnClickListener {
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
