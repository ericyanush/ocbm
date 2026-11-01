# Blazer EV bring-up: experiments and findings

This records testing on the GM Blazer EV running Android 14 through 2026-10-31. The Silverado's split USB helper and direct vehicle-hotspot connection remain useful comparison paths. Results below describe the tested vehicle, not all Android 14 or AAOS implementations.

## Installing and reaching the adapter

- Play distribution installed the main app. Attempts to use the Files UI to install the bundled helper did not produce a working helper installation. A third-party file explorer also hid APK files. DocumentsUI file picking, file browsing, and launching the package installer are separate operations; opening a picker did not establish that installation was available.
- Added optional **Main app direct USB**, retaining **Split USB helper** as the default. Direct mode requests USB permission and opens the bulk interface in the main app UID. This allows testing without the helper. The split helper remains the established truck path.
- Both standard OCBM and NCM identities produced a vehicle “USB device not supported” popup. The popup alone does not prove the interface is inaccessible: another device reportedly shows it too. Use the USB diagnostics device list, grant and endpoint information to distinguish discovery from permission/open failures.
- Testing `1314:1520` did not resolve discovery. An additional VID change also failed. Testing `0424:4917` did expose the adapter in Android's device list with bulk endpoints `0x01` and `0x81`, and selecting that identity in the main app enabled connection.
- `0424:4917` is an experimental compatibility identity, not an assigned project identity or proof of why GM accepted it. Select it explicitly in direct USB settings. The app selection does not rewrite gadget descriptors: configure the adapter separately, including any boot/re-enumeration scripts that restore IDs. Moving the adapter between Mac and vehicle power cycles it, so temporary sysfs changes alone are insufficient.
- On the Mac, confirm descriptors and use `ocbm-host hello VID PID` before moving the adapter. `box_deploy.sh --vid HEX --pid HEX` selects the current accessory identity for deployment/recovery; SSH uses `root@192.168.50.2` by default. Public-key authentication required fixing ownership of root's home directory. The adapter has no remote scp executable, so deployment uses the script's supported transfer path.

## Vehicle network experiments

- Added interface and USB diagnostics, receiver self-tests, an outbound TCP probe, a configurable inbound HTTP listener and an optional local-only hotspot test.
- The vehicle hotspot was broadcasting even when automatic hotspot-bridge selection failed. Added receiver control-port configuration and an explicit bridge-only binding option. A reboot can change the bridge IP; resolve it afresh and use the IP from the same attempt.
- Outbound TCP worked both on the app-created hotspot and on the standard vehicle hotspot.
- Inbound tests to arbitrary ports timed out, including tests against a separate file-server app. The app-created hotspot did not remove this behavior. An advertised GM secure ADB service on port 5555 was reachable, but refused ordinary ADB use.
- Ports 5000 and 5001 were reachable in listener tests. A scan of 4800–5200 found responses only on those two ports. Unbound responsive ports failed quickly; other tested ports timed out. This is consistent with inbound filtering or policy, but no privileged firewall inspection established the precise cause. Do not generalize that all other ports or UDP are blocked.
- Explicit bridge binding and a configurable control port allowed progress to “phone connected / starting CarPlay.” They did not establish a working direct session: CarPlay allocates additional TCP/UDP session sockets dynamically. The native receiver now inherits an explicit binding for these sockets as well.
- Requesting `NEARBY_WIFI_DEVICES` and successfully creating a local hotspot did not solve inbound reachability. There is no evidence that another ordinary app permission unlocks the filtered ports. IPv6 `%en0` is the Mac's local scope identifier for a link-local address, not a hostname suffix to send to another host.

## Dongle Wi-Fi through USB

The alternative network mode keeps the native receiver in AAOS. The adapter owns phone-facing TCP/UDP sockets on its AP and transports their bytes/datagrams over OCBM `CH_TUNNEL`; Android maps them to receiver sockets on loopback. The adapter does not decrypt or render CarPlay media. iAP2 StartSession provides the receiver endpoint; mDNS is optional compatibility support rather than the prerequisite for discovering this endpoint.

