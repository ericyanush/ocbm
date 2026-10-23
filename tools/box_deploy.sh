#!/bin/bash
# One-command box deploy: latch on wherever the box is, flip it to NCM, push over SSH, put it back.
#
# WHY THIS EXISTS. Deploying a box binary used to be a manual mode flip: open the macOS app, click
# "Enter NCM maintenance mode", CLOSE the app so it releases the USB device, wait, then push — and
# the push itself went over telnet, because that is what `ncm_base_install.sh` defaults to. Telnet is
# the EMERGENCY RESCUE PATH: it exists so a box with broken ssh can be repaired. Routing a 1.8 MB
# binary through it (2 KB base64 chunks over a line-oriented shell) is using the lifeboat as a ferry.
#
# What made this scriptable was one missing piece: `MGMT_ENTER_NCM` has been in `ocbm-proto` and
# handled by `ocbmd` since the CCPA tab shipped, but only the macOS app could SEND it. `ocbm-host ncm`
# closes that gap, so the whole cycle is now:
#
#     OCBM (accessory) --ocbm-host ncm--> reboot --> NCM (ssh stream) --install--[--return]--> OCBM
#
# The adapter image has no remote `scp` binary. Files are streamed over the authenticated SSH
# connection using `cat > /tmp/...`, then checked by MD5 before installation. Do not route this
# through telnet/base64 or a backgrounded `nc` listener; earlier `nc` transfers truncated large files.
#
# USAGE
#   tools/box_deploy.sh [--host SSH_DEST] [--return] [--dry-run] SRC:DEST[:MODE] [...]
#   tools/box_deploy.sh [--host SSH_DEST] --return-only # NCM back into accessory mode
#
#   --host        SSH destination (alias, hostname, or user@IP); default: ccpa-ncm
#   --return      after installing, clear /script/ncm_only and reboot back to OCBM, then wait for USB
#   --dry-run     resolve mode + verify reachability + print the plan; transfer nothing
#   MODE          octal chmod, default 755
#
# EXAMPLE
#   tools/box_deploy.sh --return \
#     target/armv7-unknown-linux-musleabihf/release/carplayd:/usr/sbin/carplayd
#
# The default SSH destination is `ccpa-ncm` (see ~/.ssh/config). Use `--host root@192.168.50.2`
# when the adapter's NCM address differs or the alias does not resolve. SSH password prompts are
# supported; connection multiplexing reuses the authenticated connection for later commands/transfers.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HOST_BIN="$REPO/target/release/ocbm-host"
BOX_SSH="ccpa-ncm"
VID=1314 PID=2d00
NCM_WAIT=90        # seconds to wait for ssh after ENTER_NCM (the reboot is normally a few seconds)
OCBM_WAIT=120      # seconds to wait for the USB device after returning

# Use a short socket path (macOS's TMPDIR can exceed the Unix-domain socket path limit). The first
# SSH connection can authenticate interactively by key or password; later commands reuse its master.
SSH_CONTROL_DIR="$(mktemp -d /tmp/ocbm-box-deploy.XXXXXX)"
SSH_CONTROL_PATH="$SSH_CONTROL_DIR/c"
SSH_OPTS=(-o ControlMaster=auto -o ControlPersist=300 -o "ControlPath=$SSH_CONTROL_PATH" -o ConnectTimeout=4)
cleanup_ssh() {
  ssh -S "$SSH_CONTROL_PATH" -O exit "$BOX_SSH" >/dev/null 2>&1 || true
  rmdir "$SSH_CONTROL_DIR" 2>/dev/null || true
}
trap cleanup_ssh EXIT

RETURN=0 DRYRUN=0 RETURN_ONLY=0
FILES=()
while [ $# -gt 0 ]; do
  case "$1" in
    --host)
      [ $# -ge 2 ] || { echo "ERROR: --host requires an SSH destination" >&2; exit 2; }
      BOX_SSH="$2"
      shift
      ;;
    --return)      RETURN=1 ;;
    --dry-run)     DRYRUN=1 ;;
    --return-only) RETURN_ONLY=1; RETURN=1 ;;
    -h|--help)     sed -n '1,40p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *)             FILES+=("$1") ;;
  esac
  shift
done

say()  { printf '[deploy] %s\n' "$*"; }
die()  { printf '[deploy] ERROR: %s\n' "$*" >&2; exit 1; }

ssh_box() { ssh "${SSH_OPTS[@]}" "$BOX_SSH" "$@"; }
# Keep stderr attached: OpenSSH may render the interactive password prompt there. Suppressing it
# makes password-only hosts look indistinguishable from an unreachable NCM address.
ssh_ok()  { ssh_box true >/dev/null; }
# ioreg, not `system_profiler SPUSBDataType` — the latter returns an empty document on this Mac, so a
# poll built on it spins the full timeout and reports "no adapter" with one plugged in. Same reasoning
# as tools/ocbm_push.sh.
usb_ok()  { ioreg -p IOUSB -w0 -l 2>/dev/null | grep -qi "idProduct.*$((16#$PID))"; }

wait_for() { # wait_for <predicate-fn> <seconds> <label>
  local fn="$1" limit="$2" label="$3" waited=0
  while ! "$fn"; do
    sleep 2; waited=$((waited + 2))
    [ "$waited" -ge "$limit" ] && return 1
    [ $((waited % 10)) -eq 0 ] && say "still waiting for $label (${waited}s)"
  done
  return 0
}

# ---- 1. Where is the box? ------------------------------------------------------------------------
# Order matters: check NCM FIRST. A box already in maintenance mode must not be sent an ENTER_NCM it
# cannot receive (ocbmd is not running in NCM), and re-flipping a box that is already there would
# reboot it for nothing.
MODE=""
if ssh_ok; then
  MODE=ncm
  say "box is in NCM maintenance mode (ssh $BOX_SSH)"
