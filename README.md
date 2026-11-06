# HueSoundCompanion

Music sync for Philips Hue that listens to **everything your phone plays** (speaker or Bluetooth), **one app**, or the **microphone**, and streams colors to your lights through the Hue Entertainment API. No Sync Box, no cloud, no extra libraries.

## What it does

- **Big on/off button.** Tap it and the lights follow whatever is playing.
- **Three sources**
  - **Phone audio** (default): everything media/game apps play, whether it goes to the speaker or Bluetooth.
  - **One app**: only the app you pick (recently opened apps are listed first).
  - **Microphone**: for music from other devices, a TV or a live band.
- **Its own music analysis**, done on the phone:
  - kick/bass beats and snare/hi-hat hits, detected separately
  - live tempo (BPM), so flashes fade in time with the song
  - 6 frequency bands with automatic gain, so quiet and loud songs both work
  - "harmony color": the dominant notes are placed on the circle of fifths, so colors follow the song's key and chord changes
- **Styles:** Beat, Spectrum (each light gets a frequency range by its position in the room), Ambient, Party (optional strobe), and **Screen** (ambilight: the lights take the colors of the screen area at their position, no music needed).
- **TV camera:** point the phone at your TV (tap **TV camera** in the Colors row). The app finds the TV with an on-device object detector (Google MediaPipe, "tv" class), fits the corners to the TV's edges, follows it when the phone moves (slide, tilt, zoom) by tracking points around the TV, and sends its colors to the lights; you can also drag the four corners. **Calibrate room light** (with the TV paused on a still image) measures how much the Hue lights themselves shine onto the screen and with what delay, and removes that glow from the colors. Nothing is recorded. The camera screen must stay open; it dims itself after 15 s.
- **Screen colors:** the **Screen** chip in the Colors row keeps any music style's rhythm but takes the colors from the phone screen. Protected video (Netflix etc.) shows as black to apps, so it can't drive the lights.
- **LED strips (ESP32 / ESP8266):** add WLED controllers (found automatically on the Wi-Fi) or any firmware that speaks **DDP**, **E1.31/sACN** or **WLED UDP**, the same protocols LedFx uses. The phone sends directly, no server needed. Strips follow the same styles, palettes, screen and TV camera colors as the Hue lights, with optional mirror-from-center, reverse and brightness. They work with or without a Hue bridge.
  - **Effects per strip:** Like the lights, Equalizer (bars with falling peaks), Spectrum, Scroll, Energy, Fireworks, Rain, Fire, with a speed setting.
  - **Own palette per strip**, or "Same as lights".
  - **Strobe overlay:** flashes in its own color (white, red, blue, UV, …) on the kick, snare/hi-hats, every 4th beat or only big hits, over the whole strip, in blocks or as sparkles, while the effect keeps showing the music underneath. Adjustable length and strength.
- **Colors:** Auto (from the music), 7 built-in palettes (incl. Synthwave), and your own palettes (tap **+ New**; long-press one of yours to edit or delete it). Every palette is a looping color ramp: beats move a playhead along it (gliding in time with the tempo) and loudness pushes it further, so drops land on different colors. **Color movement** sets how far.
- **Intensity** sets how hard and fast the lights react: low = soft swells that fade over the whole beat, high = sharp hits that drop to near-black between beats.
- **Light delay** slider, remembered separately for the phone speaker and Bluetooth (Bluetooth audio usually arrives 150–300 ms late).

## Install on your phone (no PC needed)

The APK is built automatically by GitHub Actions:

1. Put this project in a GitHub repository (branch `main`).
2. Every push runs the **Build APK** workflow. It takes about 3–5 minutes.
3. On your phone, open the repository's **Releases** page and download `HueSoundCompanion.apk` from the newest "HueSoundCompanion build".
4. Open the file. Android will ask you to allow installing apps from your browser or Files app; allow it once.

You can also start a build by hand: **Actions › Build APK › Run workflow**.

### With Android Studio instead

Open the project folder in Android Studio, connect the phone with USB debugging on, and press **Run**. Or run `./gradlew assembleRelease` and find the APK in `app/build/outputs/apk/release/`.

## First start

1. HueSoundCompanion finds your bridge on the Wi-Fi (or type its IP).
2. Press the round link button on the bridge. HueSoundCompanion connects automatically.
3. Pick your **entertainment area**. If you don't have one, create it in the Hue app under *Settings › Entertainment areas* with your color lights.
4. Tap the big button. Android asks once for the microphone permission (it's also required for phone-audio capture) and, for phone/app audio, shows a "start recording or casting" prompt each session. That's Android's standard consent for capturing audio; HueSoundCompanion records nothing and keeps no audio, it only measures it.

## Good to know

- **Some apps block audio capture** (Spotify and SoundCloud, for example). HueSoundCompanion notices when music plays but nothing arrives, and switches automatically to Android's music visualizer, which still sees the overall output. It's a bit coarser (about 20 snapshots per second instead of the full audio) and may not work on every phone or while the screen is off. If neither works, use **Microphone**.
- While HueSoundCompanion streams, the lights in that entertainment area can't be controlled by other apps. Tap the button again to give them back.
- Only one entertainment area can stream per bridge at a time; a running Hue Sync session will be replaced.
- The bridge is contacted over your local network only. HueSoundCompanion accepts the bridge's own certificate (Hue bridges use a self-signed Signify certificate).
- **Photosensitivity:** Party style with strobe enabled produces fast flashes.

## How it works (for tinkering)

| File | What it does |
|---|---|
| `core/Analyzer.java` | FFT, auto-gain bands, beat/snare onset detection, BPM, chroma → key color |
| `core/Renderer.java` | The four styles; turns analysis into per-light colors |
| `core/Palettes.java` | Built-in and custom palettes |
| `core/DtlsPskClient.java` | Minimal DTLS 1.2 PSK client (TLS_PSK_WITH_AES_128_GCM_SHA256) |
| `core/HueStream.java` | HueStream v2 packet format |
| `core/TvTracker.java` | Edge-fitted TV corners and keyframe point tracking for camera mode |
| `TvMl.java` | MediaPipe TV detector (model downloaded by the cloud build) |
| `core/StripProtocol.java` | DDP, E1.31 and WLED UDP packets |
| `core/StripOutput.java` | Renders effects on strip segments, interpolates to every LED, gamma, sends |
| `StripSetupActivity.java` | WLED discovery (mDNS + /json/info) and strip settings |
| `SyncService.java` | Foreground service: audio capture → analysis → 50 packets/s to the bridge |
| `HueApi.java` | Bridge discovery, pairing, entertainment areas, start/stop |

Requires Android 10 or newer and a Hue Bridge (square, v2) or Hue Bridge Pro.
