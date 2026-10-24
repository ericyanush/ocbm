# Experimental native wireless StartSession

This implements the wireless `CarPlayAvailability` (0x4300) → `CarPlayStartSession`
(0x4301) flow, using DiPlay's wireless implementation as a reference. It is off by
default. The adapter continues to own Bluetooth/iAP2 and MFi; CarlinkAndroid owns
the Wi-Fi receiver and media. No USB helper update is required.

## Install and test

From the repository root, build both adapter binaries:

```sh
cargo zigbuild --release --target armv7-unknown-linux-musleabihf -p ocbmd -p btd
```

Deploy with the existing SSH deployment tool (use your working SSH destination):

```sh
./tools/box_deploy.sh --host root@192.168.50.2 --return \
  target/armv7-unknown-linux-musleabihf/release/ocbmd:/usr/sbin/ocbmd \
  target/armv7-unknown-linux-musleabihf/release/btd:/usr/sbin/btd \
  tools/session_supervisor.sh:/script/session_supervisor.sh
```

Build and install a new **main app** using your usual signed Play build, or:

```sh
cd host/CarlinkAndroid
./gradlew :app:assemblePlayDebug
```

Gradle rebuilds the JNI library. Updating just Kotlin while retaining an older JNI
library is insufficient. Use the release signing configuration to update an app
installed through Play; the debug APK has the debug signature.

In app settings, verify the truck hotspot SSID, password, channel, and security
mode. Under **CarPlay StartSession (experimental)**, enable **Use iAP2 StartSession**, then restart the adapter/app
session with Reset Device. Saving the switch alone does not interrupt playback.
The switch is captured at connection startup; a retained active Wi-Fi session
keeps its existing mode until that session ends and a full restart occurs.

Test one phone first, then a key cycle, then a second phone and a temporary switch.
Capture the normal diagnostic bundle. Look for:

1. Adapter `RX 0x4300` with wireless availability true, following identification.
2. `[START_SESSION]` selected phone, slot, request ID and receiver generation.
3. Adapter `TX 0x4301 complete`, with head-unit interface, addresses and port.
4. The native receiver's incoming TCP/pairing/SETUP/media logs for the same phone.

The experiment suppresses **all HTTP connect nudges**, including automatic nudge
recovery. A missing 0x4300, unsupported declaration, write failure, or missing
callback is visible as a failure, rather than silently reverting to legacy startup.
There is a 60-second post-identification availability deadline, a 10-second
adapter-processing deadline, and a 30-second callback deadline after `sent`.
Timeouts require an explicit retry/reset or a new Bluetooth slot.
The main app preserves and logs the specific StartSession progress/failure instead
of letting the adapter's unrelated USB-phone-absent event replace it with a generic
waiting message. The updated supervisor also reconciles a missing Bluetooth
advertiser while the host remains present: it waits five seconds, retries at most
once per 15 seconds, and respects radio inhibit and transport ownership. A running
daemon or launch wrapper is left alone. This repairs a stranded advertiser after
an unsuccessful reset/teardown; it does not establish why a phone ignored 0x4301.

Rollback: disable the setting and perform a full session restart. Updated adapter
binaries support the original startup behavior when the setting is off.

## Disconnect recovery safeguards

The supervisor tracks its pending detached Bluetooth launch by PID, and `btd` holds
`/tmp/ocbm_btd.lock` for its process lifetime before initializing the controller.
Duplicate starts exit without touching Bluetooth or publishing slot state.

## Control contract

`CAP_CARPLAY_START_SESSION` is `0x80`. `CT_CARPLAY_START_SESSION` (`0x20`) carries
version-1 JSON, bounded to 8192 bytes, containing `action` (`start`/`cancel`),
`requestId`, `peerMac`, `slotId`, `receiverEpoch`, and, for start requests, `endpoint`.
The endpoint contains:

- `port`, `deviceId`, `pairingId`, `publicKey`, `sourceVersion`;
- `interface`, `addresses` (IPv4 and link-local IPv6, without scope suffixes);
- `ssid`, `passphrase`, `channel`, `security` (2 WPA2, 3 transition, 4 WPA3).

`CT_CARPLAY_START_SESSION_EVENT` (`0x21`) mirrors version, peer, slot, request and
receiver generation, plus `stage`, `reason` and `available`. `queued` acknowledges
only USB dispatch; `sent` means the complete 0x4301 frame was written on RFCOMM,
not that the iPhone acknowledged it or that CarPlay is streaming. The existing
media callbacks remain authoritative for an active session.

The dispatcher atomically replaces an ephemeral request file under
`/tmp/ocbm_start_session`; only its matching RFCOMM thread writes Bluetooth data.
Request files are mode 0600 because they contain hotspot credentials. Per-phone
event files expose no credentials or private keys. Host loss and fresh HELLO
invalidate pending requests; slot/request/generation checks reject stale work.
An old cancellation cannot replace a newer request.

The app snapshots the bound receiver and current hotspot bridge before SUBSCRIBE,
then refreshes the snapshot immediately before requesting 0x4301. `br0` is preferred;
otherwise exactly one active bridge with usable IPv4 or link-local IPv6 must exist. It sends every eligible
address on that bridge, not an address from a VPN or another interface. While an
attempt is pending, interface/address changes invalidate and replace the request.
A missing/down/addressless bridge is treated as transient: the selected phone waits
for its recovery without marking the iAP2 slot failed. An initial app connection waits
up to 60 seconds per readiness check, then schedules an automatic reconnect. A phone
reconnect on an existing OCBM connection waits until the bridge returns or its owning
phone/receiver generation is cancelled. No request is sent using cached addresses.
The public key is derived from the same persisted Ed25519 seed and pairing identity
that JNI uses to answer pairing requests. `pairingId` is correlation state; 0x4301
has no separate PI parameter.

The selected phone must be identified and report wireless availability. Existing
preferred-phone, fallback-delay, and temporary-switch policies choose the target;
background phones never preempt active media. A direct callback may arrive before
Bonjour resolves the phone, so provisional admission is limited to the selected
request and hotspot interface. Native phase-1 SETUP must identify the selected
Bluetooth phone before media sockets are allocated; phase-2 SETUP is rejected
until that identity is confirmed. This behavior requires verification on hardware,
particularly IPv6 callback routing and each phone's SETUP identity.

Reference files:
`DiPlay/shared/src/main/java/com/shilapi/xcertplay/transport/Iap2WirelessControlClient.kt`
and `DiPlay/shared/src/main/java/com/shilapi/xcertplay/iap2/message/Iap2Messages.kt`.
Unlike DiPlay's legacy-compatible downgrade, this experiment intentionally reports
unsupported 0x4300/0x4301 declarations without retrying legacy identification.
