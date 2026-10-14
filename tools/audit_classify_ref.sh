#!/bin/sh
# Classify one missing absolute-path reference reported by audit_scripts.sh.
# AUDIT_ROOT lets the regression tests evaluate an extracted rootfs tree locally.
set -u

file_arg=$1
ref=$2
root=${AUDIT_ROOT:-}

resolve_file() {
  p=$1
  if [ -f "$p" ]; then printf '%s\n' "$p"
  elif [ -n "$root" ] && [ -f "$root$p" ]; then printf '%s\n' "$root$p"
  else printf '%s\n' "$p"
  fi
}

file=$(resolve_file "$file_arg")
[ -f "$file" ] || { echo "UNREADABLE $file_arg (cannot classify $ref)"; exit 0; }

hits=$(grep -nF "$ref" "$file" 2>/dev/null || true)
unprotected=$(printf '%s\n' "$hits" | awk -v ref="$ref" '
  {
    line=$0
    sub(/^[0-9]+:/, "", line)
    if (line ~ /^[[:space:]]*#/) next
    if (index(line, "test -e " ref) || index(line, "test -f " ref) || index(line, "test -x " ref)) next
    if (index(line, "[ -e " ref " ]") || index(line, "[ -f " ref " ]") || index(line, "[ -x " ref " ]")) next
    # Log messages naming an operation are not executable references.
    if (line ~ /^[[:space:]]*log[[:space:]]/ && index(line, ref)) next
    print
  }
')

# The vendor dispatcher derives this flag from the existence of wpa_supplicant, then
# performs the copy only inside the supportStaMode=1 block. Validate both facts and
# that the copy is actually inside that block before treating this indirect guard as safe.
if [ "${file_arg##*/}" = init_bluetooth_wifi.sh ] && [ "$ref" = /usr/sbin/wpa_supplicant ]; then
  if grep -Fq 'test -e /usr/sbin/wpa_supplicant && supportStaMode=1 || supportStaMode=0' "$file" \
    && awk '
      /^[[:space:]]*if \[ "\$supportStaMode" -eq 1 \]; then[[:space:]]*$/ { active=1; depth=1; next }
      active {
        if (index($0, "cp /usr/sbin/wpa_supplicant /tmp/bin/")) found=1
        if ($0 ~ /^[[:space:]]*if[[:space:]].*; then[[:space:]]*$/) depth++
        if ($0 ~ /^[[:space:]]*fi[[:space:]]*$/) { depth--; if (depth == 0) active=0 }
      }
      END { exit !found }
    ' "$file"; then
    unprotected=$(printf '%s\n' "$unprotected" | grep -vF 'cp /usr/sbin/wpa_supplicant /tmp/bin/' || true)
  fi
fi

# The retained vendor start_bluetooth_wifi.sh copies wpa_cli only in P2P mode. On this stripped
# NCM boot path, custom_init invokes it with no arguments; riddleBoxCfg is absent, so its mode
# expression falls through to AP, and the later P2P override is gated on wpa_supplicant, which
# is also absent. Validate those exact live-state facts and the AP/P2P branch boundary. This is
# a boot-path proof, not a claim that arbitrary/manual P2P calls are safe.
if [ "${file_arg##*/}" = start_bluetooth_wifi.sh ] && [ "$ref" = /usr/sbin/wpa_cli ]; then
  custom_init=$(resolve_file /script/custom_init.sh)
  supplicant=$(resolve_file /usr/sbin/wpa_supplicant)
  riddle_cfg=$(resolve_file /usr/sbin/riddleBoxCfg)
  if [ ! -e "$supplicant" ] && [ ! -e "$riddle_cfg" ] \
    && grep -Fq '/script/start_bluetooth_wifi.sh >/tmp/sbw_early.log 2>&1' "$custom_init" \
    && { grep -Eq 'riddleBoxCfg .*WiFiP2PMode.*mode=P2P.*mode=AP' "$file" \
         || grep -Eq '^[[:space:]]*mode=AP[[:space:]]*$' "$file"; } \
    && grep -Fq '[ -e /usr/sbin/wpa_supplicant ] && [ -e /tmp/moal.ko ] && [ "$mode" = "AP" ]; then' "$file" \
    && awk '
      { line=$0; sub(/^[[:space:]]+/, "", line); sub(/[[:space:]]+$/, "", line) }
      line == "if [ \"$mode\" = \"AP\" ]; then" { in_mode=1; next }
      in_mode && line == "else" { in_p2p=1; next }
      in_mode && line == "fi" { in_mode=0; in_p2p=0 }
      in_p2p && index(line, "cp /usr/sbin/wpa_cli /tmp/bin/") { found=1 }
      END { exit !found }
    ' "$file"; then
    unprotected=$(printf '%s\n' "$unprotected" | grep -vF 'cp /usr/sbin/wpa_cli /tmp/bin/' || true)
  fi
fi

# radio_hal's owned-script calls are reachable only when radio_detect selects BACKEND=owned.
# Validate that selection is explicitly gated on the presence of BOTH scripts, and that the
# particular call remains in the owned arm. This avoids treating a mapped vendor backend as if
# it could enter an unavailable IW416-only path.
radio_detect=$(resolve_file /script/radio_detect.sh)
if [ "${file_arg##*/}" = radio_hal.sh ] \
  && { [ "$ref" = /script/bt_on.sh ] || [ "$ref" = /script/wlan_on.sh ]; }; then
  if awk '
      $0 == "if [ -f /script/wlan_on.sh ] && [ -f /script/bt_on.sh ]; then" {
        if (getline > 0 && $0 ~ /^[[:space:]]*BACKEND=owned[[:space:]]*$/) found=1
      }
      END { exit !found }
    ' "$radio_detect" \
    && awk -v ref="$ref" '
      /case "\$RADIO_BACKEND" in/ { in_case=1 }
      in_case && /^[[:space:]]*owned\)/ { in_owned=1; next }
      in_case && in_owned && /^[[:space:]]*;;/ { in_owned=0 }
      in_case && in_owned && index($0, "sh " ref ";") { found=1 }
      END { exit !found }
    ' "$file"; then
    unprotected=$(printf '%s\n' "$unprotected" | grep -vF "sh $ref;" || true)
  fi
fi

# The owned WLAN shutdown path uses a combined backend + executable check on the `if`
# immediately before its `sh` call, rather than on the call's own line.
if [ "${file_arg##*/}" = radio_hal.sh ] && [ "$ref" = /script/wlan_off.sh ]; then
  if grep -Fq "if [ \"\${RADIO_BACKEND:-none}\" = owned ] && [ -x $ref ]; then" "$file" \
    && grep -Fq "sh $ref;" "$file"; then
    unprotected=$(printf '%s\n' "$unprotected" | grep -vF "sh $ref;" || true)
  fi
fi

if [ -n "$unprotected" ]; then
  printf 'UNGUARDED %s -> MISSING %s :: %s\n' "$file_arg" "$ref" "$unprotected"
else
  printf 'guarded   %s -> %s\n' "$file_arg" "$ref"
fi
