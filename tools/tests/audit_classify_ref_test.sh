#!/bin/sh
set -eu

repo=$(CDPATH= cd -- "$(dirname "$0")/../.." && pwd)
resources=$(CDPATH= cd -- "$repo/../CPC200-CCPA_resources" && pwd)
classifier="$repo/tools/audit_classify_ref.sh"
root="$repo/ccpa/rootfs"
tmp=$(mktemp -d "${TMPDIR:-/tmp}/audit-classifier.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

expect_guarded() {
  result=$(
    AUDIT_ROOT="$root" sh "$classifier" "$1" "$2"
  )
  case "$result" in guarded*) printf 'PASS guarded: %s -> %s\n' "$1" "$2";;
    *) printf 'FAIL expected guarded, got: %s\n' "$result" >&2; exit 1;;
  esac
}

expect_guarded /script/radio_detect.sh /script/wlan_on.sh
expect_guarded /script/radio_detect.sh /script/bt_on.sh
expect_guarded /script/radio_hal.sh /script/bt_on.sh
expect_guarded /script/radio_hal.sh /script/wlan_on.sh
expect_guarded /script/radio_hal.sh /script/wlan_off.sh
expect_guarded "$resources/custom/scripts/init_bluetooth_wifi.sh" /usr/sbin/wpa_supplicant
expect_guarded "$resources/custom/firmware/2025.10.15.1127/NCM/scripts_changed/start_bluetooth_wifi.sh" /usr/sbin/wpa_cli

printf '%s\n' 'sh /script/not_present.sh' > "$tmp/unguarded.sh"
result=$(sh "$classifier" "$tmp/unguarded.sh" /script/not_present.sh)
case "$result" in UNGUARDED*) printf 'PASS unguarded reference rejected\n';;
  *) printf 'FAIL expected unguarded, got: %s\n' "$result" >&2; exit 1;;
esac

echo 'all audit-classifier tests passed'
