# MFi 2.0C I2C handling and hardware checks

All adapter chip access now uses `crates/vendor/mfi-i2c-local`: OCBM host requests in `ocbmd`,
Bluetooth iAP2 in `btd`, wired iAP2 in `iap2d`, `carplayd`'s signer and native iAP2 tunnel, and
the optional NCM `mfid` test server. Remote MFI1 signing is preserved for hosts without a local chip.
No AAOS app or USB-helper protocol changes are required.

## Register transactions

The 2.0C specification says to expect busy NACKs instead of clock stretching (pages 31–32 and 38).
Register reads retain the combined I2C write/read transaction: the intervening STOP is optional.
The continuous certificate read is also explicitly permitted. The shared driver leaves bus speed,
kernel timeout configuration, chip power and reset pins unchanged.

The standard signing sequence clears the previous error, writes challenge length/data, initializes
signature capacity at `0x11` to `0x0080`, and then triggers `0x10=1`. This adapter needs an explicit
compatibility profile: DeviceVersion `0x03` together with an unsupported error register (`0xff`)
selects `cpc200-v03`, which preserves the existing sequence without that capacity write. A direct
RSA test on this hardware verified the old sequence and rejected the capacity-initialized sequence
for the same challenge and certificate. Other parts retain the documented capacity initialization.
Diagnostics report the version, selected profile and whether capacity was initialized.
Completion requires status `0x10`,
with a successful signature process result and no error flag. Another process result with bit 4 set
(for example `0x30`) is not accepted. The final signature must be 128 bytes, and the chip error
register is checked after the read. A returned error-register value of `0xff` is recorded but
treated as unsupported, matching the existing adapter probe evidence for this register.
Status `0xff` is not accepted as success, even though bit 4 is set.

Busy/transient register failures use a 5ms retry pause and a 250ms register budget. Signing has a
separate 2500ms completion-poll budget, including the initial 100ms settle. These budgets are
checked between ioctls; a kernel-blocked ioctl still depends on the kernel controller timeout.
Polling does not reopen `/dev/i2c-1`. The shared cross-process lock still covers the whole operation
and has a ten-second acquisition deadline. File descriptors are owned and closed with RAII.

Only a trigger failure reported as an address NACK (`ENXIO`) is retried. A data NACK, short trigger
transfer or other ambiguous trigger failure may mean signing has already started, so the driver
returns a failure rather than replaying that command. Idempotent register accesses can be retried.
Every ioctl must report the exact expected number of messages; a short read is not success.

Certificate reads compare two length samples before allocating (1–1280 bytes), read the body, and
check the length afterward. On this adapter the first post-certificate length read returns
`9c:b1`, even after a 100ms delay; re-selecting `0x30` restores `03:b1` (945). After an anomalous
post-read sample, the driver allows up to three additional selections and requires two consecutive
matches to the pre-read length. Persistently wrong or unstable lengths are still rejected.
Certificate reads also require the outer DER sequence length to match
the actual buffer. Up to three consistency attempts handle observations such as `03:b1` followed
by `9c:b1`. Exhausted transport retries fail the operation rather than accepting a partially read
buffer. Structural framing is not certificate-chain validation: the phone still verifies trust and
the signature. Signatures are always generated for the supplied challenge, never cached.

## Diagnostics

Each operation emits one `[mfi-i2c]` summary with source, PID, result, elapsed time, lock wait,
read/write counts, retries, busy NACK count, other failures, first/last failed transfer, final
status, error-register value, certificate attempt count, post-read length anomaly/re-sample counts
and raw length samples, plus chip version/profile/capacity-initialization details. Failed transfers
identify the operation, register, byte count, ioctl message count and errno. Certificate bytes,
challenge bytes, signatures and private keys are not included in these summaries.

`busy_nacks>0` with `result=ok` can be normal during signing. `other_failures` includes ambiguous
I/O errors and short transfers; inspect the first/last fault rather than assuming a physical
bus defect. Old `[mfi] reopened` lines may remain in backfilled diagnostics from before deployment.
OCBM's surrounding `[mfi] request` lines still provide the host correlation tag and phone context.

## Build and deploy

From the repository root, use the existing ARM FDK-AAC installation:

