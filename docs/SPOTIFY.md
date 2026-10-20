# Spotify setup (for the music phase)
- Client ID: YOUR_SPOTIFY_CLIENT_ID (public, not a secret; the client secret is NOT used, PKCE + App Remote)
- Redirect URI: zeus://spotify-callback
- Android package: dev.phonerecall
- SHA1 (debug key): YOUR_DEBUG_KEY_SHA1 (register it in the Spotify dashboard). Add the release-key SHA1 later.
- App Remote needs Spotify Premium; with it, Spotify is the default player. "Music while driving" is pre-added to auto-approve.

## Built
- **App Remote SDK 0.8.0**, official AAR from github.com/spotify/android-sdk (owner `spotify`, not a fork), release
  `v0.8.0-appremote_v2.1.0-auth` (2023-07-04). Not committed: `app/build.gradle.kts` downloads it from the release into `app/libs/spotify-app-remote-release-0.8.0.aar` and fails the build unless it is
  132,749 bytes (matches the release asset size), sha256 `b5a6dd880eaf01f63a871cba9ef7af77c341f8a94ffc8fdf2e9021f9a9d4c198`.
  GitHub publishes no digest for the asset, so the pinned hash is the one taken at the first download.
  Its classes have no URLs and no java.net use: it binds to `com.spotify.music` (IPC). Needs Gson (added, 2.11.0).
- **Sign-in:** PKCE in a Custom Tab, scopes: user-read-playback-state, user-modify-playback-state,
  user-read-currently-playing, user-library-read, playlist-read-private, playlist-read-collaborative, user-read-recently-played.
  The redirect lands in `music.SpotifyCallbackActivity`; state + verifier are single-use, 10-minute TTL. Tokens are sealed
  (Keystore AES-GCM, alias `zeus.calypso.spotify`) in Calypso's prefs.
- **NetGate hosts:** api.anthropic.com, accounts.spotify.com, api.spotify.com. Nothing else (cover art from i.scdn.co is not loaded).
- **Proposals:** `play_music` and `music_control` (tier 2, always ask). `music_while_driving` (tier 2, made by drive mode
  only, needs "driving now") is the one music kind allowed on auto-approve, off until he turns it on.
