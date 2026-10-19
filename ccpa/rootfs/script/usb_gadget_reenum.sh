#!/bin/sh
# Re-enumerate the OCBM USB accessory only when the host has not completed an OCBM HELLO.
#
# GM AAOS can enumerate the adapter without granting/opening it for the USB helper. A fresh USB
# attach notification may make AAOS reconsider the default USB handler and grant. The timer starts
# only once the gadget reports CONFIGURED (not at adapter boot), so long/variable AAOS boot times
# do not consume the retry window. A valid OCBM CT_HELLO permanently stops retries for this USB
# attachment; detach starts a fresh window on the next CONFIGURED transition.

A=/sys/class/android_usb_accessory/android0
HELLO=/tmp/ocbm_usb_hello_seen
LOG=/tmp/box.log
INTERVAL=15
configured_since=0
attempt=0
hello_logged=0

log() {
  echo "$(cut -d. -f1 /proc/uptime) [usb-reenum] $*" >> "$LOG"
}

while :; do
  state=$(cat "$A/state" 2>/dev/null)

  # SUSPENDED retains the configured USB relationship; do not reset across AAOS sleep.
  case "$state" in
    CONFIGURED|SUSPENDED)
      if [ -e "$HELLO" ]; then
        if [ "$hello_logged" -eq 0 ]; then
          log "valid OCBM HELLO observed; retries disabled for this USB attachment"
          hello_logged=1
        fi
        configured_since=0
        sleep 1
        continue
      fi

      if [ "$configured_since" -eq 0 ]; then
        configured_since=$(cut -d. -f1 /proc/uptime)
        log "gadget $state without OCBM HELLO; starting ${INTERVAL}s window"
      fi

      now=$(cut -d. -f1 /proc/uptime)
      elapsed=$((now - configured_since))
      if [ "$elapsed" -ge "$INTERVAL" ]; then
        # Recheck immediately before toggling: HELLO may have arrived while this loop was asleep.
        state=$(cat "$A/state" 2>/dev/null)
        if [ ! -e "$HELLO" ] && { [ "$state" = CONFIGURED ] || [ "$state" = SUSPENDED ]; }; then
          attempt=$((attempt + 1))
          log "no OCBM HELLO after ${INTERVAL}s (attempt $attempt); toggling accessory gadget"
          # The HUP/EOF caused by disabling the gadget makes ocbmd exit; inittab's run_ocbmd.sh
          # wrapper then reopens /dev/usb_accessory after the gadget has returned.
          if echo 0 > "$A/enable"; then
            sleep 1
            if echo 1 > "$A/enable"; then
              log "accessory gadget re-enabled; waiting for CONFIGURED"
            else
              log "ERROR: failed to re-enable accessory gadget"
            fi
          else
            log "ERROR: failed to disable accessory gadget"
          fi
        fi
        configured_since=0
      fi
      ;;
    *)
      # A real detach (unlike suspend) ends the prior USB attachment's HELLO state.
      if [ -e "$HELLO" ]; then
        rm -f "$HELLO"
        log "gadget detached; cleared prior HELLO state"
      fi
      configured_since=0
      hello_logged=0
      ;;
  esac
  sleep 1
done
