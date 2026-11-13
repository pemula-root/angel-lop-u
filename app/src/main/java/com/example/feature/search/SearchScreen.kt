package com.example.feature.search

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
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.core.data.model.SearchHistoryEntity
import com.example.core.data.model.TrackEntity
import com.example.innertube.InnerTubeAlbum
import com.example.innertube.InnerTubeArtist
import com.example.innertube.InnerTubeClient
import com.example.innertube.NetResult
import com.example.innertube.SearchResultGroup
import com.example.innertube.parser.ArtistPageData
import com.example.ui.components.TrackPlayPauseButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    innerTubeClient: InnerTubeClient,
    searchHistory: List<SearchHistoryEntity>,
    dominantColor: Color,
    onPlayTrack: (TrackEntity, List<TrackEntity>) -> Unit,
    onSaveSearch: (String) -> Unit,
    onDeleteSearch: (String) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("All") }
    val filters = listOf("All", "Songs", "Artists", "Albums")

    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var searchResult by remember { mutableStateOf<SearchResultGroup?>(null) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var isSearching by remember { mutableStateOf(false) }

    var selectedArtistForDetail by remember { mutableStateOf<InnerTubeArtist?>(null) }
    var selectedAlbumForDetail by remember { mutableStateOf<InnerTubeAlbum?>(null) }

    val scope = rememberCoroutineScope()
    var searchJob by remember { mutableStateOf<Job?>(null) }

    fun performSearch(query: String, filter: String) {
        if (query.isBlank()) {
            suggestions = emptyList()
            searchResult = null
            searchError = null
            return
        }
        searchJob?.cancel()
        searchJob = scope.launch {
            delay(300)
            isSearching = true
            searchError = null

            suggestions = innerTubeClient.getSearchSuggestions(query)

            val filterParam = when (filter) {
                "Songs" -> InnerTubeClient.FILTER_SONGS
                "Artists" -> InnerTubeClient.FILTER_ARTISTS
                "Albums" -> InnerTubeClient.FILTER_ALBUMS
                else -> null
            }

            when (val res = innerTubeClient.search(query, filterParam)) {
                is NetResult.Success -> {
                    searchResult = res.data
                    searchError = null
                }
                is NetResult.Empty -> {
                    searchResult = null
                    searchError = "Tidak ada hasil untuk '$query'"
                }
                is NetResult.Error -> {
                    searchResult = null
                    searchError = "Tidak dapat memuat hasil pencarian. ${res.message}"
                }
            }
            isSearching = false
        }
    }

    LaunchedEffect(searchQuery, selectedFilter) {
        performSearch(searchQuery, selectedFilter)
    }

    androidx.activity.compose.BackHandler(
        enabled = selectedAlbumForDetail != null || selectedArtistForDetail != null
    ) {
        if (selectedAlbumForDetail != null) selectedAlbumForDetail = null else selectedArtistForDetail = null
    }

    if (selectedAlbumForDetail != null) {
        AlbumDetailView(
            album = selectedAlbumForDetail!!,
            innerTubeClient = innerTubeClient,
            dominantColor = dominantColor,
            onBack = { selectedAlbumForDetail = null },
            onPlayTrack = onPlayTrack
        )
    } else if (selectedArtistForDetail != null) {
        ArtistDetailView(
            artist = selectedArtistForDetail!!,
            innerTubeClient = innerTubeClient,
            dominantColor = dominantColor,
            onBack = { selectedArtistForDetail = null },
            onPlayTrack = onPlayTrack,
            onOpenAlbum = { selectedAlbumForDetail = it }
        )
    } else {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Search Input Bar
            item {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search songs, artists, or lyrics...", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    leadingIcon = {
                        Icon(imageVector = Icons.Default.Search, contentDescription = "Search", tint = MaterialTheme.colorScheme.onSurface)
                    },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(imageVector = Icons.Default.Close, contentDescription = "Clear", tint = MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = dominantColor,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface
                    ),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("search_text_field")
                )
            }

            // Filter Chips
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    filters.forEach { filter ->
                        FilterChip(
                            selected = selectedFilter == filter,
                            onClick = { selectedFilter = filter },
                            label = { Text(filter) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = dominantColor,
                                selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                            )
                        )
                    }
                }
            }

            // Suggestions List
            if (searchQuery.isNotBlank() && suggestions.isNotEmpty() && searchResult == null) {
                item {
                    Text(
                        text = "Suggestions",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                items(suggestions) { sugg ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                searchQuery = sugg
                                onSaveSearch(sugg)
                            }
                            .padding(vertical = 10.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(text = sugg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }

            // Search History when query is empty
            if (searchQuery.isBlank() && searchHistory.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Recent Searches",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        TextButton(onClick = onClearHistory) {
                            Text("Clear all", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                items(searchHistory) { history ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { searchQuery = history.query }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(text = history.query, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                        }
                        IconButton(onClick = { onDeleteSearch(history.query) }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Loading state
            if (isSearching) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = dominantColor, modifier = Modifier.size(32.dp))
                    }
                }
            }

            // Error or Empty state
            if (!isSearching && searchError != null && searchQuery.isNotBlank()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = searchError!!,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { performSearch(searchQuery, selectedFilter) },
                                colors = ButtonDefaults.buttonColors(containerColor = dominantColor)
                            ) {
                                Text("Coba lagi", color = MaterialTheme.colorScheme.onPrimary)
                            }
                        }
                    }
                }
            }

            // Search Results
            searchResult?.let { result ->
                // Top Result (Artist)
                if ((selectedFilter == "All" || selectedFilter == "Artists") && result.topResultArtist != null) {
                    val topArt = result.topResultArtist
                    item {
                        Text(
                            text = "Top Result",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { selectedArtistForDetail = topArt },
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = topArt.bannerUrl,
                                    contentDescription = topArt.name,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(CircleShape)
                                )
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = topArt.name,
                                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Artist • ${topArt.subscribers}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = "View Artist",
                                    tint = dominantColor
                                )
                            }
                        }
                    }
                }

                // Songs List
                if ((selectedFilter == "All" || selectedFilter == "Songs") && result.songs.isNotEmpty()) {
                    item {
                        Text(
                            text = "Songs",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    }
                    items(result.songs) { song ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    onPlayTrack(song, result.songs)
                                    onSaveSearch(searchQuery)
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(
                                model = song.coverUrl,
                                contentDescription = song.title,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(52.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = song.title,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${song.artist} • ${song.album.ifBlank { "Single" }}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            TrackPlayPauseButton(
                                track = song,
                                tint = dominantColor,
                                onPlay = {
                                    onPlayTrack(song, result.songs)
                                    onSaveSearch(searchQuery)
                                }
                            )
                            com.example.ui.components.TrackMenuButton(track = song)
                        }
                    }
                }

                // Albums Horizontal Row
                if ((selectedFilter == "All" || selectedFilter == "Albums") && result.albums.isNotEmpty()) {
                    item {
                        Text(
                            text = "Albums",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(result.albums) { alb ->
                                Column(
                                    modifier = Modifier
                                        .width(130.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { selectedAlbumForDetail = alb }
                                ) {
                                    AsyncImage(
                                        model = alb.coverUrl,
                                        contentDescription = alb.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(130.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = alb.title,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = alb.artist,
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

                // Artists Horizontal Row
                if ((selectedFilter == "All" || selectedFilter == "Artists") && result.artists.isNotEmpty()) {
                    item {
                        Text(
                            text = "Artists",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onBackground
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            items(result.artists) { art ->
                                Column(
                                    modifier = Modifier
                                        .width(110.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { selectedArtistForDetail = art },
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    AsyncImage(
                                        model = art.bannerUrl,
                                        contentDescription = art.name,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(96.dp)
                                            .clip(CircleShape)
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = art.name,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.onSurface,
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
    }
}

@Composable
fun ArtistDetailView(
    artist: InnerTubeArtist,
    innerTubeClient: InnerTubeClient,
    dominantColor: Color,
    onBack: () -> Unit,
    onPlayTrack: (TrackEntity, List<TrackEntity>) -> Unit,
    onOpenAlbum: (InnerTubeAlbum) -> Unit = {}
) {
    var artistPage by remember { mutableStateOf<ArtistPageData?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun loadArtist() {
        isLoading = true
        errorMessage = null
    }

    LaunchedEffect(artist) {
        isLoading = true
        errorMessage = null
        when (val res = innerTubeClient.getArtistPage(artist.id, artist.name)) {
            is NetResult.Success -> {
                artistPage = res.data
                isLoading = false
            }
            is NetResult.Empty -> {
                errorMessage = "Informasi artis tidak ditemukan."
                isLoading = false
            }
            is NetResult.Error -> {
                errorMessage = "Gagal memuat artis. ${res.message}"
                isLoading = false
            }
        }
    }

    val page = artistPage
    val artistSongs = page?.songs ?: emptyList()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(bottom = 120.dp)
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
            ) {
                AsyncImage(
                    model = page?.artist?.bannerUrl?.ifBlank { artist.bannerUrl },
                    contentDescription = artist.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = 0.3f),
                                    MaterialTheme.colorScheme.background
                                )
                            )
                        )
                )
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .statusBarsPadding()
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(20.dp)
                ) {
                    Text(
                        text = page?.artist?.name ?: artist.name,
                        style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Black),
                        color = Color.White
                    )
                    Text(
                        text = page?.artist?.subscribers ?: artist.subscribers,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }
        }

        // Action Buttons: Play & Shuffle
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Button(
                    onClick = {
                        if (artistSongs.isNotEmpty()) onPlayTrack(artistSongs.first(), artistSongs)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = dominantColor),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Play", color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                    onClick = {
                        if (artistSongs.isNotEmpty()) {
                            val shuffled = artistSongs.shuffled()
                            onPlayTrack(shuffled.first(), shuffled)
                        }
                    },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(imageVector = Icons.Default.Shuffle, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Shuffle", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)
                }
            }
        }

        if (isLoading) {
            item {
                Box(modifier = Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = dominantColor)
                }
            }
        } else if (errorMessage != null) {
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = errorMessage!!, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            item {
                Text(
                    text = "Songs (${artistSongs.size})",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }

            items(artistSongs) { song ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlayTrack(song, artistSongs) }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AsyncImage(
                        model = song.coverUrl,
                        contentDescription = song.title,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = song.title,
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onBackground,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = song.album.ifBlank { "Single" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TrackPlayPauseButton(
                        track = song,
                        tint = dominantColor,
                        onPlay = { onPlayTrack(song, artistSongs) }
                    )
                    com.example.ui.components.TrackMenuButton(track = song)
                }
            }

            // Albums / Singles row
            if (page?.albums?.isNotEmpty() == true) {
                item {
                    Text(
                        text = "Albums & Singles",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(page.albums) { alb ->
                            Column(modifier = Modifier.width(130.dp).clickable { onOpenAlbum(alb) }) {
                                AsyncImage(
                                    model = alb.coverUrl,
                                    contentDescription = alb.title,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.size(130.dp).clip(RoundedCornerShape(10.dp))
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = alb.title,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = alb.year,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
