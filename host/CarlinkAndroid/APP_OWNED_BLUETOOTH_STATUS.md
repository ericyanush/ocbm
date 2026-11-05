# App-owned Bluetooth and software MFi

This branch has a **manual authentication probe**, not an adapter-free CarPlay session yet. The
existing adapter path remains the default.

## Implemented

- The app can import `identity.pk8` and `certificate.p7b` together through Android's document
  picker. It validates the P-256 key/certificate pair, stores them under `noBackupFilesDir`, and
  does not include them in source.
- An explicit `-PbundleOfflineMfi=true` build option stages exactly those two files from
  `../carplay-certs/offline-mfi` (relative to the OCBM repo), or `CARLINK_MFI_DIR` if set. The app
  validates and copies the bundled identity to private storage on first use. A build without the
  option removes staged assets, including after a previous bundled build.
- The receiver's MFi relay accepts protocol 2 (adapter SHA-1/RSA) and protocol 3 (local
  SHA-256/P-256) digest/signature sizes. MFi-SAP has a protocol-3 test vector.
- Android Bluetooth Classic access is permission-gated. Settings → Control → Android Bluetooth
  reports whether the Android Bluetooth service and paired devices are available.
- The **Test iAP2 auth** button on a paired device opens an app-owned RFCOMM socket, sends the iAP2
  detect/SYN, and handles 0xAA00/0xAA02/0xAA05 using the imported software signer. It stops after
  authentication. No private key material or challenge bytes are logged.

## On-device test

1. Build/install the sideload or Play app on the target AAOS head unit.
2. Import the two identity files in Settings → Control (select both in one action), or build the
   APK explicitly with `./gradlew :app:assembleSideloadDebug -PbundleOfflineMfi=true`.
3. Stop any active CarPlay session and pair an iPhone to **Android's** Bluetooth radio. The phone
   being paired to the adapter's separate Bluetooth radio does not populate Android's bonded list.
4. Tap **Check Android Bluetooth**, grant the permission, then tap **Test iAP2 auth** for that phone.
5. Export the regular app diagnostics. Look for `[APP_BT] iAP2 auth phase=`. `AUTHENTICATED` proves
   app-owned Bluetooth and software iAP2 authentication on that head unit. It does not yet prove
   identify, Wi-Fi handoff, StartSession, or CarPlay streaming.

If the Android Bluetooth service is missing, or no phone can be bonded to it, the fully app-owned
path cannot work with public Android Bluetooth APIs on that head unit. The adapter path is unaffected.

## Remaining integration

1. Add IdentificationInformation, Wi-Fi configuration reply (0x5702 → 0x5703), and CarPlay
   availability/StartSession (0x4300 → 0x4301) to the app-owned RFCOMM session. Bind those
   messages to the existing live hotspot and receiver endpoint, rather than static settings.
2. Keep the app-owned RFCOMM link alive across Wi-Fi handoff and expose its phone identity/phase
   to the current native receiver and multi-phone selector.
3. Add an explicit transport choice to the app; enable app-owned Bluetooth only when selected and
   the on-device test succeeds. Define a separate teardown path; do not couple it to OCBM USB
   cleanup.
4. Verify a full first pair, warm reconnect, and phone switching on physical AAOS hardware.

The protocol implementation is original work informed by the project's existing iAP2 Rust code and
behavioral references. Reference projects with GPL/AGPL licenses are not copied into this tree.

**Distribution:** `identity.pk8` is extractable from every APK built with the bundling flag. Keep
that artifact restricted to recipients authorized to use the identity. The build flag is off by
default; normal Play and sideload builds do not contain it.
