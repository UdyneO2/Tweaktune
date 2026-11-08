# Tweak Tuner (Shizuku)

Aplikasi Android untuk menerapkan tweak tuning lewat Shizuku. Core C
(`app/src/main/cpp/tweak_core.c`) memvalidasi input dan membangun script; script dijalankan
sebagai uid shell lewat Shizuku UserService.

## Fitur
- **Profil** Gaming / Harian / Standar: ketuk card Profil untuk memilih. Memilih = menerapkan, dan profil aktif dibaca otomatis dari perangkat saat aplikasi dibuka. Standar = kembali ke bawaan.
- **Modul terpisah** (Terapkan / Reset sendiri-sendiri): tweak sistem, game overlay, kunci FPS, display, ukuran layar + DPI.
- **Renderer per game**: tekan lama game di daftar (Bawaan / Native / ANGLE / Driver game / Prarilis). Pilihan yang tersedia dideteksi otomatis.
- **FPS counter**: overlay berisi API grafis, FPS, dan GPU; jalan di latar belakang.
- **Halaman**: Script launcher, Terminal (CLI tweakcore), Tentang, Pengaturan lewat nav bawah. Tombol bulan/matahari di atas = tema malam.
- **Daftar game** satu card: ikon, nama, paket, pilih semua / kosongkan, toggle semua aplikasi.
- **Mode debug** (Pengaturan) memunculkan menu Debug log.


## Build APK
1. Android Studio (Koala+) -> Open folder ini -> pasang NDK + CMake 3.22.1 -> Sync.
2. Build > Build APK(s) (atau `gradle assembleDebug`).
3. Pasang APK, jalankan Shizuku, buka Tweak Tuner, beri izin. Untuk FPS counter, izinkan juga "Tampil di atas aplikasi lain".

| Secret | Isi |
| --- | --- |
| `KEYSTORE_BASE64` | keystore dalam base64: `base64 -w0 signed.jks` |
| `KEYSTORE_PASSWORD` | password keystore |
| `KEY_ALIAS` | alias key |
| `KEY_PASSWORD` | password key (kosong = sama dengan password keystore) |

## CLI (tanpa aplikasi)
    make cli NDK=/path/ke/ndk
    adb push tweak /data/local/tmp && adb shell chmod +x /data/local/tmp/tweak
    adb shell /data/local/tmp/tweak apply gaming --fps 120 --scale 0.85 --games com.a.b,com.c.d
    adb shell /data/local/tmp/tweak apply gaming --modules renderer --renderer com.a.b=angle
    adb shell /data/local/tmp/tweak revert            # semua; atau: revert wm,renderer
    adb shell /data/local/tmp/tweak detect
    adb shell /data/local/tmp/tweak status
    # perintah yang sama bisa diketik di halaman Terminal aplikasi (tanpa awalan "tweak")

## Darurat
Jika layar rusak setelah ubah ukuran/DPI: `adb shell wm size reset; adb shell wm density reset`
