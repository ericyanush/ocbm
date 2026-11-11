# Pi CarPlay proxy tests

The production direction is the Rust service in [../pi/carlink-proxy/README.md](../pi/carlink-proxy/README.md). The Python setup and manually entered IP/token below describe the earlier prototype and remain for historical test comparison.

## Current test: one Pi Wi-Fi radio, shared vehicle hotspot

The Pi and iPhone both join the vehicle hotspot on the same Wi-Fi network.
The Pi uses its built-in `wlan0` as a **station**; the USB Wi-Fi AP is not
needed. The iPhone pairs over Bluetooth with `Carlink-Pi`, so AAOS does not
own the phone's Bluetooth audio connection. AAOS initiates two TCP links to
the Pi: one for media socket forwarding and one for RFCOMM bytes. AAOS still
processes iAP2 and software MFi.

For this test, the old `Hotspot` profile on `wlan1` has autoconnect disabled.
The USB Wi-Fi adapter can stay plugged in, but it is idle. The saved vehicle
profile on `wlan0` retains autoconnect.

The deployed `carlink-pi-proxy` service now listens on the Pi's Ethernet
address while Ethernet is connected, or on `wlan0` otherwise. It starts its
AAOS-facing listener even before `wlan0` joins Wi-Fi. On the home bench,
`10.0.1.94:5101` is listening and the emulator's `nc` probe succeeded.
CarPlay itself will report `phone Wi-Fi network unavailable` until `wlan0`
joins a hotspot. The saved `carlink-vehicle-wifi` profile connects `wlan0`
to the vehicle hotspot automatically when it is in range. The service reads
the hotspot password from an owner-only
`~/.config/carlink-pi-proxy/vehicle-passphrase` file; the password and broker
token are absent from this repository.

In the vehicle, confirm the station connection and broker:

```sh
nmcli -t -f DEVICE,STATE,CONNECTION device status
ip -4 addr show wlan0
ss -ltn | grep ':5101 '
journalctl -u carlink-pi-proxy -n 30 --no-pager
```

Set **Pi network proxy (test)** in the Carlink app. Enter the Pi address that
AAOS can reach (`wlan0`'s current address in the vehicle, or Ethernet on the
bench), port `5101`, and the shared token. Pair the iPhone to `Carlink-Pi`.
Use **Check Pi Bluetooth** to select that phone, then apply **Pi Bluetooth +
software MFi**. The Pi reports the connected hotspot's live SSID, WPA
passphrase, channel, and its `wlan0` IPv4 address for iAP2 StartSession.
The receiver's TCP, UDP, and mDNS sockets are bound on that same Pi address.

The key remaining network check is whether the vehicle hotspot permits two
clients (iPhone and Pi) to communicate. If it isolates clients, the phone
cannot reach the Pi even though both joined the hotspot. Inspect the Pi
journal for broker `TCP accept` and `UDP` events, and export the AAOS app logs.
The old AP experiment is documented below for comparison.

## Home bench test with the AAOS emulator

This is currently prepared on the Pi. Its Ethernet address is `10.0.1.94`,
and its `wlan0` joined the home Wi-Fi with address `10.0.1.116` at setup time.
Read the current addresses after any DHCP change. The emulator uses the Pi
Ethernet address for its outbound broker links; the iPhone reaches the Pi's
`wlan0` address through the same home LAN. The old Pi AP is disabled.

The broker's `bench-network.json` is mode 0600 under
`~/.config/carlink-pi-proxy/`. It contains the saved home Wi-Fi credentials
and the live channel, but only applies while `wlan0` uses the matching home
profile. If `wlan0` switches to the vehicle hotspot, the broker reads that
network's live profile instead. Neither secret is in the repository.

1. Confirm the iPhone is on the same home Wi-Fi profile as Pi `wlan0`. Pair
   its Bluetooth with `Carlink-Pi`, rather than Android in the emulator.
2. On the Pi, check `nmcli -t -f DEVICE,STATE,CONNECTION device status`,
   `ip -4 addr show wlan0`, and `ss -ltn | grep ':5101 '`. The broker should
   listen on its Ethernet address.
