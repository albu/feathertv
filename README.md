# FeatherTV

> **An ultra-lightweight, zero-telemetry, memory-optimized Android TV launcher engineered for speed, low-RAM devices, and ambient smart home integration.**

[![Platform](https://img.shields.io/badge/Platform-Android%20TV%20%7C%20Google%20TV-blue)](https://developer.android.com/tv)
[![API](https://img.shields.io/badge/API-21%2B%20(Android%205.0%2B)-brightgreen)](https://developer.android.com/about/dashboards)
[![RAM](https://img.shields.io/badge/RAM%20Footprint-%3C%2020%20MB-success)](#-the-low-ram-problem--solution)
[![APK Size](https://img.shields.io/badge/APK%20Size-1.7%20MB-orange)](#-building--installation)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

---

FeatherTV is a standalone, open-source Android TV launcher built for performance, privacy, and simplicity. Unlike stock launchers loaded with video autoplay ads, telemetry, and background services, FeatherTV stays under **20 MB of RAM**, launches in milliseconds, and gives you complete control over your TV experience.

It also includes an optional **TV Debloat & Maintenance Toolkit** in `scripts/` designed to resolve out-of-memory crashes and kernel panic reboots on budget Android TV hardware (such as Nokia, StreamView, Vestel, and MediaTek-based Smart TVs).

---

## 📑 Table of Contents

- [The Low-RAM Problem & Solution](#-the-low-ram-problem--solution)
- [Key Features](#-key-features)
  - [Ultra-Lean Launcher Core](#1-ultra-lean-launcher-core)
  - [Global Search & Streaming Discovery (TMDB)](#2-global-search--streaming-discovery-tmdb)
  - [Ambient Smart Backlighting & Circadian Engine](#3-ambient-smart-backlighting--circadian-engine-wiz)
  - [In-App Memory Optimizer](#4-in-app-memory-optimizer)
  - [Remote-First Navigation & Customization](#5-remote-first-navigation--customization)
- [Building & Installation](#-building--installation)
  - [1. Prerequisites](#1-prerequisites)
  - [2. Configure Optional Secrets](#2-configure-optional-secrets)
  - [3. Build the APK](#3-build-the-apk)
  - [4. Install via ADB](#4-install-via-adb)
  - [5. Set FeatherTV as Default Launcher](#5-set-feathertv-as-default-launcher)
- [Remote Control Shortcuts](#-remote-control-shortcuts)
- [Device Debloat & Maintenance Toolkit (`scripts/`)](#-device-debloat--maintenance-toolkit-scripts)
  - [Debloating Budget TVs](#1-debloating-budget-tvs)
  - [Diagnostics & Verification](#2-diagnostics--verification)
  - [Reclaiming Memory Over ADB](#3-reclaiming-memory-over-adb)
  - [Desktop Audio/Visual Ambient Sync (`wiz-sync.py`)](#4-desktop-audiovisual-ambient-sync-wiz-syncpy)
- [Project Architecture](#-project-architecture)
- [Troubleshooting & Diagnostics](#-troubleshooting--diagnostics)
- [License](#-license)

---

## 🔍 The Low-RAM Problem & Solution

### The Bottleneck
Most entry-level and mid-range Android TVs / Google TVs ship with only **1.5 GB to 2.0 GB of RAM**.

- Stock launchers (`com.google.android.tvlauncher`), background voice assistants (`katniss`), recommendation scrapers, and ad carousels eat **500 MB – 750 MB of RAM** continuously.
- When resource-heavy streaming apps (such as Apple TV, Netflix, or Plex) stream high-bitrate 4K HDR or Dolby Vision content with FairPlay / Widevine DRM buffers, they require **300 MB – 500 MB** of unfragmented memory.
- Under memory starvation, Android's **Low Memory Killer (LMK)** aggressively terminates processes. If a critical hardware codec driver or system service is killed, the kernel watchdog triggers an abrupt **system reboot** (kernel panic).

### The FeatherTV Solution
1. **FeatherTV Launcher**: Replaces the 400 MB+ stock launcher with a lean native Leanback app consuming **< 20 MB RAM**, with zero ads, zero background polling, and instantaneous D-pad navigation.
2. **Companion Scripts**: Clean up background bloatware and background listeners, immediately freeing ~400 MB of RAM for smooth video decoding.

---

## ✨ Key Features

### 1. Ultra-Lean Launcher Core
- **Microscopic Footprint**: ~15 MB RAM runtime usage (compared to 450 MB+ on Google TV).
- **Tiny APK**: ~1.7 MB total download size with zero unnecessary third-party runtime frameworks.
- **Instant Boot**: Cold starts in < 100ms; never stutters or drops frames on TV remotes.
- **Zero Telemetry**: 100% offline, zero analytics, zero ad network connections.

### 2. Global Search & Streaming Discovery (TMDB)
- **Unified Media Search**: Full-screen movie and TV series search powered by [The Movie Database (TMDB)](https://www.themoviedb.org/).
- **Streaming App Deep-Linking**: One-click playback directly launches the title inside your installed streaming apps (Apple TV, Netflix, Amazon Prime Video, Disney+, YouTube, and more).
- **Rich Media Info**: Posters, release years, vote ratings, cast & crew, and plot summaries loaded asynchronously via a lightweight custom HTTP engine with zero memory leaks.
- **Voice Search**: Full support for remote microphone voice queries via system speech recognizer.
- **Filter by Active Subscriptions**: Toggle between installed streaming apps and broader web discovery.

### 3. Ambient Smart Backlighting & Circadian Engine (WiZ)
- **Dynamic App Ambient Sync**: Hovering over any app tile samples the application's dominant icon color (via AndroidX Palette) and immediately lights up your TV's WiZ LED strip in that tone over local UDP.
- **Astronomical Circadian Rhythm**: When idling on the home screen, an internal astronomical solar engine computes the exact solar elevation for your geographic latitude/longitude:
  - **Daytime**: Crisp, high-Kelvin natural white daylight.
  - **Sunset / Twilight**: Warm amber tones to reduce blue light exposure.
  - **Night**: Dim warm glow to protect your eyes and circadian rhythm.
- **Local UDP Protocol**: Communicates directly over your home Wi-Fi network without relying on cloud servers or proprietary hubs.

### 4. In-App Memory Optimizer
- **One-Click RAM Boost**: Integrated directly into the Launcher Settings panel.
- **Smart Pruning**: Safely stops dormant background streaming apps and shifts them to standby buckets, instantly freeing hundreds of megabytes of RAM without interrupting active playback or requiring root access.

### 5. Remote-First Navigation & Customization
- **D-Pad Drag & Drop Reordering**: Long-press `OK` on any app tile to enter move mode; use arrow keys to place it anywhere on your grid, press `OK` to drop.
- **Configurable Grid Layout**: Seamlessly switch between 4, 5, 6, or 7 columns to fit your screen size and seating distance.
- **Hide Unwanted Apps**: Keep your home screen focused by hiding preinstalled OEM apps or sideloaded utilities.
- **Sideload & TV Dual Support**: Automatically detects both standard Android TV Leanback apps and sideloaded smartphone APKs.
- **True Midnight AMOLED Background**: Flat `#0A0E16` dark blue-gray background prevents color banding on 8-bit TV panels and keeps GPU memory zeroed.

---

## 🚀 Building & Installation

### 1. Prerequisites
- **Android Studio** (Hedgehog / Iguana or newer) or Android SDK Command-line Tools.
- **Java JDK 17** or newer.
- An Android TV or Google TV device with **Developer Options & Network Debugging** enabled.

### 2. Configure Optional Secrets
FeatherTV works out-of-the-box without any configuration. However, to enable TMDB search and WiZ ambient lighting, create a `secrets.properties` file in the project root:

```bash
cp secrets.properties.example secrets.properties
```

Edit `secrets.properties`:
```properties
# Free API key from https://www.themoviedb.org/settings/api
TMDB_API_KEY=your_tmdb_api_key_here

# (Optional) Local IP address of your WiZ LED strip
WIZ_IP=192.168.1.100

# (Optional) Country code for streaming availability (e.g. US, GB, DE, FR, FI)
SEARCH_REGION=US

# (Optional) Coordinates for astronomical solar lighting (defaults to London)
CIRCADIAN_LAT=51.5074
CIRCADIAN_LON=-0.1278
```
*(Note: `secrets.properties` is strictly gitignored so your personal IP and keys are never committed).*

### 3. Build the APK

To build a debug APK:
```bash
./gradlew assembleDebug
```

To build an optimized, shrunk release APK (~1.7 MB):
```bash
./gradlew assembleRelease
```
The resulting APK is written to `app/build/outputs/apk/release/app-release.apk`.

### 4. Install via ADB

1. Find your TV's IP address in **Settings -> Network & Internet -> Your Wi-Fi** (e.g., `192.168.1.100`).
2. Connect from your computer:
   ```bash
   adb connect 192.168.1.100:5555
   ```
   *(Accept the debugging prompt on your TV screen).*
3. Install the APK:
   ```bash
   adb install -r app/build/outputs/apk/release/app-release.apk
   ```

### 5. Set FeatherTV as Default Launcher

1. Press the **Home** button on your remote control.
2. Select **FeatherTV** in the launcher chooser and select **Always**.
3. *(Optional)* To reclaim an extra ~200–350 MB RAM by disabling the stock launcher:
   ```bash
   adb shell pm disable-user --user 0 com.google.android.tvlauncher
   ```
   *(To re-enable at any time: `adb shell pm enable com.google.android.tvlauncher`)*.

---

## 🎮 Remote Control Shortcuts

| Remote Action | Result in FeatherTV |
|---|---|
| **D-Pad Arrows** | Smoothly navigate between apps, search, and header actions |
| **D-Pad Center / OK** | Launch selected application or open search result |
| **Long-Press OK** | Enter **Reorder Mode**: move tile with D-pad, press `OK` to place, `Back` to cancel |
| **Back Button** | Smooth-scroll back to top or close current dialog/search |
| **Menu Button** | Directly open Android TV System Settings |
| **Search Icon** (Top-Left) | Open TMDB discovery, streaming catalog search, and voice search |
| **Wi-Fi Icon** (Top-Right) | Quick-jump directly to Android Network & Wi-Fi settings |
| **Settings Icon** (Top-Right) | Access column size, app manager (hide/info/uninstall), and memory boost |

---

## 🛠️ Device Debloat & Maintenance Toolkit (`scripts/`)

The `scripts/` directory contains automation tools designed to stabilize resource-constrained Android TV devices.

### 1. Debloating Budget TVs
```bash
# Safe debloat: Disables Katniss voice scrapers, recommendation bots, telemetry, and OEM bloat
./scripts/debloat-nokia-tv.sh

# Preview changes without modifying anything
./scripts/debloat-nokia-tv.sh --dry-run

# Add standby and wake reliability optimizations
./scripts/debloat-nokia-tv.sh --standby

# Fully revert all changes or restore from an automated backup
./scripts/restore-nokia-tv.sh backups/disabled-packages-<timestamp>.txt
```

### 2. Diagnostics & Verification
```bash
# Verify TV state, memory usage, and boot health over Wi-Fi
./scripts/verify-tv.sh --ip 192.168.1.100

# Capture comprehensive crash logs and hardware state after a panic or Wi-Fi drop
./scripts/capture-logs.sh --ip 192.168.1.100
```

### 3. Reclaiming Memory Over ADB
```bash
# Force-stop background zombie media apps without killing active playback
./scripts/optimize-memory.sh --ip 192.168.1.100
```

### 4. Desktop Audio/Visual Ambient Sync (`wiz-sync.py`)
In addition to the TV-native WiZ controller, `scripts/wiz-sync.py` provides real-time DSP music-synced backlighting for macOS via audio loopback (BlackHole 2ch), calculating tempo, beat PLL, and spectral hue shifts with zero flicker.

---

## 📂 Project Architecture

```
feathertv/
├── app/
│   ├── src/main/
│   │   ├── java/com/feathertv/launcher/
│   │   │   ├── LauncherApp.kt           # Application lifecycle & ambient LED singleton
│   │   │   ├── MainActivity.kt          # Leanback home activity & D-pad focus engine
│   │   │   ├── SearchActivity.kt        # TMDB media discovery, voice search & streaming deep-links
│   │   │   ├── data/
│   │   │   │   ├── AppInfo.kt           # App representation & Leanback banner resolver
│   │   │   │   ├── AppPreferences.kt    # SharedPreferences persistence (columns, pins, order)
│   │   │   │   ├── AppRepository.kt     # High-speed parallel app intent query engine
│   │   │   │   ├── MemoryOptimizer.kt   # In-app background process killer & RAM booster
│   │   │   │   ├── TmdbClient.kt        # Lightweight TMDB HTTP REST client (no external libs)
│   │   │   │   ├── Providers.kt         # Deep-link mapping for Netflix, Apple TV, Prime, etc.
│   │   │   │   └── PosterLoader.kt      # Asynchronous image cache for TV posters
│   │   │   ├── ui/
│   │   │   │   ├── AppAdapter.kt        # D-pad focusable TV RecyclerView grid adapter
│   │   │   │   ├── FocusAnimator.kt     # GPU-accelerated spring scale focus animations
│   │   │   │   ├── SearchAdapter.kt     # Media card grid adapter
│   │   │   │   └── SettingsDialog.kt    # Slide-over TV settings & app management panel
│   │   │   └── wiz/
│   │   │       ├── CircadianEngine.kt   # Astronomical solar elevation & CCT calculator
│   │   │       ├── WizProtocol.kt       # Local WiZ UDP packet serializer / deserializer
│   │   │       └── WizLedStripController.kt # Real-time app palette & circadian coordinator
│   │   ├── res/                         # Vector icons, AMOLED drawables, TV banner & layouts
│   │   └── AndroidManifest.xml          # LEANBACK_LAUNCHER & HOME intent filters
│   ├── build.gradle.kts                 # BuildConfig injection for secrets & compiler optimization
│   └── proguard-rules.pro               # R8 shrinking rules
├── scripts/
│   ├── debloat-nokia-tv.sh              # 1-click ADB debloater & memory tuner
│   ├── restore-nokia-tv.sh              # Safe package restorer with automated rollback
│   ├── verify-tv.sh                     # Remote TV health & memory verification
│   ├── capture-logs.sh                  # Crash log & kernel panic diagnostic extractor
│   ├── optimize-memory.sh               # Standalone memory reclaim CLI
│   └── wiz-sync.py                      # Real-time macOS DSP audio ambient lighting
├── secrets.properties.example           # Template for API keys and smart home IP
├── TROUBLESHOOTING.md                   # Kernel panic analysis & device diagnostic reference
├── LICENSE                              # Apache 2.0 Open Source License
└── README.md
```

---

## 🩺 Troubleshooting & Diagnostics

For in-depth hardware debugging, kernel panic analysis, Wi-Fi duplicate SSID resolution, and memory benchmarks from actual testing, please consult [TROUBLESHOOTING.md](TROUBLESHOOTING.md).

---

## 📜 License

FeatherTV is open-source software licensed under the [Apache License, Version 2.0](LICENSE).
Contributions, bug reports, and pull requests are welcome!
