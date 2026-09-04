# FeatherTV & Nokia Android TV Performance Optimizer

An ultra-lightweight, zero-telemetry, memory-optimized Android TV launcher engineered specifically for resource-constrained Android TV devices (such as Nokia Smart TVs with 1.5GB–2GB RAM).

> [!NOTE]
> **Compatibility & Scope**:
> - **FeatherTV (The Launcher)**: Universal Android TV app. Works on any Android TV / Google TV device (Android 5.0+, API 21+) with an ultra-lean **< 15MB RAM** footprint and instant D-pad navigation.
> - **Debloat Scripts (`scripts/`)**: Engineered specifically for Nokia Smart TVs (StreamView, Vestel, and MediaTek OEM boards). Safe to run on other devices (missing packages are automatically skipped), but review the package list before using `--aggressive`.

---

## 🔍 Why Nokia Android TV Is Slow & Why Apple TV Causes Reboots

### The Root Cause:
1. **Severe Memory Pressure & Bloat**:
   - Most Nokia Smart TVs (powered by StreamView / SEI Robotics hardware with Amlogic SoCs) have **1.5 GB to 2.0 GB of RAM**.
   - The stock **Google TV Launcher** (`com.google.android.tvlauncher`), **Google Assistant background listener** (`com.google.android.katniss`), and background recommendation/ad aggregators consume **500MB – 750MB of RAM** continuously.
2. **Apple TV App Memory Spikes**:
   - The Apple TV app streams high-bitrate 4K HDR / Dolby Vision content with FairPlay / Widevine DRM buffers.
   - When the app demands 300MB–450MB of direct video decoder buffers on an already memory-starved system, Android's **Low Memory Killer (LMK)** triggers aggressively.
   - If LMK kills a critical hardware codec driver or system service, the kernel watchdog triggers a sudden **system reboot**.

### The Solution:
- **Phase 1: Debloat & Kill Background Parasites**: Disable Katniss, Google recommendation scrapers, telemetry, and OEM bloatware (frees ~400MB+ RAM immediately).
- **Phase 2: Custom Lightweight TV Launcher (`FeatherTV`)**: Replaces the 400MB stock launcher with a native launcher consuming **< 20MB RAM**, zero ads, zero background polling, and instant D-pad navigation.

---

## ⚡ Features of FeatherTV

- 🚀 **Ultra-Low Memory Footprint**: Uses ~15MB RAM (vs 450MB+ for Google TV Launcher).
- 🎮 **Full D-Pad / Remote Navigation**: Smooth focus scaling, elevation animations, and instant remote responsiveness.
- 🎨 **Colorful Gradient Tiles**: Card backgrounds are derived from each app's icon color (Palette, one-time, cached) — no image assets, near-zero RAM.
- ✨ **Spring Focus & Press Feedback**: Overshoot focus scale with a flat blue focus ring and a quick press dip — all GPU-cheap.
- 👁️ **Hide Sideloaded / Unwanted Apps**: Keep your TV interface clean.
- 📱 **Sideloaded & Leanback App Discovery**: Automatically discovers both standard Android TV apps and sideloaded mobile apps.
- ⚙️ **Custom Grid Layout**: Switch between 4, 5, 6, or 7 app columns directly from the settings dialog.
- 🔀 **Custom App Order**: Long-press a tile to enter move mode — D-pad swaps it around the grid, OK drops. Your order is saved; no auto-sorting.
- 🗂️ **Manage Apps Panel**: Per-app options (hide, info, uninstall) open as a right-side panel from Launcher Settings.
- 🔗 **Quick Header Actions**: Wi-Fi, Android Settings, and Launcher Settings shortcuts in the top-right corner.
- ⚡ **Optimize Memory**: A Launcher Settings action that stops idle media apps in the background (no root, same list as `optimize-memory.sh`), freeing RAM without touching anything that's playing.
- 🌌 **Midnight-Tinted Flat Background**: A deep, very slightly blue-toned dark (`#0A0E16`) — no gradients, so it stays band-free even on 8-bit TV panels.
- ⏰ **Clock & Date Display**: Clean minimal header.
- 📦 **Zero Ads & Zero Telemetry**: 100% offline, zero network requests.

---

## 🛠️ Step-by-Step Setup Guide

### 1. Enable Developer Options & ADB on Nokia TV

1. On your Nokia TV remote, go to **Settings (Gear icon)** -> **Device Preferences** -> **About**.
2. Scroll down to **Android TV OS build** (or **Build**) and press **OK/Enter 7 times** until you see *"You are now a developer!"*.
3. Go back to **Device Preferences** -> **Developer options**.
4. Enable:
   - **USB debugging** / **Network debugging**.
5. Check your TV's IP address under **Settings -> Network & Internet -> Your Wi-Fi** (e.g. `192.168.1.100`).

---

### 2. Connect via ADB from your Mac

If you don't have ADB installed on your Mac:
```bash
brew install android-platform-tools
```

Connect to your TV wirelessly:
```bash
adb connect 192.168.1.100:5555
```
*(Accept the "Allow USB/Network debugging?" prompt on your TV screen with your remote).*

---

### 3. Run the Debloat Script

From this project directory:
```bash
./scripts/debloat-nokia-tv.sh
```

This script automatically disables:
- Google Assistant background listener (`com.google.android.katniss`)
- Google TV recommendation scraper (`com.google.android.tvrecommendations` /
  `com.google.android.tv.recommendations`, whichever your build ships)
- Google Play Movies / TV scrapers (`com.google.android.videos`)
- Google Telemetry / Partner setup / Bug reporting
- OEM bloatware (auto-detects the platform: MediaTek/WWTV on some Nokia models,
  Vestel/StreamTV on others, Amlogic/SEI/StreamView on others)
