# HueSoundCompanion

Music sync for Philips Hue and LED strips that listens to **everything your phone plays** (speaker or Bluetooth), **one app**, or the **microphone**, and streams colors to your lights through the Hue Entertainment API. No Sync Box, no cloud. It also has a **Just lights** mode for everyday lighting without music.

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
  - **43 effects per strip:** the app's own (Like the lights, Equalizer Peaks, Spectrum Glow, Flow, Energy Bars, Fireworks, Drops, Campfire) plus all 35 one-dimensional audio-reactive effects of LedFx 2.2.1, ported to Java (Bands, Bar, Blade Power+, Block Reflections, Blocks, BPM Strobe, Crawler, Energy, Energy 2, Equalizer, Filter, Fire, Glitch, Hierarchy, Lava lamp, Magnitude, Marching, Melt, Melt and Sparkle, Metro, Multicolor Bar, Pitch Spectrum, Power, Rain, Scan, Scan and Flare, Scan Multi, Scroll, Scroll+, Spectrum, Spotlight, Strobe, VuMeter, Water, Wavelength). Effects are grouped by category in a picker; LedFx effects with a main option (e.g. Bar: wipe/bounce/in-out) offer it as **Variant**.
  - **Speed** and **Blur** (0 = sharp; higher values soften the LEDs into each other) per strip.
  - **Own palette per strip**, or "Same as lights".
  - **Strobe overlay:** flashes in its own color (white, red, blue, UV, …) on the kick, snare/hi-hats, every 4th beat or only big hits, over the whole strip, in blocks or as sparkles, while the effect keeps showing the music underneath. Adjustable length and strength.
- **Colors:** Auto (from the music), 7 built-in palettes (incl. Synthwave), and your own palettes (tap **+ New**; long-press one of yours to edit or delete it). Every palette is a looping color ramp: beats move a playhead along it (gliding in time with the tempo) and loudness pushes it further, so drops land on different colors. **Color movement** sets how far.
- **Intensity** sets how hard and fast the lights react: low = soft swells that fade over the whole beat, high = sharp hits that drop to near-black between beats.
- **Just lights mode** (switch at the top): no music, no screen sharing. Control all Hue lamps and strips with
  - static colors, with **each lamp's own color** (color picker with hue/saturation/brightness and quick picks),
  - a palette spread over the room, a single color, or one of the animations: Gradient, Fade, Rainbow, Random Flash, Pixels (from LedFx), and new ones: **Candles** (independent flickering per lamp), **Fireplace**, Breathing, Aurora, Ocean, Thunderstorm, Starry night, Color loop, Lava and Party,
  - speed and brightness; each strip can follow the lamps' animation or run its own, in its own color and palette,
  - **Keep the colors when turned off:** when you stop, the last colors are written to the lamps (normal Hue API) and to WLED strips, so the light stays as it is.
- **One room, all devices.** Hue lamps, LED strips and DMX lights are devices of the same kind:
  - **Global look**: the Look (music) and Lights (just lights) settings are global; every device follows it ("Global") or has its own look (own style, solid color pulsing/steady, own palette, own animation and color).
  - **Hue lamps as devices**: add single lamps from the bridge ("+ Add Hue lamps"); the app keeps its own entertainment configuration on the bridge with exactly these lamps and their places (Hue's limit: 10 lamps streaming at once). Entertainment areas are no longer chosen in the app; an old area is converted automatically (lamps, places, colors).
  - **Room plan**: every device on a floor plan (top = front/TV); drag lamps and DMX lights, and both ends of every strip. Hue lamps start at the places Hue already knows.
  - **Direction**: effects run left → right, front → back, around the room or from the center. One scene over every point of every device: Hue, strips and DMX move together, in space and time.
  - **Groups for every device type** (Hue lamps, strips, DMX lights): a group shares one look; with its own look it is spread over its members' places (like a Hue entertainment area), or all members show the same.
- **Shuffle** style: an automatic light director. It estimates the vibe (loudness against the song's peak, drum strength, tempo, brightness of the sound, major/minor) and picks style, palette (palettes are measured by warmth and vividness, custom ones included), intensity, color movement, an effect/variant/blur for every strip and the DMX movement. It changes the look instantly on a drop's first hit or when the bass drops out, on the next beat when the sound color changes, and otherwise after 32-beat phrases. "Shuffle now" forces a new look.
- **Build-ups & drops** (on by default, works with every style): a build-up is detected from the trend over ~4 s (sound getting brighter, hits coming faster, getting louder; two signs needed). The lights get brighter and whiter, flash on every roll hit and strips fill from both ends like a loading bar; the gap before the drop goes black and the drop lands as a white impact.
- **DMX lights via Art-Net / sACN** (e.g. Showtec NET-2/3 Pocket): the app finds Art-Net nodes (ArtPoll), shows their ports and universes, can set the universes remotely (ArtAddress, if the node accepts it) or open the node's settings page. DMX lights follow the same styles, palettes and Just-lights animations as the lamps.
  - Presets: Eurolite LED PARty TCL Spot (5 ch), Fun Generation PicoSpot 20 LED (11/9/5 ch: pan/tilt movement on beats, color wheel follows the colors, strobe), UKing UV 36 LED 72 W (7 ch, unverified layout), plus generic RGB / RGBW / Dimmer+RGB / Dimmer / UV and custom channel layouts.
  - **Channel tester** with a slider per channel, to check or work out a fixture's channels.
  - A new node usually sits at 2.0.0.1: give it an address in your home network first.
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
| `core/LedFx.java` | Java ports of the LedFx 2.2.1 1D audio effects (GPL-3.0) |
| `core/StripEffect.java` | Effect registry: the app's own strip effects plus the LedFx ports, strobe overlay |
| `core/Ambient.java` | Just-lights animations (palette, candles, fireplace, aurora, …) |
| `core/ArtNet.java` | Art-Net packets (ArtDmx, ArtPoll/Reply, ArtAddress) and sACN DMX |
| `core/Dmx.java` | DMX fixtures: channel roles, presets, color/dimmer/strobe/pan-tilt/color wheel |
| `core/StripOutput.java` | Renders effects on strip segments, interpolates to every LED, gamma, sends |
| `StripSetupActivity.java` | WLED discovery (mDNS + /json/info) and strip settings |
| `SyncService.java` | Foreground service: audio capture → analysis → 50 packets/s to the bridge |
| `HueApi.java` | Bridge discovery, pairing, entertainment areas, start/stop |

## License and credits

HueSoundCompanion is free software under the **GNU General Public License v3.0** (see `LICENSE`).

The strip effects in `core/LedFx.java`, the Gradient/Fade/Rainbow/Random Flash/Pixels animations and the rain droplet shape are ported from **[LedFx](https://github.com/LedFx/LedFx)** (v2.2.1), © the LedFx developers, licensed under GPL-3.0. Thank you to the LedFx project. TV detection uses Google MediaPipe (Apache 2.0) with the EfficientDet-Lite0 model.

Requires Android 10 or newer and a Hue Bridge (square, v2) or Hue Bridge Pro.