3. In the emulator's Carlink app, choose **Pi network proxy (test)**. Enter
   Pi Ethernet IPv4 `10.0.1.94`, port `5101`, and the shared Pi token. Tap
   **Save Pi settings and reconnect**. Import the software MFi identity if
   needed. Tap **Check Pi Bluetooth**, select the paired iPhone, choose **Pi
   Bluetooth + software MFi**, and apply.
4. Watch `journalctl -u carlink-pi-proxy -f` on the Pi. It should show
   `CarPlay broker connected`, `RFCOMM connected`, and then listener/packet
   events. Export the app's normal logs for iAP2 and receiver events.

The Pi's current `wlan0` address is sent to the iPhone in StartSession, not
its Ethernet address. A successful broker handshake on the bench reports
the home Wi-Fi SSID, live channel, and that `wlan0` address without printing
the password. If the phone changes to a different Wi-Fi SSID during handoff,
check its saved home network and the Pi's active `wlan0` profile first.

## Earlier dual-radio AP experiment (historical)

The first stage tested a **network-only** path for the Blazer: the Pi joins the
vehicle hotspot as a client and runs its own AP for a second device. The AAOS
app initiates a persistent TCP connection to the Pi. A browser on the Pi AP
then requests a page whose body is produced by the AAOS app. The Pi does not
need USB or Bluetooth for this first test. The second stage below carries the
native receiver's TCP/UDP sockets and mDNS over that same AAOS-initiated link.
The current stage also moves Bluetooth RFCOMM to the Pi. AAOS still owns iAP2
and software MFi, carried as bytes over a second AAOS-initiated TCP connection.

The original Zero W and Pi 3 Model B have **2.4 GHz** built-in Wi-Fi. A single
radio can run AP + station only when the driver supports it and both links
share a channel. The Pi 3B test uses a second, older USB Wi-Fi adapter as the
AP, leaving built-in `wlan0` for the vehicle hotspot. Both radios are still
2.4 GHz. The vehicle hotspot must offer
2.4 GHz. A 5 GHz-only vehicle hotspot requires another adapter.
Use a current Raspberry Pi OS Lite image with NetworkManager for these commands.

### Configured Pi 3B test board (2026-11-06)

The board `carplay-test` has Ethernet management at `10.0.1.94` on the home
network. Its BCM43438 `wlan0` has a saved, automatically connecting
`CHEVROLET7697` vehicle-hotspot profile. The home Wi-Fi profile has
autoconnect disabled and `wlan0` is currently disconnected; Ethernet remains
available for management. A USB
ZyDAS ZD1211B (`0ace:1215`) provides `wlan1` as `Carlink-Pi-Test` with AP
address `10.42.0.1`. The USB adapter required the distribution's
`firmware-zd1211` package. Its 802.11g performance is suitable for this
connectivity test, not a verdict on sustained CarPlay media performance.

The AP profile is set to autoconnect when the USB dongle is present. The
`carlink-pi-proxy` systemd service starts the server after both Wi-Fi
interfaces have IPv4 addresses. It reads a token from
`/home/eric/.config/carlink-pi-proxy/token`; the token is **not** stored in
this repository. The service resolves both addresses on start and restarts when
the vehicle IPv4 changes. To force an immediate restart, run
`sudo systemctl restart carlink-pi-proxy` and check
`journalctl -u carlink-pi-proxy -n 30 --no-pager`. Joining the Pi AP and
SSHing to `eric@10.42.0.1` provides a management path when home Ethernet is
absent. The service is active and waits for the vehicle hotspot while at home.
After moving the Pi
to the vehicle and powering it on, it should join the saved hotspot without
typing the vehicle password again.

In the vehicle, connect a Mac or phone to the Pi AP and SSH to
`eric@10.42.0.1`. Check that the Pi joined the vehicle's **2.4 GHz** hotspot
on `wlan0` and that the proxy started:

```sh
nmcli -t -f DEVICE,STATE,CONNECTION device status
ip -4 addr show wlan0
journalctl -u carlink-pi-proxy -n 15 --no-pager
```

