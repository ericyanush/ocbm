"""Phone-facing Pi socket broker for the app-owned Bluetooth CarPlay experiment.

One AAOS-initiated TCP connection carries OCBM socket-broker frames. The Pi owns
only AP listeners and mDNS; the native CarPlay receiver and MFi signer stay in AAOS.
"""

import queue
import json
import socket
import stat
import struct
import subprocess
import threading
import time
from pathlib import Path

from pi_zero_proxy_poc import read_exact

LISTEN_TCP, BIND_UDP, CLOSE, ACCEPT, TCP_DATA, TCP_CREDIT, TCP_EOF = range(1, 8)
UDP_DATAGRAM, UDP_SEND, RESULT, START, STOP, MDNS_START, MDNS_PACKET, MDNS_SEND = range(8, 16)
PING = 0x10
WINDOW = 256 * 1024
MAX_FRAME = 65535
CHUNK = 16384


def ap_details(ap_ip: str, ap_interface: str, network_config: Path | None = None) -> dict:
    """Read the live phone-facing Wi-Fi profile, whether Pi AP or station."""
    def run(*args: str) -> str:
        return subprocess.check_output(args, text=True, timeout=5).strip()

    if network_config is not None:
        metadata = network_config.stat()
        if stat.S_IMODE(metadata.st_mode) & 0o077:
            raise RuntimeError("bench network config must be owner-only (mode 0600)")
        values = json.loads(network_config.read_text())
        expected_profile = values.get("profile")
        if expected_profile is None or run("nmcli", "-g", "GENERAL.CONNECTION", "device", "show", ap_interface) == expected_profile:
            ssid, password, channel = values["ssid"], values["passphrase"], values["channel"]
            if not isinstance(ssid, str) or not isinstance(password, str) or not isinstance(channel, int) or \
                    not ssid or not 8 <= len(password) <= 63 or channel not in range(1, 197):
                raise RuntimeError("bench network SSID, WPA passphrase, or channel is invalid")
            return {"ap_ip": ap_ip, "ssid": ssid, "passphrase": password, "channel": channel}

    profile = run("nmcli", "-g", "GENERAL.CONNECTION", "device", "show", ap_interface)
    if not profile or profile == "--":
        raise RuntimeError(f"phone Wi-Fi interface {ap_interface} has no active NetworkManager profile")
    mode = run("nmcli", "-g", "802-11-wireless.mode", "connection", "show", profile)
    if mode not in ("ap", "infrastructure"):
        raise RuntimeError(f"phone Wi-Fi profile has unsupported mode={mode}")
    ssid = run("nmcli", "-g", "802-11-wireless.ssid", "connection", "show", profile)
    password = run("nmcli", "-s", "-g", "802-11-wireless-security.psk", "connection", "show", profile)
    if not password:
        secret_file = Path.home() / ".config/carlink-pi-proxy" / (
            "ap-passphrase" if mode == "ap" else "vehicle-passphrase")
        password = secret_file.read_text().strip()
    if mode == "ap":
        channel = int(run("nmcli", "-g", "802-11-wireless.channel", "connection", "show", profile))
    else:
        # Station profile channel is usually 0 (auto); read the current association.
        scan = run("nmcli", "-t", "-f", "IN-USE,CHAN", "device", "wifi", "list", "ifname", ap_interface,
                   "--rescan", "no")
        active = next((line for line in scan.splitlines()
                       if line.split(":", 1)[0].strip() in ("*", "yes")), "")
        if not active:
            raise RuntimeError("connected hotspot channel unavailable")
        channel = int(active.rsplit(":", 1)[1])
    if not ssid or not 8 <= len(password) <= 63 or channel not in range(1, 197):
        raise RuntimeError("phone Wi-Fi SSID, WPA passphrase, or channel is incomplete")
    return {"ap_ip": ap_ip, "ssid": ssid, "passphrase": password, "channel": channel}


def tuple_bytes(address: tuple[str, int]) -> bytes:
    return b"\x04" + socket.inet_aton(address[0]) + struct.pack("!H", address[1])