elif usb_ok; then
  MODE=ocbm
  say "box is on USB in accessory mode (OCBM $VID:$PID)"
else
  die "box not found — neither ssh '$BOX_SSH' nor a USB device $VID:$PID. Plug it into this Mac."
fi

if [ "$RETURN_ONLY" -eq 0 ] && [ ${#FILES[@]} -eq 0 ]; then
  die "nothing to deploy — pass SRC:DEST pairs, or --return-only to just exit NCM"
fi

# Validate every source BEFORE touching the box: discovering a typo after a reboot wastes a cycle.
for spec in "${FILES[@]:-}"; do
  [ -z "$spec" ] && continue
  src="${spec%%:*}"; rest="${spec#*:}"; dest="${rest%%:*}"
  [ -f "$src" ]  || die "no such file: $src"
  [ "$dest" = "$src" ] && die "malformed spec '$spec' — expected SRC:DEST[:MODE]"
  case "$dest" in /*) ;; *) die "destination must be absolute: $dest" ;; esac
done

if [ "$DRYRUN" -eq 1 ]; then
  say "DRY RUN — mode=$MODE, return=$RETURN"
  for spec in "${FILES[@]:-}"; do
    [ -z "$spec" ] && continue
    src="${spec%%:*}"; rest="${spec#*:}"; dest="${rest%%:*}"; mode="${rest#*:}"
    [ "$mode" = "$dest" ] && mode=755
    printf '[deploy]   %s -> %s (mode %s, %s bytes)\n' "$src" "$dest" "$mode" "$(wc -c < "$src" | tr -d ' ')"
  done
  exit 0
fi

# ---- 2. Flip to NCM if needed --------------------------------------------------------------------
if [ "$MODE" = ocbm ]; then
  [ -x "$HOST_BIN" ] || { say "building ocbm-host"; ( cd "$REPO" && cargo build --release -p ocbm-host >/dev/null ); }
  say "commanding ENTER_NCM over OCBM"
  # The app holds the USB device exclusively; if it is running, this fails with a claim error. Say so
  # plainly rather than letting the user read a libusb errno.
  "$HOST_BIN" ncm "$VID" "$PID" || die "ENTER_NCM failed — is the macOS app running? It holds the USB device."
  say "waiting for the box to come back on NCM (up to ${NCM_WAIT}s)"
  wait_for ssh_ok "$NCM_WAIT" "ssh $BOX_SSH" || die "box did not reappear on ssh within ${NCM_WAIT}s"
  say "box is up in NCM"
fi

# ---- 3. Transfer + install -----------------------------------------------------------------------
# Every file: stream to /tmp (tmpfs — costs no rootfs space while the old copy still exists), verify
# the md5 box-side against the local one, then move into place. The verify catches any interrupted
# or truncated stream before the installed copy is touched.
for spec in "${FILES[@]:-}"; do
  [ -z "$spec" ] && continue
  src="${spec%%:*}"; rest="${spec#*:}"; dest="${rest%%:*}"; mode="${rest#*:}"
  [ "$mode" = "$dest" ] && mode=755
  base="$(basename "$dest")"
  want="$(md5 -q "$src")"
  say "pushing $(basename "$src") -> $dest ($(wc -c < "$src" | tr -d ' ') bytes)"
  ssh_box "umask 077; cat > '/tmp/.deploy.$base'" < "$src"
  got="$(ssh_box "md5sum /tmp/.deploy.$base 2>/dev/null | cut -d' ' -f1")"
  [ "$got" = "$want" ] || {
    ssh_box "rm -f /tmp/.deploy.$base" || true
    die "md5 mismatch for $dest (local $want, box $got) — NOT installing"
  }
  # Keep the outgoing copy in tmpfs as a same-session rollback. It does NOT survive a reboot, which is
  # deliberate: after a reboot the flash copy IS the new one and a stale .prev would be a trap.
  ssh_box "
    set -e
    [ -f '$dest' ] && cp '$dest' '/tmp/.prev.$base' || true
    rm -f '$dest'
    cp '/tmp/.deploy.$base' '$dest'
    chmod $mode '$dest'
    rm -f '/tmp/.deploy.$base'
    sync
  "
  final="$(ssh_box "md5sum '$dest' | cut -d' ' -f1")"
  [ "$final" = "$want" ] || die "post-install md5 mismatch for $dest — box has $final"
  say "  installed, md5 verified (rollback this session: /tmp/.prev.$base)"
done

ssh_box 'df / | tail -1' | awk '{printf "[deploy] rootfs: %s used, %s free (%s)\n", $3, $4, $5}'

# ---- 4. Return to accessory mode -----------------------------------------------------------------
if [ "$RETURN" -eq 1 ]; then
  say "returning the box to accessory mode (clear NCM boot flags; reboot)"
  # The reboot kills the connection, so a non-zero exit here is expected and not a failure.
  # Clear both maintenance modes. A stale ncm_wifi flag takes precedence over the absence of
  # ncm_only in custom_init.sh and would otherwise leave the gadget as PID 0x1520 after return.
  ssh_box 'rm -f /script/ncm_only /script/ncm_wifi; sync; (sleep 1; /sbin/reboot) >/dev/null 2>&1 &' || true
  say "waiting for the box on USB-OCBM (up to ${OCBM_WAIT}s)"
  sleep 5
  if wait_for usb_ok "$OCBM_WAIT" "USB $VID:$PID"; then
    say "box is back on USB in accessory mode"
  else
    say "WARNING: box did not reappear on USB within ${OCBM_WAIT}s — check it before assuming a bad deploy"
    exit 1
  fi
fi

say "done"
