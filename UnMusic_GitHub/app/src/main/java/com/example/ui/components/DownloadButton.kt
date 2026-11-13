package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.example.core.data.model.TrackEntity
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.download.DownloadProgress
import com.example.download.DownloadStatus

/**
 * Tombol unduh dengan empat keadaan: belum diunduh, sedang diunduh (lingkaran progres),
 * selesai (centang), dan gagal (ulangi).
 */
@Composable
fun DownloadButton(
    isDownloaded: Boolean,
    state: DownloadProgress?,
    tint: Color,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 40.dp,
    idleColor: Color = Color.White
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val status = state?.status
    val done = status == DownloadStatus.DONE || (isDownloaded && status == null)

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable {
                when {
                    done -> confirmDelete = true
                    status == DownloadStatus.QUEUED || status == DownloadStatus.DOWNLOADING -> onCancel()
                    else -> onDownload()
                }
            },
        contentAlignment = Alignment.Center
    ) {
        when {
            done -> Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Sudah diunduh",
                tint = tint,
                modifier = Modifier.size(size * 0.6f)
            )
            status == DownloadStatus.DOWNLOADING -> {
                CircularProgressIndicator(
                    progress = { (state?.progress ?: 0f).coerceIn(0.03f, 1f) },
                    modifier = Modifier.size(size * 0.62f),
                    strokeWidth = 2.5.dp,
                    color = tint,
                    trackColor = idleColor.copy(alpha = 0.15f)
                )
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Batalkan",
                    tint = idleColor.copy(alpha = 0.85f),
                    modifier = Modifier.size(size * 0.28f)
                )
            }
            status == DownloadStatus.QUEUED -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(size * 0.62f),
                    strokeWidth = 2.5.dp,
                    color = tint
                )
            }
            status == DownloadStatus.FAILED -> Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Coba unduh lagi",
                tint = Color(0xFFFF6B6B),
                modifier = Modifier.size(size * 0.6f)
            )
            else -> Icon(
                imageVector = Icons.Default.Download,
                contentDescription = "Unduh",
                tint = idleColor.copy(alpha = 0.75f),
                modifier = Modifier.size(size * 0.6f)
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Hapus unduhan?") },
            text = { Text("Lagu dan lirik offline akan dihapus dari perangkat.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Hapus") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Batal") }
            }
        )
    }
}

/** Tombol unduh yang terhubung langsung ke DownloadManager aplikasi, untuk dipakai di baris daftar lagu. */
@Composable
fun TrackDownloadButton(
    track: TrackEntity,
    tint: Color,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 36.dp
) {
    val app = LocalContext.current.applicationContext as com.example.UnMusicApplication
    val dm = app.downloadManager
    val states by dm.downloadStates.collectAsState()
    val scope = rememberCoroutineScope()
    DownloadButton(
        isDownloaded = track.isDownloaded,
        state = states[track.id],
        tint = tint,
        onDownload = { dm.enqueue(track) },
        onCancel = { dm.cancel(track.id) },
        onDelete = { scope.launch { dm.deleteDownload(track) } },
        modifier = modifier,
        size = size,
        idleColor = com.example.ui.theme.UnColors.text
    )
}
