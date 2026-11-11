#!/usr/bin/env python3
"""Pi broker: AAOS dials Pi; phone may use Pi AP or a shared Wi-Fi hotspot."""

import argparse
from contextlib import ExitStack
import json
from pathlib import Path
import socket
import struct
import subprocess
import threading
import time

MAX_MESSAGE = 8192


def read_line(sock: socket.socket, limit: int = 256) -> bytes:
    result = bytearray()
    while len(result) < limit:
        byte = sock.recv(1)
        if not byte or byte == b"\n":
            break
        result.extend(byte)
    return bytes(result).rstrip(b"\r")


def read_exact(sock: socket.socket, count: int) -> bytes:
    result = bytearray()
    while len(result) < count:
        chunk = sock.recv(count - len(result))
        if not chunk:
            raise ConnectionError("AAOS tunnel closed while reading a message")
        result.extend(chunk)
    return bytes(result)


def read_request(sock: socket.socket) -> bytes:
    data = bytearray()
    while b"\r\n\r\n" not in data and len(data) < MAX_MESSAGE:
        chunk = sock.recv(min(1024, MAX_MESSAGE - len(data)))
        if not chunk:
            break
        data.extend(chunk)
    if b"\r\n\r\n" not in data:
        raise ValueError("incomplete or oversized HTTP request")
    return bytes(data)


def listen(address: str, port: int) -> socket.socket:
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.bind((address, port))
    sock.listen(8)
    return sock


def interface_ipv4(name: str) -> str:
    result = subprocess.run(["ip", "-4", "-o", "addr", "show", "dev", name], capture_output=True, text=True, timeout=3)
    for line in result.stdout.splitlines():
        fields = line.split()
        if "inet" in fields:
            return fields[fields.index("inet") + 1].split("/")[0]
    return ""


def relay_rfcomm(aaos: socket.socket, phone: socket.socket, address: str) -> None:
    closed = threading.Event()
    counts = [0, 0]

    def copy(source: socket.socket, target: socket.socket, index: int) -> None:
        try:
            while not closed.is_set():
                data = source.recv(8192)
                if not data:
                    break
                target.sendall(data)
                counts[index] += len(data)
        except OSError as error:
            print(f"[pi-bt] RFCOMM relay direction={index} ended: {error}", flush=True)
        finally:
            closed.set()
            try:
                source.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                target.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass

    worker = threading.Thread(target=copy, args=(phone, aaos, 1), daemon=True)
    worker.start()
    copy(aaos, phone, 0)
    worker.join(timeout=2)
    print(f"[pi-bt] RFCOMM closed phone={address} aaos_to_phone={counts[0]} phone_to_aaos={counts[1]}", flush=True)


