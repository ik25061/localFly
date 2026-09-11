package com.example.localfly.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.localfly.R
import com.example.localfly.adapters.HorizontalCardAdapter
import com.example.localfly.network.Podcast
import com.example.localfly.network.RetrofitClient
import com.example.localfly.network.SessionManager
import kotlinx.coroutines.launch

class PodcastsFragment : Fragment() {

    private lateinit var rvPodcasts: RecyclerView
    private lateinit var adapter: HorizontalCardAdapter
    private lateinit var progressBar: ProgressBar
    private lateinit var sessionManager: SessionManager

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_podcasts_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sessionManager = SessionManager(requireContext())

        rvPodcasts = view.findViewById(R.id.rvPodcastsList)
        progressBar = view.findViewById(R.id.progressPodcasts)
        
        view.findViewById<ImageButton>(R.id.btnBackPodcasts).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        adapter = HorizontalCardAdapter(emptyList()) { item ->
            if (item is Podcast) {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, PodcastDetailFragment.newInstance(item.id, item.title))
                    .addToBackStack(null)
                    .commit()
            }
        }
        
        rvPodcasts.layoutManager = GridLayoutManager(requireContext(), 2)
        rvPodcasts.adapter = adapter

        loadPodcasts()
    }

    private fun loadPodcasts() {
        progressBar.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val resp = RetrofitClient.api.getPodcasts(sessionManager.getUserId())
                if (!isAdded) return@launch
                progressBar.visibility = View.GONE
                if (resp.isSuccessful && resp.body() != null) {
                    adapter.updateItems(resp.body()!!.podcasts)
                }
            } catch (e: Exception) {
                if (isAdded) {
                    progressBar.visibility = View.GONE
                    Toast.makeText(requireContext(), "Error al cargar podcasts", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
