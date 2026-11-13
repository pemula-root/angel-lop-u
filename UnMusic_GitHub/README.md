# UnMusic

Aplikasi pemutar musik Android (Kotlin + Jetpack Compose). Tanpa backend.

- Package: `com.unmusic.unltd`
- minSdk 26

## Build APK lewat GitHub

1. Buat repository baru di GitHub, lalu upload seluruh isi folder ini (termasuk folder `.github`).
2. Buka tab **Actions**, pilih workflow **Build APK**, lalu **Run workflow** (atau cukup push ke `main`).
3. Setelah selesai, unduh APK dari bagian **Artifacts**:
   - `UnMusic-release`: APK yang dioptimalkan, pakai ini untuk dipakai sehari-hari.
   - `UnMusic-debug`: APK debug, lebih besar dan lebih lambat.
4. Untuk rilis bertag: buat tag `v1.0` dan APK otomatis terlampir di halaman Releases.

APK release ditandatangani dengan kunci debug bawaan sehingga bisa langsung dipasang.

## Build lokal

Buka di Android Studio (JDK 17), lalu jalankan `assembleRelease`.

## Akun dan sinkronisasi cloud

Playlist dan lagu disukai bisa disimpan di Firebase dan dipulihkan setelah aplikasi dipasang ulang.
Langkah persiapan ada di [FIREBASE_SETUP.md](FIREBASE_SETUP.md).
