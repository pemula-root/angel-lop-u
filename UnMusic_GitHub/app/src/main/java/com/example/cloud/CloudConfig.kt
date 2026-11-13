package com.example.cloud

/**
 * Konfigurasi Firebase cadangan. Dipakai hanya bila google-services.json belum dipasang.
 * Nilainya sama dengan konfigurasi web UnMusic, sehingga akun dan data (playlist, lagu disukai)
 * dibagi dengan versi web. Untuk Google Sign-In, daftarkan aplikasi Android di Firebase dan pasang
 * google-services.json (lihat FIREBASE_SETUP.md).
 */
object CloudConfig {
    const val API_KEY = "AIzaSyAjjVZmi0Y0i2_nwc37318HpOUubf3mIAY"
    const val PROJECT_ID = "unmusic-4a085"
    const val APP_ID = "1:821368066663:web:6510e9e36a6e2c27287ac2"
    const val SENDER_ID = "821368066663"
    const val STORAGE_BUCKET = "unmusic-4a085.firebasestorage.app"

    /** Isi bila google-services.json tidak dipakai tetapi Web Client ID tersedia. */
    const val WEB_CLIENT_ID = ""
}
