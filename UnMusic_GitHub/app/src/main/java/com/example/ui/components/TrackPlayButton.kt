package com.example.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.example.UnMusicApplication
import com.example.core.data.model.TrackEntity

/**
 * Tombol putar/jeda untuk satu baris lagu. Ikonnya mengikuti keadaan pemutar sebenarnya:
 * jeda bila lagu ini sedang diputar, putar bila tidak. Mengetuk lagu yang sedang aktif
 * menjeda atau melanjutkannya, mengetuk lagu lain memutarnya.
 */
@Composable
fun TrackPlayPauseButton(
    track: TrackEntity,
    tint: Color,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val app = LocalContext.current.applicationContext as UnMusicApplication
    val manager = app.playerManager
    val current by manager.currentTrack.collectAsState()
    val playing by manager.isPlaying.collectAsState()
    val isCurrent = current?.id == track.id
    val showPause = isCurrent && playing

    IconButton(
        onClick = {
            if (isCurrent) manager.togglePlayPause() else onPlay()
        },
        modifier = modifier
    ) {
        Icon(
            imageVector = if (showPause) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = if (showPause) "Jeda" else "Putar",
            tint = tint
        )
    }
}
