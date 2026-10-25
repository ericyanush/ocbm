#!/usr/bin/env bash
# Install the reviewed Dropbear candidate on a stock-derived NCM base that has no server.
# Telnet/NCM is deliberately kept as the recovery channel throughout staging, smoke testing,
# installation, and the persistence reboot.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BOXSH="$REPO/tools/boxsh.py"
SOURCE_BIN="${DROPBEAR_BIN:-$REPO/ccpa/binaries/dropbear-2026.91-static-musl-armv7}"
BOX="${BOX:-192.168.50.2}"
IDENTITY="${IDENTITY:-$HOME/.ssh/id_ccpa_ncm}"
RUN_DIR="${RUN_DIR:-$REPO/scratchpad/dropbear_bootstrap_$(date +%Y%m%d_%H%M%S)}"
ALT_PORT=2222
HAD_AUTH_KEYS=0

while [ $# -gt 0 ]; do
  case "$1" in
    --binary) SOURCE_BIN="$2"; shift 2 ;;
    --host) BOX="$2"; shift 2 ;;
    --identity) IDENTITY="$2"; shift 2 ;;
    --run-dir) RUN_DIR="$2"; mkdir -p "$RUN_DIR"; shift 2 ;;
    -h|--help)
      cat <<'EOF'
Usage: tools/dropbear_bootstrap.sh [--binary PATH] [--host IP] [--identity PATH] [--run-dir DIR]

Stage, smoke-test on port 2222, install, and cold-boot-verify the Dropbear 2026.91 ARMv7
binary while keeping NCM telnet as the recovery channel. The default binary is checked in at
ccpa/binaries/dropbear-2026.91-static-musl-armv7. The host's id_ccpa_ncm key is added to root's
SSH authorized_keys without removing existing entries.
EOF
      exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
done

mkdir -p "$RUN_DIR"
say() { printf '[dropbear-bootstrap] %s\n' "$*"; }
die() { printf '[dropbear-bootstrap] ABORT: %s\n' "$*" >&2; exit 1; }
box() { python3 "$BOXSH" --host "$BOX" --timeout 60 run "$1"; }
put() { python3 "$BOXSH" --host "$BOX" --timeout 60 --mode "$3" put "$1" "$2" >/dev/null; }
ssh_box() {
  ssh -n -i "$IDENTITY" -o IdentitiesOnly=yes -o BatchMode=yes \
    -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o LogLevel=ERROR \
    -o ConnectTimeout=8 -p "${1:-22}" "root@$BOX" "${2:-true}"
}
wait_ncm() {
  local i
  for i in $(seq 1 50); do
    if python3 "$BOXSH" --host "$BOX" --timeout 5 probe >/dev/null 2>&1; then return 0; fi
    sleep 5
  done
  return 1
}
stop_smoke() { box "killall dropbear 2>/dev/null; pkill -f '/tmp/ocbm-dropbear/dropbear' 2>/dev/null; :" >/dev/null 2>&1 || true; }
rollback_install() {
  box 'killall dropbear 2>/dev/null; rm -f /usr/sbin/dropbear; sync' >/dev/null 2>&1 || true
  if [ "$HAD_AUTH_KEYS" = 1 ]; then
    put "$RUN_DIR/authorized_keys.pre" /root/.ssh/authorized_keys 600 || true
  else
    box 'rm -f /root/.ssh/authorized_keys; rmdir /root/.ssh 2>/dev/null || true' >/dev/null 2>&1 || true
  fi
}

[ -f "$IDENTITY" ] && [ -f "$IDENTITY.pub" ] || die "NCM SSH identity is missing: expected $IDENTITY and $IDENTITY.pub"

candidate="$RUN_DIR/dropbear"
[ -f "$SOURCE_BIN" ] || die "Dropbear binary not found: $SOURCE_BIN"
cp "$SOURCE_BIN" "$candidate"
chmod 755 "$candidate"
candidate_sha=$(shasum -a 256 "$candidate" | awk '{print $1}')
candidate_md5=$(md5 -q "$candidate")
candidate_ver=$(strings "$candidate" | grep -oE 'SSH-2.0-dropbear_[0-9.]+' | head -1)
[ -n "$candidate_ver" ] || die "candidate does not contain a Dropbear version string"
file "$candidate" | grep -q 'ARM' || die "candidate is not an ARM executable"
[ "$candidate_sha" = "8a76bbb92107cdcf12007478d6c6133c34f5617a3a73127576c45082491a4e61" ] \
  || die "candidate SHA-256 differs from the reviewed Dropbear 2026.91 build"
say "candidate=$candidate_ver sha256=$candidate_sha"
say "source=$SOURCE_BIN"

wait_ncm || die "NCM telnet shell is not reachable at $BOX"
box 'test ! -x /usr/sbin/dropbear || { echo DROPBEAR_ALREADY_PRESENT; exit 1; }'
say "confirmed no existing Dropbear binary; telnet/NCM remains the recovery path"

# Merge the host's dedicated NCM public key without discarding any existing root keys.
if box 'test -f /root/.ssh/authorized_keys' >/dev/null 2>&1; then
  HAD_AUTH_KEYS=1
  python3 "$BOXSH" --host "$BOX" --timeout 60 get /root/.ssh/authorized_keys "$RUN_DIR/authorized_keys.pre" >/dev/null
  cp "$RUN_DIR/authorized_keys.pre" "$RUN_DIR/authorized_keys"
