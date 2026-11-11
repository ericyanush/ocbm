# Rust Pi CarPlay proxy

The Pi joins the vehicle hotspot. The phone joins that same hotspot after iAP2 StartSession. Android initiates the broker and Bluetooth control connections to the Pi. The Pi forwards CarPlay TCP/UDP/mDNS and RFCOMM bytes; the Android app owns iAP2, MFi, the receiver, video, and audio.

## Build and install on a 64-bit Pi 3B

Install Raspberry Pi OS Lite 64-bit with NetworkManager, BlueZ, and Avahi. SSH is needed for the initial binary installation; Wi-Fi credentials can subsequently be changed over BLE.

For a Mac build, install `cross-rs` and run Docker Desktop. On an Apple Silicon Mac, build from the `ocbm` root with:

```sh
DOCKER_DEFAULT_PLATFORM=linux/amd64 cross build --release -p carlink-pi-proxy --target aarch64-unknown-linux-gnu
```

If Docker's CLI is not in `PATH`, set `CROSS_CONTAINER_ENGINE=/Applications/Docker.app/Contents/Resources/bin/docker`. [Cross.toml](../../Cross.toml) adds ARM64 `libdbus` headers to the container. The resulting binary is `target/aarch64-unknown-linux-gnu/release/carlink-pi-proxy`. Native Pi builds remain a useful hardware validation step.

For a Pi Zero W running 32-bit Raspberry Pi OS, use the ARMv6 hard-float target instead:

```sh
DOCKER_DEFAULT_PLATFORM=linux/amd64 cross build --release -p carlink-pi-proxy --target arm-unknown-linux-gnueabihf
```

The proxy enables vendored D-Bus for this build so the cross image does not need ARM system libraries. Install `target/arm-unknown-linux-gnueabihf/release/carlink-pi-proxy` on the Pi. A Zero W has only 2.4 GHz Wi-Fi and no wired network; keep a tested persistent NetworkManager Wi-Fi profile before removing cloud-init or rebooting remotely.

```sh
sudo apt update
sudo apt install -y cargo rustc build-essential pkg-config libdbus-1-dev network-manager bluez avahi-daemon avahi-utils rfkill
cd ocbm
cargo build --release -p carlink-pi-proxy
sudo pi/carlink-proxy/install.sh
```

The installer enables the service and starts it immediately. It listens on TCP 5101 before Wi-Fi is ready. It does not wait for `network-online.target`. Once `wlan0` joins a normal Wi-Fi profile and has IPv4, it publishes `_carlink-pi._tcp` using Avahi. The temporary setup AP is excluded from CarPlay discovery. The Android app discovers the service on its active network and does not retain the Pi's DHCP address.

If the active NetworkManager profile hides its WPA key from `nmcli`, put the matching passphrase in `/etc/carlink-pi-proxy/vehicle-passphrase` with mode 0600. Do not add credentials to this repository.

## BLE Wi-Fi setup

The service advertises `Carlink-Pi-Setup` as a BLE GATT peripheral. From a BLE GATT client on a phone, pair with the Pi and write one UTF-8 JSON value to characteristic `9f2af86a-9c35-4428-995e-3df4dcad1151` under service `9f2af86a-9c35-4428-995e-3df4dcad1150`:

```json
{"ssid":"VEHICLE_SSID","passphrase":"VEHICLE_PASSWORD"}
```

The write requires an encrypted BLE link. The Pi stores the credentials in a NetworkManager profile named `carlink-vehicle-wifi` and tries to join it. Provisioning can happen while the vehicle is out of range; the saved profile autoconnects when the hotspot appears. The service logs only success or failure, never the password. The payload must fit in one GATT write, so request a larger MTU in the BLE client if necessary.

If BLE provisioning is unavailable and no `carlink-vehicle-wifi` profile exists, the Pi starts an open 2.4 GHz network named `Carlink-Pi-Setup` on channel 6. Join it from a phone and open `http://10.42.0.1:8080/` (or use the address shown by `journalctl -u carlink-pi-proxy`). Enter the vehicle hotspot name and password. Once saved, the Pi will leave setup mode and join the vehicle hotspot when in range. The setup page only listens on the temporary AP address.

The classic Bluetooth radio remains pairable and discoverable for the iPhone. Pair the iPhone with the Pi's Bluetooth name. In Pi proxy mode, Carlink's **Devices** tab refreshes the Pi's BlueZ paired and connected phone list, allows a preferred phone, and offers a temporary switch or removal. The **Pi Bluetooth** control card can also rename the Pi and inspect its pairings. Adapter mode keeps its own device list. The app no longer offers direct Android Bluetooth as a session transport.

When switching phones, the app closes its current iAP2 session, asks the Pi to disconnect the previous Bluetooth phone, and dials the selected phone. A Pi RFCOMM dial failure now includes the underlying error in the app log; a socket closure caused by an intentional switch is logged as teardown.

## Boot and performance measurements

After a cold power cycle, collect:

```sh
systemd-analyze time
systemd-analyze critical-chain carlink-pi-proxy.service
journalctl -b -u carlink-pi-proxy --no-pager
```

The journal records uptime when the broker listener, Bluetooth agent, and mDNS advertisement become ready. The broker logs TCP accepts, bind failures, and every 500 UDP packets' largest arrival gap. Compare those with the Android app's `PI_TUNNEL` and receiver timing logs. A sub-five-second boot is a target to measure on the fresh image, not a service guarantee.

On a Pi Zero W with Raspberry Pi OS Lite 32-bit, a bench setup improved from a first-boot 7m45s to 1m18s by removing cloud-init, disabling the initramfs, mounting `/boot/firmware` on demand, loading `brcmfmac` early, disabling Wi-Fi power saving, and replacing netplan-generated Wi-Fi profiles with persistent NetworkManager keyfiles. The proxy broker was ready at 47s, Bluetooth at 55s, and Wi-Fi/mDNS at 80s. Back up and test a persistent Wi-Fi profile before removing netplan YAML on a Wi-Fi-only board. These are measured results for that image, not guaranteed startup times.

For a bench test, put the Pi, phone, and AAOS emulator on one LAN. Build the Android app with Pi proxy mode selected; the Pi's service announcement replaces the old manually entered address and token. The default Pi 3B radio is 2.4 GHz. A later 5 GHz board can run the same service with `--phone-interface` set to its Wi-Fi interface.
