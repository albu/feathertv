#!/usr/bin/env bash
# ==============================================================================
# FeatherTV TV Memory Optimizer
#
# Reclaims RAM from idle media/background apps without disabling anything.
# Apps are force-stopped and put in the "rare" app-standby bucket, so they no
# longer sit in the background; they simply restart when you open them.
#
# Usage:
#   ./scripts/optimize-memory.sh                # reclaim from idle media apps
#   ./scripts/optimize-memory.sh --also-play    # also force-stop Play Store
#   ./scripts/optimize-memory.sh --dry-run      # show what would be done
#   ./scripts/optimize-memory.sh --ip 192.168.1.100
# ==============================================================================

set -euo pipefail

IP=""
ALSO_PLAY=0
DRY=0

while [ "$#" -gt 0 ]; do
    case "$1" in
        --ip)
            [ "$#" -ge 2 ] || { echo "Missing value for --ip" >&2; exit 1; }
            IP="$2"; shift 2 ;;
        --also-play) ALSO_PLAY=1; shift ;;
        --dry-run) DRY=1; shift ;;
        --help|-h)
            grep '^#' "$0" | sed 's/^# \{0,1\}//'
            exit 0 ;;
        *)
            echo "Unknown argument: $1 (see --help)" >&2
            exit 1 ;;
    esac
done

if ! command -v adb &>/dev/null; then
    echo "[-] 'adb' not found. Add it to PATH (e.g. ~/Library/Android/sdk/platform-tools)." >&2
    exit 1
fi

if [ -n "$IP" ]; then
    adb connect "$IP:5555" >/dev/null || true
fi

if ! adb get-state 2>/dev/null | grep -q "device"; then
    echo "[-] No device online." >&2
    exit 1
fi

# Idle media / background apps that do not need to run until opened.
MEDIA_APPS=(
    "com.apple.atve.androidtv.appletv"    # Apple TV
    "com.netflix.ninja"                   # Netflix
    "ru.kinopoisk.tv"                     # Kinopoisk
    "com.amazon.amazonvideo.livingroom"   # Prime Video
    "com.yle.webtv"                       # YLE Areena
    "tv.wuaki.apptv"                      # Rakuten TV
)

free_ram_kb() {
    adb shell "dumpsys meminfo 2>/dev/null | grep 'Free RAM'" \
        | sed 's/.*Free RAM: *//; s/(.*//' | tr -dc '0-9'
}

BEFORE=$(free_ram_kb || echo "?")
echo "[+] Free RAM before: ${BEFORE} kB"

if [ "$DRY" -eq 1 ]; then
    echo "[*] DRY RUN - no changes."
    echo "[*] Would force-stop and set 'rare' standby for:"
    printf '      %s\n' "${MEDIA_APPS[@]}"
    if [ "$ALSO_PLAY" -eq 1 ]; then
        echo "      com.android.vending (+ :background) [--also-play]"
    fi
    exit 0
fi

for pkg in "${MEDIA_APPS[@]}"; do
    if adb shell pm list packages 2>/dev/null | grep -q "$pkg"; then
        echo -n "[*] $pkg: "
        adb shell am force-stop "$pkg" >/dev/null 2>&1
        adb shell am set-standby-bucket "$pkg" rare >/dev/null 2>&1
        echo "stopped + rare"
    else
        echo "[*] $pkg: not installed (skipped)"
    fi
done

if [ "$ALSO_PLAY" -eq 1 ]; then
    echo -n "[*] com.android.vending: "
    adb shell am force-stop com.android.vending >/dev/null 2>&1
    adb shell am force-stop com.android.vending:background >/dev/null 2>&1
    echo "stopped (will restart on next Play Store use)"
fi

echo ""
AFTER=$(free_ram_kb || echo "?")
echo "[+] Free RAM after:  ${AFTER} kB"
if [ "$BEFORE" != "?" ] && [ "$AFTER" != "?" ]; then
    echo "[+] Reclaimed: $(( (AFTER - BEFORE) / 1024 )) MB"
fi
echo ""
echo "Apps restart on demand when you open them. To undo the standby buckets:"
echo "    adb shell am set-standby-bucket <package> active"
