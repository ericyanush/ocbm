# Dongle Wi-Fi tunnel performance investigation

Scope: phone TCP/UDP → adapter socket broker → OCBM USB → Android socket tunnel → native receiver → decoder/audio. Measure startup separately from sustained playback.

## Findings and priorities

1. **Adapter broker events do not wake the USB poll loop.** `ccpa/ocbmd/src/main.rs` drains up to 64 broker events before rebuilding its poll set. The broker uses an mpsc channel, which is not a pollable descriptor. A newly queued ACCEPT, bind result, TCP data or credit event cannot wake the main loop. Its subscribed timeout is 500 ms. Idle-to-active delivery can therefore wait nearly 500 ms, repeatedly during setup and media credit cycles. Add an eventfd or nonblocking pipe to the poll set; signal event publication, drain the notification and broker events on readiness. Preserve notification ordering so an event cannot be stranded after clearing the wake descriptor.

2. **Adapter worker deliberately sleeps 2 ms per pass.** Each TCP stream gets one read (at most 16 KiB) and one write attempt per pass. The ideal per-stream read ceiling is roughly 8 MiB/s before processing overhead, but small TCP reads reduce it. UDP receive loops and command draining are unbounded within a pass and can delay TCP. Replace fixed sleep with readiness polling and bounded per-stream byte/work budgets after measuring loop time and fairness.

3. **Credit return uses synchronous USB writes.** Android sends a credit message after every forwarded TCP chunk. These writes share `OcbmClient.txLock` with touch, heartbeat, MFi and other streams. Direct USB writes can wait up to 2 seconds per bulk call. Thus the downlink worker can stall returning credit despite having already delivered bytes locally. Measure lock wait and bulk duration; then batch credit updates by bytes with a short maximum delay, including a low-window escape so small control exchanges cannot deadlock.

4. **Queue limits and backpressure need observation.** The former 32-packet per-stream queue has been replaced with a 256 KiB byte bound. The global Android dispatch queue still holds 512 events; its overflow currently refuses a reliable frame. Adapter command/event/output queues also have limits. Measure queue age and occupancy, distinguish TCP from best-effort UDP, and fail reliable streams explicitly if delivery cannot be preserved. Increasing buffers alone can increase visible lag.

5. **All tunnel streams share one adapter USB output queue.** Screen bursts can delay control or audio carried inside the same tunnel channel. Existing OCBM channel priority cannot distinguish these streams. Measure per-stream queue age before introducing fair scheduling; preserve order within each stream and finish partial USB frames before changing streams.

6. **Decoder input, output and seam reads share a thread.** `VideoRenderer` can wait 100 ms for an input buffer; it drains output only while feeding or retrying input. A blocked seam read can leave decoded output undrained until another access unit arrives. This can add latency on sparse/static screens. Measure input dequeue time, output drain cadence and actual frame presentation before separating the decoder output pump. Parameter-set logging also occurs on this thread and should be emitted only on change or rate limited.

7. **Copies and packet sizing are possible CPU costs, not established causes.** USB reassembly, tunnel frame copies, loopback sockets, receiver decryption and seam forwarding copy data repeatedly. Capture CPU and allocation/GC measurements before changing ownership or buffer reuse; asynchronous buffers must never alias the reusable USB read buffer.

## Metrics to add

Use monotonic clocks for elapsed times. Emit aggregate snapshots every five seconds and a final session summary; keep packet/frame logging off the hot path. Include tunnel epoch, stream ID, listener port, control generation and video epoch. Include sample counts, byte counts, max and histogram buckets rather than averages alone.

Adapter broker: socket read/write bytes and calls, read-size histogram, WouldBlock counts, credit-zero duration, pending event bytes/age, event enqueue failures, worker iteration duration and UDP/command budget use.

Adapter USB loop: broker event enqueue-to-main-loop age, poll sleep duration and wake reason, output bytes/queue depth/oldest age, write duration, short writes and EAGAIN. An internal event envelope can carry enqueue timestamps without changing the USB protocol.

Android USB: read sizes and durations, reassembly-to-dispatch duration, transmit lock wait and bulk-write duration by channel, failures and partial transfers. Keep timing out of per-packet file logging.

Android tunnel: extend current receive/forward/peak-queue counters with enqueue-to-dequeue age, local socket write duration, USB credit-send duration, credit-zero duration, global dispatch queue age and drops. Collect periodic metrics independently of the socket writer so a blocked write cannot suppress its own diagnostics.

Native receiver: accepted socket to first byte; each SETUP stage and tunnel registration elapsed time; screen read/decrypt/seam-write durations, access-unit size and arrival intervals; local seam backpressure. Log resolved panel, safe area and negotiated features separately from performance counters.

Renderer: seam read duration, access units received, input dequeue/queue duration, output drain intervals, output frame count and MediaCodec frame-render callbacks where supported. Associate queued timestamps with callbacks to measure decoder-to-surface latency. Log decoded crop and coded dimensions on format changes. Output-buffer release counts alone do not prove display presentation.

Do not subtract timestamps from different devices to infer one-way latency without clock synchronization. Compare per-stage elapsed times and correlate stream/session identifiers instead.

### Adapter resource pressure measurements

`ocbmd` now samples Linux system procfs/sysfs counters once per second on a separate
`ocbm-resources` thread. Process/thread discovery and accounting run once per report
(approximately five seconds), since a full task census is relatively expensive on
this board. Approximately every five seconds it publishes a bounded report through the
existing internal CH_LOG encoder. These entries appear in the AAOS app's normal
adapter diagnostics and exported log bundle; no APK, JNI, helper or Bluetooth daemon
change is required. Rebuild/deploy `ocbmd` only.

