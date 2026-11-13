package com.example.feature.library

import com.example.ui.theme.UnColors
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import coil.compose.AsyncImage
import com.example.core.data.model.PlaylistCoverType
import com.example.core.data.model.PlaylistEntity
import com.example.core.data.model.TrackEntity
import kotlinx.coroutines.launch
import java.util.UUID

enum class LibrarySort {
    NAME,
    RECENT,
    DURATION,
    MOST_PLAYED
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    allTracks: List<TrackEntity>,
    likedTracks: List<TrackEntity>,
    downloadedTracks: List<TrackEntity>,
    playlists: List<PlaylistEntity>,
    dominantColor: Color,
    onPlayTrack: (TrackEntity, List<TrackEntity>) -> Unit,
    onCreatePlaylist: (String, PlaylistCoverType, String) -> Unit,
    onDeletePlaylist: (PlaylistEntity) -> Unit,
    onExportBackupJson: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs = listOf("Songs", "Playlists", "Liked", "Downloads", "Albums", "Artists")
    val tabPrefs = androidx.compose.ui.platform.LocalContext.current.getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE)
    var selectedTabIndex by remember { mutableIntStateOf(tabPrefs.getInt("library_tab", 0)) }
    androidx.compose.runtime.LaunchedEffect(selectedTabIndex) {
        tabPrefs.edit().putInt("library_tab", selectedTabIndex).apply()
    }
    var currentSort by remember { mutableStateOf(LibrarySort.NAME) }
    var isGridView by remember { mutableStateOf(false) }
    var localSearchQuery by remember { mutableStateOf("") }

    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var selectedPlaylistForDetail by remember { mutableStateOf<PlaylistEntity?>(null) }
    androidx.activity.compose.BackHandler(enabled = selectedPlaylistForDetail != null) {
        selectedPlaylistForDetail = null
    }

    // Media file picker launcher for local import
    val audioFilePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris: List<Uri> ->
        // Handle local media audio URIs
    }

    if (selectedPlaylistForDetail != null) {
        PlaylistDetailView(
            playlist = selectedPlaylistForDetail!!,
            allTracks = allTracks,
            dominantColor = dominantColor,
            onBack = { selectedPlaylistForDetail = null },
            onPlayTrack = onPlayTrack,
            onDelete = {
                onDeletePlaylist(it)
                selectedPlaylistForDetail = null
            }
        )
    } else {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            // Header: Title, Local Import, Backup, View toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Library",
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = UnColors.text
                )

                Row {
                    // Import local files
                    IconButton(onClick = { audioFilePicker.launch("audio/*") }) {
                        Icon(
                            imageVector = Icons.Default.FolderOpen,
                            contentDescription = "Import local audio",
                            tint = UnColors.text
                        )
                    }

                    // Export / Backup JSON
                    IconButton(onClick = onExportBackupJson) {
                        Icon(
                            imageVector = Icons.Default.Backup,
                            contentDescription = "Backup JSON",
                            tint = UnColors.text
                        )
                    }

                    // Grid / List toggle
                    IconButton(onClick = { isGridView = !isGridView }) {
                        Icon(
                            imageVector = if (isGridView) Icons.Default.ViewList else Icons.Default.GridView,
                            contentDescription = "Toggle Grid/List",
                            tint = UnColors.text
                        )
                    }
                }
            }

            // Tabs
            ScrollableTabRow(
                selectedTabIndex = selectedTabIndex,
                edgePadding = 0.dp,
                containerColor = Color.Transparent,
                contentColor = UnColors.text,
                indicator = {}
            ) {
                tabs.forEachIndexed { index, tabTitle ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = {
                            Text(
                                text = tabTitle,
                                fontWeight = if (selectedTabIndex == index) FontWeight.Bold else FontWeight.Normal,
                                color = if (selectedTabIndex == index) dominantColor else UnColors.text.copy(alpha = 0.6f)
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Local Search & Sort Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = localSearchQuery,
                    onValueChange = { localSearchQuery = it },
                    placeholder = { Text("Filter library...", color = Color.Gray) },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = Color.Gray, modifier = Modifier.size(20.dp))
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = dominantColor,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedTextColor = UnColors.text,
                        unfocusedTextColor = UnColors.text
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(8.dp))

                var showSortMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(
                        onClick = { showSortMenu = true },
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                    ) {
                        Icon(imageVector = Icons.Default.Sort, contentDescription = "Sort", tint = dominantColor)
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        DropdownMenuItem(
                            text = { Text("Name", color = UnColors.text) },
                            onClick = { currentSort = LibrarySort.NAME; showSortMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Recently Added", color = UnColors.text) },
                            onClick = { currentSort = LibrarySort.RECENT; showSortMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Duration", color = UnColors.text) },
                            onClick = { currentSort = LibrarySort.DURATION; showSortMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Most Played", color = UnColors.text) },
                            onClick = { currentSort = LibrarySort.MOST_PLAYED; showSortMenu = false }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Main Tab Content
            when (selectedTabIndex) {
                0 -> { // Songs
                    val filtered = applySortAndFilter(allTracks, localSearchQuery, currentSort)
                    TrackListView(
                        tracks = filtered,
                        isGrid = isGridView,
                        dominantColor = dominantColor,
                        onPlayTrack = onPlayTrack
                    )
                }
                1 -> { // Playlists
                    PlaylistListView(
                        playlists = playlists,
                        onSelectPlaylist = { selectedPlaylistForDetail = it },
                        onCreateClick = { showCreatePlaylistDialog = true }
                    )
                }
                2 -> { // Liked
                    val filtered = applySortAndFilter(likedTracks, localSearchQuery, currentSort)
                    TrackListView(
                        tracks = filtered,
                        isGrid = isGridView,
                        dominantColor = dominantColor,
                        onPlayTrack = onPlayTrack
                    )
                }
                3 -> { // Downloads
                    val filtered = applySortAndFilter(downloadedTracks, localSearchQuery, currentSort)
                    TrackListView(
                        tracks = filtered,
                        isGrid = isGridView,
                        dominantColor = dominantColor,
                        onPlayTrack = onPlayTrack
                    )
                }
                4 -> { // Albums
                    val albums = allTracks.groupBy { it.album.ifBlank { "Singles" } }
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(bottom = 120.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(albums.keys.toList()) { albumName ->
                            val tracksInAlb = albums[albumName] ?: emptyList()
                            Column(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        if (tracksInAlb.isNotEmpty()) onPlayTrack(tracksInAlb.first(), tracksInAlb)
                                    }
                            ) {
                                AsyncImage(
                                    model = tracksInAlb.firstOrNull()?.coverUrl,
                                    contentDescription = albumName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(12.dp))
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = albumName,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = UnColors.text,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${tracksInAlb.size} songs",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = UnColors.text.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                }
                5 -> { // Artists
                    val artists = allTracks.groupBy { it.artist }
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 120.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(artists.keys.toList()) { artistName ->
                            val tracksByArtist = artists[artistName] ?: emptyList()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable {
                                        if (tracksByArtist.isNotEmpty()) onPlayTrack(tracksByArtist.first(), tracksByArtist)
                                    }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = tracksByArtist.firstOrNull()?.coverUrl,
                                    contentDescription = artistName,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(52.dp)
                                        .clip(CircleShape)
                                )
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = artistName,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = UnColors.text
                                    )
                                    Text(
                                        text = "${tracksByArtist.size} songs in library",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = UnColors.text.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Create Playlist Dialog with Cover Options
        if (showCreatePlaylistDialog) {
            CreatePlaylistDialog(
                onDismiss = { showCreatePlaylistDialog = false },
                onCreate = { name, coverType, customUri ->
                    onCreatePlaylist(name, coverType, customUri)
                    showCreatePlaylistDialog = false
                }
            )
        }
    }
}

private fun applySortAndFilter(list: List<TrackEntity>, query: String, sort: LibrarySort): List<TrackEntity> {
    val filtered = if (query.isBlank()) list else list.filter {
        it.title.contains(query, ignoreCase = true) || it.artist.contains(query, ignoreCase = true)
    }
    return when (sort) {
        LibrarySort.NAME -> filtered.sortedBy { it.title }
        LibrarySort.RECENT -> filtered.sortedByDescending { it.lastPlayedAt }
        LibrarySort.DURATION -> filtered.sortedByDescending { it.durationMs }
        LibrarySort.MOST_PLAYED -> filtered.sortedByDescending { it.playCount }
    }
}

@Composable
fun TrackListView(
    tracks: List<TrackEntity>,
    isGrid: Boolean,
    dominantColor: Color,
    onPlayTrack: (TrackEntity, List<TrackEntity>) -> Unit
) {
    if (isGrid) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(bottom = 120.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(tracks) { track ->
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onPlayTrack(track, tracks) }
                ) {
                    AsyncImage(
                        model = track.coverUrl,
                        contentDescription = track.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(12.dp))
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = UnColors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = UnColors.text.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(tracks) { track ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onPlayTrack(track, tracks) }
                        .padding(horizontal = 4.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = track.coverUrl,
                        contentDescription = track.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = UnColors.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${track.artist} • ${track.album.ifBlank { "Single" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = UnColors.text.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    com.example.ui.components.TrackDownloadButton(
                        track = track,
                        tint = dominantColor,
                        size = 36.dp
                    )
                    IconButton(onClick = { onPlayTrack(track, tracks) }) {
                        Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Play", tint = dominantColor)
                    }
                    com.example.ui.components.TrackMenuButton(
                        track = track,
                        tint = UnColors.text.copy(alpha = 0.7f)
                    )
                }
            }
        }
    }
}

@Composable
fun PlaylistListView(
    playlists: List<PlaylistEntity>,
    onSelectPlaylist: (PlaylistEntity) -> Unit,
    onCreateClick: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE) }
    var gridMode by remember { mutableStateOf(prefs.getBoolean("playlist_grid", false)) }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    gridMode = false
                    prefs.edit().putBoolean("playlist_grid", false).apply()
                }) {
                    Icon(
                        imageVector = Icons.Default.ViewList,
                        contentDescription = "Tampilan daftar",
                        tint = if (!gridMode) UnColors.text else Color.Gray
                    )
                }
                IconButton(onClick = {
                    gridMode = true
                    prefs.edit().putBoolean("playlist_grid", true).apply()
                }) {
                    Icon(
                        imageVector = Icons.Default.GridView,
                        contentDescription = "Tampilan kotak",
                        tint = if (gridMode) UnColors.text else Color.Gray
                    )
                }
            }
        }

        if (!gridMode) {
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(onClick = onCreateClick),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(imageVector = Icons.Default.Add, contentDescription = "Baru", tint = UnColors.text)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "Playlist baru",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = UnColors.text
                        )
                    }
                }
            }

            items(playlists) { playlist ->
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onSelectPlaylist(playlist) },
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PlaylistCoverThumbnail(playlist = playlist, modifier = Modifier.size(52.dp))
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = playlist.name,
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = UnColors.text
                            )
                            Text(
                                text = playlistSubtitle(playlist),
                                style = MaterialTheme.typography.bodySmall,
                                color = UnColors.text.copy(alpha = 0.6f)
                            )
                        }
                        Icon(imageVector = Icons.Default.ChevronRight, contentDescription = null, tint = Color.Gray)
                    }
                }
            }
        } else {
            val cells: List<PlaylistEntity?> = listOf<PlaylistEntity?>(null) + playlists
            items(cells.chunked(2)) { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    pair.forEach { cell ->
                        Box(modifier = Modifier.weight(1f)) {
                            if (cell == null) {
                                PlaylistNewSquare(onCreateClick)
                            } else {
                                PlaylistSquareCard(cell, onSelectPlaylist)
                            }
                        }
                    }
                    if (pair.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun playlistSubtitle(playlist: PlaylistEntity): String {
    if (playlist.isSmart) return "Playlist pintar"
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.example.UnMusicApplication
    val tracks by app.database.playlistDao().getTracksForPlaylistFlow(playlist.id)
        .collectAsState(initial = emptyList())
    return "${tracks.size} lagu"
}

@Composable
private fun PlaylistSquareCard(playlist: PlaylistEntity, onSelect: (PlaylistEntity) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onSelect(playlist) }
            .padding(bottom = 4.dp)
    ) {
        PlaylistCoverThumbnail(
            playlist = playlist,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = playlist.name,
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = UnColors.text,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
        )
        Text(
            text = playlistSubtitle(playlist),
            style = MaterialTheme.typography.bodySmall,
            color = UnColors.text.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun PlaylistNewSquare(onCreateClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onCreateClick)
            .padding(bottom = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(imageVector = Icons.Default.Add, contentDescription = "Baru", tint = UnColors.text)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Playlist baru",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
            color = UnColors.text
        )
    }
}

@Composable
fun PlaylistCoverThumbnail(playlist: PlaylistEntity, modifier: Modifier = Modifier) {
    when (playlist.coverType) {
        PlaylistCoverType.ANIMATED_GRADIENT -> {
            val infiniteTransition = rememberInfiniteTransition(label = "playlist_gradient")
            val offset by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = 1000f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 4000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "offset"
            )
            Box(
                modifier = modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xFF6366F1), Color(0xFFA855F7), Color(0xFFEC4899))
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = Icons.Default.QueueMusic, contentDescription = null, tint = Color.White)
            }
        }
        PlaylistCoverType.CUSTOM_IMAGE -> {
            AsyncImage(
                model = playlist.customCoverUri,
                contentDescription = playlist.name,
                contentScale = ContentScale.Crop,
                modifier = modifier.clip(RoundedCornerShape(12.dp))
            )
        }
        else -> PlaylistCollageCover(playlist, modifier)
    }
}