def parse_tuple(data: bytes, at: int) -> tuple[tuple[str, int], int]:
    if at >= len(data):
        raise ValueError("missing peer tuple")
    family = data[at]
    size = 4 if family == 4 else 16 if family == 6 else 0
    if not size or at + 1 + size + 2 > len(data):
        raise ValueError("invalid peer tuple")
    address = socket.inet_ntop(socket.AF_INET if family == 4 else socket.AF_INET6, data[at + 1:at + 1 + size])
    return (address, struct.unpack_from("!H", data, at + 1 + size)[0]), at + 1 + size + 2


class Stream:
    def __init__(self, broker, stream_id: int, listener_id: int, sock: socket.socket, peer):
        self.broker, self.id, self.listener_id, self.sock, self.peer = broker, stream_id, listener_id, sock, peer
        self.credit = 0
        self.condition = threading.Condition()
        self.alive = True
        self.read_eof = False
        self.write_eof = False
        self.tx_bytes = self.rx_bytes = 0
        self.downlink = queue.Queue(maxsize=32)
        sock.settimeout(None)
    def start(self):
        self.broker.send(ACCEPT, self.id, struct.pack("<II", self.listener_id, WINDOW) + tuple_bytes(self.peer))
        threading.Thread(target=self.read_phone, daemon=True, name=f"pi-tcp-rx-{self.id}").start()
        threading.Thread(target=self.write_loop, daemon=True, name=f"pi-tcp-tx-{self.id}").start()

    def grant(self, amount: int):
        with self.condition:
            self.credit = min(WINDOW, self.credit + amount)
            self.condition.notify_all()

    def read_phone(self):
        try:
            while self.alive and self.broker.active:
                with self.condition:
                    self.condition.wait_for(lambda: not self.alive or not self.broker.active or self.credit > 0)
                    if not self.alive or not self.broker.active:
                        return
                    size = min(CHUNK, self.credit)
                    self.credit -= size
                chunk = self.sock.recv(size)
                if not chunk:
                    self.read_eof = True
                    self.broker.send(TCP_EOF, self.id)
                    self.maybe_close()
                    return
                if len(chunk) < size:
                    self.grant(size - len(chunk))
                self.tx_bytes += len(chunk)
                self.broker.send(TCP_DATA, self.id, chunk)
        except OSError as error:
            self.broker.note(f"TCP read error stream={self.id}: {error}")
            self.broker.send(CLOSE, self.id, b"\x01")
            self.close()

    def write_loop(self):
        while self.alive and self.broker.active:
            data = self.downlink.get()
            if data is None:
                try:
                    self.sock.shutdown(socket.SHUT_WR)
                except OSError:
                    pass
                self.write_eof = True
                self.maybe_close()
                return
            self.write_phone(data)

    def write_phone(self, data: bytes):
        try:
            self.sock.sendall(data)
            self.rx_bytes += len(data)
            self.broker.send(TCP_CREDIT, self.id, struct.pack("<I", len(data)))
        except OSError as error:
            self.broker.note(f"TCP write error stream={self.id}: {error}")
            self.broker.send(CLOSE, self.id, b"\x02")
            self.close()

    def maybe_close(self):
        if self.read_eof and self.write_eof and self.alive:
            self.broker.send(CLOSE, self.id, b"\x00")
            self.close()

    def close(self):
        if not self.alive:
            return
        self.alive = False
        try:
            self.downlink.put_nowait(None)
        except queue.Full:
            pass
        with self.condition:
            self.condition.notify_all()
        try:
            self.sock.close()
        except OSError:
            pass
        self.broker.streams.pop(self.id, None)
        self.broker.note(f"TCP close stream={self.id} phone_to_aaos={self.tx_bytes} aaos_to_phone={self.rx_bytes}")


