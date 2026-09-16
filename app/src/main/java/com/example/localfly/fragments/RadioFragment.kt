package com.example.localfly.fragments

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.localfly.R
import com.example.localfly.network.RadioManager
import com.example.localfly.network.RadioStation
import com.example.localfly.network.RetrofitClient
import com.example.localfly.network.SessionManager
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.launch

/**
 * Pantalla "Radio en vivo": emitir lo que estás escuchando (host) o unirte a
 * la radio de otro usuario (oyente). La sincronización real la hace
 * [com.example.localfly.network.RadioManager] junto a PlaybackService.
 */
@UnstableApi
class RadioFragment : Fragment() {

    private lateinit var sessionManager: SessionManager
    private lateinit var btnRadioAction: MaterialButton
    private lateinit var btnBack: ImageButton
    private lateinit var btnRefresh: ImageButton
    private lateinit var rvStations: RecyclerView
    private lateinit var pbRadio: View
    private lateinit var tvRadioEmpty: TextView
    private lateinit var adapter: RadioStationAdapter

    private val stations = mutableListOf<RadioStation>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        inflater.inflate(R.layout.fragment_radio, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sessionManager = SessionManager(requireContext())

        btnBack = view.findViewById(R.id.btnBack)
        btnRefresh = view.findViewById(R.id.btnRefresh)
        btnRadioAction = view.findViewById(R.id.btnRadioAction)
        rvStations = view.findViewById(R.id.rvRadioStations)
        pbRadio = view.findViewById(R.id.pbRadio)
        tvRadioEmpty = view.findViewById(R.id.tvRadioEmpty)

        adapter = RadioStationAdapter(
            onJoin = { station -> joinStation(station) },
            isJoined = { station -> RadioManager.isListener && RadioManager.currentHostId == station.hostId }
        )
        rvStations.layoutManager = LinearLayoutManager(requireContext())
        rvStations.adapter = adapter

        btnBack.setOnClickListener { parentFragmentManager.popBackStack() }
        btnRefresh.setOnClickListener { loadStations() }
        btnRadioAction.setOnClickListener { toggleBroadcast() }

        // Refrescar la lista cuando cambie el modo radio (emitir / dejar de escuchar)
        RadioManager.onRadioStateChanged = { _, _ ->
            view.post { refreshActionState(); loadStations() }
        }

        refreshActionState()
        loadStations()
    }

    override fun onResume() {
        super.onResume()
        refreshActionState()
        loadStations()
    }

    override fun onDestroyView() {
        RadioManager.onRadioStateChanged = null
        super.onDestroyView()
    }

    private fun refreshActionState() {
        when {
            RadioManager.isHost -> {
                btnRadioAction.text = "🔴 Detener mi radio"
                btnRadioAction.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#D32F2F"))
            }
            RadioManager.isListener -> {
                btnRadioAction.text = "✋ Dejar de escuchar"
                btnRadioAction.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FFA500"))
            }
            else -> {
                btnRadioAction.text = "📡 Emitir mi música"
                btnRadioAction.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#1DB954"))
            }
        }
    }

    private fun toggleBroadcast() {
        val service = (activity as? com.example.localfly.MainActivity)?.playbackService
        when {
            RadioManager.isHost -> {
                service?.stopRadioBroadcast()
                Toast.makeText(requireContext(), "Tu radio se detuvo", Toast.LENGTH_SHORT).show()
            }
            RadioManager.isListener -> {
                service?.leaveRadio()
                Toast.makeText(requireContext(), "Dejaste de escuchar la radio", Toast.LENGTH_SHORT).show()
            }
            else -> {
                val started = service?.startRadioBroadcast() ?: false
                Toast.makeText(
                    requireContext(),
                    if (started) "Estás emitiendo tu radio en vivo" else "Reproduce una canción primero",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        refreshActionState()
    }

    private fun joinStation(station: RadioStation) {
        if ((activity as? com.example.localfly.MainActivity)?.playbackService == null) {
            Toast.makeText(requireContext(), "Inicia la reproducción primero", Toast.LENGTH_SHORT).show()
            return
        }
        RadioManager.joinAsListener(station.hostId)
        Toast.makeText(requireContext(), "Escuchando la radio de ${station.hostName ?: "otro usuario"}", Toast.LENGTH_SHORT).show()
        refreshActionState()
        adapter.notifyDataSetChanged()
    }

    private fun loadStations() {
        pbRadio.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.getRadioStations()
                val list = response.body()?.stations.orEmpty()
                    .filter { it.hostId != sessionManager.getUserId() }
                stations.clear()
                stations.addAll(list)
                adapter.notifyDataSetChanged()
                tvRadioEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                stations.clear()
                adapter.notifyDataSetChanged()
                tvRadioEmpty.visibility = View.VISIBLE
            } finally {
                pbRadio.visibility = View.GONE
            }
        }
    }

    /** Lista simple de radios activas con botón "Escuchar". */
    private inner class RadioStationAdapter(
        val onJoin: (RadioStation) -> Unit,
        val isJoined: (RadioStation) -> Boolean
    ) : RecyclerView.Adapter<RadioStationAdapter.Holder>() {

        inner class Holder(v: View) : RecyclerView.ViewHolder(v) {
            val ivIcon: ImageView = v.findViewById(R.id.ivRadioIcon)
            val tvHost: TextView = v.findViewById(R.id.tvRadioHost)
            val tvSong: TextView = v.findViewById(R.id.tvRadioSong)
            val btnListen: MaterialButton = v.findViewById(R.id.btnListen)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(layoutInflater.inflate(R.layout.item_radio_station, parent, false))

        override fun getItemCount(): Int = stations.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val st = stations[position]
            holder.tvHost.text = st.hostName ?: "Usuario ${st.hostId.take(8)}"
            holder.tvSong.text = buildString {
                append(st.title)
                st.artist?.let { append(" · $it") }
                if (st.listeners > 0) append("  (${st.listeners} 👥)")
            }
            
            // Indicador de voz activa (DJ hablando)
            if (st.isVoiceActive) {
                holder.ivIcon.setImageResource(R.drawable.ic_mic)
                holder.ivIcon.imageTintList = ColorStateList.valueOf(Color.parseColor("#FF5252"))
            } else {
                holder.ivIcon.setImageResource(R.drawable.ic_radio)
                holder.ivIcon.imageTintList = ColorStateList.valueOf(Color.WHITE)
            }

            val joined = isJoined(st)
            holder.btnListen.text = if (joined) "Escuchando" else "Escuchar"
            holder.btnListen.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (joined) android.graphics.Color.parseColor("#FFA500") else android.graphics.Color.parseColor("#1DB954")
            )
            holder.btnListen.setOnClickListener { onJoin(st) }
            holder.itemView.setOnClickListener { onJoin(st) }
        }
    }
}

