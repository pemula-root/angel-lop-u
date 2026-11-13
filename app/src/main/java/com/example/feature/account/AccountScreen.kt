package com.example.feature.account

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.cloud.CloudRepository
import com.example.cloud.CloudUser
import com.example.cloud.SyncStatus
import com.example.ui.theme.deepenAccent
import kotlinx.coroutines.launch

/**
 * Halaman akun: masuk atau daftar (email, Google, tamu), lalu profil dengan status sinkronisasi.
 * Data playlist dan lagu disukai tersimpan di cloud dan kembali setelah aplikasi dipasang ulang.
 */
@Composable
fun AccountScreen(
    cloud: CloudRepository,
    dominantColor: Color,
    modifier: Modifier = Modifier
) {
    val available by cloud.available.collectAsState()
    val user by cloud.user.collectAsState()
    val status by cloud.status.collectAsState()
    val currentUser = user

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        when {
            !available -> UnavailableContent(dominantColor)
            currentUser == null -> LoginContent(cloud, dominantColor)
            else -> ProfileContent(cloud, currentUser, status, dominantColor)
        }
        Spacer(modifier = Modifier.height(120.dp))
    }
}

@Composable
private fun AccountHero(title: String, subtitle: String, accent: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.linearGradient(
                    listOf(deepenAccent(accent, 0.7f), deepenAccent(accent, 0.35f))
                )
            )
            .padding(24.dp)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Cloud,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(30.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = Color.White
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun UnavailableContent(accent: Color) {
    AccountHero(
        title = "Akun belum aktif",
        subtitle = "Layanan akun belum dapat dimulai di perangkat ini.",
        accent = accent
    )
    Spacer(modifier = Modifier.height(16.dp))
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = "Tambahkan file google-services.json ke folder app, lalu bangun ulang aplikasi. " +
                "Langkah lengkap ada di FIREBASE_SETUP.md.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(18.dp)
        )
    }
}