The path includes capability negotiation, per-stream TCP byte credits, bounded queues, UDP peer addressing and socket registration from the native receiver through JNI. Control-peer mapping preserves the actual phone identity behind the loopback proxy. Microphone UDP is routed through the same broker.

Early attempts still handed the phone the vehicle hotspot. The supervisor now distinguishes bridge mode from dongle-AP mode when applying credentials, and StartSession can wait for a live adapter AP endpoint instead of requiring a vehicle endpoint at SUBSCRIBE. In tunnel mode, SSID/address/port must consistently describe the adapter AP. Updating only Android cannot correct an old supervisor that rewrites the AP configuration.

Several attempts progressed through Wi-Fi association but stalled before streaming. Native socket registration, explicit local binding and receiver diagnostics were added. Native-crash investigation also led to bounded next-launch collection of Android process-exit records and binary tombstone/ANR artifacts in exported diagnostics, along with native/session and decoder lifecycle diagnostics.

Streaming has now been observed through the dongle AP and USB tunnel. Startup remained slow, and video initially rendered around 9–11 fps. The first-session log showed the screen TCP stream closing after the Android per-stream 32-packet queue filled. A packet-count limit did not match the byte-credit protocol; this is now a 256 KiB byte-bounded queue, with stream throughput and queue high-water logging. The user reported improved but still slow interactive video after that update.

The next code review found that the adapter broker's mpsc events did not wake `ocbmd`'s poll loop, allowing up to 500 ms of idle scheduling delay repeatedly during setup/media. A nonblocking socketpair now wakes poll after event publication. Batches over 64 events force another immediate pass so clearing notifications cannot strand work. A host regression test covers notification readiness, draining and subsequent publications. Vehicle performance after this wakeup fix is not yet validated.

## Display and remaining work

The tested display reports a 2914×1134 panel with top/right cutouts of 167/285 px. The app advertises the even-aligned safe rectangle `(0,168,2628,966)` and allows background drawing outside it. The user observed clipping near the upper-right physical cutout. An earlier decoder format also reported a 2400×960 visible crop while the configured panel was 2914×1134. These are leads, not a demonstrated cropping root cause. Verify the actual negotiated view-area features and decoder crop from the same active session before changing surface scaling or touch coordinates.

The exported `connected` bundle ended around screen-stream establishment and did not contain a sustained playback interval with the new tunnel metrics. The user did observe and interact with rendered video in the foreground; the captured lifecycle transitions do not disprove that observation. Export timing/coverage must be considered when drawing conclusions from missing frame metrics.

For the next comparison, update both `ocbmd` and the main APK, then collect at least 30 seconds of animated content. Inspect `[tunnel-perf]` event age on the adapter, `[CP_TUNNEL]` throughput/queue/socket-write/credit-send timings in Android, and `[video] perf` decoder waits and output counts. Decoder output-release counts do not measure actual panel presentation. See [the performance investigation plan](../../docs/carplay/TUNNEL_PERFORMANCE_INVESTIGATION.md) for the remaining instrumentation and test sequence.

## Components to update

| Change | Deployment |
| --- | --- |
| Direct USB, diagnostic tools, network mode, receiver settings, queue and decoder timing | Main AAOS APK, including rebuilt JNI libraries when native receiver code changes |
| Socket tunnel protocol and poll wakeup | Rebuild and deploy adapter `ocbmd` |
| Deferred AP endpoint / iAP2 handoff | Rebuild and deploy adapter `btd` |
| Correct dongle AP credentials | Deploy `tools/session_supervisor.sh` to the adapter's matching supervisor path |
| USB identity test | Apply persistent gadget configuration separately; select the matching app identity and deployment VID/PID |

The USB helper has no source changes in this bring-up batch. Keep each vehicle's USB ownership and phone-network mode explicit rather than assuming the Blazer's choices apply to the truck.

