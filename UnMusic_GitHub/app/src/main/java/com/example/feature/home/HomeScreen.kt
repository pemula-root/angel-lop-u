package com.example.feature.home

import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.core.data.model.TrackEntity
import com.example.mix.AutoMix
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    recentTracks: List<TrackEntity>,
    allTracks: List<TrackEntity>,
    autoMixes: List<AutoMix>,
    dominantColor: Color,
    onPlayTrack: (TrackEntity, List<TrackEntity>) -> Unit,
    onOpenCarMode: () -> Unit,
    onRetryRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    onOpenStats: () -> Unit = {},
    onOpenAccount: () -> Unit = {}
) {
    var selectedMood by remember { mutableStateOf("All") }
    val moods = listOf("All", "Sad", "Feel good", "Relax", "Energize", "Workout", "Party", "Focus")
    val moodLabels = mapOf(
        "All" to "Semua", "Sad" to "Sedih", "Feel good" to "Ceria", "Relax" to "Santai",
        "Energize" to "Semangat", "Workout" to "Olahraga", "Party" to "Pesta", "Focus" to "Fokus"
    )

    var isTimedOut by remember { mutableStateOf(false) }

    LaunchedEffect(allTracks.isEmpty()) {
        if (allTracks.isEmpty()) {
            isTimedOut = false
            delay(8000)
            if (allTracks.isEmpty()) {
                isTimedOut = true
            }
        } else {
            isTimedOut = false
        }
    }

    val filteredTracks = remember(selectedMood, allTracks) {
        if (selectedMood == "All") allTracks
        else allTracks.filter { com.example.personalization.TrackTags.chipMatches(it, selectedMood) }
            .ifEmpty { allTracks }
    }

    val forYou = remember(allTracks) { allTracks.drop(12).distinctBy { it.id }.take(30) }
    val favoriteArtist = remember(allTracks) {
        allTracks.filter { it.playCount > 0 }
            .groupBy { it.artist }
            .maxByOrNull { group -> group.value.sumOf { it.playCount } }
            ?.key
    }
    val moreFromArtist = remember(allTracks, favoriteArtist) {
        if (favoriteArtist == null) emptyList()
        else allTracks.filter { it.artist == favoriteArtist }.distinctBy { it.id }.take(30)
    }
    val mostPlayed = remember(allTracks) {
        allTracks.filter { it.playCount > 0 }.sortedByDescending { it.playCount }.distinctBy { it.id }.take(20)
    }

    val moodSections = remember(allTracks) {
        listOf(
            "sad" to "Lagu sedih",
            "chill" to "Untuk bersantai",
            "energetic" to "Penuh semangat",
            "romantic" to "Romantis"
        ).map { (mood, title) ->
            title to allTracks.filter { com.example.personalization.TrackTags.mood(it) == mood }
                .distinctBy { it.id }.take(20)
        }.filter { it.second.size >= 5 }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        // Sapaan sesuai waktu dan akses cepat ke statistik, mode mobil, dan pengaturan
        item {
            val greeting = remember {
                val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                when (hour) {
                    in 4..10 -> "Selamat pagi"
                    in 11..14 -> "Selamat siang"
                    in 15..17 -> "Selamat sore"
                    else -> "Selamat malam"
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = greeting,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    IconButton(onClick = onOpenAccount) {
                        Icon(
                            imageVector = Icons.Default.AccountCircle,
                            contentDescription = "Akun",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onOpenStats) {
                        Icon(
                            imageVector = Icons.Default.BarChart,
                            contentDescription = "Statistik",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onOpenCarMode,
                        modifier = Modifier.testTag("car_mode_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.DirectionsCar,
                            contentDescription = "Mode mobil",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Pengaturan",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Mood / Genre filter chips
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                moods.forEach { mood ->
                    FilterChip(
                        selected = selectedMood == mood,
                        onClick = { selectedMood = mood },
                        shape = RoundedCornerShape(50),
                        border = null,
                        label = { Text(moodLabels[mood] ?: mood) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                            selectedContainerColor = dominantColor,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                        )
                    )
                }
            }
        }

        // Quick Picks Grid
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Pilihan cepat",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )

                if (filteredTracks.isEmpty() && isTimedOut) {
                    TextButton(onClick = onRetryRefresh) {
                        Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Coba lagi")
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            when {
                filteredTracks.isEmpty() && isTimedOut -> {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp)),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Tidak dapat memuat musik",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Periksa koneksi internet Anda.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = onRetryRefresh,
                                colors = ButtonDefaults.buttonColors(containerColor = dominantColor)
                            ) {
                                Text("Coba lagi", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                filteredTracks.isEmpty() -> {
                    // Loading Skeleton state
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(3) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                repeat(2) {
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .height(58.dp)
                                            .clip(RoundedCornerShape(12.dp)),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    ) {}
                                }
                            }
                        }
                    }
                }
                else -> {
                    val quickPicks = filteredTracks.take(12)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        quickPicks.chunked(2).forEach { rowTracks ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                rowTracks.forEach { track ->
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(6.dp))
                                            .clickable { onPlayTrack(track, filteredTracks) },
                                        color = MaterialTheme.colorScheme.surfaceVariant
                                    ) {
                                        Row(
                                            modifier = Modifier,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            AsyncImage(
                                                model = track.coverUrl,
                                                contentDescription = "Cover",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.size(56.dp)
                                            )
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    text = track.title,
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = track.artist,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                                if (rowTracks.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }
        }

        // Auto Mixes Horizontal Carousel (only shown when mixes exist)
        if (autoMixes.isNotEmpty()) {
            item {
                Text(
                    text = "Mix untukmu",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(12.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(autoMixes) { mix ->
                        Box(
                            modifier = Modifier
                                .width(180.dp)
                                .height(200.dp)
                                .clip(RoundedCornerShape(18.dp))
                                .background(
                                    Brush.linearGradient(
                                        listOf(
                                            Color(mix.coverGradientStart),
                                            Color(mix.coverGradientEnd)
                                        )
                                    )
                                )
                                .clickable {
                                    if (mix.tracks.isNotEmpty()) {
                                        onPlayTrack(mix.tracks.first(), mix.tracks)
                                    }
                                }
                                .padding(16.dp)
                        ) {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(
                                        text = mix.title,
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = Color.White
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = mix.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = Color.White.copy(alpha = 0.8f),
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .background(Color.White, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlayArrow,
                                            contentDescription = "Play Mix",
                                            tint = Color.Black,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Rekomendasi berdasarkan kebiasaan mendengar (diurutkan oleh profil selera)
        if (selectedMood == "All" && forYou.isNotEmpty()) {
            item { TrackCarouselSection("Untuk kamu", forYou, onPlayTrack) }
        }
        if (selectedMood == "All" && favoriteArtist != null && moreFromArtist.size >= 3) {
            item { TrackCarouselSection("Karena kamu suka $favoriteArtist", moreFromArtist, onPlayTrack) }
        }
        if (selectedMood == "All" && mostPlayed.isNotEmpty()) {
            item { TrackCarouselSection("Sering diputar", mostPlayed, onPlayTrack) }
        }
        if (selectedMood == "All") {
            moodSections.forEach { section ->
                item(key = "mood_" + section.first) {
                    TrackCarouselSection(section.first, section.second, onPlayTrack)
                }
            }
        }

        // Recently Played
        if (recentTracks.isNotEmpty()) {
            item {
                Text(
                    text = "Terakhir diputar",
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(12.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(recentTracks) { track ->
                        Column(
                            modifier = Modifier
                                .width(130.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onPlayTrack(track, recentTracks) }
                        ) {
                            AsyncImage(
                                model = track.coverUrl,
                                contentDescription = "Cover",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(130.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = MaterialTheme.colorScheme.onBackground,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = track.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackCarouselSection(
    title: String,
    tracks: List<TrackEntity>,
    onPlayTrack: (TrackEntity, List<TrackEntity>) -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(12.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(tracks) { track ->
                Column(
                    modifier = Modifier
                        .width(130.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onPlayTrack(track, tracks) }
                ) {
                    AsyncImage(
                        model = track.coverUrl,
                        contentDescription = track.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(130.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
