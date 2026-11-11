#!/bin/sh
# Run on the Pi. The AAOS-facing address is required at service start; the
# phone-facing Wi-Fi may come up later, before CarPlay is requested.
set -eu

VEHICLE_IFACE=${PI_PROXY_VEHICLE_IFACE:-auto}
PHONE_IFACE=${PI_PROXY_PHONE_IFACE:-wlan0}
TOKEN_FILE=${PI_PROXY_TOKEN_FILE:-/home/eric/.config/carlink-pi-proxy/token}
NETWORK_CONFIG=${PI_PROXY_NETWORK_CONFIG:-}

address_of() {
    ip -4 -o addr show dev "$1" | awk '$3 == "inet" { split($4, parts, "/"); print parts[1]; exit }'
}

while :; do
    if [ "$VEHICLE_IFACE" = auto ]; then
        if [ -n "$(address_of eth0)" ]; then
            AAOS_IFACE=eth0
        else
            AAOS_IFACE=wlan0
        fi
    else
        AAOS_IFACE=$VEHICLE_IFACE
    fi
    VEHICLE_IP=$(address_of "$AAOS_IFACE")
    PHONE_IP=$(address_of "$PHONE_IFACE")
    if [ -n "$VEHICLE_IP" ]; then
        break
    fi
    echo "waiting for AAOS-facing IPv4: interface=$AAOS_IFACE" >&2
    sleep 3
done

set -- --vehicle-ip "$VEHICLE_IP" --ap-ip "$PHONE_IP" \
    --vehicle-interface "$AAOS_IFACE" --ap-interface "$PHONE_IFACE" --token-file "$TOKEN_FILE"
if [ -n "$NETWORK_CONFIG" ]; then
    set -- "$@" --network-config-file "$NETWORK_CONFIG"
fi
exec /usr/bin/python3 -u /home/eric/pi_zero_proxy_poc.py "$@"
