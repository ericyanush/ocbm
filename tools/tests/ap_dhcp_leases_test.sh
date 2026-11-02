#!/bin/sh
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
sed -n '/^# BEGIN DHCP LEASE POLICY/,/^# END DHCP LEASE POLICY/p' \
  "$ROOT/ccpa/rootfs/script/radio_ap_up.sh" > "$TMP/policy.sh"
. "$TMP/policy.sh"
# Check exact AP process selection without signalling any real process.
(
  ps() { printf '%s\n' '101 root udhcpd /etc/udhcpd.conf' '102 root busybox udhcpd -f /tmp/udhcpd_ncm.conf' '103 root grep udhcpd'; }
  kill() {
    [ "$1" != -0 ] || return 1
    echo "$1" >> "$TMP/killed"
  }
  stop_ap_dhcp /etc/udhcpd.conf
  [ "$(cat "$TMP/killed")" = 101 ]
)
STOPPED=0
stop_ap_dhcp() { STOPPED=$((STOPPED+1)); }
dhcp_policy_log() { echo "$*" >> "$TMP/policy.log"; }
CONF="$TMP/udhcpd.conf"
LEASES="$TMP/ap.leases"
cat > "$CONF" <<EOF
# Keep unrelated options and the lease path intact.
start 192.168.43.100
end 192.168.43.200
interface sta0
lease_file $LEASES
option lease 864000
EOF
# The opaque binary lease payload must survive repeated starts byte-for-byte.
printf '\000\377saved-client-lease\001' > "$LEASES"
cp "$LEASES" "$TMP/expected"
configure_ap_dhcp "$CONF" 192.168.43 sta0
configure_ap_dhcp "$CONF" 192.168.43 sta0
cmp "$LEASES" "$TMP/expected"
[ "$STOPPED" = 0 ]
grep -q 'leases=preserved' "$TMP/policy.log"

# Interface changes require a restart, but preserve valid addresses.
configure_ap_dhcp "$CONF" 192.168.43 wlan0
cmp "$LEASES" "$TMP/expected"
[ "$STOPPED" = 1 ]
awk '$1 == "interface" && $2 == "wlan0" { found=1 } END { exit !found }' "$CONF"

# Pool changes invalidate only the AP leases, after stopping the old server.
printf 'ncm-must-survive' > "$TMP/ncm.leases"
configure_ap_dhcp "$CONF" 192.168.44 wlan0
[ ! -e "$LEASES" ]
[ "$STOPPED" = 2 ]
grep -q 'leases=invalidated reason=pool_changed' "$TMP/policy.log"
grep -q 'ncm-must-survive' "$TMP/ncm.leases"
grep -q 'option lease 864000' "$CONF"
awk '$1 == "start" && $2 == "192.168.44.100" { found=1 } END { exit !found }' "$CONF"

# Failure to stop a running server leaves its files untouched.
printf 'valid' > "$LEASES"
cp "$CONF" "$TMP/config-before"
stop_ap_dhcp() { return 1; }
if configure_ap_dhcp "$CONF" 192.168.45 wlan0; then echo 'FAIL: accepted live old server'; exit 1; fi
cmp "$CONF" "$TMP/config-before"
grep -q valid "$LEASES"
echo 'AP DHCP lease preservation tests passed'