- Sets UI animation scale to `0.5x` for instant navigation.

The safe list never touches the Live TV / DVB tuner app, OEM media players,
or the vendor home/store apps. To disable those too (after FeatherTV is
installed, set as default, and verified), pass `--aggressive`:
```bash
./scripts/debloat-nokia-tv.sh --aggressive
```

To see exactly what the script would do without changing anything, run with
`--dry-run`. Before making changes the script writes a backup of the current
disabled packages to `backups/`.

To also apply the standby/wake reliability tweaks (disable the Backdrop
screensaver, safety-set the cached apps freezer, AOT-compile FeatherTV,
limit background processes to 3, silence the error-report dialog):
```bash
./scripts/debloat-nokia-tv.sh --standby
```
If you never cast from your phone/laptop, add `--no-cast` to also disable the
Chromecast receiver (`com.google.android.apps.mediashell`) — this stops its
background mDNS listener and wake locks. Casting will stop working; re-enable
with `./scripts/restore-nokia-tv.sh`.

Once FeatherTV is installed, set as default, and verified, you can also
disable the stock Google TV launcher (frees ~200 MB RAM):
```bash
./scripts/debloat-nokia-tv.sh --disable-stock-launcher
```

*(To revert or restore any package later, run `./scripts/restore-nokia-tv.sh`,
or pass it a backup file for an exact restore:
`./scripts/restore-nokia-tv.sh backups/disabled-packages-<timestamp>.txt`)*.
Use `--full-restore` to also reset animation scales back to 1.0x.
`--full-restore` additionally resets the `--standby` settings (screensaver on,
background process limit cleared, error-report dialog on).

### Check on the TV (auto-reconnect & verify)

```bash
./scripts/verify-tv.sh                      # verify an already-connected TV
./scripts/verify-tv.sh --ip 192.168.1.100   # connect, then verify
./scripts/verify-tv.sh --watch --ip 192.168.1.100  # poll until the TV is online
```

It reports the boot reason (kernel panic detection), FeatherTV install/default-home
status, saved Wi-Fi networks (duplicate detection), disabled packages, and
memory usage.

### Capture diagnostics after a crash / reboot / Wi-Fi failure

```bash
./scripts/capture-logs.sh --ip 192.168.1.100
```

Saves device info, boot reason, dropbox crash history, crash/system buffers,
Wi-Fi state, memory, and disabled packages into `logs/capture-<timestamp>/` —
everything a vendor would need to investigate the kernel-panic reboots.

### Reclaim RAM from idle apps

```bash
./scripts/optimize-memory.sh
./scripts/optimize-memory.sh --also-play    # also stop Play Store's background process
```

Force-stops idle media apps (Apple TV, Netflix, Kinopoisk, Prime Video, ...)
and puts them in the "rare" app-standby bucket so they don't sit in the
background. They restart on demand when opened.

---

### 4. Build & Install FeatherTV

Ensure your Android SDK is configured or open this project in **Android Studio**.

**To build from the command line:**
```bash
./gradlew assembleDebug
```

**To install directly to your connected TV:**
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The APK's package name is `com.feathertv.launcher` (the debug build no longer uses a
`.debug` suffix), so it shows up as **FeatherTV** in the Home chooser.

---

### 5. Set FeatherTV as Default

1. Press the **Home button** on your remote.
2. Select **FeatherTV** and choose **"Always"**.
3. *(Optional - To completely disable stock Google launcher to reclaim an extra 350MB RAM)*:
   ```bash
   adb shell pm disable-user --user 0 com.google.android.tvlauncher
   ```

---

## 🎮 Remote Control Shortcuts in FeatherTV

| Button / Action | Function |
|---|---|
| **D-Pad Arrows** | Navigate between apps and header controls |
| **D-Pad Center / OK** | Launch selected app |
| **Long-Press OK** | Reorder mode: pick the tile up, D-pad to the destination, OK drops, Back cancels |
| **Menu / Hamburger Button** (remote) | Open Android TV Settings |
| **App Options** | Launcher Settings → Manage Apps → pick an app |
| **Back Button** | Smooth scroll back to top of launcher |
| **Wi-Fi / Android / Launcher Settings (Top-Right)** | Open Wi-Fi, Android settings, or customize the launcher (columns, hidden apps, manage apps) |

---

## 📂 Project Structure

```
feathertv/
├── app/
│   ├── src/main/
│   │   ├── java/com/feathertv/launcher/
│   │   │   ├── data/
│   │   │   │   ├── AppInfo.kt           # App data model
│   │   │   │   ├── AppPreferences.kt    # SharedPreferences store (pins, column size)
│   │   │   │   └── AppRepository.kt     # High-speed app query & launcher logic
│   │   │   ├── receiver/
│   │   │   │   └── PackageChangeReceiver.kt # Auto-refreshes grid on install/uninstall
│   │   │   ├── ui/
│   │   │   │   ├── AppAdapter.kt        # TV RecyclerView adapter with focus listeners
│   │   │   │   ├── AppOptionDialog.kt   # Context menu (Pin, Hide, Info, Uninstall)
│   │   │   │   ├── FocusAnimator.kt     # Smooth hardware-accelerated focus scaling
│   │   │   │   └── SettingsDialog.kt    # Launcher preferences dialog
│   │   │   └── MainActivity.kt          # Leanback Home Activity
│   │   ├── res/                         # Vector icons, AMOLED theme, TV banner
│   │   └── AndroidManifest.xml          # LEANBACK_LAUNCHER & HOME intent filters
│   ├── scripts/
│   ├── debloat-nokia-tv.sh              # 1-click ADB debloater & memory optimizer
│   └── restore-nokia-tv.sh              # Safe package restore tool
└── README.md
```
