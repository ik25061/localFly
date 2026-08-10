package com.example.localfly.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.localfly.DownloadManagerHelper
import com.example.localfly.DownloadedSong
import com.example.localfly.DownloadedSongAdapter
import com.example.localfly.MainActivity
import com.example.localfly.R

class DownloadsFragment : Fragment() {

    private lateinit var rvDownloads: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var downloadHelper: DownloadManagerHelper
    private lateinit var adapter: DownloadedSongAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_downloads, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        downloadHelper = DownloadManagerHelper(requireContext())
        rvDownloads = view.findViewById(R.id.rvDownloads)
        tvEmpty = view.findViewById(R.id.tvEmptyDownloads)

        adapter = DownloadedSongAdapter(
            mutableListOf(),
            onItemClick = { downloaded -> playDownloaded(downloaded) },
            onDeleteClick = { downloaded ->
                downloadHelper.removeDownload(downloaded.id)
                loadDownloads()
            }
        )
        rvDownloads.layoutManager = LinearLayoutManager(requireContext())
        rvDownloads.adapter = adapter

        loadDownloads()
    }

    private fun loadDownloads() {
        val items = downloadHelper.getDownloadedSongs()
        adapter.updateItems(items)
        tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        rvDownloads.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun playDownloaded(downloaded: DownloadedSong) {
        (requireActivity() as? MainActivity)?.playDownloadedSong(downloaded)
    }

    override fun onResume() {
        super.onResume()
        // Refresca por si se borró/descargó algo desde otra pantalla
        loadDownloads()
    }
}