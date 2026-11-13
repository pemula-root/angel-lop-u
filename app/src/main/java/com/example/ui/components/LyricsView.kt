package com.example.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.graphics.TransformOrigin
import com.example.ui.theme.deepenAccent
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.lyrics.LyricLine
import com.example.lyrics.LyricsFollowState
import com.example.lyrics.LyricsParser
import com.example.lyrics.LyricsState
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun LyricsView(
    lrcText: String,
    currentPositionMs: Long,
    dominantColor: Color,
    isFullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    syncedJson: String = "",
    status: String = "FOUND",
    onRetry: () -> Unit = {},
    trackId: String = ""
) {
    val lines = remember(lrcText, syncedJson) { LyricsParser.parse(lrcText, syncedJson) }
    val listState = rememberLazyListState()
    val lyricsState = remember(listState) { LyricsState(listState) }
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    // Ukuran huruf dan perataan tersimpan, jadi tidak kembali ke bawaan saat pindah ke layar penuh atau dibuka ulang
    val uiPrefs = remember { context.getSharedPreferences("ui_prefs", android.content.Context.MODE_PRIVATE) }
    var fontSizeSp by remember { mutableStateOf(uiPrefs.getFloat("lyric_font_size", 24f)) }
    var alignCenter by remember { mutableStateOf(uiPrefs.getBoolean("lyric_align_center", true)) }
    LaunchedEffect(fontSizeSp, alignCenter) {
        uiPrefs.edit()
            .putFloat("lyric_font_size", fontSizeSp)
            .putBoolean("lyric_align_center", alignCenter)
            .apply()
    }

    // Lirik teks biasa (tanpa waktu) ditampilkan statis, tanpa sorotan dan tanpa auto-scroll
    val synced = remember(lines) { LyricsParser.isSynced(lines) }
    val plainMode = lines.isNotEmpty() && !synced

    // Posisi dibaca lewat State supaya daftar lirik tidak dikomposisi ulang di setiap frame pemutar
    val positionState = rememberUpdatedState(currentPositionMs)
    val introMs = remember(lines, synced) { if (synced) lines.first().timeMs else 0L }
    // Selama intro (sebelum baris pertama dinyanyikan) tidak ada baris yang menyala
    val inIntro by remember(synced, introMs) {
        derivedStateOf { synced && introMs >= 2500L && positionState.value < introMs - 250L }
    }
    val activeIndex by remember(lines, synced, introMs) {
        derivedStateOf {
            val pos = positionState.value
            val intro = synced && introMs >= 2500L && pos < introMs - 250L
            if (!synced || intro) -1 else LyricsParser.findActiveLineIndex(lines, pos)
        }
    }

    // Keterangan sumber lirik (disimpan sebagai tag [by:...] pada teks LRC)
    val sourceLabel = remember(lrcText) {
        Regex("\\[by:([^\\]]+)]").find(lrcText)?.groupValues?.get(1)?.trim()
    }
    val hasWords = remember(lines) { lines.any { it.words.isNotEmpty() } }

    // Auto-scroll when activeIndex changes and we are FOLLOWING
    LaunchedEffect(activeIndex, lyricsState.follow) {
        if (lyricsState.follow == LyricsFollowState.FOLLOWING && activeIndex >= 0) {
            // +1 karena item pertama di daftar adalah header intro
            lyricsState.scrollToActive(activeIndex + 1, animate = true)
        }
    }

    // Detect user scrolling to switch state machine to USER_SCROLLING -> DETACHED
    val isDragged by listState.interactionSource.collectIsDraggedAsState()
    LaunchedEffect(isDragged) {
        if (isDragged) {
            lyricsState.onUserScrollStart()
        } else if (lyricsState.follow == LyricsFollowState.USER_SCROLLING) {
            lyricsState.onUserScrollEnd()
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                if (isFullscreen) {
                    Brush.verticalGradient(
                        listOf(
                            deepenAccent(dominantColor),
                            Color(0xFF0B0B0D),
                            Color.Black
                        )
                    )
                } else {
                    Brush.verticalGradient(
                        listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.4f)
                        )
                    )
                }
            )
    ) {
        if (lines.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                if (status == "LOADING") {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.5.dp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Mencari lirik",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                } else {
                    Text(
                        text = "Lirik tidak tersedia",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Lirik belum ditemukan untuk lagu ini.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.45f),
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(onClick = onRetry) {
                        Text("Coba lagi", color = Color.White)
                    }
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("lyrics_list"),
                contentPadding = PaddingValues(
                    top = if (isFullscreen) 120.dp else 60.dp,
                    bottom = if (isFullscreen) 160.dp else 120.dp,
                    start = 24.dp,
                    end = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item(key = "intro_header") {
                    val headerHeight = when {
                        plainMode -> 28.dp
                        synced && introMs >= 2500L -> 56.dp
                        else -> 0.dp
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(headerHeight),
                        contentAlignment = Alignment.Center
                    ) {
                        if (plainMode) {
                            Text(
                                text = "Lirik tanpa sinkronisasi waktu",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White.copy(alpha = 0.5f)
                            )
                        } else if (inIntro) {
                            IntroDots(
                                progress = if (introMs > 0) (positionState.value.toFloat() / introMs).coerceIn(0f, 1f) else 0f
                            )
                        }
                    }
                }

                itemsIndexed(
                    items = lines,
                    key = { index, item -> "${index}_${item.timeMs}" }
                ) { index, line ->
                    val isActive = index == activeIndex

                    // Jeda instrumental: titik-titik terisi bertahap sampai baris berikutnya dimulai
                    if (line.gap) {
                        val nextTime = lines.getOrNull(index + 1)?.timeMs ?: (line.timeMs + 4000L)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(if (isActive) 44.dp else 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isActive) {
                                IntroDots(
                                    progress = ((positionState.value - line.timeMs).toFloat() /
                                        (nextTime - line.timeMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
                                )
                            }
                        }
                        return@itemsIndexed
                    }

                    val distance = abs(index - activeIndex)
                    val isPast = index < activeIndex

                    // Animated GPU visual styling
                    val animatedScale by animateFloatAsState(
                        targetValue = if (isActive) 1.05f else if (plainMode) 1f else 0.95f,
                        animationSpec = tween(durationMillis = 250),
                        label = "scale"
                    )

                    val targetAlpha = when {
                        plainMode -> 0.92f
                        isActive -> 1.0f
                        isPast -> (0.55f - distance * 0.08f).coerceIn(0.2f, 0.55f)
                        else -> (0.6f - distance * 0.10f).coerceIn(0.15f, 0.6f)
                    }

                    val animatedAlpha by animateFloatAsState(
                        targetValue = targetAlpha,
                        animationSpec = tween(durationMillis = 250),
                        label = "alpha"
                    )

                    // Efek blur dibuat sekali per tingkat jarak (bukan di setiap frame animasi)
                    val blurLevel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !isActive && !plainMode) {
                        (distance * 3).coerceIn(0, 12)
                    } else {
                        0
                    }
                    val blurEffect = remember(blurLevel) {
                        if (blurLevel > 1) {
                            RenderEffect.createBlurEffect(
                                blurLevel.toFloat(),
                                blurLevel.toFloat(),
                                Shader.TileMode.CLAMP
                            ).asComposeRenderEffect()
                        } else {
                            null
                        }
                    }

                    // Hardware accelerated graphics layer
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable(enabled = synced) {
                                onSeekTo(line.timeMs)
                                scope.launch {
                                    lyricsState.resync(index + 1)
                                }
                            }
                            .padding(vertical = 6.dp, horizontal = 8.dp)
                            .graphicsLayer {
                                transformOrigin = TransformOrigin(if (alignCenter) 0.5f else 0f, 0.5f)
                                scaleX = animatedScale
                                scaleY = animatedScale
                                alpha = animatedAlpha
                                renderEffect = blurEffect
                            }
                    ) {
                        val lineStyle = MaterialTheme.typography.headlineSmall.copy(
                            fontSize = fontSizeSp.sp,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                            lineHeight = (fontSizeSp * 1.35f).sp,
                            shadow = if (isActive) {
                                Shadow(color = dominantColor.copy(alpha = 0.6f), offset = Offset.Zero, blurRadius = 28f)
                            } else {
                                null
                            }
                        )
                        val lineAlign = if (alignCenter) TextAlign.Center else TextAlign.Start

                        Text(
                            text = AnnotatedString(line.text),
                            style = lineStyle,
                            color = if (isActive) Color.White else Color.White.copy(alpha = 0.85f),
                            textAlign = lineAlign,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        }

        // Top control bar (Font size toggle, Align toggle, Fullscreen toggle)
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = if (isFullscreen) 40.dp else 8.dp, end = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    fontSizeSp = when (fontSizeSp) {
                        20f -> 24f
                        24f -> 28f
                        else -> 20f
                    }
                },
                modifier = Modifier
                    .size(36.dp)
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.TextFields,
                    contentDescription = "Font size",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(
                onClick = { alignCenter = !alignCenter },
                modifier = Modifier
                    .size(36.dp)
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape)
            ) {
                Icon(
                    imageVector = if (alignCenter) Icons.Default.FormatAlignCenter else Icons.Default.FormatAlignLeft,
                    contentDescription = "Alignment",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }

            IconButton(
                onClick = onToggleFullscreen,
                modifier = Modifier
                    .size(36.dp)
                    .background(Color.Black.copy(alpha = 0.35f), CircleShape)
            ) {
                Icon(
                    imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    contentDescription = "Toggle Fullscreen",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (lines.isNotEmpty() && sourceLabel != null) {
            val typeLabel = when {
                plainMode -> "teks biasa"
                hasWords -> "per kata"
                else -> "per baris"
            }
            Text(
                text = "$sourceLabel · $typeLabel",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.4f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = if (isFullscreen) 12.dp else 4.dp)
            )
        }

        // Floating SYNC button - appears with animation when DETACHED
        AnimatedVisibility(
            visible = synced && lyricsState.follow == LyricsFollowState.DETACHED,
            enter = fadeIn(tween(200)) + scaleIn(tween(200)),
            exit = fadeOut(tween(200)) + scaleOut(tween(200)),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(bottom = if (isFullscreen) 48.dp else 24.dp, end = 24.dp)
        ) {
            FilledTonalButton(
                onClick = {
                    scope.launch {
                        lyricsState.resync(activeIndex + 1)
                    }
                },
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = dominantColor,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(24.dp),
                elevation = ButtonDefaults.filledTonalButtonElevation(defaultElevation = 8.dp),
                modifier = Modifier.testTag("lyrics_sync_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Sync,
                    contentDescription = "Sync Lyrics",
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Sync",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun karaokeText(line: LyricLine, positionMs: Long): AnnotatedString = buildAnnotatedString {
    line.words.forEachIndexed { i, w ->
        val sung = positionMs >= w.startMs
        withStyle(SpanStyle(color = Color.White.copy(alpha = if (sung) 1f else 0.45f))) {
            append(w.word)
        }
        if (i < line.words.lastIndex) append(" ")
    }
}

@Composable
private fun IntroDots(progress: Float, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "introDots")
    val pulse by transition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "introPulse"
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until 3) {
            val fill = (progress * 3f - i).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .graphicsLayer {
                        scaleX = pulse
                        scaleY = pulse
                    }
                    .background(Color.White.copy(alpha = 0.25f + 0.75f * fill), CircleShape)
            )
        }
    }
}