def serve(vehicle: str, ap: str, port: int, phone_port: int, token: str, ap_interface: str,
          vehicle_interface: str, network_config: Path | None = None) -> None:
    from pi_bluetooth import PiBluetooth, MAC
    bluetooth = PiBluetooth()
    bluetooth.start()
    broker_lock = threading.Lock()
    active_broker = {"socket": None, "phone_ip": None}
    with ExitStack() as listeners:
        aaos_listener = listeners.enter_context(listen(vehicle, port))
        phone_listener = listeners.enter_context(listen(ap, phone_port)) if ap else None
        print(f"AAOS dial target {vehicle}:{port}; phone interface={ap_interface} IPv4={ap or 'pending'}", flush=True)
        aaos_listener.settimeout(1)
        print("waiting for AAOS outbound connection", flush=True)
        def handle(aaos: socket.socket, peer) -> None:
            with aaos:
                aaos.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                aaos.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
                aaos.settimeout(15)
                handshake = read_line(aaos)
                if handshake == f"PI_CARPLAY/1 {token}".encode("ascii"):
                    if not broker_lock.acquire(blocking=False):
                        aaos.sendall(b"BUSY\n")
                        return
                    try:
                        from pi_carplay_broker import Broker, ap_details
                        phone_ip = interface_ipv4(ap_interface)
                        if not phone_ip:
                            aaos.sendall(b"ERROR phone Wi-Fi network unavailable\n")
                            return
                        details = ap_details(phone_ip, ap_interface, network_config)
                        aaos.sendall(b"READY " + json.dumps(details, separators=(",", ":")).encode("utf-8") + b"\n")
                        aaos.settimeout(None)
                        active_broker.update(socket=aaos, phone_ip=phone_ip)
                        print(f"CarPlay broker connected from {peer}; phone Wi-Fi={details['ssid']} channel={details['channel']}", flush=True)
                        Broker(aaos, phone_ip).run()
                    except (OSError, ValueError, RuntimeError) as error:
                        print(f"CarPlay broker ended: {error}", flush=True)
                    finally:
                        active_broker.update(socket=None, phone_ip=None)
                        broker_lock.release()
                    return
                if handshake == f"PI_STATUS/1 {token}".encode("ascii"):
                    status = {"bt_mac": bluetooth.address(), "paired": bluetooth.paired()}
                    aaos.sendall(b"READY " + json.dumps(status, separators=(",", ":")).encode() + b"\n")
                    return
                prefix = f"PI_RFCOMM/1 {token} ".encode("ascii")
                if handshake.startswith(prefix):
                    address = handshake[len(prefix):].decode("ascii", errors="replace").upper()
                    if not MAC.fullmatch(address):
                        aaos.sendall(b"ERROR invalid phone address\n")
                        return
                    try:
                        with bluetooth.connect_phone(address) as phone:
                            aaos.sendall(f"READY {bluetooth.address()}\n".encode("ascii"))
                            aaos.settimeout(None)
                            relay_rfcomm(aaos, phone, address)
                    except Exception as error:
                        print(f"[pi-bt] RFCOMM dial failed phone={address}: {error}", flush=True)
                        try:
                            aaos.sendall(b"ERROR RFCOMM dial failed\n")
                        except OSError:
                            pass
                    return
                if handshake != f"PI_PROXY_POC/1 {token}".encode("ascii"):
                    print(f"rejected handshake from {peer}", flush=True)
                    return
                if phone_listener is None:
                    aaos.sendall(b"ERROR phone Wi-Fi network unavailable\n")
                    return
                aaos.sendall(b"READY\n")
                print(f"AAOS connected from {peer}; local={aaos.getsockname()}", flush=True)
                phone_listener.settimeout(1)
                while True:
                    try:
                        phone, phone_peer = phone_listener.accept()
                    except socket.timeout:
                        try:
                            aaos.sendall(struct.pack("!I", 0))  # heartbeat
                            if struct.unpack("!I", read_exact(aaos, 4))[0] != 0:
                                raise ValueError("bad heartbeat")
                        except (OSError, ValueError):
                            print("AAOS tunnel disconnected", flush=True)
                            break

                        continue
                    with phone:
                        phone.settimeout(5)
                        try:
                            request = read_request(phone)
                            start = time.monotonic_ns()
                            aaos.sendall(struct.pack("!I", len(request)) + request)
                            size = struct.unpack("!I", read_exact(aaos, 4))[0]
                            if not 0 < size <= MAX_MESSAGE:
                                raise ValueError(f"invalid AAOS response size={size}")
                            response = read_exact(aaos, size)
                            phone.sendall(response)
                            elapsed_ms = (time.monotonic_ns() - start) / 1_000_000
                            print(f"phone={phone_peer} request={len(request)} response={size} "
                                  f"relay_ms={elapsed_ms:.1f}", flush=True)
                        except (OSError, ValueError) as error:
                            print(f"request failed: {error}", flush=True)
                            try:
                                phone.sendall(b"HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n")
                            except OSError:
                                pass
                            break

        try:
            while True:
                if interface_ipv4(vehicle_interface) != vehicle:
                    raise RuntimeError("vehicle hotspot IPv4 changed; restarting Pi proxy listener")
                if active_broker["socket"] is not None and interface_ipv4(ap_interface) != active_broker["phone_ip"]:
                    print("phone-facing Wi-Fi address changed; closing stale broker", flush=True)
                    try:
                        active_broker["socket"].shutdown(socket.SHUT_RDWR)
                    except OSError:
                        pass
                try:
                    aaos, peer = aaos_listener.accept()
                except socket.timeout:
                    continue
                threading.Thread(target=handle, args=(aaos, peer), name="pi-client", daemon=True).start()
        finally:
            bluetooth.close()

def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--vehicle-ip", required=True, help="Pi's IPv4 on vehicle hotspot")
    parser.add_argument("--ap-ip", required=True, help="initial phone-facing IPv4, or empty until Wi-Fi joins")
    token_source = parser.add_mutually_exclusive_group(required=True)
    token_source.add_argument("--token", help="shared test token; ASCII, no whitespace")
    token_source.add_argument("--token-file", type=Path, help="owner-readable file containing the test token")
    parser.add_argument("--port", type=int, default=5101)
    parser.add_argument("--phone-port", type=int, default=5102)
    parser.add_argument("--ap-interface", default="wlan1", help="phone-facing Wi-Fi interface: AP or shared hotspot station")
    parser.add_argument("--vehicle-interface", default="wlan0", help="restart when this interface's IPv4 changes")
    parser.add_argument("--network-config-file", type=Path,
                        help="owner-only JSON with phone Wi-Fi SSID, passphrase, and channel for an Ethernet bench test")
    args = parser.parse_args()
    token = args.token if args.token is not None else args.token_file.read_text().strip()
    if not token or not token.isascii() or any(c.isspace() for c in token):
        parser.error("token must be ASCII without whitespace")
    serve(args.vehicle_ip, args.ap_ip, args.port, args.phone_port, token, args.ap_interface,
          args.vehicle_interface, args.network_config_file)


if __name__ == "__main__":
    main()
