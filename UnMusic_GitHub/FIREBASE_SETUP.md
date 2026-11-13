# Menyiapkan akun dan sinkronisasi cloud (Firebase)

Aplikasi memakai **project Firebase yang sama dengan versi web UnMusic** (`unmusic-4a085`).
Data disimpan di dokumen `users/{uid}` dengan susunan yang sama (lagu disukai, riwayat putar, playlist,
riwayat pencarian), jadi satu akun dipakai bersama di web dan aplikasi. Saat aplikasi dihapus lalu dipasang
lagi, cukup masuk ke akun yang sama dan semuanya kembali.

## Yang berlaku tanpa persiapan tambahan
- Masuk atau daftar dengan **email dan kata sandi**, serta **mode tamu**, memakai konfigurasi web sebagai cadangan.
- Aktifkan metode **Email/Password** dan **Anonymous** di Firebase Console: *Authentication → Sign-in method*.

## Yang dibutuhkan untuk Google Sign-In (disarankan juga untuk semua fitur)
Di aplikasi tidak ada "alamat web" yang perlu diisi. Gantinya, Firebase mengenali aplikasi lewat
**nama paket** dan **sidik jari (SHA)** tanda tangan.

1. Buka Firebase Console, pilih project **unmusic-4a085**, lalu *Project settings → Your apps → Add app → Android*.
2. Isi:
   - **Package name:** `com.unmusic.unltd`
   - **SHA-1:** `65:B7:6C:1B:81:81:79:00:A9:CB:FF:92:74:7A:16:37:90:26:20:F2`
   - **SHA-256:** `30:E5:98:75:A3:26:D8:94:60:3E:02:61:CD:94:74:50:3A:67:DC:45:62:0A:67:28:B7:A8:8A:29:13:A8:35:4D`
3. Aktifkan **Google** di *Authentication → Sign-in method*.
4. Unduh **google-services.json** (unduh lagi setelah langkah 2 dan 3 supaya berisi OAuth client).
5. Taruh file itu di folder `app/` repository (`app/google-services.json`), lalu push. Workflow GitHub otomatis
   mendeteksinya dan membangun APK dengan Firebase.

Sidik jari di atas berasal dari `keystore/unmusic.p12`, yang dipakai menandatangani **setiap** build, jadi tidak
berubah antar build. Jangan mengganti file itu kecuali Anda siap mendaftarkan SHA yang baru.

## Aturan Firestore
Aturan yang sudah dipakai web sudah cukup (hanya pemilik akun yang bisa membaca dan menulis dokumennya):

```
match /users/{uid} {
  allow read, write: if request.auth != null && request.auth.uid == uid;
}
```

## Kalau ada masalah
| Gejala | Penyebab umum |
|---|---|
| "Google belum disiapkan" | `google-services.json` belum ditaruh di `app/`, atau Google belum diaktifkan lalu file belum diunduh ulang |
| Masuk Google gagal | SHA-1 belum didaftarkan, atau paket aplikasi tidak sama dengan `com.unmusic.unltd` |
| "Metode masuk ini belum diaktifkan" | Email/Password atau Anonymous belum diaktifkan di Firebase Console |
| Data tidak muncul di web | Akun berbeda; pastikan masuk dengan email atau Google yang sama |

## Catatan keamanan
Keystore dan sandinya (`unmusic123`) ada di repository agar build selalu menghasilkan tanda tangan yang sama.
Itu cukup untuk dipakai pribadi. Untuk rilis publik, buat keystore sendiri, simpan sandinya sebagai secret,
dan daftarkan SHA-nya ke Firebase.
