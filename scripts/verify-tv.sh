#!/usr/bin/env bash
# ==============================================================================
# FeatherTV TV Verification / Auto-Reconnect
#
# Usage:
#   ./scripts/verify-tv.sh                           # verify an already-connected TV
#   ./scripts/verify-tv.sh --ip 192.168.1.100        # connect first, then verify
#   ./scripts/verify-tv.sh --watch                   # keep polling until the TV is online
#   ./scripts/verify-tv.sh --watch --ip 192.168.1.100
# ==============================================================================

set -euo pipefail

IP=""
WATCH=0
ATTEMPTS=10
SLEEP=2

while [ "$#" -gt 0 ]; do
    case "$1" in
        --ip)
            [ "$#" -ge 2 ] || { echo "Missing value for --ip" >&2; exit 1; }
            IP="$2"; shift 2 ;;
        --watch) WATCH=1; shift ;;
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
    echo "[*] Connecting to $IP:5555 ..."
    adb connect "$IP:5555" || true
fi

wait_for_device() {
    local i=0
    while :; do
        if adb get-state 2>/dev/null | grep -q "device"; then
            return 0
        fi
        i=$((i + 1))
        if [ "$WATCH" -eq 1 ] && [ "$i" -ge "$ATTEMPTS" ]; then
            i=0   # watch mode: keep polling forever
        elif [ "$i" -ge "$ATTEMPTS" ]; then
            return 1
        fi
        sleep "$SLEEP"
    done
}

echo "[*] Waiting for device ..."
if ! wait_for_device; then
    echo "[-] No device online. Is the TV on and on the same Wi-Fi?" >&2
    echo "[-] Find the IP under Settings -> Network & Internet, then rerun with --ip." >&2
    exit 1
fi

echo "[+] Device online."
echo ""

echo "=== Device ==="
echo "  Model:      $(adb shell getprop ro.product.model | tr -d '\r')"
echo "  Android:    $(adb shell getprop ro.build.version.release | tr -d '\r')"
echo "  Bootreason: $(adb shell getprop ro.boot.bootreason | tr -d '\r')   (kernel_pnc = last session ended in kernel panic)"

echo ""
echo "=== FeatherTV ==="
if adb shell pm list packages 2>/dev/null | grep -q com.feathertv.launcher; then
    echo "  Installed:  yes"
else
    echo "  Installed:  NO - run: adb install -r app/build/outputs/apk/debug/app-debug.apk"
fi
echo "  Default home: $(adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null | tail -1 | tr -d '\r')"

echo ""
echo "=== Wi-Fi saved networks ==="
adb shell "cmd wifi list-networks 2>/dev/null | tail -n +2" | tr -d '\r' | sed 's/^/  /'
COUNT=$(adb shell "cmd wifi list-networks 2>/dev/null | tail -n +2 | grep -c ." | tr -d '\r' || true)
CONNECTED_ID=$(adb shell "dumpsys wifi 2>/dev/null | grep mLastNetworkId" | sed 's/.*mLastNetworkId //' | tr -d '\r' || true)
if [ "${COUNT:-0}" -gt 1 ]; then
    echo "  [!] Duplicate entries for the same network. The connected one is id ${CONNECTED_ID:-?}."
    echo "      Remove each stale entry with: adb shell cmd wifi forget-network <stale-id>"
    echo "      (Stale entries are often hidden configs the Settings UI won't show/forget.)"
fi

echo ""
echo "=== Disabled packages ==="
adb shell "pm list packages -d 2>/dev/null | sed 's/^package://'" | tr -d '\r' | sed 's/^/  /'

echo ""
echo "=== Memory (PSS) ==="
echo "  FeatherTV:      $(adb shell "dumpsys meminfo com.feathertv.launcher 2>/dev/null | grep 'TOTAL PSS'" | sed 's/.*PSS: *//; s/ .*//' | tr -d '\r') kB"
echo "  Stock launcher: $(adb shell "dumpsys meminfo com.google.android.tvlauncher 2>/dev/null | grep 'TOTAL PSS'" | sed 's/.*PSS: *//; s/ .*//' | tr -d '\r') kB"
adb shell "dumpsys meminfo 2>/dev/null | grep -E 'Free RAM|Used RAM'" | tr -d '\r' | sed 's/^/  /'
