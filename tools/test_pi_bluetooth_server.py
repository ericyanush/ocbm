#!/usr/bin/env python3
"""Run on the Pi to verify the status handshake without a paired phone."""

import json
import socket
import threading
import time

from pi_zero_proxy_poc import interface_ipv4, serve, read_line
from pi_bluetooth import parse_iap2_channels


def main():
    sdp = '''Service RecHandle: 0x10000
Service Class ID List:
  "Wireless iAP v2" (02030302-1d19-415f-86f2-22a2106a0a77)
Protocol Descriptor List:
  "L2CAP" (0x0100)
  "RFCOMM" (0x0003)
    Channel: 7
Service RecHandle: 0x10001
Service Class ID List:
  "Other" (0x1101)
Protocol Descriptor List:
  "RFCOMM" (0x0003)
    Channel: 8
'''
    assert parse_iap2_channels(sdp) == [7]
    print("iAP2 SDP channel selection PASS")
    vehicle = interface_ipv4("eth0")
    # Status does not need the AP; use Ethernet for both listeners in this bench test.
    ap = vehicle
    assert vehicle, "Ethernet must be up"
    token = "local-test-token"
    port = 55101
    threading.Thread(target=serve, args=(vehicle, ap, port, port + 1, token, "eth0", "eth0"), daemon=True).start()
    for _ in range(30):
        try:
            socket.create_connection((vehicle, port), timeout=1).close()
            break
        except OSError:
            time.sleep(0.1)
    with socket.create_connection((vehicle, port), timeout=3) as client:
        client.sendall(b"PI_STATUS/1 local-test-token\n")
        line = read_line(client)
        assert line.startswith(b"READY "), line
        status = json.loads(line[6:])
        assert len(status["bt_mac"].split(":")) == 6
        assert isinstance(status["paired"], list)
        print("Pi status handshake PASS; paired phone count=", len(status["paired"]))
    with socket.create_connection((vehicle, port), timeout=3) as client:
        client.sendall(b"PI_STATUS/1 wrong-token\n")
        assert not read_line(client)
        print("Invalid-token rejection PASS")
    with socket.create_connection((vehicle, port), timeout=3) as client:
        client.sendall(b"PI_RFCOMM/1 local-test-token invalid-address\n")
        assert read_line(client) == b"ERROR invalid phone address"
        print("Invalid RFCOMM target rejection PASS")
    with socket.create_connection((vehicle, port), timeout=3) as client:
        client.sendall(b"PI_RFCOMM/1 local-test-token 02:00:00:00:00:01\n")
        assert read_line(client) == b"ERROR RFCOMM dial failed"
        print("Unpaired phone rejection PASS")


if __name__ == "__main__":
    main()
