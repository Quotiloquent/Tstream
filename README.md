# Tstream

Paste a magnet link (or open a .torrent), pick a video, it plays while it downloads.

`Media3 -> TorrentDataSource -> LtFileStream (piece window, deadlines, eviction) -> libtorrent4j -> peers/DHT/trackers`

## Features
- Smart cache (on by default): downloads at most *ahead* MB beyond the playhead (default 50), deletes data more than
  *behind* MB (default 30) behind it using `fallocate(PUNCH_HOLE)`, never deletes the first/last two pieces
  (container headers/index). Seeking back past deleted data triggers a recheck and re-download.
- Smart cache off: the whole file is cached straight into the cache folder.
- Cache folder picker (system folder picker, Android 10+). Only the `Tstream-cache` sub-folder is ever written/deleted.
- Seed while watching (off by default, only available with smart cache off). Shows upload speed in the player. Seeding stops and the cache is deleted when you leave the player.
- Player orientation follows the video: landscape or portrait, whichever shows the picture larger.
- Google Sans (OFL) bundled; Material You colours on Android 12+.

## Build
GitHub Actions (`.github/workflows/android.yml`) installs NDK 26.1 + CMake 3.22.1, runs unit tests and builds the debug APK.
Local: `gradle assembleDebug` (Gradle 8.9, JDK 17).

Fonts: Google Sans, SIL Open Font License (see `app/src/main/assets/OFL-GoogleSans.txt`).
