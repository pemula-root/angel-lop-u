package com.example.feature.carmode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.player.MusicPlayerManager

@Composable
fun CarModeScreen(
    playerManager: MusicPlayerManager,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentTrack by playerManager.currentTrack.collectAsState()
    val isPlaying by playerManager.isPlaying.collectAsState()
    val lyricSubtext by playerManager.activeLyricSubtext.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Exit bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.DirectionsCar,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "CAR MODE",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.5.sp
                    ),
                    color = Color.White
                )
            }

            IconButton(
                onClick = onExit,
                modifier = Modifier
                    .size(52.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    .testTag("car_mode_exit")
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Exit Car Mode",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // Center: High-contrast cover, giant title, artist, and large lyrics display
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth()
        ) {
            currentTrack?.let { track ->
                AsyncImage(
                    model = track.coverUrl,
                    contentDescription = track.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(180.dp)
                        .clip(RoundedCornerShape(20.dp))
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = track.title,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = track.artist,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFFA1A1AA),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(16.dp))

                // High-visibility active lyric prompt for driver
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp)),
                    color = Color(0xFF18181B)
                ) {
                    Text(
                        text = lyricSubtext.ifBlank { "UnMusic • Driving Mode" },
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        ),
                        color = Color(0xFF00E5FF),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp)
                    )
                }
            } ?: run {
                Text(
                    text = "No track currently playing",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.Gray
                )
            }
        }

        // Bottom: Giant, high-contrast touch controls (minimum 64dp+ touch targets)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Like button
            FilledIconButton(
                onClick = { playerManager.toggleLikeCurrentTrack() },
                modifier = Modifier.size(68.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color(0xFF27272A)
                )
            ) {
                Icon(
                    imageVector = if (currentTrack?.isLiked == true) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    contentDescription = "Like",
                    tint = if (currentTrack?.isLiked == true) Color.Red else Color.White,
                    modifier = Modifier.size(34.dp)
                )
            }

            // Previous button
            FilledIconButton(
                onClick = { playerManager.skipToPrevious() },
                modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color(0xFF27272A)
                )
            ) {
                Icon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = "Previous",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }

            // Play / Pause giant button
            FilledIconButton(
                onClick = { playerManager.togglePlayPause() },
                modifier = Modifier.size(92.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color.White
                )
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    tint = Color.Black,
                    modifier = Modifier.size(54.dp)
                )
            }

            // Next button
            FilledIconButton(
                onClick = { playerManager.skipToNext() },
                modifier = Modifier.size(72.dp),
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color(0xFF27272A)
                )
            ) {
                Icon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = "Next",
                    tint = Color.White,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
    }
}
