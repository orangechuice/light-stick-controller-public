# Lightstick Controller

An open-source Android app that drives K-pop lightsticks over Bluetooth Low Energy — full RGB colour control, animated patterns, and music-reactive sync, all from your phone.

> **Note:** Supports **KATSEYE OLS**, **XG Lightstick**, **IVE Lightstick**,
> **TWICE Lightstick (CANDY BONG ∞)**, and the **aespa Official Light Stick Ver. 2**.

## Features

- **Arbitrary RGB colour** — pick any colour, not just the vendor palette
- **Brightness control** — smooth software-scaled brightness
- **Built-in patterns** — Breathing, Rainbow, Strobe, and editable Keyframe timelines
- **Music sync** — eight reactive modes that drive the light from your phone's microphone:
  - **Pulse** — flashes on detected beats
  - **Strobe** — hard flash on each beat, black between
  - **Flip** — alternates your colour and its opposite on each beat
  - **Palette** — cycles through colours on each beat
  - **Random** — jumps to a new far-apart colour on each beat
  - **Loudness** — brightness tracks audio level
  - **Bass only** — brightness tracks the bass band alone, which crowd noise doesn't reach
  - **Spectrum** — bass → red, mids → green, treble → blue
- **Background operation** — a foreground service keeps the mic alive when the screen is off (the normal case at a concert)
- **No internet, no account** — everything runs locally over BLE

## Requirements

- Android 8.0+ (API 26)
- Bluetooth LE support
- A supported lightstick (KATSEYE OLS, XG Lightstick, IVE Lightstick, TWICE CANDY BONG ∞, or aespa Official Light Stick Ver. 2)

## Install

Download the latest **`app-release.apk`** from the [Releases](../../releases) page
and open it on your phone. Android asks you to allow installs from your browser or
file manager the first time.

Every release is signed with the same key, so a newer one installs straight over the
top. A build you make yourself is signed differently — uninstall the release build
first if you switch.

## Building

### Prerequisites

- **JDK 17** or newer
- **Android SDK** with build tools for API 35 (installed via Android Studio or `sdkmanager`)

### Build and install on a connected device

1. Enable **USB Debugging** on your Android phone  
   *Settings → About phone → tap Build number 7 times → back to Settings → Developer options → USB Debugging*

2. Connect the phone via USB and authorise the computer when prompted

3. Verify the device is detected:
   ```bash
   adb devices
   ```

4. Build and install:
   ```bash
   ./gradlew installDebug
   ```

The app appears as **Lightstick** in your launcher. Locally-built debug
installs use the application id `com.orangechuice.lightstick.debug`, so they sit
beside a downloaded release rather than colliding with it.

### Other build commands

| Command | Description |
|---|---|
| `./gradlew assembleDebug` | Build the APK without installing |
| `./gradlew installDebug` | Build + install on a connected device |
| `./gradlew test` | Run unit tests |
| `./gradlew clean` | Delete all build outputs |

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Usage

1. **Pair your lightstick** in Android Bluetooth settings while the stick is in pairing mode  
   *(The stick stops advertising once bonded — the app connects by address from the bonded-device list, not by scanning)*

   *TWICE sticks are the exception: they never bond, so they keep advertising and are found by scanning. Skip this step and go straight to Scan.*

2. **Open the app** and tap your stick to connect

3. **Pick a colour** with the colour wheel, or select a pattern

4. **Music sync** — choose a music mode, grant microphone permission, and the light follows whatever is playing around you

## Architecture

```
com.orangechuice.lightstick
├── audio/               # Mic capture, FFT, beat detection, foreground service
├── ble/                 # BLE scanning, connection management, write gating
├── device/              # LightState model, protocol interface, device profiles
│   └── profiles/        #   └── KatseyeProfile (KATSEYE OLS protocol + checksum)
├── pattern/             # PatternSource interface + Solid, Breathing, Rainbow,
│                        #   Strobe, Keyframe, and MusicPattern implementations
└── ui/                  # Jetpack Compose UI, ViewModel
```

**Key design decisions:**

- **`LightstickProtocol.encode(LightState)`** — a single method encodes the full desired state into a wire packet. No separate colour/brightness/mode commands, because the KATSEYE hardware has none; brightness is client-side RGB scaling.
- **Patterns are pure functions of time** (`PatternSource.tick(timeMs)`) — testable without a device, and the music-reactive pattern slots in as just another implementation.
- **Write gate** — rate-limits BLE writes to `minWriteIntervalMs` (15 ms for KATSEYE) and conflates, so nothing upstream can queue writes behind each other. Analysis now arrives faster than that (a 1024-sample FFT window hopped every 256 samples, so ~5 ms), which makes the gate the last rate limit in the chain rather than a formality: it paces from the start of each write and re-reads the freshest state before sending, so the interval is a floor on the period rather than a delay added to it.
- **Connection priority** — the link is put on `CONNECTION_PRIORITY_HIGH` (11.25–15 ms) after connecting. Android's default is 30–50 ms, which both adds latency and lets writes queue faster than the radio drains them. Confirm with `adb logcat -s BluetoothGatt | grep onConnectionUpdated`; `interval` is in 1.25 ms units.
- **No encryption on the payload** — every supported stick accepts plaintext commands (though a bonded BLE *link* is itself encrypted). Handshakes vary: the Fanlight sticks and aespa need none and act on the first colour command, while TWICE ignores every command until the vendor's opening exchange has been replayed — which is what `LightstickProtocol.handshake()` exists for.

## Protocol

Each supported stick speaks its own wire format, implemented in its profile under
[`device/profiles/`](app/src/main/kotlin/com/orangechuice/lightstick/device/profiles).
The unit tests pin the exact bytes every profile emits, so they double as the
specification — start there to see what actually goes over the air.

Three families are represented: the Fanlight sticks (KATSEYE, XG, IVE) share one
checksummed fixed-length frame, aespa uses SM's own length-prefixed format, and
TWICE runs over Nordic UART with no checksum at all.

## Tech Stack

- **Kotlin** + **Jetpack Compose** (Material 3)
- **Kable** for BLE (Kotlin coroutines-based)
- **Coroutines** throughout — no threads, no callbacks
- **Gradle** with version catalogs

## License

[MIT](LICENSE).

## Disclaimer

Not affiliated with, endorsed by, or connected to any artist, label, or lightstick
manufacturer named in this repository. All product and company names are the
trademarks of their respective owners, used here only to identify which hardware
each protocol targets.

The protocol notes come from observing traffic between a lightstick and its own
vendor app, on hardware the author owns, for the purpose of interoperability.
