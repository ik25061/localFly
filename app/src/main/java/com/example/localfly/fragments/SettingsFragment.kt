package com.example.localfly.fragments

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.localfly.LoginActivity
import com.example.localfly.MainActivity
import com.example.localfly.R
import com.example.localfly.adapters.GradientAdapter
import com.example.localfly.adapters.PresetGradient
import com.example.localfly.dialogs.ColorPickerDialog
import com.example.localfly.network.ApiConfig
import com.example.localfly.network.RescanManager
import com.example.localfly.network.RetrofitClient
import com.example.localfly.network.ServerReachability
import com.example.localfly.network.SessionManager
import com.example.localfly.utils.LocalLogger
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private lateinit var sessionManager: SessionManager
    private lateinit var contentFrame: FrameLayout
    private lateinit var chipGroupTabs: ChipGroup

    private val pickBackgroundImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@registerForActivityResult
        sessionManager.setBackgroundMode("image")
        sessionManager.setBackgroundImageUri(uri.toString())
        // Nueva imagen: la rotación acumulada anterior no se aplica
        sessionManager.setBackgroundImageRotation(0)
        Toast.makeText(requireContext(), "Fondo de imagen guardado", Toast.LENGTH_SHORT).show()
        (requireActivity() as? MainActivity)?.applyBackgroundAppearance()
        refreshCurrentSection()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings_redesign, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sessionManager = SessionManager(requireContext())
        contentFrame = view.findViewById(R.id.settingsContentFrame)
        chipGroupTabs = view.findViewById(R.id.chipGroupSettingsTabs)

        chipGroupTabs.setOnCheckedStateChangeListener { group, checkedIds ->
            when (checkedIds.firstOrNull()) {
                R.id.chipTabScreen -> showScreenSettings()
                R.id.chipTabBackground -> showBackgroundSettings()
                R.id.chipTabDisliked -> showDislikedSettings()
                R.id.chipTabTools -> showToolsSettings()
                R.id.chipTabAccount -> showAccountSettings()
            }
        }

        // Default tab
        chipGroupTabs.check(R.id.chipTabScreen)
        showScreenSettings()
    }

    private fun refreshCurrentSection() {
        when (chipGroupTabs.checkedChipId) {
            R.id.chipTabScreen -> showScreenSettings()
            R.id.chipTabBackground -> showBackgroundSettings()
            // others don't strictly need refresh on background change
        }
    }

    private fun clearContentArea() {
        contentFrame.removeAllViews()
        // Eliminar cualquier fragmento anterior para evitar solapamientos
        val existing = childFragmentManager.findFragmentById(R.id.settingsContentFrame)
        if (existing != null) {
            childFragmentManager.beginTransaction().remove(existing).commitNow()
        }
    }

    // --- Aa Pantalla ---

    private fun showScreenSettings() {
        clearContentArea()
        val view = layoutInflater.inflate(R.layout.settings_section_screen, contentFrame, false)
        contentFrame.addView(view)
        
        // ... rest of showScreenSettings ...

        val btnSmall = view.findViewById<MaterialButton>(R.id.btnTextSmall)
        val btnNormal = view.findViewById<MaterialButton>(R.id.btnTextNormal)
        val btnLarge = view.findViewById<MaterialButton>(R.id.btnTextLarge)
        val btnExtraLarge = view.findViewById<MaterialButton>(R.id.btnTextExtraLarge)

        fun updateSelectedSize(size: String) {
            val green = Color.parseColor("#1DB954")
            val gray = Color.parseColor("#333333")
            btnSmall.setBackgroundColor(if (size == "Extra pequeño") green else gray)
            btnNormal.setBackgroundColor(if (size == "Normal") green else gray)
            btnLarge.setBackgroundColor(if (size == "Grande") green else gray)
            btnExtraLarge.setBackgroundColor(if (size == "Extra grande") green else gray)
        }

        val currentSize = sessionManager.getTextSize()
        updateSelectedSize(currentSize)

        val sizeListener = View.OnClickListener { v ->
            val newSize = when (v.id) {
                R.id.btnTextSmall -> "Extra pequeño"
                R.id.btnTextNormal -> "Normal"
                R.id.btnTextLarge -> "Grande"
                else -> "Extra grande"
            }
            if (newSize != sessionManager.getTextSize()) {
                sessionManager.setTextSize(newSize)
                updateSelectedSize(newSize)
                requireActivity().recreate()
            }
        }
        btnSmall.setOnClickListener(sizeListener)
        btnNormal.setOnClickListener(sizeListener)
        btnLarge.setOnClickListener(sizeListener)
        btnExtraLarge.setOnClickListener(sizeListener)

        // Font Family List
        val fontLayout = view.findViewById<LinearLayout>(R.id.layoutFontList)
        val fonts = listOf("Default", "Serif", "Monospace")
        fonts.forEach { fontName ->
            val fontView = layoutInflater.inflate(R.layout.item_playlist_pick, fontLayout, false) // Reusar item simple
            val tv = fontView.findViewById<TextView>(R.id.tvPlaylistPickName)
            tv.text = fontName
            tv.typeface = when (fontName) {
                "Serif" -> Typeface.SERIF
                "Monospace" -> Typeface.MONOSPACE
                else -> Typeface.DEFAULT
            }
            if (fontName == sessionManager.getFontFamily()) {
                tv.setTextColor(Color.parseColor("#1DB954"))
            }
            fontView.setOnClickListener {
                if (fontName != sessionManager.getFontFamily()) {
                    sessionManager.setFontFamily(fontName)
                    requireActivity().recreate()
                }
            }
            fontLayout.addView(fontView)
        }
    }

    // --- Fondo ---

    private fun showBackgroundSettings() {
        clearContentArea()
        val view = layoutInflater.inflate(R.layout.settings_section_background, contentFrame, false)
        contentFrame.addView(view)

        val previewLarge = view.findViewById<ImageView>(R.id.viewBgPreviewLarge)
        val subContentFrame = view.findViewById<FrameLayout>(R.id.backgroundSubContentFrame)
        
        val btnSolid = view.findViewById<MaterialButton>(R.id.btnModeSolid)
        val btnGradient = view.findViewById<MaterialButton>(R.id.btnModeGradient)
        val btnImage = view.findViewById<MaterialButton>(R.id.btnModeImage)

        fun updateLargePreview() {
            val mode = sessionManager.getBackgroundMode()
            val alpha = (sessionManager.getBackgroundAlphaPct() * 255) / 100
            previewLarge.setImageDrawable(null)
            previewLarge.rotation = 0f
            when (mode.lowercase()) {
                "gradient" -> previewLarge.background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.parseColor(sessionManager.getBackgroundGradientStart()), Color.parseColor(sessionManager.getBackgroundGradientEnd()))
                ).apply { this.alpha = alpha }
                "image" -> {
                    val uriString = sessionManager.getBackgroundImageUri()?.trim()
                    previewLarge.background = ColorDrawable(Color.DKGRAY).apply { this.alpha = alpha }
                    if (!uriString.isNullOrBlank()) {
                        Glide.with(this).load(uriString).centerCrop().into(previewLarge)
                        previewLarge.rotation = sessionManager.getBackgroundImageRotation().toFloat()
                    }
                }
                else -> previewLarge.background = ColorDrawable(Color.parseColor(sessionManager.getBackgroundSolidColor())).apply { this.alpha = alpha }
            }
        }

        fun attachBackgroundActionsForMode(mode: String) {
            subContentFrame.removeAllViews()
            val container = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 0)
            }

            fun addFullWidth(v: View) {
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = 16
                v.layoutParams = lp
                container.addView(v)
            }

            fun applyAndRefresh() {
                (requireActivity() as? MainActivity)?.applyBackgroundAppearance()
                updateLargePreview()
            }

            // Actualizar estilo de botones principales
            val green = Color.parseColor("#1DB954")
            val gray = Color.parseColor("#333333")
            btnSolid.setBackgroundColor(if (mode == "solid") green else gray)
            btnGradient.setBackgroundColor(if (mode == "gradient") green else gray)
            btnImage.setBackgroundColor(if (mode == "image") green else gray)

            when (mode) {
                "solid" -> {
                    // Colores predefinidos
                    addFullWidth(buildColorSwatchGrid(solidPresetColors) { hex ->
                        sessionManager.setBackgroundMode("solid")
                        sessionManager.setBackgroundSolidColor(hex)
                        applyAndRefresh()
                    })
                    // Selector personalizado
                    addFullWidth(MaterialButton(requireContext()).apply {
                        text = "Elegir color personalizado..."
                        setOnClickListener {
                            ColorPickerDialog.show(requireContext(), sessionManager.getBackgroundSolidColor()) { hex, alpha ->
                                sessionManager.setBackgroundMode("solid")
                                sessionManager.setBackgroundSolidColor(hex)
                                sessionManager.setBackgroundAlphaPct(alpha)
                                applyAndRefresh()
                            }
                        }
                    })
                }
                "gradient" -> {
                    // Degradados predefinidos
                    addFullWidth(buildGradientSwatchGrid(presetGradients) { pair ->
                        sessionManager.setBackgroundMode("gradient")
                        sessionManager.setBackgroundGradientStart(pair.first)
                        sessionManager.setBackgroundGradientEnd(pair.second)
                        applyAndRefresh()
                        attachBackgroundActionsForMode("gradient")
                    })
                    
                    val rowColors = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                    }
                    
                    // Los dos selectores de color
                    rowColors.addView(MaterialButton(requireContext()).apply {
                        text = "Color 1"
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        setOnClickListener {
                            ColorPickerDialog.show(requireContext(), sessionManager.getBackgroundGradientStart()) { hex, _ ->
                                sessionManager.setBackgroundMode("gradient")
                                sessionManager.setBackgroundGradientStart(hex)
                                applyAndRefresh()
                                attachBackgroundActionsForMode("gradient")
                            }
                        }
                    })
                    rowColors.addView(MaterialButton(requireContext()).apply {
                        text = "Color 2"
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 12 }
                        setOnClickListener {
                            ColorPickerDialog.show(requireContext(), sessionManager.getBackgroundGradientEnd()) { hex, _ ->
                                sessionManager.setBackgroundMode("gradient")
                                sessionManager.setBackgroundGradientEnd(hex)
                                applyAndRefresh()
                                attachBackgroundActionsForMode("gradient")
                            }
                        }
                    })
                    addFullWidth(rowColors)
                }
                "image" -> {
                    val d = resources.displayMetrics.density

                    val rowFit = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = (12 * d).toInt(); bottomMargin = (12 * d).toInt() }
                    }
                    val fitOptions = listOf("cover" to "Cubrir", "fit_width" to "Ancho", "fit_height" to "Alto")
                    fitOptions.forEachIndexed { idx, (value, label) ->
                        rowFit.addView(MaterialButton(requireContext(), null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                            text = label
                            val isSelected = sessionManager.getBackgroundImageFitMode() == value
                            if (isSelected) {
                                setBackgroundColor(Color.parseColor("#1DB954"))
                                setTextColor(Color.BLACK)
                                strokeWidth = 0
                            } else {
                                setBackgroundColor(Color.TRANSPARENT)
                                setTextColor(Color.WHITE)
                                setStrokeColor(ColorStateList.valueOf(Color.parseColor("#33FFFFFF")))
                                strokeWidth = (1 * d).toInt()
                            }
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                                if (idx > 0) leftMargin = (8 * d).toInt()
                            }
                            setOnClickListener {
                                sessionManager.setBackgroundImageFitMode(value)
                                (requireActivity() as? MainActivity)?.applyBackgroundAppearance()
                                attachBackgroundActionsForMode("image")
                            }
                        })
                    }
                    addFullWidth(rowFit)

                    val rowImageActions = LinearLayout(requireContext()).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }

                    rowImageActions.addView(MaterialButton(requireContext()).apply {
                        text = "Elegir imagen"
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        setOnClickListener { pickBackgroundImage.launch("image/*") }
                    })

                    // Rotar 45°: solo icono
                    rowImageActions.addView(ImageButton(requireContext()).apply {
                        setImageResource(android.R.drawable.ic_menu_rotate)
                        contentDescription = "Rotar"
                        setImageTintList(ColorStateList.valueOf(Color.WHITE))
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(Color.parseColor("#33FFFFFF"))
                        }
                        layoutParams = LinearLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt()).apply { leftMargin = 12 }
                        setOnClickListener { rotatePickedBackgroundImage() }
                    })

                    // Selector del color DETRÁS
                    rowImageActions.addView(ImageButton(requireContext()).apply {
                        contentDescription = "Color fondo"
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(try { Color.parseColor(sessionManager.getBackgroundOverlayColor()) } catch (_: Exception) { Color.BLACK })
                            setStroke(2, Color.WHITE)
                        }
                        layoutParams = LinearLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt()).apply { leftMargin = 12 }
                        setOnClickListener {
                            ColorPickerDialog.show(requireContext(), sessionManager.getBackgroundOverlayColor()) { hex, alpha ->
                                sessionManager.setBackgroundOverlayColor(hex)
                                sessionManager.setBackgroundOverlayAlphaPct(alpha)
                                applyAndRefresh()
                                attachBackgroundActionsForMode("image")
                            }
                        }
                    })
                    addFullWidth(rowImageActions)
                }
            }
            subContentFrame.addView(container)
        }

        btnSolid.setOnClickListener {
            sessionManager.setBackgroundMode("solid")
            attachBackgroundActionsForMode("solid")
            (requireActivity() as? MainActivity)?.applyBackgroundAppearance()
            updateLargePreview()
        }
        btnGradient.setOnClickListener {
            sessionManager.setBackgroundMode("gradient")
            attachBackgroundActionsForMode("gradient")
            (requireActivity() as? MainActivity)?.applyBackgroundAppearance()
            updateLargePreview()
        }
        btnImage.setOnClickListener {
            sessionManager.setBackgroundMode("image")
            attachBackgroundActionsForMode("image")
            (requireActivity() as? MainActivity)?.applyBackgroundAppearance()
            updateLargePreview()
        }

        // Estado inicial
        val currentMode = sessionManager.getBackgroundMode()
        attachBackgroundActionsForMode(currentMode)

        val seekAlpha = view.findViewById<SeekBar>(R.id.seekBgAlphaRedesign)
        val tvValAlpha = view.findViewById<TextView>(R.id.tvValueAlpha)
        seekAlpha.progress = sessionManager.getBackgroundAlphaPct()
        tvValAlpha.text = "${seekAlpha.progress}%"
        seekAlpha.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                if (!f) return
                sessionManager.setBackgroundAlphaPct(p)
                tvValAlpha.text = "$p%"
                (requireActivity() as? MainActivity)?.applyBackgroundAppearance()
                updateLargePreview()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        val seekBlur = view.findViewById<SeekBar>(R.id.seekBgBlurRedesign)
        val tvValBlur = view.findViewById<TextView>(R.id.tvValueBlur)
        seekBlur.progress = sessionManager.getBackgroundBlur()
        tvValBlur.text = "${seekBlur.progress}px"
        seekBlur.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                if (!f) return
                sessionManager.setBackgroundBlur(p)
                tvValBlur.text = "${p}px"
                (requireActivity() as? MainActivity)?.applyBackgroundAppearance()
                updateLargePreview()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })

        updateLargePreview()
    }

    private fun showPresetGradientsDialog() {
        val dialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.fragment_playlists, null)
        view.findViewById<View>(R.id.btnAIPlaylist).visibility = View.GONE
        view.findViewById<View>(R.id.btnNewPlaylist).visibility = View.GONE
        view.findViewById<TextView>(R.id.tvEmptyPlaylists).apply {
            visibility = View.VISIBLE
            text = "Selecciona un degradado"
            setTextColor(Color.WHITE)
            textSize = 18f
        }
        
        val rv = view.findViewById<RecyclerView>(R.id.rvPlaylists)
        val presets = listOf(
            PresetGradient("Aurora", "#4A148C", "#F06292"),
            PresetGradient("Océano", "#0D47A1", "#26C6DA"),
            PresetGradient("Atardecer", "#FF6F00", "#EC407A"),
            PresetGradient("Bosque", "#1B5E20", "#29B6F6"),
            PresetGradient("Noche", "#121212", "#434343"),
            PresetGradient("Personalizado...", "#000000", "#FFFFFF")
        )
        
        rv.adapter = GradientAdapter(presets) { item ->
            if (item.name == "Personalizado...") {
                pickCustomGradient()
            } else {
                sessionManager.setBackgroundMode("gradient")
                sessionManager.setBackgroundGradientStart(item.startColor)
                sessionManager.setBackgroundGradientEnd(item.endColor)
                (requireActivity() as? MainActivity)?.applyBackgroundAppearance()
                refreshCurrentSection()
            }
            dialog.dismiss()
        }
        rv.layoutManager = GridLayoutManager(requireContext(), 2)
        dialog.setContentView(view)
        dialog.show()
    }

    private fun pickCustomGradient() {
        ColorPickerDialog.show(requireContext(), sessionManager.getBackgroundGradientStart()) { startHex, _ ->
            ColorPickerDialog.show(requireContext(), sessionManager.getBackgroundGradientEnd()) { endHex, _ ->
                sessionManager.setBackgroundMode("gradient")
                sessionManager.setBackgroundGradientStart(startHex)
                sessionManager.setBackgroundGradientEnd(endHex)
                (requireActivity() as? MainActivity)?.applyBackgroundAppearance()
                refreshCurrentSection()
                Toast.makeText(requireContext(), "Degradado guardado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** Gira la imagen 45° más. Solo guarda los grados acumulados: la rotación
     *  se aplica desde la imagen ORIGINAL (sin re-encodar ni re-comprimir), así
     *  que nunca se degrada, no encoge y no genera esquinas negras. */
    private fun rotatePickedBackgroundImage() {
        if (sessionManager.getBackgroundImageUri().isNullOrBlank()) {
            Toast.makeText(requireContext(), "Primero selecciona una imagen de fondo", Toast.LENGTH_SHORT).show()
            return
        }
        val rotation = (sessionManager.getBackgroundImageRotation() + 45) % 360
        sessionManager.setBackgroundImageRotation(rotation)
        sessionManager.setBackgroundMode("image")
        (requireActivity() as? MainActivity)?.applyBackgroundAppearance()
        refreshCurrentSection()
        Toast.makeText(requireContext(), "Imagen girada ${rotation}°", Toast.LENGTH_SHORT).show()
    }

    // --- Paletas de fondo ---

    private val solidPresetColors = listOf(
        "#121212", "#000000", "#FFFFFF", "#1DB954", "#E91E63", "#9C27B0",
        "#673AB7", "#3F51B5", "#2196F3", "#00BCD4", "#009688", "#4CAF50",
        "#CDDC39", "#FFC107", "#FF9800", "#FF5722", "#795548", "#607D8B"
    )

    private val presetGradients = listOf(
        "#4A148C" to "#F06292", // Aurora
        "#0D47A1" to "#26C6DA", // Océano
        "#FF6F00" to "#EC407A", // Atardecer
        "#1B5E20" to "#29B6F6", // Bosque
        "#121212" to "#434343", // Noche
        "#FF512F" to "#DD2476", // Fuego
        "#7F00FF" to "#E100FF", // Púrpura
        "#11998E" to "#38EF7D"  // Selva
    )

    /** Cuadrícula de colores predefinidos (círculos seleccionables). */
    private fun buildColorSwatchGrid(colors: List<String>, onPick: (String) -> Unit): View {
        val d = resources.displayMetrics.density
        val grid = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        colors.chunked(6).forEach { rowColors ->
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            rowColors.forEach { hex ->
                val sw = View(requireContext())
                sw.layoutParams = LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()).apply {
                    rightMargin = (8 * d).toInt()
                    bottomMargin = (8 * d).toInt()
                }
                sw.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(try { Color.parseColor(hex) } catch (_: Exception) { Color.GRAY })
                    setStroke(2, Color.parseColor("#33FFFFFF"))
                }
                sw.setOnClickListener { onPick(hex) }
                row.addView(sw)
            }
            grid.addView(row)
        }
        return grid
    }

    /** Cuadrícula de degradados predefinidos (círculos de dos colores). */
    private fun buildGradientSwatchGrid(pairs: List<Pair<String, String>>, onPick: (Pair<String, String>) -> Unit): View {
        val d = resources.displayMetrics.density
        val grid = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        pairs.chunked(6).forEach { rowPairs ->
            val row = LinearLayout(requireContext()).apply { orientation = LinearLayout.HORIZONTAL }
            rowPairs.forEach { pair ->
                val sw = View(requireContext())
                sw.layoutParams = LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()).apply {
                    rightMargin = (8 * d).toInt()
                    bottomMargin = (8 * d).toInt()
                }
                sw.background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(
                        try { Color.parseColor(pair.first) } catch (_: Exception) { Color.GRAY },
                        try { Color.parseColor(pair.second) } catch (_: Exception) { Color.GRAY }
                    )
                ).apply {
                    shape = GradientDrawable.OVAL
                    setStroke(2, Color.parseColor("#33FFFFFF"))
                }
                sw.setOnClickListener { onPick(pair) }
                row.addView(sw)
            }
            grid.addView(row)
        }
        return grid
    }

    private fun showConfigServerIp() {
        val current = sessionManager.getServerBaseUrl().replace("/$".toRegex(), "")
        val editText = EditText(requireContext()).apply {
            setText(current.removePrefix("http://").removePrefix("https://"))
            hint = "ej: 192.168.1.152"
            inputType = InputType.TYPE_CLASS_TEXT
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Servidor (IP del PC)")
            .setMessage("Usa la IP que muestra 'npm run dev' en tu PC, sin http://. El puerto 5002 se añade automáticamente.")
            .setView(editText)
            .setPositiveButton("Guardar y probar") { _, _ ->
                val raw = editText.text.toString().trim()
                if (raw.isEmpty()) {
                    Toast.makeText(requireContext(), "Escribe una IP", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val url = ApiConfig.buildBaseUrl(raw)
                sessionManager.setServerBaseUrl(url)

                lifecycleScope.launch {
                    val ok = ServerReachability.isServerReachable()
                    val msg = if (ok) {
                        "Servidor alcanzable ✓"
                    } else {
                        "No se pudo conectar a $url. Verifica que mirepo esté encendido y que el móvil esté en la misma WiFi."
                    }
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // --- No me gusta ---

    private fun showDislikedSettings() {
        clearContentArea()
        val fragment = DislikedSongsAdminFragment()
        childFragmentManager.beginTransaction()
            .replace(R.id.settingsContentFrame, fragment)
            .commit()
    }

    // --- Herramientas ---

    private fun showToolsSettings() {
        clearContentArea()
        val view = layoutInflater.inflate(R.layout.settings_section_tools, contentFrame, false)
        
        val btnDJ = view.findViewById<MaterialButton>(R.id.btnStartDJSession)
        if (sessionManager.isDJ()) {
            btnDJ.visibility = View.VISIBLE
            btnDJ.setOnClickListener {
                Toast.makeText(requireContext(), "Modo DJ: Transmitiendo...", Toast.LENGTH_SHORT).show()
                // Aquí iría la lógica de broadcast del DJ
            }
        }
        contentFrame.addView(view)

        view.findViewById<MaterialButton>(R.id.btnRescanLibraryRedesign).setOnClickListener {
            RescanManager.triggerRescan(viewLifecycleOwner.lifecycleScope)
        }

        view.findViewById<MaterialButton>(R.id.btnRescanPodcasts).setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val resp = RetrofitClient.api.rescanPodcasts()
                    if (resp.isSuccessful) {
                        Toast.makeText(requireContext(), "Reescaneo de podcasts iniciado", Toast.LENGTH_SHORT).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(requireContext(), "Error al iniciar reescaneo", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val swMix = view.findViewById<MaterialSwitch>(R.id.swMixPodcastsRedesign)
        swMix.isChecked = sessionManager.isPodcastMixingEnabled()
        swMix.setOnCheckedChangeListener { _, isChecked -> sessionManager.setPodcastMixingEnabled(isChecked) }

        view.findViewById<MaterialButton>(R.id.btnOpenAdminPanelRedesign).setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.container, AdminPanelFragment())
                .addToBackStack(null)
                .commit()
        }

        view.findViewById<MaterialButton>(R.id.btnViewLogRedesign).setOnClickListener {
            showLogDialog()
        }
    }

    private fun showLogDialog() {
        val logFile = File(requireContext().filesDir, "app_debug_log.txt")
        val content = if (logFile.exists()) logFile.readText() else "No hay registros todavía."
        AlertDialog.Builder(requireContext())
            .setTitle("Registro de Depuración")
            .setMessage(content)
            .setPositiveButton("Cerrar", null)
            .setNeutralButton("Borrar Log") { _, _ ->
                logFile.delete()
                Toast.makeText(requireContext(), "Log borrado", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // --- Cuenta ---

    private fun showAccountSettings() {
        clearContentArea()
        val view = layoutInflater.inflate(R.layout.settings_section_account, contentFrame, false)
        contentFrame.addView(view)

        view.findViewById<MaterialButton>(R.id.btnServerIpRedesign).setOnClickListener {
            showConfigServerIp()
        }

        view.findViewById<MaterialButton>(R.id.btnLogoutRedesign).setOnClickListener {
            sessionManager.clearSession()
            Intent(requireContext(), LoginActivity::class.java).also {
                it.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                startActivity(it)
            }
        }
    }
}