class Broker:
    def __init__(self, aaos: socket.socket, ap_ip: str):
        self.aaos, self.ap_ip = aaos, ap_ip
        self.send_lock = threading.Lock()
        self.active = True
        self.epoch = None
        self.listeners = {}
        self.udp = {}
        self.udp_stats = {}
        self.streams = {}
        self.mdns = None
        self.next_stream = 0x80000000
        self.stats_at = time.monotonic()
        self.rx_frames = self.tx_frames = self.rx_bytes = self.tx_bytes = 0

    def note(self, message: str):
        print(f"[pi-broker] {message}", flush=True)

    def send(self, op: int, object_id: int, body: bytes = b""):
        if not self.active or self.epoch is None:
            return
        frame = struct.pack("<BII", op, self.epoch, object_id) + body
        if len(frame) > MAX_FRAME:
            self.note(f"oversized output frame op={op} bytes={len(frame)}")
            return
        try:
            with self.send_lock:
                self.aaos.sendall(struct.pack("!I", len(frame)) + frame)
            self.tx_frames += 1
            self.tx_bytes += len(frame)
        except OSError:
            self.close()

    def result(self, object_id: int, status: int, port: int = 0):
        self.send(RESULT, object_id, struct.pack("<BH", status, port))

    def register(self, op: int, object_id: int, body: bytes):
        sock = None
        try:
            if len(body) < 12 or body[0] != 4 or body[1:5] != socket.inet_aton(self.ap_ip):
                raise ValueError("listener must bind Pi AP IPv4")
            port = struct.unpack_from("<H", body, 6)[0]
            if not 1 <= port <= 65535:
                raise ValueError("invalid listener port")
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM if op == LISTEN_TCP else socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind((self.ap_ip, port))
            if op == LISTEN_TCP:
                sock.listen(16)
                self.listeners[object_id] = sock
                threading.Thread(target=self.accept_loop, args=(object_id, sock), daemon=True).start()
            else:
                self.udp[object_id] = sock
                threading.Thread(target=self.udp_loop, args=(object_id, sock), daemon=True).start()
            self.result(object_id, 0, port)
            self.note(f"bound {'TCP' if op == LISTEN_TCP else 'UDP'} {self.ap_ip}:{port} id={object_id}")
        except (OSError, ValueError) as error:
            if sock:
                sock.close()
            self.note(f"bind failed id={object_id}: {error}")
            self.result(object_id, 1)

    def accept_loop(self, listener_id: int, listener: socket.socket):
        while self.active:
            try:
                phone, peer = listener.accept()
            except OSError:
                break
            phone.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            self.next_stream = (self.next_stream + 1) & 0xFFFFFFFF
            stream_id = self.next_stream
            stream = Stream(self, stream_id, listener_id, phone, peer)
            self.streams[stream_id] = stream
            stream.start()
            self.note(f"TCP accept peer={peer} listener={listener_id} stream={stream_id}")

    def udp_loop(self, object_id: int, sock: socket.socket):
        while self.active:
            try:
                packet, peer = sock.recvfrom(60000)
            except OSError:
                break
            now = time.monotonic_ns()
            stats = self.udp_stats.setdefault(object_id, {"packets": 0, "bytes": 0, "last": 0, "max_gap_us": 0})
            if stats["last"]:
                stats["max_gap_us"] = max(stats["max_gap_us"], (now - stats["last"]) // 1000)
            stats["last"] = now
            stats["packets"] += 1
            stats["bytes"] += len(packet)
            self.send(UDP_DATAGRAM, object_id, tuple_bytes(peer) + packet)

    def mdns_loop(self, sock: socket.socket):
        while self.active:
            try:
                packet, peer = sock.recvfrom(9000)
            except OSError:
                break
            if peer[0] != self.ap_ip:
                self.send(MDNS_PACKET, 0, tuple_bytes(peer) + packet)

    def start_mdns(self, body: bytes):
        sock = None
        try:
            if body != b"\x04" + socket.inet_aton(self.ap_ip):
                raise ValueError("mDNS requested on wrong AP IP")
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(("", 5353))
            membership = socket.inet_aton("224.0.0.251") + socket.inet_aton(self.ap_ip)
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, membership)
            sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(self.ap_ip))
            self.mdns = sock
            threading.Thread(target=self.mdns_loop, args=(sock,), daemon=True).start()
            self.result(0, 0, 5353)
            self.note(f"mDNS joined on {self.ap_ip}")
        except (OSError, ValueError) as error:
            if sock:
                sock.close()
            self.note(f"mDNS failed: {error}")
            self.result(0, 1)

    def handle(self, frame: bytes):
        if len(frame) < 9:
            raise ValueError("short broker frame")
        op, epoch, object_id = struct.unpack_from("<BII", frame)
        body = frame[9:]
        if op == START:
            self.close_sockets()
            self.epoch = epoch
            self.note(f"broker started epoch={epoch:08x}")
            return
        if epoch != self.epoch:
            return
        if op == PING:
            self.send(PING, 0)
        elif op == STOP:
            self.active = False
        elif op in (LISTEN_TCP, BIND_UDP):
            self.register(op, object_id, body)
        elif op == MDNS_START:
            self.start_mdns(body)
        elif op == MDNS_SEND and self.mdns and len(body) <= 9000:
            self.mdns.sendto(body, ("224.0.0.251", 5353))
        elif op == TCP_CREDIT and len(body) == 4:
            stream = self.streams.get(object_id)
            if stream:
                stream.grant(struct.unpack("<I", body)[0])
        elif op == TCP_DATA:
            stream = self.streams.get(object_id)
            if stream and len(body) <= CHUNK:
                try:
                    stream.downlink.put_nowait(body)
                except queue.Full:
                    self.note(f"TCP downlink queue overflow stream={object_id}; closing")
                    self.send(CLOSE, object_id, b"\x03")
                    stream.close()
        elif op == TCP_EOF:
            stream = self.streams.get(object_id)
            if stream:
                try:
                    stream.downlink.put_nowait(None)
                except queue.Full:
                    self.send(CLOSE, object_id, b"\x03")
                    stream.close()
        elif op == UDP_SEND:
            peer, offset = parse_tuple(body, 0)
            sock = self.udp.get(object_id)
            if sock:
                sock.sendto(body[offset:], peer)
        elif op == CLOSE:
            if object_id in self.streams:
                self.streams[object_id].close()
            elif object_id in self.listeners:
                self.listeners.pop(object_id).close()
            elif object_id in self.udp:
                self.udp.pop(object_id).close()

    def run(self):
        try:
            self.aaos.settimeout(20)
            while self.active:
                length = struct.unpack("!I", read_exact(self.aaos, 4))[0]
                if length not in range(9, MAX_FRAME + 1):
                    raise ValueError(f"invalid broker frame length={length}")
                frame = read_exact(self.aaos, length)
                self.rx_frames += 1
                self.rx_bytes += len(frame)
                self.handle(frame)
                if time.monotonic() - self.stats_at >= 5:
                    self.note(f"stats rx={self.rx_frames}fr/{self.rx_bytes}B tx={self.tx_frames}fr/{self.tx_bytes}B streams={len(self.streams)}")
                    for port_id, stats in list(self.udp_stats.items()):
                        self.note(f"UDP id={port_id} packets={stats['packets']} bytes={stats['bytes']} max_gap_us={stats['max_gap_us']}")
                        stats["packets"] = stats["bytes"] = stats["max_gap_us"] = 0
                    self.stats_at = time.monotonic()
        finally:
            self.close()

    def close_sockets(self):
        for stream in list(self.streams.values()):
            stream.close()
        for sock in list(self.listeners.values()) + list(self.udp.values()):
            sock.close()
        if self.mdns:
            self.mdns.close()
        self.listeners.clear()
        self.udp.clear()
        self.udp_stats.clear()
        self.mdns = None

    def close(self):
        self.active = False
        self.close_sockets()
        try:
            self.aaos.close()
        except OSError:
            pass