/** Sampul bawaan: kolase 4 bagian dari sampul lagu teratas di playlist. */
@Composable
private fun PlaylistCollageCover(playlist: PlaylistEntity, modifier: Modifier) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.example.UnMusicApplication
    val tracks by app.database.playlistDao().getTracksForPlaylistFlow(playlist.id)
        .collectAsState(initial = emptyList())
    val covers = remember(tracks) {
        tracks.map { it.coverUrl }.filter { it.isNotBlank() }.distinct().take(4)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        when {
            covers.isEmpty() -> {
                Icon(imageVector = Icons.Default.QueueMusic, contentDescription = null, tint = UnColors.text)
            }
            covers.size == 1 -> {
                AsyncImage(
                    model = covers[0],
                    contentDescription = playlist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            else -> {
                val cells = (0 until 4).map { covers[it % covers.size] }
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        CollageCell(cells[0], Modifier.weight(1f).fillMaxHeight())
                        CollageCell(cells[1], Modifier.weight(1f).fillMaxHeight())
                    }
                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        CollageCell(cells[2], Modifier.weight(1f).fillMaxHeight())
                        CollageCell(cells[3], Modifier.weight(1f).fillMaxHeight())
                    }
                }
            }
        }
    }
}

@Composable
private fun CollageCell(url: String, modifier: Modifier) {
    AsyncImage(
        model = url,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
    )
}

