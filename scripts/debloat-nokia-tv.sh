#!/usr/bin/env bash
# ==============================================================================
# Nokia Android TV Debloat & Memory Optimization Script
# Author: FeatherTV Project
#
# Usage:
#   ./scripts/debloat-nokia-tv.sh                 # disable safe list only
#   ./scripts/debloat-nokia-tv.sh --aggressive    # also Live TV / OEM media / StreamView
#   ./scripts/debloat-nokia-tv.sh --disable-stock-launcher  # also disable the stock Google TV launcher
#   ./scripts/debloat-nokia-tv.sh --standby       # also apply standby/wake reliability tweaks
#   ./scripts/debloat-nokia-tv.sh --standby --no-cast  # also disable the Chromecast receiver
#   ./scripts/debloat-nokia-tv.sh --dry-run       # show what would change, change nothing
#   ./scripts/debloat-nokia-tv.sh --help          # show this help
#
# Every change is reversible: run ./scripts/restore-nokia-tv.sh, or restore the
# exact pre-debloat state from the backup file written to backups/.
# ==============================================================================

set -euo pipefail

DRY_RUN=0
AGGRESSIVE=0
DISABLE_STOCK=0
STANDBY=0
NO_CAST=0

for arg in "$@"; do
    case "$arg" in
        --dry-run) DRY_RUN=1 ;;
        --aggressive) AGGRESSIVE=1 ;;
        --disable-stock-launcher) DISABLE_STOCK=1 ;;
        --standby) STANDBY=1 ;;
        --no-cast) NO_CAST=1 ;;
        --help|-h)
            grep '^#' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "Unknown argument: $arg (see --help)" >&2
            exit 1
            ;;
    esac
done

echo "======================================================"
echo "    Nokia Android TV Debloat & Memory Optimizer      "
echo "======================================================"

# Check ADB connection
if ! command -v adb &> /dev/null; then
    echo "[-] Error: 'adb' command not found."
    echo "[*] Install ADB on macOS: brew install android-platform-tools"
    exit 1
fi

DEVICE_COUNT=$(adb devices | grep -v "List of devices" | grep "device$" | wc -l | tr -d ' ')
if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo "[-] No Android TV detected via ADB."
    echo "[*] Ensure ADB debugging is enabled in Developer Options on your TV."
    echo "[*] Connect via Wi-Fi: adb connect <TV_IP_ADDRESS>:5555"
    exit 1
fi

echo "[+] Connected to Android TV."
echo ""

# ----------------------------------------------------------------------------
# Detect platform / OEM stack (Nokia TVs ship on different SoCs)
# ----------------------------------------------------------------------------
PLATFORM=$(adb shell getprop ro.board.platform 2>/dev/null | tr -d '\r')
echo "[*] Detected platform: ${PLATFORM:-unknown}"
echo ""

# ----------------------------------------------------------------------------
# Package lists
# ----------------------------------------------------------------------------
# SAFE: background services / telemetry / scrapers. Disabling these does not
# remove user-visible apps you would normally launch from the home screen.
SAFE_PACKAGES=(
    "com.google.android.katniss"                      # Google Assistant / Katniss daemon
    "com.google.android.tv.assistant"                 # Google TV assistant overlay/daemon
    "com.google.android.tvrecommendations"            # Google TV recommendation rows (API 30+)
    "com.google.android.tv.recommendations"           # Google TV recommendations (older builds)
    "com.google.android.videos"                       # Google Play Movies & TV
    "com.google.android.play.games"                   # Play Games
    "com.google.android.feedback"                     # Feedback collector
    "com.google.android.partnersetup"                 # Partner setup & telemetry
    "com.google.android.onetimeinitializer"           # One-time setup helper
    "com.google.android.syncadapters.contacts"        # Google contacts sync
    "com.google.android.syncadapters.calendar"        # Google calendar sync
    "com.google.android.printservice.recommendation"  # Print service suggestions
)

