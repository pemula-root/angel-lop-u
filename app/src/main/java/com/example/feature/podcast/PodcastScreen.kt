package com.example.feature.podcast

import com.example.ui.theme.UnColors
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.core.data.model.PodcastEntity
import com.example.core.data.model.PodcastEpisodeEntity
import com.example.core.data.model.TrackEntity
import com.example.podcast.PodcastManager
import kotlinx.coroutines.launch

@Composable
fun PodcastScreen(
    podcastManager: PodcastManager,
    dominantColor: Color,
    onPlayEpisode: (TrackEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    var podcasts by remember { mutableStateOf<List<PodcastEntity>>(emptyList()) }
    var showAddFeedDialog by remember { mutableStateOf(false) }
    var selectedPodcast by remember { mutableStateOf<PodcastEntity?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        podcasts = podcastManager.getInitialPodcasts()
    }

    if (selectedPodcast != null) {
        val pod = selectedPodcast!!
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = { selectedPodcast = null }) {
                        Text("← Back", color = dominantColor)
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = pod.coverUrl,
                        contentDescription = pod.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(100.dp)
                            .clip(RoundedCornerShape(14.dp))
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = pod.title,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                            color = UnColors.text
                        )
                        Text(
                            text = pod.author,
                            style = MaterialTheme.typography.bodyMedium,
                            color = UnColors.text.copy(alpha = 0.7f)
                        )
                    }
                }
            }
            item {
                Text(
                    text = pod.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = UnColors.text.copy(alpha = 0.6f)
                )
            }
            item {
                Text(
                    text = "Episodes",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = UnColors.text
                )
            }
            items(
                listOf(
                    "Ep. 1: Anatomy of Analog Synthesizers",
                    "Ep. 2: Spatial Audio & Soundstage Mastery",
                    "Ep. 3: Golden Age of 24-Track Tape Machines"
                )
            ) { epTitle ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            onPlayEpisode(
                                TrackEntity(
                                    id = "ep_${epTitle.hashCode()}",
                                    title = epTitle,
                                    artist = pod.author,
                                    album = pod.title,
                                    durationMs = 1800000L,
                                    coverUrl = pod.coverUrl,
                                    streamUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3",
                                    genre = "Podcast"
                                )
                            )
                        },
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = epTitle,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = UnColors.text
                            )
                            Text(
                                text = "30 mins • Published this week",
                                style = MaterialTheme.typography.bodySmall,
                                color = UnColors.text.copy(alpha = 0.5f)
                            )
                        }
                        IconButton(onClick = {
                            onPlayEpisode(
                                TrackEntity(
                                    id = "ep_${epTitle.hashCode()}",
                                    title = epTitle,
                                    artist = pod.author,
                                    album = pod.title,
                                    durationMs = 1800000L,
                                    coverUrl = pod.coverUrl,
                                    streamUrl = "https://www.soundhelix.com/examples/mp3/SoundHelix-Song-1.mp3",
                                    genre = "Podcast"
                                )
                            )
                        }) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Play", tint = dominantColor)
                        }
                    }
                }
            }
        }
    } else {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Podcasts",
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = UnColors.text
                    )
                    FilledTonalButton(
                        onClick = { showAddFeedDialog = true },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = UnColors.text
                        )
                    ) {
                        Icon(imageVector = Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add RSS Feed")
                    }
                }
            }

            items(podcasts) { pod ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { selectedPodcast = pod },
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = pod.coverUrl,
                            contentDescription = pod.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = pod.title,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = UnColors.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = pod.author,
                                style = MaterialTheme.typography.bodySmall,
                                color = UnColors.text.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            imageVector = Icons.Default.Podcasts,
                            contentDescription = null,
                            tint = dominantColor
                        )
                    }
                }
            }
        }
    }

    if (showAddFeedDialog) {
        var feedUrl by remember { mutableStateOf("") }
        var isSubscribing by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = { showAddFeedDialog = false },
            title = { Text("Subscribe via RSS", color = UnColors.text, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text(
                        text = "Enter any podcast RSS feed link to subscribe and download episodes locally.",
                        style = MaterialTheme.typography.bodySmall,
                        color = UnColors.text.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = feedUrl,
                        onValueChange = { feedUrl = it },
                        placeholder = { Text("https://example.com/feed.xml") },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = UnColors.text,
                            unfocusedTextColor = UnColors.text
                        )
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isSubscribing = true
                        scope.launch {
                            podcastManager.subscribeFeed(feedUrl)
                            podcasts = podcastManager.getInitialPodcasts()
                            isSubscribing = false
                            showAddFeedDialog = false
                        }
                    },
                    enabled = feedUrl.isNotBlank() && !isSubscribing
                ) {
                    Text(if (isSubscribing) "Subscribing..." else "Subscribe")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddFeedDialog = false }) { Text("Cancel", color = Color.Gray) }
            },
            containerColor = MaterialTheme.colorScheme.surface
        )
    }
}
