package com.example.ui.components

import androidx.compose.ui.graphics.vector.ImageVector
import com.example.ui.theme.deepenAccent
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.core.data.model.TrackEntity
import com.example.player.EqPreset
import com.example.player.MusicPlayerManager
import com.example.player.RepeatMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FullPlayer(
    playerManager: MusicPlayerManager,
    dominantColor: Color,
    onDismiss: () -> Unit,
    onDownloadTrack: (TrackEntity) -> Unit,
    modifier: Modifier = Modifier,
    downloadStates: Map<String, com.example.download.DownloadProgress> = emptyMap(),
    onCancelDownload: (TrackEntity) -> Unit = {},
    onDeleteDownload: (TrackEntity) -> Unit = {}
) {
    val lyricsStatus by playerManager.lyricsLoadState.collectAsState()
    val playbackSpeed by playerManager.playbackSpeed.collectAsState()
    var showAddToPlaylist by remember { mutableStateOf(false) }
    val currentTrack by playerManager.currentTrack.collectAsState()
    val isPlaying by playerManager.isPlaying.collectAsState()
    val positionMs by playerManager.playbackPositionMs.collectAsState()
    val durationMs by playerManager.durationMs.collectAsState()
    val isShuffle by playerManager.isShuffle.collectAsState()
    val repeatMode by playerManager.repeatMode.collectAsState()
    val queue by playerManager.queue.collectAsState()
    val currentIndex by playerManager.currentIndex.collectAsState()
    val sleepTimerMin by playerManager.sleepTimerRemainingMinutes.collectAsState()

    // Posisi terakhir (kartu lagu atau lirik) diingat, termasuk setelah pemutar ditutup dan dibuka lagi
    val playerPrefs = androidx.compose.ui.platform.LocalContext.current
        .getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE)
    var showLyrics by remember { mutableStateOf(playerPrefs.getBoolean("player_show_lyrics", false)) }
    var isFullscreenLyrics by remember { mutableStateOf(playerPrefs.getBoolean("player_lyrics_fullscreen", false)) }
    androidx.compose.runtime.LaunchedEffect(showLyrics, isFullscreenLyrics) {
        playerPrefs.edit()
            .putBoolean("player_show_lyrics", showLyrics)
            .putBoolean("player_lyrics_fullscreen", isFullscreenLyrics)
            .apply()
    }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showEqSheet by remember { mutableStateOf(false) }
    var showSleepTimerSheet by remember { mutableStateOf(false) }
    var showSpeedSheet by remember { mutableStateOf(false) }

    var isScrubbing by remember { mutableStateOf(false) }
    var scrubPositionMs by remember { mutableFloatStateOf(0f) }

    val currentDisplayPosition = if (isScrubbing) scrubPositionMs.toLong() else positionMs
    val track = currentTrack ?: return

    // Background gradient derived from track's dominant color
    val backgroundBrush = remember(dominantColor) {
        Brush.verticalGradient(
            colors = listOf(
                deepenAccent(dominantColor),
                Color(0xFF0B0B0D),
                Color(0xFF000000)
            )
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(backgroundBrush)
    ) {
        if (isFullscreenLyrics) {
            LyricsView(
                lrcText = track.lyricsLrc,
                currentPositionMs = currentDisplayPosition,
                dominantColor = dominantColor,
                isFullscreen = true,
                onToggleFullscreen = { isFullscreenLyrics = false },
                onSeekTo = { playerManager.seekTo(it) },
                syncedJson = track.lyricsSyncedJson,
                status = lyricsStatus,
                onRetry = { playerManager.retryLyrics() },
                trackId = track.id
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Top App Bar inside Player
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.testTag("full_player_close")
                    ) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "Tutup pemutar",
                            tint = Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "DIPUTAR DARI",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                            color = Color.White.copy(alpha = 0.6f)
                        )
                        Text(
                            text = track.album.ifBlank { "Koleksi UnMusic" },
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Row {
                        IconButton(onClick = { showEqSheet = true }) {
                            Icon(
                                imageVector = Icons.Default.GraphicEq,
                                contentDescription = "Equalizer",
                                tint = Color.White
                            )
                        }
                        IconButton(onClick = { showQueueSheet = true }) {
                            Icon(
                                imageVector = Icons.Default.QueueMusic,
                                contentDescription = "Antrean",
                                tint = Color.White
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(0.5f))

                // Center Content: Cover Art OR Synchronized Lyrics View
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(4f)
                        .padding(horizontal = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AnimatedContent(
                        targetState = showLyrics,
                        transitionSpec = { fadeIn(tween(300)) togetherWith fadeOut(tween(300)) },
                        label = "player_center_content"
                    ) { lyricsMode ->
                        if (lyricsMode) {
                            LyricsView(
                                lrcText = track.lyricsLrc,
                                currentPositionMs = currentDisplayPosition,
                                dominantColor = dominantColor,
                                isFullscreen = false,
                                onToggleFullscreen = { isFullscreenLyrics = true },
                                onSeekTo = { playerManager.seekTo(it) },
                                syncedJson = track.lyricsSyncedJson,
                                status = lyricsStatus,
                                onRetry = { playerManager.retryLyrics() },
                trackId = track.id
                            )
                        } else {
                            // HD Artwork with double tap to like
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.9f)
                                    .aspectRatio(1f)
                                    .shadow(elevation = 24.dp, shape = RoundedCornerShape(24.dp))
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(Color(0xFF141417))
                                    .pointerInput(Unit) {
                                        detectTapGestures(
                                            onDoubleTap = {
                                                playerManager.toggleLikeCurrentTrack()
                                            }
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                AsyncImage(
                                    model = track.coverUrl,
                                    contentDescription = "Cover",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(0.5f))

                // Track Metadata (Title, Artist, Like, Lyrics toggle)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = track.artist,
                            style = MaterialTheme.typography.bodyLarge,
                            color = Color.White.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    IconButton(onClick = { playerManager.toggleLikeCurrentTrack() }) {
                        Icon(
                            imageVector = if (track.isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Suka",
                            tint = if (track.isLiked) Color(0xFFFF4D6D) else Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Seekbar & time elapsed/remaining
                Column(modifier = Modifier.fillMaxWidth()) {
                    Slider(
                        value = if (durationMs > 0) currentDisplayPosition.toFloat() / durationMs else 0f,
                        onValueChange = { frac ->
                            isScrubbing = true
                            scrubPositionMs = frac * durationMs
                        },
                        onValueChangeFinished = {
                            playerManager.seekTo(scrubPositionMs.toLong())
                            isScrubbing = false
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = dominantColor,
                            inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("full_player_seekbar")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatMs(currentDisplayPosition),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                        Text(
                            text = formatMs(durationMs),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.6f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Primary Playback Controls
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Shuffle
                    IconButton(onClick = { playerManager.toggleShuffle() }) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (isShuffle) dominantColor else Color.White.copy(alpha = 0.5f)
                        )
                    }

                    // Previous
                    IconButton(
                        onClick = { playerManager.skipToPrevious() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Previous",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    // Play / Pause FAB
                    FilledIconButton(
                        onClick = { playerManager.togglePlayPause() },
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(containerColor = dominantColor),
                        modifier = Modifier
                            .size(72.dp)
                            .testTag("full_player_play_pause")
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.Black,
                            modifier = Modifier.size(38.dp)
                        )
                    }

                    // Next
                    IconButton(
                        onClick = { playerManager.skipToNext() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    // Repeat
                    IconButton(onClick = { playerManager.toggleRepeat() }) {
                        Icon(
                            imageVector = when (repeatMode) {
                                RepeatMode.OFF -> Icons.Default.Repeat
                                RepeatMode.ALL -> Icons.Default.Repeat
                                RepeatMode.ONE -> Icons.Default.RepeatOne
                            },
                            contentDescription = "Repeat",
                            tint = if (repeatMode != RepeatMode.OFF) dominantColor else Color.White.copy(alpha = 0.5f)
                        )
                    }
                }

                // Aksi sekunder: lirik, unduh, playlist, timer tidur, kecepatan
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlayerAction(Icons.Default.Lyrics, "Lirik", showLyrics, dominantColor) { showLyrics = !showLyrics }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        DownloadButton(
                            isDownloaded = track.isDownloaded,
                            state = downloadStates[track.id],
                            tint = dominantColor,
                            onDownload = { onDownloadTrack(track) },
                            onCancel = { onCancelDownload(track) },
                            onDelete = { onDeleteDownload(track) },
                            size = 36.dp
                        )
                        Text(
                            text = "Unduh",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.75f)
                        )
                    }
                    PlayerAction(Icons.Default.Add, "Playlist", false, dominantColor) { showAddToPlaylist = true }
                    PlayerAction(
                        Icons.Default.Bedtime,
                        if (sleepTimerMin != null) "${sleepTimerMin}m" else "Timer",
                        sleepTimerMin != null,
                        dominantColor
                    ) { showSleepTimerSheet = true }
                    PlayerAction(Icons.Default.Speed, formatSpeed(playbackSpeed), playbackSpeed != 1f, dominantColor) {
                        showSpeedSheet = true
                    }
                }
                if (showAddToPlaylist) {
                    AddToPlaylistDialog(track = track, onDismiss = { showAddToPlaylist = false })
                }
            }
        }

        // Equalizer Bottom Sheet
        if (showEqSheet) {
            ModalBottomSheet(
                onDismissRequest = { showEqSheet = false },
                containerColor = Color(0xFF141417)
            ) {
                EqualizerSheetContent(
                    audioEffects = playerManager.audioEffects,
                    dominantColor = dominantColor
                )
            }
        }

        // Queue Bottom Sheet
        if (showQueueSheet) {
            ModalBottomSheet(
                onDismissRequest = { showQueueSheet = false },
                containerColor = Color(0xFF141417)
            ) {
                QueueSheetContent(
                    queue = queue,
                    currentIndex = currentIndex,
                    dominantColor = dominantColor,
                    onPlayTrack = { playerManager.playTrack(it) },
                    onRemove = { playerManager.removeFromQueue(it) },
                    onClearUpcoming = { playerManager.clearUpcoming() }
                )
            }
        }

        // Sleep Timer Sheet
        if (showSleepTimerSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSleepTimerSheet = false },
                containerColor = Color(0xFF141417)
            ) {
                SleepTimerSheetContent(
                    currentTimerMin = sleepTimerMin,
                    onSelectMinutes = {
                        playerManager.setSleepTimer(it)
                        showSleepTimerSheet = false
                    }
                )
            }
        }

        // Speed Sheet
        if (showSpeedSheet) {
            ModalBottomSheet(
                onDismissRequest = { showSpeedSheet = false },
                containerColor = Color(0xFF141417)
            ) {
                SpeedSheetContent(
                    current = playbackSpeed,
                    onSelectSpeed = {
                        playerManager.setPlaybackSpeed(it)
                        showSpeedSheet = false
                    }
                )
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return String.format("%d:%02d", min, sec)
}

@Composable
fun EqualizerSheetContent(
    audioEffects: com.example.player.AudioEffectManager,
    dominantColor: Color
) {
    var selectedPreset by remember { mutableStateOf(EqPreset.FLAT) }
    var bassBoostStrength by remember { mutableFloatStateOf(0f) }
    var virtualizerStrength by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Text(
            text = "Audio Equalizer",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = Color.White
        )
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "PRESETS",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EqPreset.values().take(3).forEach { preset ->
                FilterChip(
                    selected = selectedPreset == preset,
                    onClick = {
                        selectedPreset = preset
                        audioEffects.applyPreset(preset)
                    },
                    label = { Text(preset.displayName) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = dominantColor,
                        selectedLabelColor = Color.Black
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Bass Boost Slider
        Text(
            text = "Bass Boost (${(bassBoostStrength * 100).toInt()}%)",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White
        )
        Slider(
            value = bassBoostStrength,
            onValueChange = {
                bassBoostStrength = it
                audioEffects.setBassBoostStrength((it * 1000).toInt())
            },
            colors = SliderDefaults.colors(thumbColor = dominantColor, activeTrackColor = dominantColor)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Virtualizer Slider
        Text(
            text = "Virtualizer 3D (${(virtualizerStrength * 100).toInt()}%)",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White
        )
        Slider(
            value = virtualizerStrength,
            onValueChange = {
                virtualizerStrength = it
                audioEffects.setVirtualizerStrength((it * 1000).toInt())
            },
            colors = SliderDefaults.colors(thumbColor = dominantColor, activeTrackColor = dominantColor)
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun QueueSheetContent(
    queue: List<TrackEntity>,
    currentIndex: Int,
    dominantColor: Color,
    onPlayTrack: (TrackEntity) -> Unit,
    onRemove: (Int) -> Unit,
    onClearUpcoming: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Antrean (${queue.size} lagu)",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
            if (queue.size > currentIndex + 1) {
                TextButton(onClick = onClearUpcoming) {
                    Text("Kosongkan berikutnya", color = Color.White.copy(alpha = 0.7f))
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxHeight(0.6f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(queue) { index, track ->
                val isCurrent = index == currentIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isCurrent) dominantColor.copy(alpha = 0.2f) else Color.Transparent)
                        .clickable { onPlayTrack(track) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = track.coverUrl,
                        contentDescription = "Cover",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = if (isCurrent) dominantColor else Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = track.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { onRemove(index) }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Remove",
                            tint = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SleepTimerSheetContent(
    currentTimerMin: Int?,
    onSelectMinutes: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Text(
            text = "Sleep Timer",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = Color.White
        )
        Spacer(modifier = Modifier.height(16.dp))

        listOf(15, 30, 45, 60, 0).forEach { min ->
            val label = if (min == 0) "Turn Off Timer" else "$min Minutes"
            TextButton(
                onClick = { onSelectMinutes(min) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (min == 0) Color.Red else Color.White,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun SpeedSheetContent(
    current: Float = 1f,
    onSelectSpeed: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Text(
            text = "Playback Speed",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = Color.White
        )
        Spacer(modifier = Modifier.height(16.dp))

        listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
            TextButton(
                onClick = { onSelectSpeed(speed) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = formatSpeed(speed),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        fontWeight = if (speed == current) FontWeight.Bold else FontWeight.Normal
                    ),
                    color = if (speed == current) Color.White else Color.White.copy(alpha = 0.6f),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}

private fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"

@Composable
private fun PlayerAction(
    icon: ImageVector,
    label: String,
    active: Boolean,
    tint: Color,
    onClick: () -> Unit
) {
    val color = if (active) tint else Color.White.copy(alpha = 0.75f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = color,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}