else
  : > "$RUN_DIR/authorized_keys.pre"
  : > "$RUN_DIR/authorized_keys"
fi
pubkey=$(awk 'NF >= 2 {print $2; exit}' "$IDENTITY.pub")
[ -n "$pubkey" ] || die "public key file is malformed"
if ! grep -Fq "$pubkey" "$RUN_DIR/authorized_keys"; then cat "$IDENTITY.pub" >> "$RUN_DIR/authorized_keys"; fi

box 'chown root:root /root && chmod 755 /root; mkdir -p /tmp/ocbm-dropbear /root/.ssh; chown root:root /root/.ssh; chmod 700 /root/.ssh'
put "$candidate" /tmp/ocbm-dropbear/dropbear 755
put "$RUN_DIR/authorized_keys" /tmp/ocbm-dropbear/authorized_keys 600
got=$(box 'md5sum /tmp/ocbm-dropbear/dropbear | cut -d" " -f1' | tr -d '\r\n')
[ "$got" = "$candidate_md5" ] || die "staged candidate hash mismatch"
ver=$(box '/tmp/ocbm-dropbear/dropbear -V 2>&1 | head -1' | tr -d '\r')
echo "$ver" | grep -q 'Dropbear v2026.91' || die "staged binary failed its on-device version check: $ver"
say "staged candidate runs on the adapter: $ver"

# Temporarily install the key for the alternate-port test; preserve a host-side copy for rollback.
box '[ ! -e /root/.ssh/authorized_keys ] || cp -p /root/.ssh/authorized_keys /root/.ssh/authorized_keys.pre_ocbm_dropbear'
box 'cp /tmp/ocbm-dropbear/authorized_keys /root/.ssh/authorized_keys && chmod 600 /root/.ssh/authorized_keys'
box "setsid /tmp/ocbm-dropbear/dropbear -R -F -E -p $ALT_PORT >/tmp/ocbm-dropbear/smoke.log 2>&1 </dev/null &"
sleep 3
if ! ssh_box "$ALT_PORT" 'echo DROPBEAR_SMOKE_OK; id; uname -srm' > "$RUN_DIR/smoke.txt" 2>&1 \
   || ! grep -q DROPBEAR_SMOKE_OK "$RUN_DIR/smoke.txt"; then
  box 'cat /tmp/ocbm-dropbear/smoke.log 2>/dev/null || true' > "$RUN_DIR/smoke.log" 2>&1 || true
  stop_smoke
  if [ "$HAD_AUTH_KEYS" = 1 ]; then
    put "$RUN_DIR/authorized_keys.pre" /root/.ssh/authorized_keys 600 || true
  else
    box 'rm -f /root/.ssh/authorized_keys; rmdir /root/.ssh 2>/dev/null || true' >/dev/null 2>&1 || true
  fi
  die "candidate failed the alternate-port public-key SSH smoke test; it was not installed"
fi
stop_smoke
say "alternate-port SSH smoke test passed"

# Persist the tested key and executable. rcS already invokes `dropbear` on each boot; -R above
# generated the missing modern host key alongside the stock RSA/DSS keys.
box 'test ! -e /usr/sbin/dropbear || { echo DROPBEAR_APPEARED; exit 1; }; cp /tmp/ocbm-dropbear/dropbear /usr/sbin/dropbear.stage && chmod 755 /usr/sbin/dropbear.stage && mv /usr/sbin/dropbear.stage /usr/sbin/dropbear && sync'
box 'dropbear'
sleep 2
if ! ssh_box 22 'echo DROPBEAR_INSTALL_OK; id' > "$RUN_DIR/install.txt" 2>&1 \
   || ! grep -q DROPBEAR_INSTALL_OK "$RUN_DIR/install.txt"; then
  rollback_install
  die "SSH on port 22 failed; the new binary was removed over telnet/NCM"
fi
say "port 22 SSH works with the dedicated NCM key"

say "rebooting once to prove rcS starts Dropbear while NCM telnet remains available"
box 'sync; (sleep 1; reboot) >/dev/null 2>&1 &' >/dev/null 2>&1 || true
sleep 10
wait_ncm || die "NCM did not return after the Dropbear persistence reboot; use the saved full NOR backup for recovery"
if ! ssh_box 22 'echo DROPBEAR_COLD_BOOT_OK; id; pidof dropbear' > "$RUN_DIR/coldboot_ssh.txt" 2>&1 \
   || ! grep -q DROPBEAR_COLD_BOOT_OK "$RUN_DIR/coldboot_ssh.txt"; then
  rollback_install
  die "NCM returned but SSH did not survive cold boot; telnet remains available for repair"
fi
say "cold boot verified: rcS starts Dropbear and SSH works; telnet/NCM still answers"
printf 'version=%s\nsha256=%s\nsource=%s\n' "$candidate_ver" "$candidate_sha" "$SOURCE_BIN" \
  | tee "$RUN_DIR/result.txt"
say "result: $RUN_DIR/result.txt"
