# WiseTV mobile

WiseTV for Android phones: the same IPTV player as the Fire TV and Windows apps, laid out for touch.

- **Playlists**: Xtream Codes login, an M3U link, or one of the built-in free channel lists
  (Samsung TV Plus, XUMO, Local Now, LG Channels, Roku Channel, Redbox, DistroTV, Xiaomi, Tablo, Vizio, Sports, IPTV-org).
- **Live TV**: a TV guide you swipe through (or a plain channel list), groups and favorites, a mini player at the top,
  and a full-screen player. In full screen: swipe up/down to change channel, tap for controls, double-tap to zoom.
- **Movies and series** from the provider, plus your **Plex** servers (yours and shared ones), with resume,
  next-episode autoplay and Plex watch status.
- **Search** across channels, the guide, movies, series and Plex.
- **Chromecast**: the cast button sends live TV, movies, episodes and Plex titles to a Chromecast or Google TV.
- **Background audio**: sound keeps playing when you leave the app or lock the phone, with controls in the
  notification, on the lock screen and on headphones (next/previous changes channel).
- **Updates itself** from this repo's releases.

Not included (yet): recording.

## Install

Download `WiseTV-mobile-<version>.apk` from the latest [release](https://github.com/jonathandwise/WiseTV-mobile/releases/latest)
on the phone and open it (allow your browser or file manager to install apps when Android asks). After that, WiseTV
checks for new versions by itself and offers to install them.

## Releasing a new version

Bump `appVersion` in `app/build.gradle.kts`, add a `## <version>` section to `CHANGELOG.md`, commit and push to `main`.
GitHub Actions builds the APK, runs the unit tests and an end-to-end test on a phone emulator against a mock IPTV
server (`ci/`), then publishes release `v<version>` with the APK and `latest.json` (what the app's updater reads).
Screenshots and logs from the emulator test go to the `test-results` branch; every build's APK goes to `builds`.

Releases are signed with a permanent key so updates install over the existing app. It comes from four repository
secrets (Settings → Secrets and variables → Actions): `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
Without them builds use a throwaway key and nothing is released.

## Layout

- `data/` – playlists (Xtream, M3U), the XMLTV guide, Plex, the database (Room), free playlist presets.
- `player/` – `PlaybackManager` (the one ExoPlayer, retries, picture fixes, Chromecast hand-over) and
  `PlaybackService` (media session for background audio and notification controls).
- `cast/` – Chromecast (Google's standard receiver). Xtream live channels are cast as HLS; Plex titles are
  converted by the Plex server when the Chromecast can't play the file.
- `ui/` – Compose screens: `live/` (guide, channel list), `player/`, `vod/`, `search/`, `settings/`.
- `update/` – checks this repo's latest release.
