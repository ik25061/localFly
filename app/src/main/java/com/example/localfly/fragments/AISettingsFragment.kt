package com.example.localfly.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.example.localfly.R
import com.example.localfly.ai.AIWeightsStore
import com.google.android.material.materialswitch.MaterialSwitch

class AISettingsFragment : Fragment() {

    private lateinit var weightsStore: AIWeightsStore

    private val factorLabels = mapOf(
        AIWeightsStore.FACTOR_FAV_ARTIST to "Artista favorito",
        AIWeightsStore.FACTOR_LIKED_ARTIST to "Ya te gustan otras de ese artista",
        AIWeightsStore.FACTOR_DECADE_MATCH to "Década que sueles escuchar",
        AIWeightsStore.FACTOR_SEED_ARTIST to "Mismo artista que la canción actual",
        AIWeightsStore.FACTOR_SEED_DECADE to "Años cercanos a la canción actual",
        AIWeightsStore.FACTOR_HAS_COVER to "Tiene portada"
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_ai_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        weightsStore = AIWeightsStore(requireContext())

        view.findViewById<View>(R.id.btnBackAiSettings).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        val swOnlineLearning = view.findViewById<MaterialSwitch>(R.id.swOnlineLearning)
        swOnlineLearning.isChecked = weightsStore.isOnlineLearningEnabled()
        swOnlineLearning.setOnCheckedChangeListener { _, isChecked ->
            weightsStore.setOnlineLearningEnabled(isChecked)
        }

        val tvSongCountLabel = view.findViewById<TextView>(R.id.tvSongCountLabel)
        val seekSongCount = view.findViewById<SeekBar>(R.id.seekSongCount)
        val currentCount = weightsStore.getPlaylistSongCount()
        seekSongCount.progress = (currentCount - 10).coerceIn(0, 40)
        tvSongCountLabel.text = "Canciones por lista generada: $currentCount"
        seekSongCount.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val count = progress + 10
                tvSongCountLabel.text = "Canciones por lista generada: $count"
                if (fromUser) weightsStore.setPlaylistSongCount(count)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        buildWeightSliders(view)

        view.findViewById<View>(R.id.btnResetLearning).setOnClickListener {
            weightsStore.resetWeights()
            buildWeightSliders(view)
        }
    }

    private fun buildWeightSliders(rootView: View) {
        val container = rootView.findViewById<android.widget.LinearLayout>(R.id.layoutWeightSliders)
        container.removeAllViews()

        for (factor in AIWeightsStore.ALL_FACTORS) {
            val itemView = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_ai_weight_slider, container, false)

            val tvLabel = itemView.findViewById<TextView>(R.id.tvWeightLabel)
            val seek = itemView.findViewById<SeekBar>(R.id.seekWeight)

            val label = factorLabels[factor] ?: factor
            val currentWeight = weightsStore.getWeight(factor)
            seek.progress = weightToProgress(currentWeight)
            tvLabel.text = "$label (${"%.1f".format(currentWeight)}x)"

            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val newWeight = progressToWeight(progress)
                    tvLabel.text = "$label (${"%.1f".format(newWeight)}x)"
                    if (fromUser) weightsStore.setWeight(factor, newWeight)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })

            container.addView(itemView)
        }
    }

    private fun weightToProgress(weight: Float): Int = (((weight - 0.2f) / 0.1f).toInt()).coerceIn(0, 23)
    private fun progressToWeight(progress: Int): Float = 0.2f + (progress * 0.1f)
}