```sh
FDK_AAC_PREFIX="$PWD/scratchpad/fdk/install" \
  cargo zigbuild --release --target armv7-unknown-linux-musleabihf \
  -p ocbmd -p btd -p iap2d -p carplayd --offline

./tools/box_deploy.sh --host root@192.168.50.2 --return \
  target/armv7-unknown-linux-musleabihf/release/ocbmd:/usr/sbin/ocbmd \
  target/armv7-unknown-linux-musleabihf/release/btd:/usr/sbin/btd \
  target/armv7-unknown-linux-musleabihf/release/iap2d:/usr/sbin/iap2d \
  target/armv7-unknown-linux-musleabihf/release/carplayd:/usr/sbin/carplayd
```

Close Mac applications using the adapter before deployment. `mfid` is a bench-only server;
rebuild it with `-p mfid` if using that separate NCM test path. It is not required for CarPlay.
The deploy script defaults to `root@192.168.50.2` and uses `~/.ssh/id_ccpa_ncm` when present.
Use `--identity /path/to/key` to select a different key. With a selected key it does not fall
back to password prompts; an encrypted key must first be loaded with `ssh-add`.

## Direct Mac test over OCBM

With the updated adapter connected to the Mac in OCBM mode and other clients closed:

```sh
cargo run --release -p ocbm-host --offline -- mfi-check 1314 2d00 20
```

This explicitly runs 20 rounds of certificate/sign/certificate operations. It compares every
certificate byte against the initial read, exercises back-to-back certificate reads between
rounds, varies the challenge and requires distinct 128-byte signatures. OpenSSL must be available
on PATH: the tool extracts the certificate's public key and verifies every RSA signature against
its challenge. It reports per-request timing and correlation tags, stops on the first failure and
returns a nonzero exit status. It does not validate certificate-chain trust, pair phones or
subscribe to projection.

After the bench test, test real CarPlay startup, a warm key cycle, switching between two phones,
and GPS forwarding. Export the AAOS diagnostic bundle to collect the adapter summaries under
real authentication contention. These hardware tests remain necessary even when unit tests pass.

## Adapter read-sequence comparison (2026-10-24)

Temporary diagnostics under the shared chip lock compared combined repeated-START reads with
STOP-separated register selection/read transactions. Both produced `03:b1` before a continuous
945-byte certificate read, `9c:b1` on the first post-read selection, then `03:b1` on subsequent
selections. Waiting 5ms or 100ms before the first post-read did not eliminate it. Both transaction
forms also observed a transient `ff` signing status followed by `10` when signature capacity was
initialized, but those signatures failed RSA verification. Without that capacity write the chip
NACKed while computing, returned `10`, and produced a signature that verified. Adding the optional
STOP therefore did not resolve either adapter response or the incompatible capacity write.

A temporary executable using the corrected shared driver completed five signing rounds on the
adapter: all eleven certificate reads matched, and OpenSSL verified all five signatures against
their respective challenges and the certificate's public key. These tests used RAM diagnostics;
the installed adapter binaries still require deployment of the rebuilt production artifacts.

The successful combined-read sequence is allowed by the specification. Our retry policy is not
a literal implementation of its two separate address-phase loops: it retries the combined
transaction and uses 5ms instead of 500µs. The observed signing NACKs in the split-phase diagnostic
were in register selection (phase 0), for which restarting from selection is appropriate. We do
not infer that all I2C errors are NACKs or that these successful-but-stale responses are clock
stretching faults.

## References

- Local specification: `CPC200-CCPA_resources/hardware/MFI337S3959_iPod_Auth_Coprocessor_2.0C_spec.pdf`
  in the parent workspace (signature capacity p.22; continuous certificate read p.23; I2C p.31–32,38).
- [Apple HomeKit ADK signing sequence](https://github.com/apple/HomeKitADK/blob/master/HAP/HAPMFiHWAuth.c)
- [Apple POSIX I2C implementation](https://github.com/apple/HomeKitADK/blob/master/PAL/POSIX/HAPPlatformMFiHWAuth.c)
- Board evidence: `CPC200-CCPA_resources/documentation/_evidence/MFi_research/docs/register_map.md`
  in the parent workspace, especially the documented busy-NACK and error-register behavior.
