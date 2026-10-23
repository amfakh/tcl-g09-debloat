# Debloat TCL Google TV (G09)

Debloat TV TCL Google TV lewat ADB di Wi-Fi, berdasarkan
[cleanyourtv](https://github.com/Gathin23/cleanyourtv) (daftar `examples/tcl-g09-google-tv`, firmware
yang sama persis). Semua aplikasi cuma di-**disable** untuk user 0, tidak ada yang di-uninstall, dan
`tools/restore.sh` mengembalikan TV ke kondisi sebelum debloat.

Status: debloat selesai dan sudah dicek di TV. Launcher custom belum dikerjakan (lihat
[Fase berikutnya](#fase-berikutnya-launcher)).

## TV ini

| | |
|---|---|
| Model | TCL "Smart TV Pro", device `G09`, product `G09_4K_GB`, Amlogic T5M, 2023 |
| Firmware | Android 12 (SDK 31), `STT2.230203.001.AS24`, software `V8-T800T02-LF1V157` |
| CPU ABI | `armeabi-v7a` (userspace 32-bit, kernel armv8l). Semua binary native harus 32-bit ARM |
| RAM | 2 GB (1.9 GB terbaca) |
| Home bawaan | `com.google.android.apps.tv.launcherx/.home.HomeActivity` |
| Live TV / HDMI | `com.tcl.tv` (player), `com.tcl.tvinput` (HDMI 3/15/16/17 dan tuner) |

## Isi folder

```
debloat.txt          daftar paket yang di-disable, dikelompokkan dan diberi komentar
tools/lib.sh         alamat TV (TV_HOST), koneksi ADB, helper `tv`, validasi daftar paket
tools/backup.sh      simpan kondisi TV ke backup/<waktu>/
tools/debloat.sh     backup, lalu disable semua paket di debloat.txt, lalu pasang Private DNS
tools/restore.sh     kembalikan semuanya ke backup paling awal
tools/screenshot.sh  tekan tombol remote lalu simpan screenshot ke shots/<nama>.png
tools/tvprobe.sh     cek data Continue watching, channel, dan siaran antena yang bisa dibaca launcher
tools/tvprobe/       source APK probe untuk tvprobe.sh
tools/plex-check.py  cek apa yang bisa diambil launcher dari server Plex (token di plex.local)
tools/build-install.sh  build TCL Home, install, jadikan Home, cek di layar
launcher/            source TCL Home; launcher/res/values/config.xml untuk semua setelan
plex.local           token Plex, satu baris (git-ignored, jangan dibagikan)
backup/              hasil backup (jangan di-commit, isinya serial number dan identitas device)
shots/               screenshot (jangan dishare, ada nama akun dan nama Wi-Fi)
```

`backup/` dan `shots/` sudah masuk `.gitignore`.

## Persiapan

1. Mac: `brew install android-platform-tools` (butuh `adb`).
2. TV: Settings → System → About → klik "Android TV OS build" 7x → balik → Developer options →
   nyalakan **USB debugging**.
3. Koneksi pertama memunculkan "Allow USB debugging?" di TV. Centang "Always allow" lalu OK.
4. Alamat TV default ada di `tools/lib.sh`. Kalau berubah (DHCP), jalankan dengan
   `TV_HOST=<ip-tv> tools/debloat.sh`. Lebih enak lagi kalau IP TV di-reserve di router.

## Cara pakai

```bash
tools/debloat.sh                 # backup + disable semua isi debloat.txt + Private DNS
tools/restore.sh                 # balik ke kondisi sebelum debloat pertama
tools/screenshot.sh 01-home HOME # tekan HOME, simpan shots/01-home.png
tools/backup.sh                  # backup saja, tanpa mengubah apa pun
```

- `debloat.sh` aman dijalankan ulang, misalnya setelah factory reset. Paket yang sudah disabled cukup
  dilewati. Di akhir, script membandingkan daftar disabled di TV dengan `debloat.txt` dan keluar dengan
  error kalau ada yang masih nyala.
- Paket yang tidak terpasang (misalnya at4k setelah factory reset) dilewati dengan pesan
  "not installed".
- `debloat.sh` bisa menerima file daftar lain: `tools/debloat.sh daftar-lain.txt`.
- `PRIVATE_DNS= tools/debloat.sh` menjalankan debloat tanpa menyentuh setelan DNS.
- `restore.sh` memakai folder backup paling awal sebagai patokan, atau folder yang ditunjuk:
  `tools/restore.sh backup/20261022-185411`. Paket yang memang sudah disabled sebelum debloat pertama
  (`com.tcl.ocean.instructions`, `com.google.android.youtube.tvmusic`) tetap disabled.
- `screenshot.sh` menerima nama tombol tanpa awalan `KEYCODE_`: `HOME`, `BACK`, `DPAD_DOWN`,
  `DPAD_CENTER`, `TV_INPUT`, `VOICE_ASSIST`.

## Pengaman di debloat.sh

Diambil dari `tools/disable.sh` di cleanyourtv. Tiap pengaman mencegah kerusakan nyata kalau
`debloat.txt` nanti diedit:

| Pengaman | Kenapa |
|---|---|
| Nama paket divalidasi | Tiap nama masuk ke perintah shell di TV. Satu baris aneh, seluruh daftar ditolak |
| Pemilik Home dilewati | Tanpa launcher, setup wizard Google TV mengambil alih Home dan bisa crash-loop |
| `setupwraith` dilewati selama dia memiliki Wi-Fi tersimpan | Android menghapus jaringan itu saat setupwraith di-disable, TV putus dari Wi-Fi dan ADB |
| Paket yang minta `TV_INPUT_HARDWARE` dilewati | Itu biasanya driver HDMI/tuner. Kalau di-disable, input jadi "No Signal" dan baru pulih lewat restore penuh plus cold boot |
| Satu perintah `pm` per paket | Perintah berantai bisa berhenti diam-diam setelah gagal pertama |

## Apa yang di-disable

32 paket di `debloat.txt` (30 berubah, 2 memang sudah disabled dari pabrik).

| Grup | Isi |
|---|---|
| Telemetry TCL | `ttvs` (BI dan identifier), `logkit` (upload log), `interactive` (TIAS), Google feedback, Ginga (aplikasi siaran ISDB-Tb Brasil, tidak terpakai di DVB-T2) |
| Konten dan promo TCL | TCL Live FAST channels, waterfall, push konten ke home, `partnercustomizer` (tab "TCL" di Home), messagebox, auto-install partner |
| Service latar belakang | TGuard, EULA, akun TCL, demo toko dan stiker, hotel mode, Alexa |
| Aplikasi preload | e-manual, repair guide, game bar, updater soundbar TCL, tes pendengaran, TalkBack, Play Games, YouTube Music, Ambient mode (`dreamx`) |
| Dimatikan sesuai pilihan | Prime Video, Miracast, MagiConnect, USB media player TCL |
| Launcher pihak ketiga | AT4K Launcher (`com.overdevs.at4k`), dipasang sendiri, bukan bloat. Dimatikan supaya Google TV jadi Home lagi |

Update firmware di build ini datang lewat Play Services, jadi `ttvs` aman di-disable.

## Yang sengaja tidak disentuh

- Dipakai: Netflix, YouTube, Google TV/Play Movies (`com.google.android.videos`), Chromecast
  (`mediashell`), AirPlay (`com.tcl.airplay2`, `com.amlogic.airplay`), remote di HP
  (`com.google.android.tv.remote.service`, juga jalur mic remote fisik), Google Assistant (`katniss`),
  scan channel antena (`com.tcl.initsetup`). Semuanya ada sebagai baris komentar di bagian bawah
  `debloat.txt`.
- Wajib untuk TV: `com.tcl.tv`, `com.tcl.tvinput`, `com.tcl.suspension` (menu Input),
  `com.tcl.dashboard`, `com.tcl.autopair`, `com.tcl.systemserver`, `com.tcl.providers.config`,
  `com.tcl.globalkeyoverlay`, Play Store, Play Services, keyboard, Bluetooth.
- `com.google.android.apps.tv.launcherx` dan `com.google.android.tungsten.setupwraith`: belum ada
  launcher pengganti.
- `com.tcl.factory.view`: paket factory. Jangan pernah dibuka, bahkan cuma untuk melihat.
- `com.tcl.android.webview`: WebView yang aktif adalah milik Google, yang TCL tidak terpakai.

## Mengaktifkan lagi satu aplikasi

```bash
adb connect <ip-tv>:5555
adb -s <ip-tv>:5555 shell pm enable --user 0 com.tcl.ui_mediaCenter
```

Lalu jadikan komentar barisnya di `debloat.txt` supaya `debloat.sh` berikutnya tidak mematikannya lagi.

Tanpa komputer: Settings → Apps → See all apps → Show system apps → pilih aplikasinya → Enable.

## Private DNS (blok iklan)

`debloat.sh` memasang Private DNS ke `dns.adguard-dns.com`. Domain iklan seperti `doubleclick.net`
di-resolve ke `127.0.0.1`. Ini **tidak** memblok iklan YouTube karena iklannya dikirim dari domain
yang sama dengan videonya.

Mematikan tanpa restore penuh:

```bash
adb -s <ip-tv>:5555 shell settings put global private_dns_mode opportunistic
```

Atau lewat TV: Settings → Network and Internet → Private DNS → Automatic. `restore.sh` mengembalikan
nilai aslinya (kosong).

## Hasil pengecekan di TV (22 Oktober 2026)

| Fitur | Bukti |
|---|---|
| Backup | 11 file di `backup/20261022-185411/`, 2 paket disabled di baseline |
| Home Google TV sebelum, setelah tiap batch, dan setelah reboot | `shots/00` sampai `07` |
| Tab "TCL" hilang dari Home (efek `partnercustomizer`, baru hilang sekitar satu menit setelah reboot) | masih ada di `shots/07`, sudah hilang di `shots/09`, `14`, `17` |
| Settings, menu Input, Play Store, YouTube, Google TV/Play Movies | `shots/08` sampai `13` |
| Netflix | terbuka (`com.netflix.ninja/.MainActivity` resumed). Screenshot kosong karena DRM |
| Keyboard Gboard | `shots/15-keyboard.png`, `mInputShown=true` |
| Live TV / HDMI | `TVActivity` resumed dan ada sesi TV input aktif. Screenshot kosong karena protected. HDMI 3/15/16/17 dan tuner terdaftar |
| Google Assistant | overlay Assistant muncul (`shots/17-assistant.png`) dan pencarian genre jalan |
| Screensaver Aerial Views | tertangkap sedang jalan, setelan screensaver tidak berubah |
| Chromecast, AirPlay, remote di HP | `_googlecast` (8009), `_airplay` (7000), `_androidtvremote2` (6466) masih diumumkan |
| Private DNS | `ValidatedPrivateDnsAddresses` terisi, jaringan `VALIDATED` |
| Bertahan setelah reboot | daftar disabled setelah reboot sama persis dengan `debloat.txt` |
| Google TV jadi layar pertama setelah boot (at4k disabled) | `shots/20-after-reboot-no-at4k.png`, launcherx resumed |
| `restore.sh` lalu `debloat.sh` lagi | daftar disabled sama dengan baseline, lalu sama dengan `debloat.txt` lagi |
| Memori | MemAvailable sekitar 654 MB sebelum, 845 MB setelah reboot (TV sebelumnya sudah lama menyala, jadi perbandingannya kasar) |

Belum dicek: tombol mic di remote fisik (tombol yang diinjeksi lewat ADB tidak membuktikan apa-apa
untuk mic) dan gambar HDMI dilihat langsung dengan mata.

## Catatan dan keanehan

- Screenshot kosong (0 byte) saat Live TV, HDMI, Netflix, atau video screensaver tampil. Itu
  proteksi konten, bukan kerusakan. `screenshot.sh` lalu mencetak activity yang sedang tampil.
- AT4K Launcher tidak pernah jadi Home sistem. Dia muncul lewat `BootReceiver`-nya sendiri tiap
  boot, lalu service aksesibilitas `Hra` menimpa Google TV tiap kali Google TV tampil. Disable
  paketnya mematikan dua jalur itu sekaligus. Mau pakai lagi:
  `adb -s <ip-tv>:5555 shell pm enable --user 0 com.overdevs.at4k`, lalu jadikan komentar barisnya
  di `debloat.txt`. Service aksesibilitasnya masih tercatat di setelan, jadi langsung aktif lagi.
- Panel Input TCL masih menampilkan shortcut "TCL CHANNEL" dan "User Guide". Aplikasinya sudah
  di-disable, jadi shortcut itu tidak akan membuka apa-apa.
- Tile "TV" di baris "Top picks for you" itu dinamis. Tile itu muncul lagi setelah Live TV dibuka.
- Setelah `restore.sh`, tab "TCL" baru muncul lagi setelah reboot (belum diuji).
- `restore.sh` menetapkan launcherx sebagai Home secara eksplisit (`set-home-activity`), bukan
  sekadar karena prioritas.

## Menutup ADB

Port 5555 itu plaintext. TV ini `ro.adb.secure=1`, jadi perangkat lain tetap butuh izin "Allow" di
layar. Tapi kalau sudah tidak dipakai: Developer options → matikan USB debugging → "Revoke USB
debugging authorizations" → reboot. Cek dari Mac: `nc -vz <ip-tv> 5555` harus menjawab
"Connection refused".

ADB sengaja dibiarkan menyala untuk fase launcher.

## Launcher (TCL Home)

`launcher/` berisi TCL Home: Android framework biasa (Java, tanpa AndroidX, tanpa kode native, jadi
aman untuk `armeabi-v7a`), dibangun langsung dengan tool SDK oleh `build_apk` di `tools/lib.sh`.
APK-nya sekitar 45 KB. Dengan ketujuh baris terisi, launcher memakai sekitar 165 MB RAM (PSS), sekitar
90 MB di antaranya native heap (bitmap kartu dan hero).

```bash
tools/build-install.sh   # build, install, beri izin, nyalakan redirect Home, cek Home di layar
```

Ubah tampilan dan isi lewat `launcher/res/values/config.xml`, lalu jalankan `tools/build-install.sh`:

| Kunci | Isi |
|---|---|
| `profile_initial` | Huruf di tombol profil bulat kiri atas, sebelum foto user Plex kebaca |
| `show_live_tv` | TV antena live di belakang Home |
| `live_tv_input` | Input tuner (`com.tcl.tvinput/.TunerInputService/HW2`) |
| `dock_apps` | Favorit awal (baris "Your apps") sebelum favorit pernah diubah dari remote |
| `hidden_apps` | Aplikasi yang tidak pernah tampil |
| `watch_next_excluded` | Aplikasi yang dibuang dari Continue watching (TCL browser, VLC) |
| `youtube_package`, `youtube_rows` | Channel YouTube di TvProvider yang jadi baris, berurutan |
| `stock_home_activity` | Home Google TV yang dialihkan ke TCL Home |
| `plex_enabled` | Baris dari API Plex: On Deck di Continue watching, Recently added, Movies in your library |
| `plex_recommendations` | Nama channel Plex di TvProvider untuk baris dan tiga pilihan di samping LIVE |
| `plex_home_user` | User Plex Home untuk Continue watching, posisi di daftar Plex Home (2 di TV ini). Harus user tanpa PIN |
| `weather_latitude`, `weather_longitude` | Lokasi cuaca dan kualitas udara di samping jam, koordinat tingkat kota (dua desimal) |

Top bar seperti desain: tombol profil, pill Search / Home / Apps, pill ambient (screensaver,
sekarang Aerial Views) dan Settings, lalu di kanan jam besar, dan di bawahnya rata kanan: ikon cuaca, suhu, peluang hujan,
dan kualitas udara. Semuanya diambil dari Open-Meteo (tanpa akun atau key, yang dikirim cuma
koordinat di config). Peluang hujan itu angka untuk 15 menit saat ini. AQI pakai skala US AQI, dan
pill-nya diwarnai pastel sesuai kategorinya (hijau, kuning, oranye, merah, ungu, merah bata) dengan
teks gelap. Kalau cuma data kualitas udara yang gagal, pill AQI disembunyikan. Datanya dibaca ulang
tiap 30 menit atau 5 menit setelah gagal, dan bacaan terakhir disimpan jadi langsung tampil saat
Home dibuka. Sebelum bacaan pertama, baris cuacanya belum muncul. Ikonnya: matahari atau bulan
(cerah, ikut siang/malam), cerah berawan (matahari atau bulan di balik awan), berawan, kabut, hujan,
dan petir. Tombol profil memakai foto user Plex
di `plex_home_user` (huruf `profile_initial` sebelum fotonya kebaca) dan membuka pemilih profil
Google TV. Foto akun Google tidak bisa dibaca app pihak ketiga. Search membuka pencarian Google TV
dalam mode suara. Apps membuka grid kartu aplikasi di tengah layar (6 kolom): Favorites, lalu All apps.

Isi Home mengikuti desain MagicPath:
- Hero di atas baris. Default-nya stage LIVE: siaran antena full-screen di belakang, nama channel,
  nomor channel, dan petunjuk tombol. Fokus awal ada di pill LIVE, dan CH+/CH− mengganti channel.
  OK di LIVE membuka Live TV full-screen di channel yang sama.
- Channel di LIVE ikut channel yang terakhir ditonton, baik di Home maupun di Live TV. Ganti channel
  di Live TV, lalu tekan Home, dan siaran di Home lanjut di channel itu. Sebaliknya, OK di LIVE dan
  tile TV membuka Live TV di channel yang sedang diputar Home. Live TV yang dibuka lewat jalan lain
  (misalnya tombol input di remote) tetap mulai dari catatannya sendiri, lalu Home yang ikut.
- Di kanan LIVE ada tiga titik: tiga item pertama "Recommendations from Plex" (atau Recently added
  kalau baris itu kosong). Panah kanan pindah ke titik itu, siaran berhenti, dan hero menampilkan
  gambar item itu full-screen, logo app dan nama baris asalnya,
  judul, meta, dan deskripsi dua baris. Panah kiri kembali ke LIVE dan siaran jalan lagi.
- Fokus di kartu mana pun juga mengganti hero dengan item kartu itu: logo app dan baris asalnya,
  meta (episode, tahun, atau channel; lalu sisa waktu atau durasi, dan kapan terakhir ditonton),
  dan deskripsi. Nama app tidak ditulis karena sudah diwakili logonya. Siaran berhenti selama fokus di baris, jadi tidak ada suara di belakang.
- Urutan baris: Your apps, Continue watching, Recommendations from Plex, YouTube Recommended for
  you, Recently added, YouTube Subscriptions, Movies in your library.
- Tampilan default cuma hero, baris Your apps, dan sedikit intipan baris berikutnya (judul dan ujung
  atas kartunya) di tepi bawah layar sebagai tanda masih bisa di-scroll. Baris lain baru terbuka saat
  fokus turun ke Continue watching, dan tertutup lagi saat fokus naik ke Your apps atau stage. Scrim
  gelap di kiri dan bawah menjaga teks tetap kebaca di atas gambar yang terang. Baris dari satu
  app punya logo app itu di kiri judulnya. Continue watching, yang isinya campuran, tanpa logo. Kartu menampilkan tahun di kiri atas untuk baris Plex (kartu di Continue watching tanpa label, appnya kelihatan di hero saat kartu difokus),
  badge kanan atas (sisa waktu, "Next", atau durasi), dan progress bar oranye untuk Plex, merah untuk
  YouTube, putih untuk aplikasi lain.
- Baris Your apps hanya berisi app favorit, ditutup garis pemisah tipis dan tile All apps (ikon kotak 3×3) yang membuka tab Apps.
  Grid Apps punya dua bagian: Favorites (urutan sama dengan baris Your apps) dan All apps (semua app,
  A–Z, selalu dihitung ulang, jadi app baru langsung masuk di posisi abjadnya). Favorit awal diambil
  dari `dock_apps`; setelah diubah dari remote, daftarnya disimpan di TV, bukan di `config.xml`.
- Tahan OK di tile aplikasi untuk membuka menunya. Di favorit: Move (khusus di baris Your apps),
  Remove from favorites, App info, dan Uninstall. Di app lain: Add to favorites, App info, dan
  Uninstall. Uninstall hanya muncul untuk app yang bisa dihapus (app sistem tidak) dan membuka
  konfirmasi bawaan sistem. Move menandai tile dengan ring kuning dan chevron di atas gradasi gelap di tepi kiri/kanan card ke arah yang masih bisa
  dituju (app paling kiri cuma chevron kanan, favorit terakhir cuma chevron kiri), kiri/kanan
  menggeser posisinya di antara favorit (tidak bisa melewati tile All apps), lalu OK atau Back
  menyimpan.
- Navigasi: atas dari Your apps ke stage, atas dari stage ke tab aktif, bawah dari top bar ke stage,
  bawah dari stage ke kartu terakhir (atau aplikasi pertama).

OK di kartu membuka item lewat intent yang ditulis aplikasinya. Gambar kartu diambil lewat internet,
di-decode selebar kartu (320 px), dan disimpan di cache memori seperdelapan heap app. Gambar yang sama
hanya diambil sekali walaupun baris dibangun ulang, dan gambar hero (1280 px) punya thread sendiri.
Selama Home tampil, launcher memantau TvProvider. Kalau ada app yang menulis ulang barisnya,
baris dibaca lagi dua detik setelah tulisan terakhir.

Plex: Continue watching juga berisi On Deck user Plex di `plex_home_user`, digabung dengan Watch Next
dan diurutkan dari yang terakhir ditonton. Recently added dan Movies in your library (20 film acak
dari library film pertama) juga dari API Plex. Baris "Recommendations from Plex" dibaca dari
TvProvider, tapi gambarnya `content://com.plexapp.android.RecommendationsProvider/…`, yang hanya
boleh dibuka launcher bawaan. Karena link tiap item memuat id server dan `ratingKey`, gambarnya
diambil dari server Plex dalam satu request per server (`/library/metadata/k1,k2,…`). Login sekali lewat kartu "Sign in to Plex" di ujung baris
itu (atau `adb shell am start -n dev.tcl.home/.PlexLoginActivity`): TV menampilkan kode, masukkan
di plex.tv/link dari HP. Token akun, token user, dan alamat server yang berhasil disimpan di
penyimpanan privat aplikasi. Watch Next tampil dulu, On Deck menyusul sekitar 1–3 detik kemudian, dan
On Deck terakhir ditampilkan langsung saat Home dibuka lagi. Kartu Plex langsung memutar itemnya dari
posisi terakhir ditonton. Launcher membuka halaman item lewat rute app Plex sendiri,
`plex://libraries/<machineId server>/movie/x/<ratingKey>` (jalan untuk film maupun episode; segmen
`movie` dan slug `x` tidak dicek), lalu `HomeRedirectService` mengirim
`…/movie/x/<ratingKey>?autoplay=1&offset=<detik>` begitu Plex menampilkan tombol Resume, Watch, atau
Play (tanda Plex sudah tersambung ke server). Link autoplay langsung tidak dipakai sendirian karena
Plex yang harus start dulu mencoba memutar sebelum tersambung ke server dan menampilkan "Can't Reach
Server". `offset` dibaca Plex dalam detik; tanpa `offset` Plex memutar dari awal walaupun ada posisi
resume. Link dari baris "Recommendations from Plex" (Plex menulisnya dalam bentuk lama
`plex://server://…/library/metadata/…?viewOffset=<ms>`) diubah dengan cara yang sama.

Cara jadi Home: Google TV tetap enabled dan tetap jadi Home sistem. Di build ini Google TV punya
prioritas HOME 2, setupwraith 1, dan aplikasi pihak ketiga selalu 0. Selain itu tidak ada preferensi
Home yang tersimpan, jadi `cmd package set-home-activity` dan `cmd role add-role-holder` menjawab
sukses tanpa mengubah apa pun. Karena itu TCL Home memakai service aksesibilitas
(`HomeRedirectService`, cara yang sama dengan at4k): setiap kali HomeActivity Google TV muncul, TCL
Home dibuka di atasnya. Service ini cuma menerima event dari tiga app: Google TV, Live TV, dan Plex. Dari
Live TV dia membaca nomor di banner channel (`com.tcl.tv:id/tvCurChannelNum`) dan menyimpannya
sebagai channel terakhir. Dari Plex dia hanya menunggu tombol Resume/Watch/Play muncul setelah kartu
Plex dibuka, lalu mengirim link autoplay; dia tidak mengklik apa pun. Plex app React Native, dan
tombolnya baru terlihat oleh service dengan `flagIncludeNotImportantViews`. Dia tidak memfilter tombol,
karena filter tombol di keluarga G09 membuat volume tidak bisa ditahan. Layar Google TV lain
seperti pemilih profil tetap bisa dibuka. Saat boot, service tersambung setelah Google TV tampil,
jadi dalam lima menit pertama setelah boot service langsung membuka TCL Home.

Sudah dicek di TV (22 Oktober 2026), lewat `dumpsys` dan struktur UI dari `uiautomator`:

| Tes | Hasil |
|---|---|
| Tekan Home dari Settings atau YouTube | TCL Home |
| Setelah reboot | TCL Home di detik ke-5, 15, dan 30 |
| Standby lalu nyala lagi | TCL Home, siaran lanjut di channel terakhir |
| CH+ / CH− | `1 TVRI Nasional` → `2 TVRI DKI HD` → kembali |
| Buka aplikasi dari baris | YouTube terbuka, Home kembali ke TCL Home |
| Pemilih profil Google TV | Tetap terbuka, BACK kembali ke TCL Home |
| Baris konten | Continue watching berisi episode Viu dengan progress bar, dua baris YouTube terisi, Your apps di paling bawah (screenshot dengan `show_live_tv=false`) |
| OK di kartu | Kartu Continue watching membuka Viu, kartu YouTube membuka YouTube; Home kembali ke TCL Home |
| Login Plex | Kode tampil, setelah dimasukkan di plex.tv/link layar menutup sendiri |
| On Deck Plex | Muncul di Continue watching dengan poster dari VPS, label episode, dan progress bar |
| Top bar | Profil membuka pemilih profil Google TV, ambient memulai Aerial Views, Settings membuka Settings, Search membuka pencarian suara Google TV, Apps membuka grid, Home kembali ke baris |
| Kartu Plex | Lihat catatan app Plex di bawah: link lama berakhir di beranda Plex, rute `plex://libraries/…` membuka detail item |

Dicek lagi 23 Oktober 2026, setelah hero dan stage dibuat:

| Tes | Hasil |
|---|---|
| Fokus awal | Pill LIVE, siaran jalan (ada sesi TV input, screencap 0 byte) |
| Kanan dari LIVE | Titik pick pertama, siaran berhenti, hero berisi gambar, sumber, meta, deskripsi (`shots/stage-pick1f.png`) |
| Bawah ke kartu | Hero ganti ke item kartu (`shots/small-cards.png`), naik lagi kembali ke titik yang sama |
| Kiri ke LIVE | Siaran jalan lagi, hero kembali ke nama channel |
| Atas dari LIVE / bawah dari top bar | Tab Home / pill LIVE |
| Baris | Ketujuh baris ada sesuai urutan desain, dicek lewat `uiautomator` dan screenshot (`shots/rows-yt.png`, `shots/small-bottom.png`) |
| Recommendations from Plex | Gambar terisi dari server Plex |
| Avatar | Foto user Plex #2 |
| Baris YouTube setelah YouTube ditutup | Dibaca ulang otomatis begitu YouTube menulis ulang barisnya (cek dengan log sementara) |
| Channel dari Live TV ke Home | Live TV pindah ke 20, Home lalu memutar CH 20 (sebelumnya 21) |
| Channel dari Home ke Live TV | Home di 21, tile TV dan OK di LIVE membuka Live TV di 21; Live TV yang dibuka dari ikon biasa tetap di catatannya sendiri |

Screenshot TCL Home kosong (0 byte) selama stage LIVE tampil: selama `TvView` memutar siaran,
seluruh layar dianggap konten terproteksi. Pindah fokus ke titik pick atau ke kartu, siaran berhenti,
dan screenshot bisa diambil lagi.

Jangan `am force-stop dev.tcl.home`: service redirect ikut mati dan tidak tersambung lagi, jadi
Home Google TV yang tampil. Jalankan `tools/build-install.sh` lagi untuk menyambungkannya.

Baris YouTube mengikuti akun yang sedang aktif di app YouTube, bukan launcher. YouTube menulis ulang
barisnya lewat job `RecommendationService` yang terjadwal sekitar tiap 4,5 jam, dan juga beberapa
detik setelah app YouTube ditutup. Broadcast `INITIALIZE_PROGRAMS` dan menjalankan job itu paksa
(`cmd jobscheduler run -f`) tidak mengubah isinya. Jadi setelah ganti profil Google TV, buka YouTube
sekali lalu tekan Home, dan baris YouTube di TCL Home ikut berganti beberapa detik kemudian.
Menyetel akun YouTube di launcher tidak mungkin: launcher tidak punya akses ke akun Google, dan API
resmi YouTube tidak menyediakan feed rekomendasi.
Belum dicek dengan remote fisik: tombol Home, tombol aplikasi, tombol mic, dan menahan volume.
Belum terlihat juga apakah Viu langsung masuk ke episode yang dipilih (layar pemutarnya terproteksi).

`tools/restore.sh` mengembalikan setelan aksesibilitas ke baseline, jadi redirect mati dan Google TV
kembali jadi Home. TCL Home tetap terpasang tapi tidak dipakai.

Catatan untuk tahap berikutnya:
- Desain: tampilannya tetap Home Google TV (tab Home/Apps, top bar, baris tile, baris aplikasi
  bulat, tab Apps berisi kartu 16:9). Layar default adalah siaran TV antena full-screen di belakang
  UI. Panah kanan dari situ pindah ke tiga preview Plex, dan panah bawah masuk ke baris konten:
  Continue watching (Watch Next sistem jenis CONTINUE dan NEXT tanpa TCL browser dan VLC, digabung
  dengan On Deck Plex, urut dari yang terakhir ditonton), Recommendations from Plex, YouTube
  Recommended for you, Recently added, aplikasi, YouTube Subscriptions, dan Movies in your library.
  Ikon profil membuka pemilih profil Google TV bawaan.
  Prototipenya ada di MagicPath: [Google TV Home + Plex](https://designs.magicpath.ai/v1/mighty-week-8644),
  proyek [di sini](https://www.magicpath.ai/files/464025014786736128). Datanya masih contoh.
- Yang bisa dibaca launcher pihak ketiga dari TvProvider dengan izin `READ_TV_LISTINGS`, diukur
  dengan `tools/tvprobe.sh` (lihat di bawah):

  | Sumber | Baris channel | Isi baris (preview) | Continue watching (Watch Next) |
  |---|---|---|---|
  | Plex | "Recommendations from Plex", "Free Movies & TV & more from Plex" | 42 item | tidak ada |
  | YouTube | "Recommended for you", "Subscriptions", "Trending", "YouTube Music" | 28 item | tidak ada |
  | Plex Channel Connect | tidak ada | tidak ada | tidak ada |
  | TV antena (`com.tcl.tvinput`) | 52 channel (TVRI dan lain-lain) | - | - |

  Jadi baris rekomendasi dan subscriptions YouTube, juga rekomendasi Plex, bisa diambil tanpa
  internet dan tanpa token. "Continue watching" dari Plex dan YouTube tidak tersedia lewat jalur ini.
  Untuk itu perlu API Plex langsung (izin internet dan token Plex) atau tidak ditampilkan.
  Riwayat tontonan YouTube tidak tersedia lewat API resmi sama sekali.
- Siaran antena bisa diputar di dalam app pihak ketiga: `TvView` di probe yang disetel ke
  `com.tcl.tvinput/.TunerInputService/HW2` mendapat `onVideoAvailable`, dan Live TV bawaan tetap bisa
  memakai tuner setelah probe ditutup. Gambarnya tidak bisa di-screencap karena konten terproteksi.
- Channel terakhir Live TV tidak bisa dibaca langsung oleh launcher. Live TV (`com.tcl.tv`) jalan
  dengan uid sistem dan menyimpan channel terakhirnya di penyimpanan privat. Ganti channel di Live TV
  tidak mengubah setelan global/system/secure atau property apa pun.
  `last_play_channel_infos.EN_TCL_DVBT` tidak ikut berubah, dan `persist.sys.livetvLatestInput`
  hanya menyimpan input terakhir. Tabel `watched_program` di TvProvider mencatat setiap tune
  (dari Live TV maupun launcher, ditulis oleh `com.tcl.tvinput`), tapi barisnya baru lengkap saat
  channel ditinggal, dan tabel itu dijaga izin `ACCESS_WATCHED_PROGRAMS` (signature|privileged):
  shell bisa membacanya, app biasa tidak, dan `pm grant` ditolak. Yang bisa dibaca adalah banner
  channel Live TV lewat aksesibilitas (`tvCurChannelNum`, `tvCurChannelName`), yang muncul setiap
  Live TV dibuka atau ganti channel. Sebaliknya, Live TV menuruti
  `ACTION_VIEW content://android.media.tv/channel/<id>`, juga saat task-nya sudah terbuka.
- Isi Watch Next per jenis (`tools/tvprobe.sh` mencatat `watch_next_types`): Viu 9 CONTINUE + 7
  NEXT, Disney+ Hotstar 8 CONTINUE + 1 NEXT, Vidio 1 + 1, semuanya dengan posisi tonton. Jellyfin
  1 NEW tanpa posisi. TCL browser 30 NEW tanpa posisi (artikel dan promo, bukan tontonan). VLC 4
  CONTINUE berisi nama file pribadi. Aturan baris "Continue watching" di launcher: ambil jenis
  CONTINUE dan NEXT saja, dan abaikan `com.tcl.browser` dan `org.videolan.vlc`.
- Plex lewat API (server di VPS). `tools/plex-check.py` membaca token dari `plex.local`
  (git-ignored, mode 600) dan hanya mencetak angka. Hasil dari Mac: dua server milik akun, keduanya
  terjangkau lewat koneksi remote HTTPS (sekitar 0,8–1 detik per request), koneksi lokal tidak
  terjangkau. Server utama: On Deck 3 item, Recently added terisi, lima library (tiga film, satu
  serial, satu musik), poster 320×180 sekitar 36 KB. Jadi baris Plex "Continue watching" (On Deck),
  "Recently added", dan "Movies in your library" bisa diisi dari API. Karena tiap request hampir satu
  detik, launcher harus memuat data di belakang dan menyimpan cache, bukan menunggu saat Home dibuka.
  Di launcher, token didapat lewat login kode PIN (plex.tv/link) dan disimpan di penyimpanan privat
  app.
- On Deck dihitung per user Plex. Akun ini punya tiga user Plex Home: admin (pakai PIN, tidak dipakai
  menonton) dan dua user managed tanpa PIN. Token admin bisa pindah ke user managed lewat
  `POST https://plex.tv/api/v2/home/users/{uuid}/switch` tanpa PIN, dan hasilnya token user itu
  sendiri. User #2: On Deck 18 item di server utama (server kedua menolak koneksi untuk user ini).
  User #3: 2 item. TV ini memakai user #2, user #3 untuk TV lain. Di launcher, user Plex jadi satu
  setelan di `config.xml`, tanpa pemilih user di layar.
- App Plex untuk TV (versi 2026.20.0, `tv.plex.app.MainActivity`) adalah app React Native; routing
  link ada di bundle JavaScript (Hermes), bukan di kode Java-nya. Rute yang dikenalinya antara lain
  `libraries/:serverId/movie/:slug/:key` dan `libraries/:serverId/show/…`. Link lama
  `plex://server://<id>/com.plexapp.plugins.library/library/metadata/<key>` (yang masih Plex tulis
  ke TvProvider, dan yang dipakai launcher Dispatch 1.0.175) berakhir di beranda Plex, baik Plex
  sedang terbuka maupun tidak. `plex://libraries/<id>/movie/x/<key>` membuka detail item dalam
  semua kasus yang dicoba: Plex mati, Plex di background, dan Plex sedang di detail item lain.
  Bundle-nya punya `useAutoplayDeeplinkRedirect`: `?autoplay=1` memutar item, `&offset=<detik>`
  mengatur posisi awal (`viewOffset`, `resume`, dan `t` diabaikan; `offset` dalam milidetik melompat
  melewati akhir film, dan Plex lalu menandainya sudah ditonton). Klik lewat aksesibilitas
  (`ACTION_CLICK`) maupun tap sentuh pada tombol Resume tidak memicu apa pun di Plex. Plex Companion
  (port 32500, `/player/playback/playMedia`) tidak terbuka di TV ini. Sejak "sign in
  automatically" aktif di Plex, "Who's watching?" tidak muncul lagi.
- Dispatch (`com.spauldhaliwal.dispatch`) adalah app Flutter. String di `libapp.so` menunjukkan dia
  membuka item Plex dengan `android_intent_plus` dan link `plex://server://…?viewOffset=`, plus
  extra miliknya sendiri (`extra_detail_page`, `android.intent.extra.START_PLAYBACK`) yang tidak
  dibaca app Plex.
- Log yang ditulis app di `onCreate` tidak pernah muncul di logcat build ini, sedangkan log dari
  callback muncul. Karena itu probe `live` mencatat detail penyetelan di baris callback.
- `tools/tvprobe.sh` membangun APK probe kecil (`tools/tvprobe/`), memasangnya, memberi izin
  `READ_TV_LISTINGS`, menjalankannya, mencetak hasil, lalu menghapusnya lagi dari TV. Yang dicatat
  hanya jumlah baris per aplikasi, nama channel, bentuk link dan URI gambar (id dan angka disamarkan),
  rentang `_id` program, dan hash judul per aplikasi, bukan judul tontonan. `tools/tvprobe.sh live`
  menguji siaran antena di `TvView` selama 12 detik. Butuh JDK 17 dan Android SDK
  (`brew install openjdk@17`, `brew install --cask android-commandlinetools`, lalu `sdkmanager
  "platforms;android-34" "build-tools;34.0.0"`).
- Ikon profil di launcher bisa membuka pemilih profil bawaan Google TV:
  `am start -n com.google.android.apps.tv.launcherx/.profile.chooser.ProfileChooserActivity`
  (sudah dicoba, layar "Choose an account" muncul). TV ini punya dua user (Owner dan satu user
  tambahan). Layar itu milik launcherx, jadi launcherx harus tetap enabled walaupun bukan Home lagi.
- Tombol app di remote TCL (Netflix, YouTube, Prime Video) dikirim ke launcherx. Tanpa launcherx,
  tombol itu perlu dipetakan ulang. Di build G09 lain, service aksesibilitas yang memfilter tombol
  membuat volume tidak bisa ditahan untuk naik terus. TV ini sudah menjalankan satu service seperti itu
  (`tvquickactions`).
