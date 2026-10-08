# TorrentStream (minimal)

Paste a magnet link (or open a .torrent), pick a video, it plays while downloading.

Pipeline: `Media3 -> TorrentDataSource -> FileStream (piece mapping + deadlines) -> libtorrent4j -> peers/DHT/trackers`

## Build
- GitHub Actions: push; `.github/workflows/android.yml` runs unit tests + `assembleDebug` and uploads the APK artifact.
- Local: `gradle assembleDebug` (Gradle 8.9, JDK 17). Run `gradle wrapper` once if you want a wrapper committed.

## Not implemented (yet)
Foreground service/notification, settings UI (see `AppSettings`), resume/persistence, piece-level cache eviction
(cache is wiped per torrent), subtitles/track selection UI, gestures, debug screen, integration tests.
