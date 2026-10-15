#!/usr/bin/env bash
# GM AAOS fixed-handler package smoke test. The helper intentionally has no attach activity:
# original Carlink testing showed it triggers driving distraction overlays, while the package-level
# silent grant and bound service work without it. See OCBMANDROID.md.
#
# The user-10 target is load-bearing: a user-0 install resolves in the wrong UsbUserPermissionManager
# and does NOTHING (docs/host/01_ANDROID_AND_AAOS.md). adb reachability on a locked GM unit is its own problem (ADB is OTG-only
# on the center-console Type-C, CAN-gated) — that is the real risk this proof is meant to expose, not
# the framework behaviour. If `adb` cannot reach the unit, this script cannot run; that IS a finding.
set -euo pipefail

PKG="android.car.usb.handler"
USER_ID="${USER_ID:-10}"          # override only to A/B the user-0-does-nothing claim
HERE="$(cd "$(dirname "$0")/.." && pwd)"   # host/CarlinkAndroid
APK="$HOME/.cache/gradle-builds/carlink-android-ocbm/usbhandler/outputs/apk/debug/usbhandler-debug.apk"

echo "== build =="
"$HERE/gradlew" -p "$HERE" :usbhandler:assembleDebug --console=plain

echo "== adb device =="
adb get-state >/dev/null 2>&1 || { echo "!! no adb device — see header: on a locked GM unit this is EXPECTED and is itself the finding"; exit 2; }
adb shell pm list users | sed 's/^/   /'

echo "== install $PKG into user $USER_ID =="
# --user places the install in the foreground user; -g/-t not needed (no runtime perms declared).
adb install -r --user "$USER_ID" "$APK"
echo "   installed: $(adb shell pm list packages --user "$USER_ID" "$PKG")"

echo "== verify no helper activity is registered =="
if adb shell dumpsys package "$PKG" | grep -Fq "android.car.usb.handler.UsbHostManagementActivity"; then
    echo "!! fixed-handler activity is still present; this can trigger the driving overlay"
    exit 1
fi
echo "   no UsbHostManagementActivity registered"
echo
echo "Now replug the CCPA-OCBM and launch the main app while parked. Confirm it logs a successful"
echo "[USB_HELPER] bind/open and starts the raw OCBM stream, with no USB permission prompt."
