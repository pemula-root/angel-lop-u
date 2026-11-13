package com.example.ui.components

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.UnMusicApplication
import com.example.core.data.model.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Menu tiga titik untuk satu lagu: putar berikutnya, tambah ke antrean, tambah ke playlist,
 * suka, unduh, bagikan, dan (bila di dalam playlist) hapus dari playlist.
 */
@Composable
fun TrackMenuButton(
    track: TrackEntity,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    onRemoveFromPlaylist: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val app = context.applicationContext as UnMusicApplication
    val manager = app.playerManager
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var showPlaylistPicker by remember { mutableStateOf(false) }
    val iconTint = if (tint == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else tint

    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Menu lagu",
                tint = iconTint
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Putar berikutnya") },
                onClick = {
                    expanded = false
                    if (manager.queue.value.isEmpty()) {
                        manager.playTrack(track, listOf(track))
                    } else {
                        manager.playNext(track)
                        Toast.makeText(context, "Akan diputar berikutnya", Toast.LENGTH_SHORT).show()
                    }
                }
            )
            DropdownMenuItem(
                text = { Text("Tambah ke antrean") },
                onClick = {
                    expanded = false
                    if (manager.queue.value.isEmpty()) {
                        manager.playTrack(track, listOf(track))
                    } else {
                        manager.addToQueue(track)
                        Toast.makeText(context, "Ditambahkan ke antrean", Toast.LENGTH_SHORT).show()
                    }
                }
            )
            DropdownMenuItem(
                text = { Text("Tambah ke playlist") },
                onClick = {
                    expanded = false
                    showPlaylistPicker = true
                }
            )
            DropdownMenuItem(
                text = { Text(if (track.isLiked) "Hapus dari lagu disukai" else "Suka") },
                onClick = {
                    expanded = false
                    scope.launch(Dispatchers.IO) {
                        app.database.trackDao().insertTracksIgnore(listOf(track))
                        app.database.trackDao().updateLiked(track.id, !track.isLiked)
                        app.cloudChanged()
                    }
                    Toast.makeText(
                        context,
                        if (track.isLiked) "Dihapus dari lagu disukai" else "Ditambahkan ke lagu disukai",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
            DropdownMenuItem(
                text = { Text("Unduh") },
                onClick = {
                    expanded = false
                    app.downloadManager.enqueue(track)
                    Toast.makeText(context, "Mengunduh ${track.title}", Toast.LENGTH_SHORT).show()
                }
            )
            DropdownMenuItem(
                text = { Text("Bagikan") },
                onClick = {
                    expanded = false
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(
                            Intent.EXTRA_TEXT,
                            "${track.title} - ${track.artist}\nhttps://music.youtube.com/watch?v=${track.id}"
                        )
                    }
                    context.startActivity(Intent.createChooser(send, "Bagikan lagu"))
                }
            )
            if (onRemoveFromPlaylist != null) {
                DropdownMenuItem(
                    text = { Text("Hapus dari playlist") },
                    onClick = {
                        expanded = false
                        onRemoveFromPlaylist()
                    }
                )
            }
        }
    }

    if (showPlaylistPicker) {
        AddToPlaylistDialog(track = track, onDismiss = { showPlaylistPicker = false })
    }
}
