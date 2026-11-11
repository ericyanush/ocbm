#!/bin/sh
# Run as root on a Raspberry Pi OS image after building the release binary for its CPU.
set -eu

if [ "$(id -u)" -ne 0 ]; then
    echo 'Run with sudo' >&2
    exit 1
fi
repo=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
binary=${1:-$repo/target/release/carlink-pi-proxy}
if [ ! -f "$binary" ]; then
    echo "Missing release binary: $binary" >&2
    exit 1
fi
for tool in nmcli bluetoothctl sdptool avahi-publish-service rfkill; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "Missing $tool. Install NetworkManager, bluez, and avahi-utils first." >&2
        exit 1
    fi
done
if [ -x /usr/libexec/bluetooth/bluetoothd ]; then
    bluetoothd=/usr/libexec/bluetooth/bluetoothd
elif [ -x /usr/lib/bluetooth/bluetoothd ]; then
    bluetoothd=/usr/lib/bluetooth/bluetoothd
else
    echo 'Could not find bluetoothd' >&2
    exit 1
fi
install -m 0755 "$binary" /usr/local/bin/carlink-pi-proxy
install -d -m 0700 /etc/carlink-pi-proxy
rm -f /etc/carlink-pi-proxy/setup-password
install -m 0644 "$repo/tools/carlink-pi-proxy.service" /etc/systemd/system/carlink-pi-proxy.service
install -d -m 0755 /etc/systemd/system/bluetooth.service.d
printf '[Service]\nExecStart=\nExecStart=%s --noplugin=audio,avrcp\n' "$bluetoothd" > /etc/systemd/system/bluetooth.service.d/carlink-no-audio.conf
systemctl daemon-reload
systemctl restart bluetooth.service
systemctl enable carlink-pi-proxy.service
systemctl restart carlink-pi-proxy.service
echo 'Installed. Inspect journalctl -u carlink-pi-proxy -b --no-pager'
echo 'Initial setup AP: Carlink-Pi-Setup (open network)'
