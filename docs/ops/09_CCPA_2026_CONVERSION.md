# CPC200-CCPA conversion runbook: firmware 2026.07.31.1733

This runbook records the tested conversion for hardware `YMA0-WN16-0003` running firmware
`2026.07.31.1733`. It is written for an operator starting with the same firmware and hardware.
Commands assume the repository root is the current directory and the adapter is connected to the
Mac by USB.

## Result on the reference unit

- OCBM is installed and is the default boot mode.
- OCBM USB identity is `0x1314:0x2d00`; the default `ocbm-host` handshake and root console work.
- The first-boot rollback timer was confirmed and cleared after the successful finalize.
- `tools/ocbm_install.sh revert` returns the unit to NCM when attached to the Mac.
- A native receiver app session on Android/AAOS has not yet been tested.

NCM is the recovery mode. In NCM mode, the reference unit enumerated as `0x12d1:0x0001` and
provided SSH/telnet at `192.168.50.2`; this is expected and is not the OCBM identity. In OCBM mode,
the receiver-facing identity is `0x1314:0x2d00`.

## Before starting

1. Use the repository revision containing the current `tools/ccpa_stock_usb_bootstrap.sh`,
   `tools/ncm_base_install.sh`, `tools/dropbear_bootstrap.sh`, and `tools/ocbm_install.sh`.
2. Connect the adapter directly to the Mac. Keep it on this host through bootstrap, NCM conversion,
   OCBM placement, reversible trial, and finalize.
3. If the full ARM payload is missing, build it with the repository's target toolchain before
   continuing:

   ```sh
   cargo zigbuild --release --target armv7-unknown-linux-musleabihf
   ```

   `ocbm_install.sh preflight` reports any remaining missing artifacts. Its host client also needs
   libusb development files on the Mac.
4. Do not use a backup or firmware image from a different adapter. The previous unit had different
   NOR partition hashes. No compatible downgrade image was identified for this firmware.
5. Create one run directory for the backup and logs. Keep this shell open so `RUN_DIR` remains set:

   ```sh
   RUN_DIR="scratchpad/YMA0-WN16-0003_2026.07.31.1733"
   mkdir -p "$RUN_DIR"
   ```

   Choose a new directory name if that path already contains a run.

The bootstrap replaces `/etc/profile` and installs an OCBM-owned shell hook. It does not preserve
the original profile. The handoff step below saves the active bootstrap profile and hook, then
restores the canonical profile from this repository. The NOR backup is taken after bootstrap and
therefore includes those bootstrap files; it is not a pristine stock image.

## Step 1 — Bootstrap a root shell and make a backup

Build and scan with the adapter connected:

```sh
tools/ccpa_stock_usb_bootstrap.sh build
tools/ccpa_stock_usb_bootstrap.sh scan
```

Bootstrap the root shell and confirm the exact unit and firmware when prompted:

```sh
tools/ccpa_stock_usb_bootstrap.sh bootstrap
```

Unplug and reconnect the adapter to the Mac. Wait about 30 seconds, then verify the shell:

```sh
tools/ccpa_stock_usb_bootstrap.sh shell
```

The check must show `uid=0(root)` and firmware `2026.07.31.1733`. Type `exit` to close the shell.
Take and verify the full NOR and root filesystem backup:

```sh
tools/ccpa_stock_usb_bootstrap.sh backup --run-dir "$RUN_DIR"
```

Keep `backup_pre/full_nor_16MB.bin`, `rootfs.tar.gz`, and the checksum files. The backup is the
unit-specific recovery image; do not restore another unit's image to this adapter.

## Step 2 — Install and cold-boot the owned NCM base

Run preflight and review the unit details before changing the boot path:

```sh
tools/ncm_base_install.sh --host 192.168.50.2 --via telnet --run-dir "$RUN_DIR" preflight
```

The expected report includes firmware `2026.07.31.1733`, a 16 MB flash layout, and IW416 SDIO ID
`0x9159`. Stop if the hardware or firmware differs from the expected unit.

Install the owned early boot path, select `ncm_only`, complete the bootstrap handoff, and cold-boot
to prove the adapter returns on NCM:

```sh
tools/ncm_base_install.sh --host 192.168.50.2 --via telnet --run-dir "$RUN_DIR" \
  bootpath flag bootstrap_handoff coldtest
```

The `coldtest` must pass with NCM at `192.168.50.2`. Do not continue if it fails. This conversion did
not use a physical UART; the recovery shell was verified over NCM telnet and SSH.

## Step 3 — Install Dropbear and verify SSH recovery

The stock firmware did not include a running Dropbear server. The repository contains the tested
static ARMv7 binary at `ccpa/binaries/dropbear-2026.91-static-musl-armv7`. Install it while telnet
is still available:

```sh
tools/dropbear_bootstrap.sh --host 192.168.50.2 --run-dir "$RUN_DIR/dropbear"
```

The script verifies the binary hash and version, tests key authentication on port 2222, installs
`/usr/sbin/dropbear`, then reboots and confirms SSH returns on port 22. It uses
`~/.ssh/id_ccpa_ncm` and adds its public key to root's `authorized_keys` without replacing other
keys. Keep telnet enabled as a second recovery channel.

## Step 4 — Strip the vendor projection stack and reclaim space

