package com.example.feature.settings

import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import com.example.ui.theme.UnColors
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.data.repository.PreferencesRepository
import com.example.core.data.repository.ThemeStyle
import com.example.download.DownloadManager
import com.example.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    preferencesRepository: PreferencesRepository,
    downloadManager: DownloadManager,
    dominantColor: Color,
    onExportBackupJson: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenAccount: () -> Unit = {}
) {
    val themeStyle by preferencesRepository.themeStyleFlow.collectAsState(initial = ThemeStyle.DYNAMIC)
    val accentColorInt by preferencesRepository.accentColorFlow.collectAsState(initial = 0xFF00E5FF.toInt())
    val offlineOnly by preferencesRepository.offlineOnlyFlow.collectAsState(initial = false)
    val normalizeVolume by preferencesRepository.normalizeVolumeFlow.collectAsState(initial = false)
    val crossfadeSec by preferencesRepository.crossfadeSecFlow.collectAsState(initial = 0)

    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var storageSizeBytes by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        storageSizeBytes = downloadManager.getTotalDownloadedSizeBytes()
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        item {
            val accountApp = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.example.UnMusicApplication
            val accountUser by accountApp.cloud.user.collectAsState()
            val accountLabel = accountUser?.let { u ->
                when {
                    u.isGuest -> "Akun tamu"
                    u.name.isNotBlank() -> u.name
                    else -> u.email
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenAccount() }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Cloud,
                        contentDescription = null,
                        tint = dominantColor,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Akun dan sinkronisasi",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = UnColors.text
                        )
                        Text(
                            text = accountLabel ?: "Belum masuk. Simpan playlist dan lagu disukai di cloud.",
                            style = MaterialTheme.typography.bodySmall,
                            color = UnColors.text.copy(alpha = 0.6f)
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = UnColors.text.copy(alpha = 0.5f)
                    )
                }
            }
        }

        item {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                color = UnColors.text
            )
        }

        // Appearance & Themes
        item {
            Text(
                text = "APPEARANCE",
                style = MaterialTheme.typography.labelMedium,
                color = dominantColor
            )
            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Theme Style",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = UnColors.text
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            ThemeStyle.DYNAMIC to "Dynamic",
                            ThemeStyle.DARK to "Dark",
                            ThemeStyle.AMOLED to "AMOLED",
                            ThemeStyle.LIGHT to "Light"
                        ).forEach { (style, label) ->
                            FilterChip(
                                selected = themeStyle == style,
                                onClick = {
                                    scope.launch { preferencesRepository.setThemeStyle(style) }
                                },
                                label = { Text(label, maxLines = 1, softWrap = false) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = dominantColor,
                                    selectedLabelColor = Color.Black
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = "Accent Color",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = UnColors.text
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        listOf(AccentCyan, AccentPurple, AccentPink, AccentEmerald, AccentAmber).forEach { color ->
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(color, CircleShape)
                                    .clickable {
                                        scope.launch {
                                            preferencesRepository.setAccentColor(color.hashCode())
                                        }
                                    }
                            )
                        }
                    }
                }
            }
        }

        // Playback Preferences
        item {
            Text(
                text = "PLAYBACK & AUDIO",
                style = MaterialTheme.typography.labelMedium,
                color = dominantColor
            )
            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Offline Only Mode",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = UnColors.text
                            )
                            Text(
                                text = "Play only downloaded media on device",
                                style = MaterialTheme.typography.bodySmall,
                                color = UnColors.text.copy(alpha = 0.6f)
                            )
                        }
                        Switch(
                            checked = offlineOnly,
                            onCheckedChange = {
                                scope.launch { preferencesRepository.setOfflineOnly(it) }
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = dominantColor)
                        )
                    }

                    Divider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Volume Normalization",
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                color = UnColors.text
                            )
                            Text(
                                text = "Keep audio levels balanced across songs",
                                style = MaterialTheme.typography.bodySmall,
                                color = UnColors.text.copy(alpha = 0.6f)
                            )
                        }
                        Switch(
                            checked = normalizeVolume,
                            onCheckedChange = {
                                scope.launch { preferencesRepository.setNormalizeVolume(it) }
                            },
                            colors = SwitchDefaults.colors(checkedThumbColor = dominantColor)
                        )
                    }

                    Divider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)

                    Column {
                        Text(
                            text = "Crossfade: ${crossfadeSec}s",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = UnColors.text
                        )
                        Slider(
                            value = crossfadeSec.toFloat(),
                            onValueChange = {
                                scope.launch { preferencesRepository.setCrossfadeSec(it.toInt()) }
                            },
                            valueRange = 0f..10f,
                            steps = 9,
                            colors = SliderDefaults.colors(thumbColor = dominantColor, activeTrackColor = dominantColor)
                        )
                    }
                }
            }
        }

        // Storage & Cache Manager
        item {
            Text(
                text = "STORAGE & DATA",
                style = MaterialTheme.typography.labelMedium,
                color = dominantColor
            )
            Spacer(modifier = Modifier.height(10.dp))

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val sizeMb = storageSizeBytes / (1024 * 1024)
                    Text(
                        text = "Downloaded Music Storage",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = UnColors.text
                    )
                    Text(
                        text = "$sizeMb MB stored offline",
                        style = MaterialTheme.typography.bodySmall,
                        color = UnColors.text.copy(alpha = 0.6f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(
                            onClick = {
                                downloadManager.clearAllDownloads()
                                storageSizeBytes = downloadManager.getTotalDownloadedSizeBytes()
                                Toast.makeText(context, "Downloads cleared", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red)
                        ) {
                            Text("Clear Downloads")
                        }

                        Button(
                            onClick = onExportBackupJson,
                            colors = ButtonDefaults.buttonColors(containerColor = dominantColor)
                        ) {
                            Icon(imageVector = Icons.Default.Backup, contentDescription = null, tint = Color.Black, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Backup JSON", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        item {
            val appCtx = context.applicationContext as com.example.UnMusicApplication
            var personalization by remember { mutableStateOf(appCtx.tasteEngine.enabled) }
            var autoplay by remember { mutableStateOf(appCtx.playerManager.autoRadio) }

            Text(
                text = "Pemutaran",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = dominantColor
            )
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Putar Otomatis", color = UnColors.text, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Lanjutkan dengan lagu yang sejenis saat antrean habis",
                                style = MaterialTheme.typography.bodySmall,
                                color = UnColors.text.copy(alpha = 0.6f)
                            )
                        }
                        Switch(checked = autoplay, onCheckedChange = {
                            autoplay = it
                            appCtx.playerManager.autoRadio = it
                        })
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Personalisasi", color = UnColors.text, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Rekomendasi mengikuti lagu yang sering Anda putar. Data hanya di perangkat ini",
                                style = MaterialTheme.typography.bodySmall,
                                color = UnColors.text.copy(alpha = 0.6f)
                            )
                        }
                        Switch(checked = personalization, onCheckedChange = {
                            personalization = it
                            appCtx.tasteEngine.enabled = it
                        })
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(onClick = {
                        appCtx.tasteEngine.reset()
                        Toast.makeText(context, "Profil selera dihapus", Toast.LENGTH_SHORT).show()
                    }) {
                        Text("Hapus riwayat dan profil selera")
                    }
                }
            }
        }
    }
}