@Composable
private fun LoginContent(cloud: CloudRepository, accent: Color) {
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current as? Activity

    var registerMode by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    fun submit() {
        if (loading) return
        val trimmed = email.trim()
        if (trimmed.isEmpty() || password.isEmpty()) {
            error = "Isi email dan kata sandi."
            return
        }
        if (registerMode && password.length < 6) {
            error = "Kata sandi minimal 6 karakter."
            return
        }
        loading = true
        error = null
        info = null
        scope.launch {
            try {
                if (registerMode) cloud.signUpEmail(name, trimmed, password)
                else cloud.signInEmail(trimmed, password)
            } catch (e: Exception) {
                error = cloud.errorMessage(e)
            } finally {
                loading = false
            }
        }
    }

    AccountHero(
        title = "Simpan musikmu di cloud",
        subtitle = "Playlist dan lagu yang disukai ikut tersimpan. Pasang ulang aplikasi, masuk, dan semuanya kembali.",
        accent = accent
    )
    Spacer(modifier = Modifier.height(20.dp))

    // Pilihan Masuk / Daftar
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
            ModeTab("Masuk", !registerMode, Modifier.weight(1f)) {
                registerMode = false
                error = null
            }
            ModeTab("Daftar", registerMode, Modifier.weight(1f)) {
                registerMode = true
                error = null
            }
        }
    }
    Spacer(modifier = Modifier.height(16.dp))

    if (registerMode) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            singleLine = true,
            label = { Text("Nama") },
            leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
    }
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        singleLine = true,
        label = { Text("Email") },
        leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        singleLine = true,
        label = { Text("Kata sandi") },
        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = { showPassword = !showPassword }) {
                Icon(
                    imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = if (showPassword) "Sembunyikan" else "Tampilkan"
                )
            }
        },
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    )

    if (!registerMode) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = {
                val target = email.trim()
                if (target.isEmpty()) {
                    error = "Isi email dulu untuk mengatur ulang kata sandi."
                } else {
                    scope.launch {
                        try {
                            cloud.sendPasswordReset(target)
                            error = null
                            info = "Tautan pengaturan ulang dikirim ke $target."
                        } catch (e: Exception) {
                            error = cloud.errorMessage(e)
                        }
                    }
                }
            }) {
                Text("Lupa kata sandi?")
            }
        }
    } else {
        Spacer(modifier = Modifier.height(8.dp))
    }

    error?.let {
        Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(8.dp))
    }
    info?.let {
        Text(text = it, color = accent, style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(8.dp))
    }

    Button(
        onClick = { submit() },
        enabled = !loading,
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = accent),
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.5.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            Text(
                text = if (registerMode) "Buat akun" else "Masuk",
                color = MaterialTheme.colorScheme.onPrimary,
                fontWeight = FontWeight.Bold
            )
        }
    }

    Spacer(modifier = Modifier.height(18.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = "  atau  ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
    Spacer(modifier = Modifier.height(18.dp))

    OutlinedButton(
        onClick = {
            val host = activity
            if (host == null) {
                error = "Tidak dapat membuka pilihan akun Google."
            } else if (!loading) {
                loading = true
                error = null
                scope.launch {
                    try {
                        cloud.signInGoogle(host)
                    } catch (e: Exception) {
                        error = cloud.errorMessage(e)
                    } finally {
                        loading = false
                    }
                }
            }
        },
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().height(52.dp)
    ) {
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "G",
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "Lanjutkan dengan Google",
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold
        )
    }

    Spacer(modifier = Modifier.height(8.dp))
    TextButton(
        onClick = {
            if (!loading) {
                loading = true
                error = null
                scope.launch {
                    try {
                        cloud.signInGuest()
                    } catch (e: Exception) {
                        error = cloud.errorMessage(e)
                    } finally {
                        loading = false
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Gunakan sebagai tamu", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = "Akun ini sama dengan akun di versi web UnMusic, jadi playlist di kedua tempat selalu sama.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ModeTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.background else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ProfileContent(
    cloud: CloudRepository,
    user: CloudUser,
    status: SyncStatus,
    accent: Color
) {
    val scope = rememberCoroutineScope()
    var confirmSignOut by remember { mutableStateOf(false) }

    val displayName = when {
        user.isGuest -> "Tamu"
        user.name.isNotBlank() -> user.name
        user.email.isNotBlank() -> user.email.substringBefore("@")
        else -> "Pengguna"
    }

    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .clip(CircleShape)
                    .background(deepenAccent(accent, 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                if (user.photoUrl.isNotBlank()) {
                    AsyncImage(
                        model = user.photoUrl,
                        contentDescription = displayName,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Text(
                        text = displayName.take(1).uppercase(),
                        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Text(
                    text = if (user.isGuest) "Akun tamu" else user.email,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (status.syncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.5.dp,
                        color = accent
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = null,
                        tint = if (status.isError) MaterialTheme.colorScheme.error else accent,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (status.syncing) "Menyinkronkan" else "Sinkronisasi",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = when {
                            status.isError -> status.message
                            status.syncing -> "Mohon tunggu"
                            else -> "Terakhir: " + relativeTime(status.lastSyncMs)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (status.isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (status.favorites > 0 || status.playlists > 0) {
                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CountChip("${status.favorites} disukai")
                    CountChip("${status.playlists} playlist")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { scope.launch { cloud.syncNow() } },
                enabled = !status.syncing,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text(
                    text = "Sinkronkan sekarang",
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    if (user.isGuest) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Anda masuk sebagai tamu. Keluar lalu buat akun agar data tidak hilang bila aplikasi dihapus.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Spacer(modifier = Modifier.height(20.dp))
    OutlinedButton(
        onClick = { confirmSignOut = true },
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().height(50.dp)
    ) {
        Text("Keluar", color = MaterialTheme.colorScheme.onSurface)
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Keluar dari akun?") },
            text = { Text("Data terakhir dikirim ke cloud dulu. Anda bisa masuk lagi kapan saja untuk memulihkannya.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    cloud.signOut()
                }) { Text("Keluar") }
            },
            dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Batal") } }
        )
    }
}

@Composable
private fun CountChip(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.background
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
        )
    }
}

private fun relativeTime(ms: Long): String {
    if (ms <= 0L) return "belum pernah"
    val minutes = (System.currentTimeMillis() - ms) / 60_000L
    return when {
        minutes < 1 -> "baru saja"
        minutes < 60 -> "$minutes menit lalu"
        minutes < 1440 -> "${minutes / 60} jam lalu"
        else -> "${minutes / 1440} hari lalu"
    }
}
