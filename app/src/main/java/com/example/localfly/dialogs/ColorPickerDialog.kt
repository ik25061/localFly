package com.example.localfly.dialogs

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import com.example.localfly.R
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import java.util.Locale

object ColorPickerDialog {

    fun show(
        context: Context,
        initialColorHex: String,
        onColorSelected: (String, Int) -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_color_picker, null)

        val viewPreview = view.findViewById<View>(R.id.viewColorPreview)
        val tvHex = view.findViewById<TextView>(R.id.tvHexCode)
        
        val seekRed = view.findViewById<SeekBar>(R.id.seekRed)
        val seekGreen = view.findViewById<SeekBar>(R.id.seekGreen)
        val seekBlue = view.findViewById<SeekBar>(R.id.seekBlue)
        val seekAlpha = view.findViewById<SeekBar>(R.id.seekAlpha)

        val tvRed = view.findViewById<TextView>(R.id.tvRedValue)
        val tvGreen = view.findViewById<TextView>(R.id.tvGreenValue)
        val tvBlue = view.findViewById<TextView>(R.id.tvBlueValue)
        val tvAlpha = view.findViewById<TextView>(R.id.tvAlphaValue)

        // Initial state
        val startColor = try { Color.parseColor(initialColorHex) } catch (e: Exception) { Color.WHITE }
        
        seekRed.progress = Color.red(startColor)
        seekGreen.progress = Color.green(startColor)
        seekBlue.progress = Color.blue(startColor)
        seekAlpha.progress = 100

        fun updateUI() {
            val r = seekRed.progress
            val g = seekGreen.progress
            val b = seekBlue.progress
            val a = seekAlpha.progress
            
            val currentColor = Color.rgb(r, g, b)
            val hex = String.format(Locale.getDefault(), "#%02X%02X%02X", r, g, b)
            
            tvHex.text = hex
            tvRed.text = r.toString()
            tvGreen.text = g.toString()
            tvBlue.text = b.toString()
            tvAlpha.text = "$a%"
            
            // Preview
            val previewDrawable = GradientDrawable()
            previewDrawable.shape = GradientDrawable.OVAL
            previewDrawable.setColor(currentColor)
            previewDrawable.alpha = (a * 255) / 100
            viewPreview.background = previewDrawable

            // Update Sliders Gradients (iOS Style)
            updateSliderGradient(seekRed, Color.rgb(0, g, b), Color.rgb(255, g, b))
            updateSliderGradient(seekGreen, Color.rgb(r, 0, b), Color.rgb(r, 255, b))
            updateSliderGradient(seekBlue, Color.rgb(r, g, 0), Color.rgb(r, g, 255))
            
            // Alpha slider background (checkerboard would be better, but let's use a simple gradient for now)
            updateSliderGradient(seekAlpha, Color.argb(0, r, g, b), Color.rgb(r, g, b))
        }

        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { updateUI() }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        }

        seekRed.setOnSeekBarChangeListener(listener)
        seekGreen.setOnSeekBarChangeListener(listener)
        seekBlue.setOnSeekBarChangeListener(listener)
        seekAlpha.setOnSeekBarChangeListener(listener)

        updateUI()

        view.findViewById<ImageButton>(R.id.btnClosePicker).setOnClickListener { dialog.dismiss() }
        view.findViewById<MaterialButton>(R.id.btnApplyColor).setOnClickListener {
            onColorSelected(tvHex.text.toString(), seekAlpha.progress)
            dialog.dismiss()
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun updateSliderGradient(seekBar: SeekBar, startColor: Int, endColor: Int) {
        val gd = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(startColor, endColor))
        gd.cornerRadius = 30f
        gd.setStroke(2, Color.parseColor("#33FFFFFF"))
        
        // Use a LayerDrawable to keep it simple and avoid overriding thumb
        seekBar.progressDrawable = gd
    }
}
