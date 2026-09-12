package com.example.localfly.fragments

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
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

    // --- Aa Pantalla ---

    private fun showScreenSettings() {
        val view = layoutInflater.inflate(R.layout.settings_section_screen, contentFrame, false)
        contentFrame.removeAllViews()
        contentFrame.addView(view)

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
        val view = layoutInflater.inflate(R.layout.settings_section_background, contentFrame, false)
        contentFrame.removeAllViews()
        contentFrame.addView(view)

        val previewLarge = view.findViewById<View>(R.id.viewBgPreviewLarge)
        val subContentFrame = view.findViewById<FrameLayout>(R.id.backgroundSubContentFrame)
        val chipGroupSub = view.findViewById<ChipGroup>(R.id.chipGroupBackgroundSubTabs)

        fun updateLargePreview() {
            val mode = sessionManager.getBackgroundMode()
            val alpha = (sessionManager.getBackgroundAlphaPct() * 255) / 100
            val drawable = when (mode.lowercase()) {
                "gradient" -> GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.parseColor(sessionManager.getBackgroundGradientStart()), Color.parseColor(sessionManager.getBackgroundGradientEnd()))
                )
                "image" -> GradientDrawable().apply { setColor(Color.DKGRAY) }
                else -> GradientDrawable().apply { setColor(Color.parseColor(sessionManager.getBackgroundSolidColor())) }
            }
            drawable.alpha = alpha
            previewLarge.background = drawable
        }

        fun configureOverlayButton(): MaterialButton {
            val btnOverlay = MaterialButton(requireContext()).apply {
                text = "Color de superposición"
                setOnClickListener {
                    ColorPickerDialog.show(requireContext(), sessionManager.getBackgroundOverlayColor()) { hex, overlayAlpha ->
                        sessionManager.setBackgroundOverlayColor(hex)
                        sessionManager.setBackgroundOverlayAlphaPct(overlayAlpha)
                        (requireActivity() as? MainActivity)?.applyBackgroundAppearance()
                        updateLargePreview()
                    }
                }
            }
            return btnOverlay
        }

        fun attachBackgroundActionsForMode(mode: String) {
            subContentFrame.removeAllViews()
            val buttonList = mutableListOf<MaterialButton>()

            when (mode) {
                "solid" -> {
                    buttonList += MaterialButton(requireContext()).apply {
                        text = "Elegir color sólido"
                        setOnClickListener {
                            ColorPickerDialog.show(requireContext(), sessionManager.getBackgroundSolidColor()) { hex, alpha ->
                                sessionManager.setBackgroundMode("solid")
                                sessionManager.setBackgroundSolidColor(hex)
                                sessionManager.setBackgroundAlphaPct(alpha)
                                (requireActivity() as? MainActivity)?.applyBackgroundAppearance()
                                updateLargePreview()
                            }
                        }
                    }
                }
                "gradient" -> {
                    buttonList += MaterialButton(requireContext()).apply {
                        text = "Configurar degradado"
                        setOnClickListener { showPresetGradientsDialog() }
                    }
                }
                "image" -> {
                    buttonList += MaterialButton(requireContext()).apply {
                        text = "Seleccionar imagen"
                        setOnClickListener { pickBackgroundImage.launch("image/*") }
                    }
                    buttonList += MaterialButton(requireContext()).apply {
                        text = "Rotar imagen"
                        setOnClickListener { rotatePickedBackgroundImage() }
                    }
                }
            }

            buttonList += configureOverlayButton()
            val container = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 8, 0, 0)
            }
            buttonList.forEach { button ->
                val lp = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                lp.bottomMargin = 12
                button.layoutParams = lp
                container.addView(button)
            }
            subContentFrame.addView(container)
        }

        chipGroupSub.setOnCheckedStateChangeListener { _, checkedIds ->
            val mode = when (checkedIds.firstOrNull()) {
                R.id.chipSubTabGradient -> "gradient"
                R.id.chipSubTabImage -> "image"
                else -> "solid"
            }
            sessionManager.setBackgroundMode(mode)
            attachBackgroundActionsForMode(mode)
        }

        when (sessionManager.getBackgroundMode()) {
            "gradient" -> chipGroupSub.check(R.id.chipSubTabGradient)
            "image" -> chipGroupSub.check(R.id.chipSubTabImage)
            else -> chipGroupSub.check(R.id.chipSubTabSolid)
        }

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

    private fun rotatePickedBackgroundImage() {
        val uriString = sessionManager.getBackgroundImageUri() ?: run {
            Toast.makeText(requireContext(), "Primero selecciona una imagen de fondo", Toast.LENGTH_SHORT).show()
            return
        }

        val currentUri = Uri.parse(uriString)
        try {
            val inputStream = requireContext().contentResolver.openInputStream(currentUri)
            val original = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()
            if (original == null) {
                Toast.makeText(requireContext(), "No se pudo leer la imagen actual", Toast.LENGTH_SHORT).show()
                return
            }

            val rotation = (sessionManager.getBackgroundImageRotation() + 45) % 360
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
            if (rotated != original) original.recycle()

            val file = File(requireContext().cacheDir, "background_rotated_${System.currentTimeMillis()}.jpg")
            FileOutputStream(file).use { out ->
                rotated.compress(Bitmap.CompressFormat.JPEG, 92, out)
            }
            rotated.recycle()

            sessionManager.setBackgroundImageRotation(rotation)
            sessionManager.setBackgroundImageUri(Uri.fromFile(file).toString())
            sessionManager.setBackgroundMode("image")
            (requireActivity() as? MainActivity)?.applyBackgroundAppearance()
            refreshCurrentSection()
            Toast.makeText(requireContext(), "Imagen girada ${rotation}°", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "No se pudo rotar la imagen: ${e.message}", Toast.LENGTH_SHORT).show()
        }
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

    private fun showConfigServerIp() {
        val current = sessionManager.getServerBaseUrl().replace("/$".toRegex(), "")
        val editText = EditText(requireContext()).apply {
            setText(current.removePrefix("http://").removePrefix("https://"))
            hint = "ej: 192.168.1.152"
            inputType = android.text.InputType.TYPE_CLASS_TEXT
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
        val fragment = DislikedSongsAdminFragment()
        childFragmentManager.beginTransaction()
            .replace(R.id.settingsContentFrame, fragment)
            .commit()
    }

    // --- Herramientas ---

    private fun showToolsSettings() {
        val view = layoutInflater.inflate(R.layout.settings_section_tools, contentFrame, false)
        contentFrame.removeAllViews()
        contentFrame.addView(view)

        view.findViewById<MaterialButton>(R.id.btnRescanLibraryRedesign).setOnClickListener {
            RescanManager.triggerRescan(viewLifecycleOwner.lifecycleScope)
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
        val view = layoutInflater.inflate(R.layout.settings_section_account, contentFrame, false)
        contentFrame.removeAllViews()
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