If `wlan0` has an address but the service is still bound to an earlier
address, run `sudo systemctl restart carlink-pi-proxy`. If the saved hotspot
does not connect, inspect `nmcli device wifi list ifname wlan0` and confirm
that the vehicle is advertising its 2.4 GHz SSID.

Use the current `wlan0` IPv4 in the AAOS diagnostics card. Do not use the
Pi's `10.42.0.1` AP address there: that address is for the phone-facing test
client. The generated AP password and test token are handed to the tester
separately and intentionally absent from version control.

## Pi setup

The following single-radio setup is for a Zero W or a Pi without a second
adapter. **Skip it on the configured Pi 3B above.**

First connect the Pi to the vehicle's **2.4 GHz** hotspot and find the channel
and the Pi's assigned address:

```sh
nmcli device wifi connect 'VEHICLE_SSID' password 'VEHICLE_PASSWORD' ifname wlan0
iw dev wlan0 link
ip -4 addr show wlan0
iw list
```

In `iw list`, inspect `valid interface combinations` for a combination with
both managed (station) and AP interfaces. If it only allows one channel,
use the channel reported by `iw dev wlan0 link` for the AP. Creating the AP
interface or starting the AP may fail on some firmware versions; record that
failure instead of changing the vehicle hotspot as a workaround.

```sh
sudo iw dev wlan0 interface add ap0 type __ap
sudo nmcli dev set ap0 managed yes
sudo nmcli device wifi hotspot ifname ap0 ssid Carlink-Pi-Test band bg channel VEHICLE_CHANNEL password 'TEST_PASSWORD'
ip -4 addr show ap0
```

If `ap0` has no IPv4 address, inspect `nmcli device status` and the hotspot
profile. Record both `wlan0` (vehicle) and `ap0` (phone/test client) addresses.
The vehicle address may change after a key cycle. Choose a fresh ASCII token
without spaces; use the same token in the app. Copy `tools/pi_zero_proxy_poc.py`
to the Pi, then run:

```sh
python3 pi_zero_proxy_poc.py --vehicle-ip PI_VEHICLE_IPV4 --ap-ip PI_AP_IPV4 --token TEST_TOKEN
```

The script binds its AAOS listener to `PI_VEHICLE_IPV4:5101` and its test-client
listener to `PI_AP_IPV4:5102`. It prints each connection and relay duration.
It does not change Wi-Fi settings, expose the AAOS receiver, or log the token.

## AAOS and phone/client test

1. Install the APK containing the **Pi Wi-Fi proxy PoC** card in **Settings →
   Network diagnostics**. Keep that tab open while testing. Enter the Pi's
   **vehicle-hotspot IPv4 address**, port `5101`, and the shared token. Press
   **Start Pi proxy test**. The app should report `connected`; the Pi should
   report `AAOS connected`. The connection is initiated by AAOS.
2. Join a phone or laptop to `Carlink-Pi-Test`. From that client visit
   `http://PI_AP_IPV4:5102/`. The page should say
   `Carlink AAOS Pi proxy PoC reached the app`.
3. Export the regular Carlink logs. `[PI_PROXY_POC]` lines record the AAOS
   connection and the relayed request. The Pi prints the two peer addresses
   and `relay_ms`. Repeat while the vehicle hotspot is active and after a key
   cycle. Press **Stop** when done.

If AAOS can connect but the test client cannot, check that the Pi AP is still
up and that `ap0` has its expected address. If AAOS cannot connect, test from
AAOS's existing **Outbound TCP test** against the same Pi vehicle address and
port `5101`, then inspect the Pi listener and vehicle-client isolation. The
outbound test sends a different marker, so the Pi PoC server will reject its
handshake; the successful TCP connection itself is still useful evidence.

## Result boundary

Passing this test proves the *routing and connection direction*: phone/test
client → Pi AP → Pi → AAOS, with one persistent connection initiated by AAOS.
It does **not** prove that a Zero W can sustain CarPlay media, that Android's
Bluetooth/iAP2 session can hand the phone to the Pi AP, or that a single TCP
backhaul will avoid head-of-line stalls. Those need separate integration and
latency tests after the network path is demonstrated.