# AGGRESSIVE: may remove user-visible functionality on some models. Only use
# after FeatherTV is installed, set as default, and verified.
# Entries are harmless if not installed (the script skips missing packages).
AGGRESSIVE_PACKAGES=(
    # Vestel / StreamTV OEM stack (observed on Vestel-built Nokia TVs)
    "com.vestel.tv.speedup"                           # OEM "System Speed Up" RAM booster
    "com.vestel.smartlogger"                          # OEM telemetry logger
    "com.vestel.vestelreporter"                       # OEM crash/usage reporter
    "com.vestel.smartcenter"                          # OEM smart center app
    # MediaTek / WWTV OEM stack (observed on m7632-based Nokia TVs)
    "com.mediatek.wwtv.mediaplayer"                  # OEM media player
    "com.mediatek.wwtv.tvcenter"                     # OEM TV center app
    "com.mediatek.wwtv.webview"                      # OEM webview app
    "com.mediatek.autopair"                          # OEM auto-pairing helper
    "com.mediatek.android.boot.appsplashscreen"      # OEM boot splash
    "com.mediatek.android.leanbacklauncher.partnercustomizer" # MediaTek launcher customization
    # Amlogic / SEI / StreamView OEM stack (other Nokia TV models)
    "com.sei.android.tv.livetv"                      # Live TV / DVB tuner app
    "com.sei.smarttv.operator"                       # SEI operator config/telemetry
    "com.droidlogic.appinstall"                      # Amlogic OEM app installer prompts
    "com.droidlogic.mediacenter"                     # Amlogic OEM media center / player
    "com.streamview.tv"                              # StreamView vendor app (may be the OEM launcher on some models)
)

if [ "$AGGRESSIVE" -eq 1 ]; then
    TARGET_PACKAGES=("${SAFE_PACKAGES[@]}" "${AGGRESSIVE_PACKAGES[@]}")
    echo "[*] Mode: aggressive (includes Live TV / OEM media / StreamView apps)"
else
    TARGET_PACKAGES=("${SAFE_PACKAGES[@]}")
    echo "[*] Mode: safe (Live TV / OEM media / StreamView apps are NOT disabled; use --aggressive for those)"
fi

if [ "$DRY_RUN" -eq 1 ]; then
    echo "[*] DRY RUN - no changes will be made."
    echo "[*] Packages that would be disabled:"
    printf '      %s\n' "${TARGET_PACKAGES[@]}"
    echo ""
    echo "[*] Would also set animation scale to 0.5x."
    if [ "$DISABLE_STOCK" -eq 1 ]; then
        echo "[*] Would also disable the stock Google TV launcher (com.google.android.tvlauncher)."
    fi
    if [ "$STANDBY" -eq 1 ]; then
        echo ""
        echo "[*] Standby mode (--standby) would also:"
        echo "    - Disable the Backdrop screensaver + screensaver auto-activation"
        echo "    - Set cached_apps_freezer = enabled (no-op on Android 11, safety set)"
        echo "    - AOT speed-compile FeatherTV only (com.feathertv.launcher)"
        echo "    - Limit background processes to 3"
        echo "    - Silence the app-error report dialog (send_action_app_error 0)"
        if [ "$NO_CAST" -eq 1 ]; then
            echo "    - Disable the Chromecast receiver (com.google.android.apps.mediashell) [--no-cast]"
        fi
    fi
    exit 0
fi

# ----------------------------------------------------------------------------
# Backup current disabled state (for exact restore)
# ----------------------------------------------------------------------------
BACKUP_DIR="$(cd "$(dirname "$0")/.." && pwd)/backups"
mkdir -p "$BACKUP_DIR"
BACKUP_FILE="$BACKUP_DIR/disabled-packages-$(date +%Y%m%d-%H%M%S).txt"
adb shell pm list packages -d | sed 's/^package://' | tr -d '\r' > "$BACKUP_FILE"
echo "[+] Backed up current disabled packages to: $BACKUP_FILE"
echo ""

disable_package() {
    local pkg="$1"
    echo -n "[*] Disabling package: $pkg ... "
    # Prefer pm disable-user; fall back to uninstall for the current user.
    if adb shell pm disable-user --user 0 "$pkg" &>/dev/null; then
        echo "SUCCESS"
    elif adb shell pm uninstall -k --user 0 "$pkg" &>/dev/null; then
        echo "SUCCESS (uninstalled for user 0)"
    else
        echo "SKIPPED (not installed or system protected)"
    fi
}

echo "------------------------------------------------------"
echo " 1. Disabling Background Services & Telemetry        "
echo "------------------------------------------------------"
for pkg in "${TARGET_PACKAGES[@]}"; do
    disable_package "$pkg"
