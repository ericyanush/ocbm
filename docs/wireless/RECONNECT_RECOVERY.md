# Native CarPlay short-disconnect recovery

The AAOS app and adapter have independent recovery responsibilities. These changes target
short phone disconnects within a key cycle, including two paired phones.

## Main app

The projection overlay checks the current renderer and native control session. A historical
frame no longer permanently hides Settings and Reset Device. After all native A/V has been
idle for ten seconds, it requests a keyframe. If neither media nor a new decoded frame arrives
within another five seconds, recovery controls appear over the old frame. Returning media
hides them again. This probe does not close TCP, restart the decoder, reset USB, or select a
different phone. Audio activity keeps a static video screen visible; an unanswered keyframe
probe is a recovery hint, not proof of a network disconnection.

Native state reads and keyframe requests run off the UI thread, because they can wait behind
authentication. Exported logs include the control generation and renderer epoch for each probe,
plus overlay visibility transitions.

## Adapter

The Bluetooth reconnect coordinator pages phones serially and gives each established iAP2
socket its own worker. A second phone's slot cannot block reconnecting the first phone.
Admission includes both published slots and workers awaiting publication, rejects duplicate
peers, and observes the eight-slot limit. Anonymous/Android Auto claims remain exclusive.
An inbound connection can win the authoritative peer claim while an outbound connection is
being established; the losing outbound worker drops its socket.

Slot changes wake the coordinator promptly. A per-phone ten-second retry floor prevents fast
handshake failures from causing repeated paging. Ordinary failures retain bounded backoff,
and pairing rejection retains its hold-off. Outbound workers observe wireless-stack shutdown
and are joined before the coordinator exits.

StartSession writes report the requesting peer, slot, request ID, receiver epoch, bytes written,
and socket error/errno when available. A request cancelled before any bytes are sent preserves
its RFCOMM connection and restores the untransmitted iAP2 sequence number. Partial cancellation
or a socket error retires the link: continuing after a truncated frame would corrupt framing.
StartSession writes have a five-second deadline, checked between the socket's timed writes.
No credentials or packet payloads are included in these new diagnostics.

## Deployment and truck test

Update the main AAOS app and adapter `btd`. The USB helper, `ocbmd`, and supervisor do not need
changes for these fixes. From the repository root:

```sh
cargo zigbuild --release --target armv7-unknown-linux-musleabihf -p btd --offline
./tools/box_deploy.sh --host ccpa-ncm --return \
  target/armv7-unknown-linux-musleabihf/release/btd:/usr/sbin/btd
```

Close Mac applications using the adapter before deployment. The deployment script handles
NCM maintenance mode and returns to OCBM. Build and distribute the main app using the usual
Android Studio signing configuration.

1. Connect one phone and stream video/audio. Briefly disable that phone's Wi-Fi or move out of
   range without ending the key cycle. Verify that recovery controls appear without leaving
   the app, then that streaming resumes and hides the overlay after the phone returns.
2. Repeat with the second phone retaining an iAP2 slot. Verify the returning phone gets a fresh
   slot without Reset Device. Switch between phones afterward to check slot isolation.
3. Leave a static CarPlay screen with music playing, then a quiet screen with music paused.
   Confirm the keyframe probe does not repeatedly cover a responsive screen.
4. Export diagnostics after the attempt. Correlate `[CP_RX] A/V idle`, `[UI_STATE]`, per-peer
   reconnect messages, and `StartSession write stopped` if any write fails.

Automated tests cover overlay probe timing and recovery, StartSession cancellation before/after
partial transmission, socket error preservation, duplicate peer admission, capacity, and the
retry floor. Real Bluetooth reconnection still requires the truck/phone test above.
