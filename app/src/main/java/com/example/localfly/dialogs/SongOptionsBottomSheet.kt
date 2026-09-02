package com.example.localfly.dialogs

// Colocar en: app/src/main/java/com/example/localfly/dialogs/
//
// Reemplaza al PopupMenu de texto plano de SongAdapter.kt / PlaylistAdapter.kt /
// LikedSongsAdapter.kt por una grilla de iconos en un BottomSheetDialog, usando
// el layout adjunto bottom_sheet_song_options.xml.

import android.content.Context
import android.view.LayoutInflater
import android.widget.ImageView
import android.widget.TextView
import com.example.localfly.R
import com.example.localfly.network.Song
import com.google.android.material.bottomsheet.BottomSheetDialog

object SongOptionsBottomSheet {

    fun show(
        context: Context,
        song: Song,
        isDownloaded: Boolean,
        showDelete: Boolean,
        showAddToPlaylist: Boolean,
        onDelete: () -> Unit,
        onAddToQueue: () -> Unit,
        onPlayNext: () -> Unit,
        onAddToPlaylist: () -> Unit,
        onDownloadToggle: () -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val view = LayoutInflater.from(context)
            .inflate(R.layout.bottom_sheet_song_options, null)

        view.findViewById<TextView>(R.id.tvSheetSongTitle).text = song.title

        view.findViewById<android.view.View>(R.id.optionPlayNext).setOnClickListener {
            onPlayNext()
            dialog.dismiss()
        }

        view.findViewById<android.view.View>(R.id.optionAddQueue).setOnClickListener {
            onAddToQueue()
            dialog.dismiss()
        }

        val addPlaylistOption = view.findViewById<android.view.View>(R.id.optionAddPlaylist)
        if (showAddToPlaylist) {
            addPlaylistOption.setOnClickListener {
                onAddToPlaylist()
                dialog.dismiss()
            }
        } else {
            addPlaylistOption.visibility = android.view.View.GONE
        }

        val downloadIcon = view.findViewById<ImageView>(R.id.ivOptionDownloadIcon)
        val downloadLabel = view.findViewById<TextView>(R.id.tvOptionDownloadLabel)
        downloadIcon.setImageResource(
            if (isDownloaded) R.drawable.ic_download_done else R.drawable.ic_download
        )
        downloadLabel.text = if (isDownloaded) "Quitar\ndescarga" else "Descargar"
        view.findViewById<android.view.View>(R.id.optionDownload).setOnClickListener {
            onDownloadToggle()
            dialog.dismiss()
        }

        val deleteOption = view.findViewById<android.view.View>(R.id.optionDelete)
        if (showDelete) {
            deleteOption.setOnClickListener {
                onDelete()
                dialog.dismiss()
            }
        } else {
            deleteOption.visibility = android.view.View.GONE
        }

        dialog.setContentView(view)
        dialog.show()
    }
}