The full OCBM payload needs more space than the unstripped root filesystem provides. First strip
the projection stack using the guarded conversion phase:

```sh
tools/ncm_base_install.sh --host 192.168.50.2 --via ssh --run-dir "$RUN_DIR" strip
```

Review the `strip` report and confirm it completed. Then review the reference-checked library prune
before applying it:

```sh
tools/ncm_base_install.sh --host 192.168.50.2 --via ssh --run-dir "$RUN_DIR" prunelibs --dry-run
tools/ncm_base_install.sh --host 192.168.50.2 --via ssh --run-dir "$RUN_DIR" prunelibs
```

Run the script audit and cold-boot test after pruning:

```sh
tools/ncm_base_install.sh --host 192.168.50.2 --via ssh --run-dir "$RUN_DIR" audit
tools/ncm_base_install.sh --host 192.168.50.2 --via ssh --run-dir "$RUN_DIR" coldtest2
```

Both must pass. Keep Dropbear, telnet, NCM, and radio files. The guarded strip and prune phases
protect radio support and verify script references before deleting files.

## Step 5 — Place and verify the full OCBM payload

Use `--full` to include the projection daemons and receiver support. Check the firmware and free
space, place the files, verify hashes and shell syntax, and reboot once while still in NCM:

```sh
tools/ocbm_install.sh --full --expect-sw 2026.07.31.1733 --run-dir "$RUN_DIR/ocbm" preflight
tools/ocbm_install.sh --full --expect-sw 2026.07.31.1733 --run-dir "$RUN_DIR/ocbm" \
  place verify reboot
```

The preflight must report the expected firmware, zero vendor projection binaries, and enough
space for the payload. The reboot phase must report that the unit is still on NCM and the payload
survived. The adapter has no remote SFTP server or SCP helper; the install scripts transfer files
through SSH `cat` streams and verify hashes.

Confirm the deployed boot script includes the VID setting before finalizing:

```sh
ssh -i ~/.ssh/id_ccpa_ncm root@192.168.50.2 'grep -n idVendor /script/ocbm_boot.sh'
```

The output must include `echo 1314 > "$A/idVendor"`. This check prevents the stale-deployment
failure described below.

If `ccpa/rootfs/script/ocbm_boot.sh` changes after placement, repeat `place verify reboot` before
finalize. A stale on-device boot script caused the first finalize attempt on this unit to boot as
`0x12d1:0x2d00`; the host could not confirm its expected OCBM VID. The watchdog returned it to NCM.
Redeploying and verifying the corrected file fixed the issue.

## Step 6 — Run the reversible OCBM trial

With the Mac still the USB host, run a short trial:

```sh
tools/ocbm_install.sh --full --expect-sw 2026.07.31.1733 --run-dir "$RUN_DIR/ocbm" \
  --ttl 180 trial
```

Expected results:

1. The Mac sees USB VID/PID `0x1314:0x2d00`.
2. The default `ocbm-host` command returns `HELLO_ACK`.
3. The console reports `uid=0(root)`.
4. With no `/tmp/ocbm_keep` marker, the 180-second timer reboots the adapter to NCM.
5. The installer reports NCM SSH is back at `192.168.50.2`.

Do not create `/tmp/ocbm_keep` during a normal trial. That file deliberately stops the timer. If a
trial is held and NCM SSH is reachable, recover with:

```sh
ssh -i ~/.ssh/id_ccpa_ncm root@192.168.50.2 \
  'rm -f /tmp/ocbm_keep; touch /script/ncm_only; rm -f /script/ocbm_trial; sync; reboot'
```

The first trial on this unit left a hold marker. `ocbm-host` could not recover the adapter while it
was showing `0x12d1:0x0001` (`claim IF1: Access denied`); NCM SSH was the working recovery route.
If SSH is not available, let an unheld trial's TTL expire and allow its watchdog to reboot to NCM.

## Step 7 — Make OCBM the boot default

After the reversible trial passes, finalize:

```sh
tools/ocbm_install.sh --full --expect-sw 2026.07.31.1733 --run-dir "$RUN_DIR/ocbm" finalize
```

Confirm the prompt. The script arms a 240-second first-boot rollback, removes the NCM boot flag,
and reboots. It then checks OCBM from the Mac. On success, it confirms the first boot and clears
the rollback timer. Expected output includes `HELLO_ACK` and `OCBM is now the boot default.`

Verify the resulting USB identity in System Information or with `ioreg`: VID `0x1314`, PID
`0x2d00`. The NCM recovery identity `0x12d1:0x0001` should no longer be present while OCBM is
active.

## Step 8 — Test the native receiver app

The OCBM conversion and root console were verified on the Mac. A native receiver app session on
Android/AAOS remains to be verified. Connect the adapter to the Android/AAOS host while it is
booting OCBM and confirm that the app:

1. Detects USB identity `0x1314:0x2d00` and receives USB permission.
2. Opens the OCBM connection.
3. Completes a receiver session.

The `0x12d1:0x0001` identity shown during NCM recovery is expected and will not match the receiver
app. Test the app only while the adapter is in OCBM mode.

If the app test fails, reconnect the adapter to the Mac and run the reversible NCM command while
the OCBM console is reachable:

```sh
tools/ocbm_install.sh revert
```

This sets `/script/ncm_only` and reboots to NCM. SSH and telnet should then be available at
`192.168.50.2`.
