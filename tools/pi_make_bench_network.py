#!/usr/bin/env python3
"""On the Pi, copy the active home Wi-Fi settings into a private broker config.

The password is read from NetworkManager through passwordless sudo and is never
printed or passed on a command line. This file is for a local bench test only.
"""

import json
import os
from pathlib import Path
import subprocess


OUTPUT = Path.home() / ".config/carlink-pi-proxy/bench-network.json"


def run(*args):
    return subprocess.check_output(args, text=True, timeout=5).strip()


def main():
    profile = run("nmcli", "-g", "GENERAL.CONNECTION", "device", "show", "wlan0")
    if not profile or profile == "--":
        raise SystemExit("Connect wlan0 to the home Wi-Fi network first")
    ssid = run("nmcli", "-g", "802-11-wireless.ssid", "connection", "show", profile)
    password = run("sudo", "-n", "nmcli", "-s", "-g", "802-11-wireless-security.psk",
                   "connection", "show", profile)
    scan = run("nmcli", "-t", "-f", "IN-USE,CHAN", "device", "wifi", "list", "ifname", "wlan0",
               "--rescan", "no")
    active_line = next((line for line in scan.splitlines()
                        if line.split(":", 1)[0].strip() in ("*", "yes")), "")
    if not active_line:
        raise SystemExit("Could not read wlan0's current Wi-Fi channel")
    channel = int(active_line.rsplit(":", 1)[1])
    if not ssid or not 8 <= len(password) <= 63 or channel not in range(1, 197):
        raise SystemExit("Saved home Wi-Fi settings are incomplete")
    OUTPUT.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    temporary = OUTPUT.with_suffix(".tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w") as stream:
        json.dump({"profile": profile, "ssid": ssid, "passphrase": password, "channel": channel}, stream)
    os.chmod(temporary, 0o600)
    os.replace(temporary, OUTPUT)
    print(f"Bench Wi-Fi config ready, channel={channel}; secret omitted")


if __name__ == "__main__":
    main()