### Native CarPlay over the Pi AP (earlier experiment)

The Pi broker is implemented in `pi_carplay_broker.py` and selected by the
`PI_CARPLAY/1` handshake on the existing port 5101. The old `PI_PROXY_POC/1`
HTTP test still works. The app uses Pi Bluetooth and its software MFi
identity for iAP2; the iAP2 Wi-Fi configuration and StartSession advertise the
Pi AP's live SSID, channel, passphrase, IPv4, and the receiver's control port.
The receiver binds only on AAOS loopback. The Pi broker binds TCP, UDP, and mDNS
on its AP and relays their packets over the outbound connection from AAOS.
The broker never logs the AP passphrase or shared token.

On the configured Pi, `/home/eric/pi_zero_proxy_poc.py`,
`/home/eric/pi_carplay_broker.py`, and `/home/eric/pi_bluetooth.py` are deployed.
BlueZ's `audio,avrcp` plugins are disabled by
`/etc/systemd/system/bluetooth.service.d/carlink-no-audio.conf`; the Pi should
advertise no A2DP or HFP UUIDs. The Pi Bluetooth controller must be powered,
pairable, and discoverable. NetworkManager's AP profile
is `Hotspot` (SSID `Carlink-Pi-Test`, channel 6). Since a non-root service
cannot read NetworkManager's saved PSK, the passphrase is copied into an
owner-only file at `/home/eric/.config/carlink-pi-proxy/ap-passphrase`. After
changing the AP password, refresh that file without printing the secret:

```sh
umask 077
sudo nmcli -s -g 802-11-wireless-security.psk connection show Hotspot > /home/eric/.config/carlink-pi-proxy/ap-passphrase
chmod 600 /home/eric/.config/carlink-pi-proxy/ap-passphrase
```

With the Pi in the vehicle, confirm `wlan0` joined the vehicle hotspot and
`carlink-pi-proxy` is running. Note the Pi's `wlan0` IPv4 address. In the
Carlink app's **Connection and receiver** settings:

1. Select **Pi Wi-Fi proxy (test)**. Enter the Pi's vehicle-hotspot IPv4,
   port `5101`, and the same token used by the Pi service. Press **Save Pi
   settings and reconnect**.
2. Pair the iPhone with the Pi Bluetooth device (`Carlink-Pi`). Remove the old
   Android Bluetooth pairing for this test
   so AAOS cannot take the phone's media audio source. The Pi service must be
   running while pairing. Check `bluetoothctl show` and
   `journalctl -u carlink-pi-proxy -n 30 --no-pager` over SSH.
3. Import the software MFi identity if needed. Under **CarPlay session
   transport**, tap **Check Pi Bluetooth**, select the paired iPhone, select
   **Pi Bluetooth + software MFi**, then **Apply transport and reconnect**.
4. The app should log `[PI_TUNNEL] link ready`, `registered TCP tunnel`,
   receiver advertisement readiness, then `[APP_BT] StartSession sent`.
   On the Pi, inspect `journalctl -u carlink-pi-proxy -f` for `[pi-bt] RFCOMM
   connected`, `mDNS joined`, bound ports, accepted sockets, and byte counts.
   Export the usual app diagnostic bundle after the test.

`PI_STATUS/1` lists Pi-paired phones. `PI_RFCOMM/1` carries raw Bluetooth
RFCOMM bytes on a separate AAOS-initiated TCP connection from the
`PI_CARPLAY/1` media broker. The Pi's physical Bluetooth address is used as
the local iAP2 accessory identity. The same shared token protects all three
handshakes and is never logged, nor is the Pi AP password. The Pi pairing
agent and RFCOMM events are in its service journal; AAOS logs iAP2 phases in
the normal app diagnostic bundle. The status and token rejection handshakes
passed on the bench. Live iPhone pairing and CarPlay still require a vehicle
test.

The Pi 3B's current USB AP dongle is 802.11g on 2.4 GHz. This tests whether
the phone accepts the handoff and whether the routing works; it is not a
representative performance baseline for a production 5 GHz CarPlay AP.
Only one phone and one AAOS broker connection are supported at a time.
