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

## Changelog
- **1.9** Navigasi bawah 1 baris 4 menu, masing-masing satu halaman: Script launcher (daftar script + author/tanggal), Terminal (perintah tweakcore langsung), Tentang, Pengaturan (tema, DNS, FPS counter, mode debug, author). Titik tiga diganti toggle tema malam. Fix: status profil tidak berubah setelah dijalankan; reset semua kembali ke Standar; FPS counter `novalid` di Android 14+ (fallback timestats).
- **1.8** Profil dipilih lewat card (tab dihapus) + deteksi profil aktif saat dibuka. Menu Debug log hanya saat mode debug aktif. Perbaikan FPS counter yang selalu 0 (layer salah pilih, fallback timestats, log ke Debug log). Card renderer hanya info; terapkan/reset lewat tekan lama game. Game + daftar jadi satu card.
- **1.7** FPS counter overlay + info GPU. Tombol floating dihapus. Kartu driver rata dua kolom. Daftar game gaya Pengaturan Apps.
- **1.6** Mode Gaming/Harian/Standar. Modul terpisah dengan backup masing-masing. Deteksi dan switch renderer per game. Reset diperbaiki (loop tidak berhenti di baris pertama, backup gagal tidak dihapus).
- **1.5** Versi dasar: profil Gaming/Daily, game_overlay, game_driver, wm size/density.

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
