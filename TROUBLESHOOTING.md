# FeatherTV — Troubleshooting & Diagnostics

On-device findings and the exact commands to capture logs for the next reboot or
Wi-Fi wake failure. Everything here is read-only except the restore commands
listed at the end (which only re-enable what the debloat script disabled).

## Device under test

| Property | Value |
|---|---|
| Model | Nokia Android TV (`EMIR_Nokia`, board `shandao`) |
| OS | Android 11 (API 30), `RTM6.230109.138` |
| SoC | MediaTek `m7632` (32-bit userspace) |
| RAM | ~1.79 GB usable (MemTotal 1,830,712 kB) |
| OEM stack | Vestel StreamTV (`com.vestel.*`) + MediaTek WWTV (`com.mediatek.wwtv.*`) |

## Known findings

1. **Reboots are kernel panics.** `getprop ro.boot.bootreason` reads
   `kernel_pnc` — the bootloader records the previous session ended in a kernel
   panic. The panic backtrace lives in kernel `pstore`/`dmesg` and is **not
   readable without root** (`dmesg: Permission denied` on retail builds).
2. **Play Store process loop.** `dumpsys dropbox` shows recurring
   `system_server_wtf` entries: `com.android.vending` background process
   "refused to die, but we need to launch...". Churn under memory pressure,
   not the panic cause.
3. **Vendor boot bug.** `system_app_wtf` at every boot:
   `com.mediatek.tv.service` / `com.mediatek.tv.freeviewplay` —
   "Data directory doesn't exist for package". Pre-existing vendor issue.
4. **Wi-Fi duplicate configs.** The TV had two saved entries for the same
   network (`cp-4a5` and `cp-4a5 ` with a trailing space), both flagged hidden.
   This is a classic cause of flaky reconnection after standby wake. Fix:
   forget both and re-add once, without forcing "hidden" unless the router
   really hides the SSID.

   The Settings UI often cannot show or forget the stale hidden entry (e.g.
   `"cp-4a5 "` with a trailing space), so re-adding keeps creating a second
   visible entry. Remove it directly over adb instead:

   ```bash
   adb shell cmd wifi list-networks      # note the network ids
   adb shell cmd wifi forget-network <stale-id>
   ```

## After the next reboot — capture this

```bash
ADB="$HOME/Library/Android/sdk/platform-tools/adb"
"$ADB" shell getprop ro.boot.bootreason          # kernel_pnc => kernel panic
"$ADB" shell cat /proc/uptime                    # time since boot
"$ADB" shell "dumpsys dropbox | tail -40"        # crash/boot entries
"$ADB" shell "dumpsys dropbox --print system_server_wtf | tail -60"
"$ADB" shell "dumpsys dropbox --print system_app_wtf | tail -60"
"$ADB" shell "ls -la /sys/fs/pstore/"            # only with root
"$ADB" shell "dmesg | grep -iE 'panic|oops|watchdog' | tail -40"   # only with root
```

For the vendor to fix a kernel panic they need the pstore/ramoops dump, which
requires root (`adb root`) or their service tooling.

## After a Wi-Fi wake failure — capture this

```bash
"$ADB" shell "logcat -d | grep -iE 'wifi|wlan|wpa|supplicant' | tail -100"
"$ADB" shell "dumpsys wifi | grep -iE 'mWifiInfo|Supplicant state|mLastBssid'"
"$ADB" shell "cmd wifi list-networks"            # look for duplicates
"$ADB" shell "ip addr show wlan0"
```

## Memory measurements (real numbers from this TV)

```bash
"$ADB" shell "dumpsys meminfo com.google.android.tvlauncher | grep 'TOTAL PSS'"
"$ADB" shell "dumpsys meminfo com.feathertv.launcher | grep 'TOTAL PSS'"
"$ADB" shell "dumpsys meminfo | grep -E 'Total RAM|Free RAM|Used RAM'"
```

Observed: stock launcher ~205 MB PSS foreground, FeatherTV ~56–67 MB PSS,
Apple TV app ~95 MB PSS idle.

## Restore everything

```bash
./scripts/restore-nokia-tv.sh                          # built-in list
./scripts/restore-nokia-tv.sh backups/disabled-packages-<timestamp>.txt  # exact pre-debloat state
```

Re-enable the stock Google TV launcher if it was disabled:

```bash
adb shell pm enable com.google.android.tvlauncher
```