@Composable
fun CreatePlaylistDialog(
    onDismiss: () -> Unit,
    onCreate: (String, PlaylistCoverType, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedCoverType by remember { mutableStateOf(PlaylistCoverType.DEFAULT) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Playlist", color = UnColors.text, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Playlist name") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = UnColors.text,
                        unfocusedTextColor = UnColors.text
                    )
                )

                Text("Cover Style", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedCoverType == PlaylistCoverType.DEFAULT,
                        onClick = { selectedCoverType = PlaylistCoverType.DEFAULT },
                        label = { Text("Default") }
                    )
                    FilterChip(
                        selected = selectedCoverType == PlaylistCoverType.ANIMATED_GRADIENT,
                        onClick = { selectedCoverType = PlaylistCoverType.ANIMATED_GRADIENT },
                        label = { Text("Animated") }
                    )
                    FilterChip(
                        selected = selectedCoverType == PlaylistCoverType.MOSAIC,
                        onClick = { selectedCoverType = PlaylistCoverType.MOSAIC },
                        label = { Text("Mosaic") }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onCreate(name, selectedCoverType, "") },
                enabled = name.isNotBlank()
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color.Gray) }
        },
        containerColor = MaterialTheme.colorScheme.surface
    )
}

@Composable
fun PlaylistDetailView(
    playlist: PlaylistEntity,
    allTracks: List<TrackEntity>,
    dominantColor: Color,
    onBack: () -> Unit,
    onPlayTrack: (TrackEntity, List<TrackEntity>) -> Unit,
    onDelete: (PlaylistEntity) -> Unit
) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.example.UnMusicApplication
    val playlistTracks by app.database.playlistDao().getTracksForPlaylistFlow(playlist.id)
        .collectAsState(initial = emptyList())
    val allPlaylists by app.database.playlistDao().getAllPlaylistsFlow().collectAsState(initial = emptyList())
    val livePlaylist = allPlaylists.firstOrNull { it.id == playlist.id } ?: playlist
    val removeScope = androidx.compose.runtime.rememberCoroutineScope()
    var showRename by remember { mutableStateOf(false) }
    var showEditSongs by remember { mutableStateOf(false) }
    var showEditCover by remember { mutableStateOf(false) }
    val tracks = remember(playlist, allTracks, playlistTracks) {
        if (playlist.name == "Liked Songs") allTracks.filter { it.isLiked }
        else if (playlist.name == "Most Played") allTracks.sortedByDescending { it.playCount }.take(10)
        else playlistTracks
    }
    if (showRename) {
        com.example.ui.components.RenamePlaylistDialog(
            playlist = livePlaylist,
            onDismiss = { showRename = false }
        )
    }
    if (showEditCover) {
        com.example.ui.components.EditPlaylistCoverDialog(
            playlist = livePlaylist,
            tracks = playlistTracks,
            tint = dominantColor,
            onDismiss = { showEditCover = false }
        )
    }
    if (showEditSongs) {
        com.example.ui.components.EditPlaylistSongsDialog(
            playlist = playlist,
            allTracks = allTracks,
            tint = dominantColor,
            onDismiss = { showEditSongs = false }
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 120.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(imageVector = Icons.Default.ArrowBack, contentDescription = "Back", tint = UnColors.text)
                }
                IconButton(onClick = { onDelete(playlist) }) {
                    Icon(imageVector = Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
                }
            }
        }

        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                PlaylistCoverThumbnail(playlist = livePlaylist, modifier = Modifier.size(160.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = livePlaylist.name,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                    color = UnColors.text
                )
                Text(
                    text = "${tracks.size} lagu",
                    style = MaterialTheme.typography.bodyMedium,
                    color = UnColors.text.copy(alpha = 0.6f)
                )
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = { if (tracks.isNotEmpty()) onPlayTrack(tracks.first(), tracks) },
                    colors = ButtonDefaults.buttonColors(containerColor = dominantColor),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Putar semua", color = Color.Black, fontWeight = FontWeight.Bold)
                }
                if (tracks.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = {
                        val shuffled = tracks.shuffled()
                        onPlayTrack(shuffled.first(), shuffled)
                    }) {
                        Text("Acak", color = UnColors.text)
                    }
                }
                if (!playlist.isSmart) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = { showEditSongs = true }) {
                        Text("Tambah atau keluarkan lagu", color = UnColors.text)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = { showEditCover = true }) {
                        Text("Ubah sampul", color = UnColors.text)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(onClick = { showRename = true }) {
                        Text("Ubah nama", color = UnColors.text)
                    }
                }
            }
        }

        if (tracks.isEmpty()) {
            item {
                Text(
                    text = "Playlist masih kosong",
                    style = MaterialTheme.typography.bodyMedium,
                    color = UnColors.text.copy(alpha = 0.55f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }

        items(tracks) { track ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlayTrack(track, tracks) }
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AsyncImage(
                    model = track.coverUrl,
                    contentDescription = track.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = UnColors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = UnColors.text.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { onPlayTrack(track, tracks) }) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = "Play", tint = dominantColor)
                }
                com.example.ui.components.TrackMenuButton(
                    track = track,
                    tint = UnColors.text.copy(alpha = 0.7f),
                    onRemoveFromPlaylist = if (playlist.isSmart) null else ({
                        removeScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            app.database.playlistDao().removeTrackFromPlaylist(playlist.id, track.id)
                        }
                        Unit
                    })
                )
            }
        }
    }
}
