# Tstream

Paste a magnet link (or open a .torrent), pick a video, it plays while it downloads.

`libVLC -> http://127.0.0.1 (StreamServer, Range) -> LtFileStream (piece window, deadlines, eviction) -> libtorrent4j -> peers/DHT/trackers`

## Features
- Smart cache (on by default): downloads at most *ahead* MB beyond the read position (default 25), deletes data more than
  *behind* MB (default 15) behind the playhead using `fallocate(PUNCH_HOLE)`, never deletes the first/last two pieces
  (container headers/index). Seeking back past deleted data triggers a recheck and re-download.
- Smart cache off: the whole file is cached straight into the cache folder.
- Cache folder picker (system folder picker, Android 10+). Only the `Tstream-cache` sub-folder is ever written/deleted.
- Seed while watching (off by default, only available with smart cache off). Shows upload speed in the player. Seeding stops and the cache is deleted when you leave the player.
- Player engine: libVLC. Software decoders cover formats the phone's chip can't decode (e.g. 10-bit HEVC), and libass
  renders ASS/SSA with the release's own fonts, colors and positioning. Plain subtitles use a size you choose in Settings. Hardware decoding, fast software decoding and the audio output are switchable in Settings.
- Player: double-tap left/right = -/+10 s (middle = play/pause), vertical swipe on the left half = volume,
  hold 1.2 s = 2x speed while held, lock button, screenshot button (Pictures/Tstream), subtitle picker
  (SRT / ASS / SSA / VTT files from the torrent, plus tracks embedded in the video), audio track picker, speed picker, resume position per exact title.
- Resuming skips the beginning of the file: the byte position is saved with the time, so the download window starts
  there (only the container header/index pieces are fetched from the start).
- Nothing downloads in the background: torrents start in upload mode (metadata only), the browse-time prefetch is
  capped (speed and total size), cancelled loads are removed, and all network activity pauses when no screen is visible.
- Leaving the player stops the torrent and deletes its cache. The file list stays on the main screen, but the torrent is
  not re-opened in the background: it is fetched again only when you pick a video.
- Swiping the app away from Recents stops the torrent and wipes the cache right away (`ExitCleanupService`).
- Cache left behind by a crash / force-stop is wiped on the next launch.
- Player orientation follows the video: landscape or portrait, whichever shows the picture larger.
- Google Sans (OFL) bundled; Material You colours on Android 12+.

## Build
GitHub Actions (`.github/workflows/android.yml`) installs NDK 26.1 + CMake 3.22.1, runs unit tests and builds the release APKs.
Local: `gradle assembleRelease` (Gradle 8.9, JDK 17). Output in `app/build/outputs/apk/release/`:
- `app-arm64-v8a-release.apk`: 64-bit ARM, almost every phone made since ~2017. Pick this one if unsure.
- `app-armeabi-v7a-release.apk`: 32-bit ARM, older phones.
- `app-universal-release.apk`: both ABIs in one file (larger).

The release build uses R8 + resource shrinking and is signed with the debug key so it installs directly.
x86 / x86_64 are not built (they need the matching `libtorrent4j-android-x86*` dependencies plus an `include(...)` entry in `splits`).

Fonts: Google Sans, SIL Open Font License (see `app/src/main/assets/OFL-GoogleSans.txt`).
