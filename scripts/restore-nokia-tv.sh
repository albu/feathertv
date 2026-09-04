#!/usr/bin/env bash
# ==============================================================================
# Nokia Android TV Package Restore Utility
#
# Usage:
#   ./scripts/restore-nokia-tv.sh                       # restore the built-in list
#   ./scripts/restore-nokia-tv.sh <backup-file.txt>     # restore an exact backup
#                                                       # (from backups/disabled-packages-*.txt)
#   ./scripts/restore-nokia-tv.sh --full-restore        # also reset animation scales + standby settings
# ==============================================================================

set -euo pipefail

FULL=0
while [ "$#" -gt 0 ]; do
    case "$1" in
        --full-restore) FULL=1; shift ;;
        *) break ;;
    esac
done

echo "======================================================"
echo "      Nokia Android TV Package Restore Utility        "
echo "======================================================"

# Check ADB connection
if ! command -v adb &> /dev/null; then
    echo "[-] Error: 'adb' command not found."
    exit 1
fi

DEVICE_COUNT=$(adb devices | grep -v "List of devices" | grep "device$" | wc -l | tr -d ' ')
if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo "[-] No Android TV detected via ADB."
    exit 1
fi

enable_package() {
    local pkg="$1"
    echo -n "[*] Restoring package: $pkg ... "
    if adb shell cmd package install-existing "$pkg" &>/dev/null || adb shell pm enable "$pkg" &>/dev/null; then
        echo "RESTORED"
    else
        echo "SKIPPED"
    fi
}

if [ "$#" -ge 1 ]; then
    BACKUP_FILE="$1"
    if [ ! -f "$BACKUP_FILE" ]; then
        echo "[-] Backup file not found: $BACKUP_FILE" >&2
        exit 1
    fi
    echo "[*] Restoring from backup: $BACKUP_FILE"
    echo ""
    # Restore every package that was disabled at backup time.
    while IFS= read -r pkg; do
        [ -z "$pkg" ] && continue
        enable_package "$pkg"
    done < "$BACKUP_FILE"
else
    echo "[*] Restoring built-in package list"
    echo ""
    # Mirrors everything debloat-nokia-tv.sh can disable (safe + aggressive),
    # plus the stock launcher.
    PACKAGES=(
        "com.google.android.tvlauncher"
        "com.google.android.backdrop"
        "com.google.android.apps.mediashell"
        "com.google.android.katniss"
        "com.google.android.tv.assistant"
        "com.google.android.tv.recommendations"
        "com.google.android.tvrecommendations"
        "com.google.android.videos"
        "com.google.android.play.games"
        "com.google.android.feedback"
        "com.google.android.partnersetup"
        "com.google.android.onetimeinitializer"
        "com.google.android.syncadapters.contacts"
        "com.google.android.syncadapters.calendar"
        "com.google.android.printservice.recommendation"
        "com.vestel.tv.speedup"
        "com.vestel.smartlogger"
        "com.vestel.vestelreporter"
        "com.vestel.smartcenter"
        "com.droidlogic.appinstall"
        "com.droidlogic.mediacenter"
        "com.sei.android.tv.livetv"
        "com.sei.smarttv.operator"
        "com.streamview.tv"
    )
    for pkg in "${PACKAGES[@]}"; do
        enable_package "$pkg"
    done
fi

echo ""
echo "[+] Package restoration complete."

if [ "$FULL" -eq 1 ]; then
    echo ""
    echo "------------------------------------------------------"
    echo " Resetting animation scales back to 1.0x              "
    echo "------------------------------------------------------"
    adb shell settings put global window_animation_scale 1.0
    adb shell settings put global transition_animation_scale 1.0
    adb shell settings put global animator_duration_scale 1.0
    echo "[+] Animation scales restored to 1.0x"

    echo ""
    echo "------------------------------------------------------"
    echo " Resetting standby/wake tweaks (from --standby)       "
    echo "------------------------------------------------------"
    echo -n "[*] Re-enabling screensaver ... "
    adb shell settings put secure screensaver_enabled 1
    adb shell settings put secure screensaver_activate_on_sleep 1
    adb shell settings put secure screensaver_activate_on_dock 1
    echo "done"
    echo -n "[*] Clearing background process limit ... "
    adb shell settings delete global background_process_limit
    echo "done"
    echo -n "[*] Re-enabling app-error report dialog ... "
    adb shell settings put secure send_action_app_error 1
    echo "done"
    echo "[*] NOTE: Backdrop / Chromecast packages are re-enabled by the built-in restore list above."
fi
