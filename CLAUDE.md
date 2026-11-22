# WiseTV mobile

Android phone version of WiseTV (Kotlin, Jetpack Compose, Media3/ExoPlayer, Room). The Fire TV app lives in the
private repo `jonathandwise/wisetv` (same data/player code, TV interface) and the Windows app in `jonathandwise/WiseTV-PC`.
Owner: Jonathan. He installs it from GitHub releases; installed copies update themselves from the latest release.

## Building
There is no Android SDK in Claude sessions (Google's Maven is blocked), so every build runs on GitHub Actions:
push to `main`, then `gh run list -R jonathandwise/WiseTV-mobile`. The APK of each build is on the `builds` branch;
emulator screenshots, `report.txt`, `app-log.txt` and `logcat.txt` on `test-results` (`git fetch origin test-results`).

## Releasing an update (every change the user should get)
1. Make the change; push and check the build and emulator test pass.
2. Bump `appVersion` in `app/build.gradle.kts` (semver; patch for fixes, minor for features).
3. Add a `## <version>` section at the top of `CHANGELOG.md`: short, plain-language bullets (shown in the app's update dialog).
4. Commit and push. The workflow publishes release `v<version>` with the APK and `latest.json` once the emulator test
   passes and the build is signed with the permanent key (repo secrets). Never re-use a published version.

## Notes
- No recording on mobile (the user chose that for the first version).
- Chromecast uses the Default Media Receiver: no custom headers, so Xtream live is cast as `.m3u8`, and Plex is
  asked for a cast-friendly stream (`Plex.playback(item, forCast = true)`).
- Background audio: `PlaybackService` (MediaSessionService) publishes `PlaybackManager.sessionPlayer`; the video track
  is switched off while no picture is on screen (`PlaybackManager.setAppVisible` / `surfaceAttached`).
- Test hooks: `debug.wisetv.plex_tv`, `debug.wisetv.update_url`, `debug.wisetv.fake_stall` system properties (adb only).