## Local validation of this batch

The adapter wakeup regression test passed, as did the Android sideload-debug Kotlin compile and focused unit tests for the diagnostic HTTP server, outbound probe, receiver control-port storage, receiver TCP diagnostics and hotspot endpoint selection. Shell syntax checks passed for the deployment and supervisor scripts. Android checks excluded `buildCarPlayJni`; they do not substitute for rebuilding both Android native-library ABIs and the ARM adapter binaries, or for an on-vehicle test of the latest wakeup change.

## Host-owned receiver service audit

When SUBSCRIBE explicitly enables `wirelessStartSession: true`, `ocbmd` now closes legacy on-box HID/microphone/RTSP connections, suppresses their forwarding channels and skips eager microphone/RTSP connection attempts. Those attempts each allowed a 500 ms connect timeout on the USB dispatch thread. Measured event age approached one second despite wake notifications; the attempted connections are a plausible contributor, but the capture did not time each connection individually.

The supervisor uses the same setting to disable wired receiver arming/escalation, wired hot-handover/radio-yield logic and wireless Android Auto startup. The Bluetooth driver's existing native-StartSession gate already suppresses `carplayd` startup. Bluetooth attachment, pairing/iAP2 slots, MFi access, the requested SoftAP/DHCP, OCBM management/diagnostics and socket brokerage remain required. Legacy modes without this explicit setting retain their existing behavior. Deploy both `ocbmd` and the supervisor script for these gates; no main-app update or btd rebuild is required for this service audit.

## Touch coordinate correction

The user confirmed that touching above a visual target activated that target. Inspection found the native receiver still served the bundled 2400×960 display and HID descriptor, while Android generated touch reports using the detected 2914×1134 panel. The safe rectangle in the adapter SUBSCRIBE configuration did not update that independent native `/info` asset. The main app now transforms the native display, safe area, frame rate and both HID contact maxima together using the detected geometry, preserving the compact view area and return tile. A test exercises the shipped binary plist and verifies both contact axes and the safe rectangle after serialization. Update the main APK for this correction; no adapter change is needed. On-vehicle touch alignment and cutout layout still require confirmation.

## System bars and remaining loop stalls

The `metrics_touch_fix` capture confirmed the native geometry transform and service gates were active, but adapter event age still approached one second. Added `[loop-perf]` aggregates (count, total microseconds and maximum microseconds) through CH_LOG for poll waiting, event brokerage, USB/socket dispatch, maintenance subtasks, legacy-service checks and USB output draining. Categories overlap where a parent stage includes subtasks; do not sum parent and child totals. These measure elapsed wall time, including scheduling delays, rather than CPU time.

Showing system bars reduced the declared panel height to 968 px with a 166 px top inset, but the projection surface stayed at the full window origin. The main screen now applies the profile's surface insets to the projection container, keeping the surface and its local touch coordinates inside the advertised content region. Safe-area padding remains separate from the surface's system-bar inset. Update the main APK for placement and `ocbmd` for loop metrics; neither change needs a new btd or supervisor script.

## Resume placement and USB dispatch isolation

The `screen_shift_performance` capture reported good initial touch alignment, followed by video moving upward after background/resume. New full-view SurfaceViews now retain Android's own layer placement instead of receiving an explicit full-panel `setGeometry` transaction merely because panel size differs from the old 2400×960 default. Compact cropping and restoration from compact on an existing layer remain explicit. Surface lifecycle diagnostics include screen/window location, layout, translation and buffer bounds.

Loop metrics identified USB input dispatch as the dominant stage (roughly 5.9 seconds in a six-second interval), rather than maintenance or poll waiting. Instrumentation now separates the actual accessory read from CTRL, tunnel, MFi, input, microphone, RTSP, file and IP handlers. Summaries are split into bounded lines to avoid the truncation observed in the first loop profiles. Update the main app for placement and `ocbmd` for these measurements. A slow accessory read versus a slow handler is still an open question until the next vehicle capture.

