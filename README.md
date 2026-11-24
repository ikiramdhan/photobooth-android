# Photobooth Android

Aplikasi pembungkus untuk booth di https://photo.rencanakita.my.id/booth.php.
Menambahkan deteksi kamera USB (Canon) lewat kabel OTG.

## Pasang di tablet

1. Buka halaman Releases repositori ini, unduh `photobooth.apk` dari rilis **Photobooth terbaru**.
2. Izinkan pemasangan dari sumber ini bila Android bertanya, lalu pasang.
3. Buka aplikasi, login seperti biasa.

## Diagnosa kamera USB

Di booth: Pengaturan, lalu "Diagnosa kamera USB". Colok kamera, tekan Izinkan, lalu Baca info kamera.

## Tahapan

1. Deteksi USB dan izin (ada).
2. Baca info kamera lewat PTP (ada, eksperimental).
3. Jepret dari tablet dan ambil foto.
4. Pratinjau langsung.

Build otomatis lewat GitHub Actions pada setiap push ke `main`.
