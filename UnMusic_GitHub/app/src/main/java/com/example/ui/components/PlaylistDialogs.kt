package com.example.ui.components

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.Column
import coil.compose.AsyncImage
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import com.example.core.data.model.PlaylistCoverType
import java.io.File
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.UnMusicApplication
import com.example.core.data.model.PlaylistEntity
import com.example.core.data.model.PlaylistTrackCrossRef
import com.example.core.data.model.TrackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Memilih playlist untuk menambahkan satu lagu. */
@Composable
fun AddToPlaylistDialog(track: TrackEntity, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as UnMusicApplication
    val playlistDao = app.database.playlistDao()
    val playlists by playlistDao.getAllPlaylistsFlow().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val custom = playlists.filter { !it.isSmart }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tambah ke playlist") },
        text = {
            if (custom.isEmpty()) {
                Text("Belum ada playlist. Buat playlist baru di Library.")
            } else {
                LazyColumn {
                    items(custom) { playlist ->
                        Text(
                            text = playlist.name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch(Dispatchers.IO) {
                                        app.database.trackDao().insertTracksIgnore(listOf(track))
                                        val count = playlistDao.getTrackCountForPlaylist(playlist.id)
                                        playlistDao.insertPlaylistTrackCrossRef(
                                            PlaylistTrackCrossRef(playlist.id, track.id, count)
                                        )
                                        app.cloudChanged()
                                    }
                                    Toast.makeText(context, "Ditambahkan ke ${playlist.name}", Toast.LENGTH_SHORT).show()
                                    onDismiss()
                                }
                                .padding(vertical = 12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Tutup") } }
    )
}

/** Menambah atau mengeluarkan lagu dari satu playlist dengan mengetuk lagu. */
@Composable
fun EditPlaylistSongsDialog(
    playlist: PlaylistEntity,
    allTracks: List<TrackEntity>,
    tint: Color,
    onDismiss: () -> Unit
) {
    val app = LocalContext.current.applicationContext as UnMusicApplication
    val playlistDao = app.database.playlistDao()
    val members by playlistDao.getTracksForPlaylistFlow(playlist.id).collectAsState(initial = emptyList())
    val memberIds = remember(members) { members.map { it.id }.toSet() }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }

    val shown = remember(allTracks, query) {
        val q = query.trim().lowercase()
        (if (q.isEmpty()) allTracks else allTracks.filter {
            it.title.lowercase().contains(q) || it.artist.lowercase().contains(q)
        }).take(150)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Lagu di ${playlist.name}") },
        text = {
            androidx.compose.foundation.layout.Column {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Cari lagu") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.height(320.dp)) {
                    items(shown) { track ->
                        val inPlaylist = track.id in memberIds
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch(Dispatchers.IO) {
                                        if (inPlaylist) {
                                            playlistDao.removeTrackFromPlaylist(playlist.id, track.id)
                                        } else {
                                            app.database.trackDao().insertTracksIgnore(listOf(track))
                                            val count = playlistDao.getTrackCountForPlaylist(playlist.id)
                                            playlistDao.insertPlaylistTrackCrossRef(
                                                PlaylistTrackCrossRef(playlist.id, track.id, count)
                                            )
                                        }
                                        app.cloudChanged()
                                    }
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                                Text(track.title, maxLines = 1)
                                Text(track.artist, maxLines = 1, color = Color.Gray)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            if (inPlaylist) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Ada di playlist",
                                    tint = tint,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Selesai") } }
    )
}

/** Mengubah sampul playlist: kolase bawaan, gradien, gambar dari galeri, atau sampul salah satu lagu. */
@Composable
fun EditPlaylistCoverDialog(
    playlist: PlaylistEntity,
    tracks: List<TrackEntity>,
    tint: Color,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as UnMusicApplication
    val playlistDao = app.database.playlistDao()
    val scope = rememberCoroutineScope()
    var showTrackCovers by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val saved = copyCoverImage(context, uri, playlist.id)
                if (saved != null) {
                    playlistDao.insertPlaylist(
                        playlist.copy(coverType = PlaylistCoverType.CUSTOM_IMAGE, customCoverUri = saved)
                    )
                }
            }
        }
        onDismiss()
    }

    fun apply(type: PlaylistCoverType, uri: String) {
        scope.launch(Dispatchers.IO) {
            playlistDao.insertPlaylist(playlist.copy(coverType = type, customCoverUri = uri))
        }
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ubah sampul") },
        text = {
            Column {
                CoverOption("Kolase 4 lagu teratas (bawaan)") { apply(PlaylistCoverType.DEFAULT, "") }
                CoverOption("Gradien animasi") { apply(PlaylistCoverType.ANIMATED_GRADIENT, "") }
                CoverOption("Pilih gambar dari galeri") { picker.launch("image/*") }
                CoverOption("Pakai sampul salah satu lagu") { showTrackCovers = !showTrackCovers }
                if (showTrackCovers) {
                    Spacer(modifier = Modifier.height(8.dp))
                    if (tracks.isEmpty()) {
                        Text("Tambahkan lagu ke playlist terlebih dahulu.", color = Color.Gray)
                    } else {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(tracks.distinctBy { it.coverUrl }.take(30)) { track ->
                                AsyncImage(
                                    model = track.coverUrl,
                                    contentDescription = track.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(64.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .clickable { apply(PlaylistCoverType.CUSTOM_IMAGE, track.coverUrl) }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Tutup") } }
    )
}

@Composable
private fun CoverOption(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    )
}

private fun copyCoverImage(context: Context, uri: Uri, playlistId: String): String? {
    return try {
        val dir = File(context.filesDir, "covers")
        if (!dir.exists()) dir.mkdirs()
        val out = File(dir, "${playlistId}_${System.currentTimeMillis()}.jpg")
        val input = context.contentResolver.openInputStream(uri)
        if (input == null) {
            null
        } else {
            input.use { src -> out.outputStream().use { dst -> src.copyTo(dst) } }
            Uri.fromFile(out).toString()
        }
    } catch (e: Exception) {
        null
    }
}

/** Mengubah nama playlist. */
@Composable
fun RenamePlaylistDialog(playlist: PlaylistEntity, onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as UnMusicApplication
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(playlist.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ubah nama playlist") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val newName = name.trim()
                if (newName.isNotEmpty()) {
                    scope.launch(Dispatchers.IO) {
                        app.database.playlistDao().insertPlaylist(playlist.copy(name = newName))
                        app.cloudChanged()
                    }
                }
                onDismiss()
            }) { Text("Simpan") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } }
    )
}