## Isolating blocking gadget reads

The `shift_fixed_performance` capture isolated 160 seconds of elapsed time in accessory reads, including a 12.37-second maximum read and repeated roughly one-second reads, while tunnel handlers totaled about 103 ms. The adapter now uses a dedicated accessory reader with a cloned descriptor, a bounded 32-chunk queue and a socketpair wakeup. The main loop polls accessory writability separately and never invokes the driver read. Queued chunks retain byte order and go through the existing OCBM reassembler; backpressure blocks only the reader worker. EOF, worker failure and transport errors use the daemon's existing exit/respawn path. The process owns the reader for its lifetime; daemon exit terminates any driver-blocked worker.

New captures identify `tunnel-wake-v3` and `accessory_reader=threaded`. `[loop-perf]` distinguishes `accessory_driver_read` (worker elapsed time) from `accessory_queue_age` (completion-to-dispatch delay). Worker duration overlaps main-loop time and must not be summed with it. Main-loop event service should stay responsive while a driver read waits. Update only `ocbmd` for this change. On-device testing must still confirm that the kernel permits writes to progress concurrently with a read on the shared gadget device.

## Serialized MFi worker

The `reader_split_perf` capture reduced control-connect-to-first-frame time to about 3.47 seconds and phase-one socket setup to 22 ms. Steady tunnel event delays mostly fell to milliseconds. A remaining approximately 1.68-second event spike matched synchronous MFi signing on the main dispatch thread.

`ocbmd` now serializes host MFi requests on a dedicated worker with bounded request/reply queues and a pollable completion notification. Protocol status and optional correlation tags are preserved. HELLO and session teardown invalidate the worker generation: queued stale work is skipped and completed old-session signatures are discarded. Queue saturation returns a tagged failure instead of blocking USB. The shared chip driver's interprocess locking remains responsible for coordination with btd. `[mfi-perf]` records operation/tag, queue wait, chip elapsed time and response status through CH_LOG. Chip elapsed time may remain around 1.7 seconds; it should no longer produce the same tunnel event stall. Update only `ocbmd`, and verify the exported marker includes `mfi_worker=serialized`.

## UDP/audio and radio instrumentation

The longer `update_mfi_worker` capture contained foreground audio gaps of 1562 ms and 1138 ms at approximately 00:42:04 and 00:42:14, while broker event delays remained below 39 ms. TCP timing does not measure the UDP media stream, so additional diagnostics now trace the audio path:

- Adapter `[udp-perf]`: per-registration port/ID, interval packet and byte counts, maximum receive gap, time since last receipt, rejected peers, event-queue drops and socket errors. These use the existing CH_LOG diagnostic path.
- Adapter `[wifi-perf]`: cumulative interface packet/error/drop counters and available `iw station dump` signal, retry, failure and bitrate fields. A dedicated worker samples every ten seconds with a two-second query deadline; absent/unsupported iw statistics are explicitly unavailable. These do not prove radio loss by themselves.
- Android `UDP tunnel stats`: per-port incoming counts/gaps, maximum local forwarding duration, send errors, global dispatch queue depth and cumulative refused-frame count. Five-second reports continue when a registered socket is idle.
- Native `[audio-perf]`: per-stream receive counts/gaps, idle time, maximum processing duration and cumulative drops. Processing includes decryption and seam forwarding; a slow value needs further separation before attributing it to one operation.
- Player `audio-perf`: tagged-seam arrival gaps, read wait and decode/AudioTrack-write duration. Player reports are emitted when frames arrive, so a long stalled read reports its duration on resumption.

Compare local elapsed times and matching ports/IDs; adapter and Android wall clocks are not synchronized enough for cross-device one-way timing. Idle/static video alone is not proof of a stall. Update `ocbmd` plus the main APK with rebuilt JNI libraries for this instrumentation; no supervisor or btd update is needed.