done

echo ""
echo "------------------------------------------------------"
echo " 2. Optimizing System Animation Scales (Instant UI)   "
echo "------------------------------------------------------"
adb shell settings put global window_animation_scale 0.5
adb shell settings put global transition_animation_scale 0.5
adb shell settings put global animator_duration_scale 0.5
echo "[+] Set animation scale to 0.5x (drastically reduces UI lag)"

echo ""
echo "------------------------------------------------------"
echo " 3. Memory / Kernel Logging Optimizations             "
echo "------------------------------------------------------"
# persist.* properties require root; verify the value actually applied before
# claiming success.
adb shell "su -c 'setprop persist.logd.size 64K'" 2>/dev/null \
    || adb shell setprop persist.logd.size 64K 2>/dev/null \
    || true
ACTUAL_LOG_SIZE=$(adb shell getprop persist.logd.size 2>/dev/null | tr -d '\r')
if [ "$ACTUAL_LOG_SIZE" = "64K" ]; then
    echo "[+] Reduced system logger buffer size to 64K"
else
    echo "[-] Skipped logger buffer tuning: requires root (current value: '${ACTUAL_LOG_SIZE}')"
fi

if [ "$STANDBY" -eq 1 ]; then
    echo ""
    echo "------------------------------------------------------"
    echo " 4. Standby / Wake Reliability Tweaks (--standby)    "
    echo "------------------------------------------------------"

    # 4a. Backdrop screensaver: heavy 4K wallpaper decoding in GPU memory while
    # idle. Disabling avoids idle RAM spikes / LMK thrash. Reversible.
    echo -n "[*] Disabling Backdrop screensaver ... "
    if adb shell pm disable-user --user 0 com.google.android.backdrop &>/dev/null; then
        echo "SUCCESS"
    else
        echo "SKIPPED (not installed or system protected)"
    fi

    echo -n "[*] Disabling screensaver auto-activation ... "
    adb shell settings put secure screensaver_enabled 0
    adb shell settings put secure screensaver_activate_on_sleep 0
    adb shell settings put secure screensaver_activate_on_dock 0
    echo "done (TV now dims/sleeps straight to black)"

    echo -n "[*] Cached apps freezer ... "
    adb shell settings put global cached_apps_freezer enabled
    echo "enabled (safety set; default on Android 11)"

    echo -n "[*] AOT speed-compiling FeatherTV ... "
    if adb shell cmd package compile -m speed -f com.feathertv.launcher &>/dev/null; then
        echo "SUCCESS (faster cold start, no JIT churn)"
    else
        echo "SKIPPED (cmd package compile unavailable)"
    fi

    echo -n "[*] Limiting background processes to 3 ... "
    adb shell settings put global background_process_limit 3
    echo "done (keeps memory pressure low; apps may cold-start more)"

    echo -n "[*] Silencing app-error report dialog ... "
    adb shell settings put secure send_action_app_error 0
    echo "done"

    if [ "$NO_CAST" -eq 1 ]; then
        echo -n "[*] Disabling Chromecast receiver (--no-cast) ... "
        if adb shell pm disable-user --user 0 com.google.android.apps.mediashell &>/dev/null; then
            echo "SUCCESS (casting from phones/laptops will stop)"
        else
            echo "SKIPPED (not installed or system protected)"
        fi
    fi
fi

echo ""
echo "======================================================"
echo "[+] Debloat complete!"
echo ""
echo "NOTE ON STOCK LAUNCHER:"
if [ "$DISABLE_STOCK" -eq 1 ]; then
    echo "Disabling stock Google TV Launcher (frees RAM) ..."
    adb shell pm disable-user --user 0 com.google.android.tvlauncher || echo "SKIPPED (could not disable)"
fi
echo "If you kept it enabled and later want to free its RAM once FeatherTV"
echo "is set as default, run:"
echo "    ./scripts/debloat-nokia-tv.sh --disable-stock-launcher"
echo ""
echo "To restore:"
echo "    ./scripts/restore-nokia-tv.sh                   # built-in list"
echo "    ./scripts/restore-nokia-tv.sh $BACKUP_FILE      # exact pre-debloat state"
echo "======================================================"
