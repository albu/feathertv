#!/usr/bin/env bash
# ==============================================================================
# FeatherTV TV Log Capture
#
# One command that saves everything useful for diagnosing a reboot or a
# Wi-Fi wake failure into a timestamped folder under logs/.
#
# Usage:
#   ./scripts/capture-logs.sh                     # capture from connected TV
#   ./scripts/capture-logs.sh --ip 192.168.1.100  # connect first, then capture
# ==============================================================================

set -euo pipefail

IP=""

while [ "$#" -gt 0 ]; do
    case "$1" in
        --ip)
            [ "$#" -ge 2 ] || { echo "Missing value for --ip" >&2; exit 1; }
            IP="$2"; shift 2 ;;
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

if ! adb get-state 2>/dev/null | grep -q "device"; then
    echo "[-] No device online." >&2
    exit 1
fi

OUT_DIR="$(cd "$(dirname "$0")/.." && pwd)/logs/capture-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT_DIR"
echo "[+] Saving logs to: $OUT_DIR"
echo ""

run() {
    local name="$1"; shift
    echo "--- $name ---" > "$OUT_DIR/$name.txt"
    "$@" >> "$OUT_DIR/$name.txt" 2>&1 || echo "(command failed)" >> "$OUT_DIR/$name.txt"
}

run device           adb shell getprop ro.product.model
run android-version  adb shell getprop ro.build.version.release
run bootreason       adb shell getprop ro.boot.bootreason
run sys-boot-reason  adb shell getprop sys.boot.reason
run uptime           adb shell cat /proc/uptime
run dropbox          adb shell dumpsys dropbox
run crash-buffer     adb shell logcat -d -b crash
run system-errors    adb shell logcat -d -b system
run wifi-state       adb shell dumpsys wifi
run wifi-networks    adb shell cmd wifi list-networks
run wifi-logcat      adb shell logcat -d -b all
run memory           adb shell dumpsys meminfo
run feathertv-memory  adb shell dumpsys meminfo com.feathertv.launcher
run disabled-pkgs    adb shell pm list packages -d

# Kernel panic traces live in pstore; usually root-only.
if adb shell "ls -la /sys/fs/pstore/ 2>/dev/null" > "$OUT_DIR/pstore.txt" 2>&1; then
    echo "pstore readable (root available)" >> "$OUT_DIR/pstore.txt"
else
    echo "pstore not readable without root (expected on retail builds)" >> "$OUT_DIR/pstore.txt"
fi
if adb shell "dmesg 2>/dev/null | grep -iE 'panic|oops|watchdog' | tail -50" > "$OUT_DIR/kernel-panic-grep.txt" 2>&1; then
    :
else
    echo "dmesg not readable without root" >> "$OUT_DIR/kernel-panic-grep.txt"
fi

echo "[+] Done. Files:"
ls -1 "$OUT_DIR" | sed 's/^/    /'
echo ""
echo "Zip the folder and attach it when reporting the issue."
