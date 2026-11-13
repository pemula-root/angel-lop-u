package com.example

import com.example.feature.account.AccountScreen
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.sp
import com.example.ui.theme.normalizeAccentColor
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.core.data.model.PlaylistCoverType
import com.example.core.data.model.PlaylistEntity
import com.example.core.data.model.SearchHistoryEntity
import com.example.core.data.model.TrackEntity
import com.example.core.data.repository.ThemeStyle
import com.example.feature.carmode.CarModeScreen
import com.example.feature.home.HomeScreen
import com.example.feature.library.LibraryScreen
import com.example.feature.podcast.PodcastScreen
import com.example.feature.search.SearchScreen
import com.example.feature.settings.SettingsScreen
import com.example.feature.stats.StatsRewindScreen
import com.example.mix.AutoMix
import com.example.ui.components.FullPlayer
import com.example.ui.components.MiniPlayer
import com.example.ui.theme.UnMusicTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class AppScreen(val title: String) {
    HOME("Home"),
    SEARCH("Search"),
    LIBRARY("Library"),
    PODCASTS("Podcasts"),
    STATS("Stats"),
    SETTINGS("Settings"),
    ACCOUNT("Akun")
}

class MainActivity : ComponentActivity() {

    override fun onStop() {
        super.onStop()
        (application as UnMusicApplication).cloudPushSoon()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        enableEdgeToEdge()

        val app = application as UnMusicApplication
        val playerManager = app.playerManager
        val database = app.database
        val preferencesRepository = app.preferencesRepository
        val downloadManager = app.downloadManager
        val innerTubeClient = app.innerTubeClient
        val podcastManager = app.podcastManager
        val autoMixGenerator = app.autoMixGenerator
        val statsManager = app.statsManager
        val cloud = app.cloud
        val tasteEngine = app.tasteEngine

        setContent {
            val scope = rememberCoroutineScope()

            // Observe settings & theme
            val themeStyle by preferencesRepository.themeStyleFlow.collectAsState(initial = ThemeStyle.DYNAMIC)
            val accentColorInt by preferencesRepository.accentColorFlow.collectAsState(initial = 0xFF00E5FF.toInt())

            // Observe player states
            val currentTrack by playerManager.currentTrack.collectAsState()
            val isPlaying by playerManager.isPlaying.collectAsState()
            val positionMs by playerManager.playbackPositionMs.collectAsState()
            val durationMs by playerManager.durationMs.collectAsState()
            val playerErrorMessage by playerManager.playerErrorMessage.collectAsState()

            val downloadStates by downloadManager.downloadStates.collectAsState()
            LaunchedEffect(Unit) {
                downloadManager.events.collect { msg ->
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                }
            }
            val playerErrorDetail by playerManager.playerErrorDetail.collectAsState()
            var showErrorDialog by remember { mutableStateOf(false) }
            LaunchedEffect(playerErrorMessage) {
                if (playerErrorMessage != null) {
                    showErrorDialog = true
                    playerManager.clearPlayerErrorMessage()
                }
            }
            if (showErrorDialog) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showErrorDialog = false },
                    title = { androidx.compose.material3.Text("Lagu tidak dapat diputar") },
                    text = {
                        androidx.compose.foundation.layout.Column(
                            modifier = androidx.compose.ui.Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())
                        ) {
                            androidx.compose.material3.Text(
                                text = if (playerErrorDetail.isBlank()) "Tidak ada detail." else playerErrorDetail,
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                            )
                        }
                    },
                    confirmButton = {
                        androidx.compose.foundation.layout.Row {
                            androidx.compose.material3.TextButton(onClick = {
                                val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("log", playerErrorDetail))
                                Toast.makeText(this@MainActivity, "Detail disalin", Toast.LENGTH_SHORT).show()
                            }) { androidx.compose.material3.Text("Salin") }
                            androidx.compose.material3.TextButton(onClick = {
                                showErrorDialog = false
                                playerManager.retryCurrent()
                            }) { androidx.compose.material3.Text("Coba lagi") }
                        }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { showErrorDialog = false }) {
                            androidx.compose.material3.Text("Tutup")
                        }
                    }
                )
            }

            // Dynamic color from track dominant color
            val dominantColor = remember(currentTrack, themeStyle, accentColorInt) {
                normalizeAccentColor(
                    if (themeStyle == ThemeStyle.DYNAMIC && currentTrack != null) {
                        Color(currentTrack!!.dominantColor)
                    } else {
                        Color(accentColorInt)
                    }
                )
            }

            // Observe database lists
            val allTracks by database.trackDao().getAllTracksFlow().collectAsState(initial = emptyList())
            val rankedTracks = remember(allTracks) { tasteEngine.quickPicks(allTracks) }
            val likedTracks by database.trackDao().getLikedTracksFlow().collectAsState(initial = emptyList())
            val downloadedTracks by database.trackDao().getDownloadedTracksFlow().collectAsState(initial = emptyList())
            val recentTracks by database.trackDao().getRecentlyPlayedFlow().collectAsState(initial = emptyList())
            val playlists by database.playlistDao().getAllPlaylistsFlow().collectAsState(initial = emptyList())
            val searchHistory by database.searchHistoryDao().getRecentSearchesFlow().collectAsState(initial = emptyList())

            var autoMixes by remember { mutableStateOf<List<AutoMix>>(emptyList()) }
            LaunchedEffect(allTracks) {
                if (allTracks.isNotEmpty()) {
                    autoMixes = autoMixGenerator.generateMixes()
                }
            }

            // Navigation state
            val screenPrefs = remember { getSharedPreferences("ui_prefs", MODE_PRIVATE) }
            var currentScreen by remember {
                mutableStateOf(
                    try {
                        AppScreen.valueOf(screenPrefs.getString("last_screen", "HOME") ?: "HOME")
                    } catch (e: Exception) {
                        AppScreen.HOME
                    }
                )
            }
            LaunchedEffect(currentScreen) {
                screenPrefs.edit().putString("last_screen", currentScreen.name).apply()
            }
            var isFullPlayerVisible by remember { mutableStateOf(false) }
            var isCarModeVisible by remember { mutableStateOf(false) }

            // Back handler
            BackHandler(enabled = isFullPlayerVisible || isCarModeVisible || currentScreen != AppScreen.HOME) {
                when {
                    isCarModeVisible -> isCarModeVisible = false
                    isFullPlayerVisible -> isFullPlayerVisible = false
                    else -> currentScreen = AppScreen.HOME
                }
            }

            UnMusicTheme(themeStyle = themeStyle, dominantColor = dominantColor) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = Color.Transparent,
                        bottomBar = {
                            Column {
                                // MiniPlayer anchored above Navigation Bar
                                AnimatedVisibility(
                                    visible = currentTrack != null && !isFullPlayerVisible && !isCarModeVisible,
                                    enter = slideInVertically { it } + fadeIn(),
                                    exit = slideOutVertically { it } + fadeOut()
                                ) {
                                    MiniPlayer(
                                        track = currentTrack,
                                        isPlaying = isPlaying,
                                        playbackPositionMs = positionMs,
                                        durationMs = durationMs,
                                        dominantColor = dominantColor,
                                        onPlayPause = { playerManager.togglePlayPause() },
                                        onSkipNext = { playerManager.skipToNext() },
                                        onSkipPrevious = { playerManager.skipToPrevious() },
                                        onToggleLike = { playerManager.toggleLikeCurrentTrack() },
                                        onClick = { isFullPlayerVisible = true }
                                    )
                                }

                                // Bottom Navigation Bar
                                NavigationBar(
                                    containerColor = MaterialTheme.colorScheme.background,
                                    tonalElevation = 0.dp,
                                    modifier = Modifier.testTag("bottom_nav_bar")
                                ) {
                                    NavigationBarItem(
                                        selected = currentScreen == AppScreen.HOME,
                                        onClick = { currentScreen = AppScreen.HOME },
                                        icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                                        label = { Text("Home", maxLines = 1, softWrap = false, fontSize = 10.sp) },
                                        alwaysShowLabel = true,
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            indicatorColor = Color.Transparent
                                        )
                                    )
                                    NavigationBarItem(
                                        selected = currentScreen == AppScreen.SEARCH,
                                        onClick = { currentScreen = AppScreen.SEARCH },
                                        icon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                                        label = { Text("Search", maxLines = 1, softWrap = false, fontSize = 10.sp) },
                                        alwaysShowLabel = true,
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            indicatorColor = Color.Transparent
                                        )
                                    )
                                    NavigationBarItem(
                                        selected = currentScreen == AppScreen.LIBRARY,
                                        onClick = { currentScreen = AppScreen.LIBRARY },
                                        icon = { Icon(Icons.Default.LibraryMusic, contentDescription = "Library") },
                                        label = { Text("Library", maxLines = 1, softWrap = false, fontSize = 10.sp) },
                                        alwaysShowLabel = true,
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            indicatorColor = Color.Transparent
                                        )
                                    )
                                    NavigationBarItem(
                                        selected = currentScreen == AppScreen.PODCASTS,
                                        onClick = { currentScreen = AppScreen.PODCASTS },
                                        icon = { Icon(Icons.Default.Podcasts, contentDescription = "Podcasts") },
                                        label = { Text("Podcasts", maxLines = 1, softWrap = false, fontSize = 10.sp) },
                                        alwaysShowLabel = true,
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                            selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            indicatorColor = Color.Transparent
                                        )
                                    )
                                }
                            }
                        }
                    ) { innerPadding ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                        ) {
                            when (currentScreen) {
                                AppScreen.HOME -> HomeScreen(
                                    recentTracks = recentTracks,
                                    allTracks = rankedTracks,
                                    autoMixes = autoMixes,
                                    dominantColor = dominantColor,
                                    onPlayTrack = { track, _ ->
                                        playerManager.playTrack(track, listOf(track))
                                    },
                                    onOpenCarMode = { isCarModeVisible = true },
                                    onOpenSettings = { currentScreen = AppScreen.SETTINGS },
                                    onOpenStats = { currentScreen = AppScreen.STATS },
                                    onOpenAccount = { currentScreen = AppScreen.ACCOUNT },
                                    onRetryRefresh = {
                                        scope.launch(Dispatchers.IO) {
                                            when (val res = innerTubeClient.getHomeCharts()) {
                                                is com.example.innertube.NetResult.Success -> {
                                                    if (res.data.isNotEmpty()) {
                                                        database.trackDao().insertTracks(res.data)
                                                    }
                                                }
                                                else -> {}
                                            }
                                        }
                                    }
                                )
                                AppScreen.SEARCH -> SearchScreen(
                                    innerTubeClient = innerTubeClient,
                                    searchHistory = searchHistory,
                                    dominantColor = dominantColor,
                                    onPlayTrack = { track, _ ->
                                        playerManager.playTrack(track, listOf(track))
                                    },
                                    onSaveSearch = { query ->
                                        scope.launch(Dispatchers.IO) {
                                            database.searchHistoryDao().insertSearch(SearchHistoryEntity(query))
                                        }
                                    },
                                    onDeleteSearch = { query ->
                                        scope.launch(Dispatchers.IO) {
                                            database.searchHistoryDao().deleteSearch(query)
                                        }
                                    },
                                    onClearHistory = {
                                        scope.launch(Dispatchers.IO) {
                                            database.searchHistoryDao().clearHistory()
                                        }
                                    }
                                )
                                AppScreen.LIBRARY -> LibraryScreen(
                                    allTracks = allTracks,
                                    likedTracks = likedTracks,
                                    downloadedTracks = downloadedTracks,
                                    playlists = playlists,
                                    dominantColor = dominantColor,
                                    onPlayTrack = { track, queue ->
                                        playerManager.playTrack(track, queue)
                                    },
                                    onCreatePlaylist = { name, coverType, customUri ->
                                        scope.launch(Dispatchers.IO) {
                                            database.playlistDao().insertPlaylist(
                                                PlaylistEntity(
                                                    id = "pl_${UUID.randomUUID()}",
                                                    name = name,
                                                    coverType = coverType,
                                                    customCoverUri = customUri
                                                )
                                            )
                                            app.cloudChanged()
                                        }
                                    },
                                    onDeletePlaylist = { playlist ->
                                        scope.launch(Dispatchers.IO) {
                                            database.playlistDao().deletePlaylist(playlist)
                                            app.cloudChanged()
                                        }
                                    },
                                    onExportBackupJson = {
                                        exportBackupJson(allTracks, playlists)
                                    }
                                )
                                AppScreen.PODCASTS -> PodcastScreen(
                                    podcastManager = podcastManager,
                                    dominantColor = dominantColor,
                                    onPlayEpisode = { track ->
                                        playerManager.playTrack(track, listOf(track))
                                    }
                                )
                                AppScreen.STATS -> StatsRewindScreen(
                                    statsManager = statsManager,
                                    dominantColor = dominantColor
                                )
                                AppScreen.SETTINGS -> SettingsScreen(
                                    preferencesRepository = preferencesRepository,
                                    downloadManager = downloadManager,
                                    dominantColor = dominantColor,
                                    onExportBackupJson = {
                                        exportBackupJson(allTracks, playlists)
                                    },
                                    onOpenAccount = { currentScreen = AppScreen.ACCOUNT }
                                )
                                AppScreen.ACCOUNT -> AccountScreen(
                                    cloud = cloud,
                                    dominantColor = dominantColor
                                )
                            }
                        }
                    }

                    // Full Player overlay with smooth slide-up
                    AnimatedVisibility(
                        visible = isFullPlayerVisible,
                        enter = slideInVertically(initialOffsetY = { it }, animationSpec = tween(350)),
                        exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(350))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) { detectTapGestures { } }
                        ) {
                            FullPlayer(
                                playerManager = playerManager,
                                dominantColor = dominantColor,
                                onDismiss = { isFullPlayerVisible = false },
                                onDownloadTrack = { trackToDownload ->
                                    downloadManager.enqueue(trackToDownload)
                                },
                                downloadStates = downloadStates,
                                onCancelDownload = { downloadManager.cancel(it.id) },
                                onDeleteDownload = { t -> scope.launch { downloadManager.deleteDownload(t) } }
                            )
                        }
                    }

                    // Car Mode overlay
                    AnimatedVisibility(
                        visible = isCarModeVisible,
                        enter = fadeIn(tween(250)),
                        exit = fadeOut(tween(250))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(Unit) { detectTapGestures { } }
                        ) {
                            CarModeScreen(
                                playerManager = playerManager,
                                onExit = { isCarModeVisible = false }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun exportBackupJson(tracks: List<TrackEntity>, playlists: List<PlaylistEntity>) {
        try {
            val root = JSONObject().apply {
                put("app", "UnMusic")
                put("version", "1.0")
                put("exportTimestamp", System.currentTimeMillis())

                val tracksArray = JSONArray()
                tracks.forEach { t ->
                    tracksArray.put(JSONObject().apply {
                        put("id", t.id)
                        put("title", t.title)
                        put("artist", t.artist)
                        put("album", t.album)
                        put("isLiked", t.isLiked)
                        put("playCount", t.playCount)
                    })
                }
                put("tracks", tracksArray)

                val playlistsArray = JSONArray()
                playlists.forEach { p ->
                    playlistsArray.put(JSONObject().apply {
                        put("id", p.id)
                        put("name", p.name)
                        put("coverType", p.coverType.name)
                    })
                }
                put("playlists", playlistsArray)
            }

            val sendIntent = Intent().apply {
                action = Intent.ACTION_SEND
                putExtra(Intent.EXTRA_TEXT, root.toString(2))
                type = "application/json"
            }
            startActivity(Intent.createChooser(sendIntent, "Backup UnMusic Library"))
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to export backup", Toast.LENGTH_SHORT).show()
        }
    }
}