* `[resource-perf] implementation=proc-v1`: whole-system CPU percentages (user,
  system, IRQ, softIRQ, idle and I/O wait), peak sampled busy percentage, runnable/
  blocked counts, load average, sampler lateness and collection time. These CPU
  measurements are separate from the broker's **wall-time** active fraction.
* `[memory-perf]`: current and minimum sampled available RAM, free RAM, slab,
  unreclaimable slab, shared/dirty memory, and interval deltas for major faults,
  allocation stalls, reclaim and OOM kills where exposed. Values ending in `_kb`
  follow procfs's KiB units. An idle snapshot does not rule out a transient stall.
* `[process-perf]`: busiest processes plus ocbmd/btd/carplayd when observed; CPU,
  sampled RSS/peak RSS, thread count, state and block-I/O delay ticks. CPU is a
  percentage of **one core**, so a multithreaded process can exceed 100% on SMP.
  Task CPU/RSS peaks are based on the five-second task census, not one-second
  samples; system-wide CPU peaks and available-memory minima retain one-second resolution.
* `[thread-perf]`: busiest threads and threads with the most sampled run-queue wait.
  This includes detailed daemon threads and single-threaded processes/kernel workers.
  Scheduler runtime/wait and slice counts come from `schedstat`; run-queue wait is
  runnable time waiting for CPU, not time blocked on socket input. The maximum is
  the largest **sample interval's accumulated wait**, not an individual wakeup delay.
* `[net-kernel-perf]`: softnet processed/drop/time-squeeze deltas, network softIRQ
  counts, UDP error/buffer-drop deltas, TCP retransmissions and memory-pressure
  counters. They are system-wide; correlate with the socket-specific metrics.
* `[resource-hw]`: CPU0 current/minimum/maximum sampled frequency, configured and
  hardware maximum frequency, governor, and current/peak sampled thermal readings.
  Temperatures are millidegrees Celsius. A low frequency alone does not prove
  thermal throttling; correlate it with utilization, policy limits and temperature.

Every line carries the report's kernel `sample_uptime_ms` and measured interval.
Only the latest unread report is retained, avoiding stale backfill when logging was
disabled. The dispatch loop takes it without waiting for filesystem reads or locks.
Sampling does not spawn external utilities, write persistent files or change
governors/scheduler priorities. Discovery is capped at 256 processes and 128 detailed
daemon threads; truncation flags are explicit. Process/TID start times fence PID
reuse. Counter resets are ignored and reported; softnet's 32-bit per-CPU counters
handle wraparound. Missing metrics print `na`, not zero. All-zero scheduling records
are labeled `zero_or_disabled` and their wait metrics print `na`, since this kernel
may omit/disable scheduler accounting (the bench adapter exposes no `schedstat`).
Short-lived processes and subsecond peaks
can be missed. Check `max_collect_us` and `max_sample_late_ms` for observer overhead
and sampler starvation before attributing a media stall to resource pressure.

## Execution and acceptance

1. Instrument baseline and fix the missing adapter poll wakeup first. Compare startup stage timing and event age; expect broker events to reach the USB loop promptly rather than waiting for unrelated host traffic or the 500 ms timer.
2. Repeat on the same phone/display settings: initial connect, static screen, continuous Maps pan/zoom or Music scrolling, audio plus video, and Siri. Keep each active segment at least 30 seconds and export after the segment. Record which segment was observed as slow.
3. Compare current direct USB with helper USB on hardware where both are available. Compare dongle tunnel with direct vehicle networking on the truck to isolate tunnel overhead.
4. If USB credit writes dominate, introduce bounded credit batching and verify progress for tiny request/response exchanges and window-sized bursts. If local receiver/seam writes dominate, trace receiver and renderer backpressure before modifying USB.
5. If decoder output waits for the next input, decouple output draining with safe single-owner lifecycle coordination. Verify reconnect, background/foreground and phone switching; codec release must not race dequeue calls.
6. Require no reliable-stream drops, no growing queue age, prompt touch feedback and sustained presentation appropriate to the advertised frame rate during animated content. Static content need not produce 60 fps. Report measured startup and per-stage latency distributions before choosing larger windows, packet sizes or lower display resolution.

### Preserve AP leases and expose microphone diagnostics (2026-11-01)

The resource-log sessions showed a returning phone making its first receiver connection using a
cached address before DHCP completed; the AP script unconditionally deleted its lease database on
bring-up. `radio_ap_up.sh` now preserves the opaque lease file when the pool is unchanged, including
when only the serving interface changes. A pool change stops the AP DHCP server before invalidating
its leases so that it cannot write the old database back on exit. The NCM DHCP server is excluded.
This fixes the lease-loss mechanism; another vehicle test must establish whether it eliminates all
first-connection freezes. Test: `sh tools/tests/ap_dhcp_leases_test.sh`.

Microphone capture now logs bounded two-second PCM summaries (peak, RMS, zero percentage), recording
state and Android's client-silenced status. No audio is retained in diagnostics. Native `[uplink]`
lines, previously visible only in logcat, now reach the standard exported app log, including input
format, capture gate, received PCM chunks, encoder errors, and socket/tunnel acceptance failures.
Successful packet acceptance is explicitly labelled as local acceptance, not proof of phone delivery.
These are diagnostic changes, not a microphone routing or codec change.

Deploy `ccpa/rootfs/script/radio_ap_up.sh` to `/script/radio_ap_up.sh` and update the main app with
rebuilt JNI libraries. No new ocbmd, btd or USB-helper binary is required for these two changes.
Android validation: `./gradlew :app:testSideloadDebugUnitTest --tests com.carlink.av.MicPcmLevelsTest
--offline` (also rebuilds both JNI ABIs).
