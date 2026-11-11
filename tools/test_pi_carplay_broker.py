"""Loopback protocol tests for the AAOS-initiated Pi socket broker."""

import socket
import struct
import threading
import tempfile
from pathlib import Path
import unittest
from unittest.mock import patch

from pi_carplay_broker import (
    ACCEPT, BIND_UDP, Broker, LISTEN_TCP, RESULT, START, STOP,
    TCP_CREDIT, TCP_DATA, UDP_DATAGRAM, UDP_SEND, PING, ap_details,
)
from pi_zero_proxy_poc import read_exact


class BrokerTest(unittest.TestCase):
    def setUp(self):
        self.aaos, peer = socket.socketpair()
        self.aaos.settimeout(2)
        self.broker = Broker(peer, "127.0.0.1")
        self.worker = threading.Thread(target=self.broker.run, daemon=True)
        self.worker.start()
        self.send(START, 0)

    def tearDown(self):
        try:
            self.send(STOP, 0)
        except OSError:
            pass
        self.worker.join(2)
        self.broker.close()
        self.aaos.close()

    def send(self, op, object_id, body=b""):
        frame = struct.pack("<BII", op, 1234, object_id) + body
        self.aaos.sendall(struct.pack("!I", len(frame)) + frame)

    def receive(self):
        frame = read_exact(self.aaos, struct.unpack("!I", read_exact(self.aaos, 4))[0])
        return frame[0], struct.unpack_from("<I", frame, 5)[0], frame[9:]

    @staticmethod
    def free_port(kind):
        with socket.socket(socket.AF_INET, kind) as temporary:
            temporary.bind(("127.0.0.1", 0))
            return temporary.getsockname()[1]

    def register(self, op, object_id, port):
        body = b"\x04" + socket.inet_aton("127.0.0.1") + b"\x08" + struct.pack("<HI", port, 262144)
        self.send(op, object_id, body)
        result, result_id, reply = self.receive()
        self.assertEqual((result, result_id, reply[0], struct.unpack_from("<H", reply, 1)[0]),
                         (RESULT, object_id, 0, port))

    def test_tcp_round_trip(self):
        port = self.free_port(socket.SOCK_STREAM)
        self.register(LISTEN_TCP, 1, port)
        with socket.create_connection(("127.0.0.1", port)) as phone:
            phone.settimeout(2)
            op, stream, _ = self.receive()
            self.assertEqual(op, ACCEPT)
            self.send(TCP_CREDIT, stream, struct.pack("<I", 262144))
            phone.sendall(b"phone request")
            self.assertEqual(self.receive(), (TCP_DATA, stream, b"phone request"))
            self.send(TCP_DATA, stream, b"AAOS response")
            self.assertEqual(phone.recv(13), b"AAOS response")
            self.assertEqual(self.receive()[:2], (TCP_CREDIT, stream))

    def test_liveness_ping(self):
        self.send(PING, 0)
        self.assertEqual(self.receive(), (PING, 0, b""))

    def test_udp_round_trip(self):
        port = self.free_port(socket.SOCK_DGRAM)
        self.register(BIND_UDP, 2, port)
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as phone:
            phone.settimeout(2)
            phone.bind(("127.0.0.1", 0))
            phone.sendto(b"RTP", ("127.0.0.1", port))
            op, object_id, body = self.receive()
            self.assertEqual((op, object_id, body[7:]), (UDP_DATAGRAM, 2, b"RTP"))
            peer = b"\x04" + socket.inet_aton("127.0.0.1") + struct.pack("!H", phone.getsockname()[1])
            self.send(UDP_SEND, 2, peer + b"reply")
            self.assertEqual(phone.recvfrom(1024)[0], b"reply")


class SharedHotspotDetailsTest(unittest.TestCase):
    def test_ethernet_bench_network_config(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "network.json"
            config.write_text('{"ssid":"BenchWiFi","passphrase":"example-only","channel":36}')
            config.chmod(0o600)
            self.assertEqual(ap_details("192.0.2.10", "eth0", config), {
                "ap_ip": "192.0.2.10", "ssid": "BenchWiFi",
                "passphrase": "example-only", "channel": 36,
            })
            config.chmod(0o644)
            with self.assertRaisesRegex(RuntimeError, "owner-only"):
                ap_details("192.0.2.10", "eth0", config)

    def test_station_uses_live_channel(self):
        def nmcli(args, **_):
            if "GENERAL.CONNECTION" in args:
                return "vehicle-profile\n"
            if "802-11-wireless.mode" in args:
                return "infrastructure\n"
            if "802-11-wireless.ssid" in args:
                return "VehicleHotspot\n"
            if "802-11-wireless-security.psk" in args:
                return "test-passphrase\n"
            if "IN-USE,CHAN" in args:
                return ":36\n*:149\n"
            raise AssertionError(args)

        with patch("pi_carplay_broker.subprocess.check_output", side_effect=nmcli):
            self.assertEqual(ap_details("192.0.2.10", "wlan0"), {
                "ap_ip": "192.0.2.10", "ssid": "VehicleHotspot",
                "passphrase": "test-passphrase", "channel": 149,
            })

    def test_bench_config_does_not_override_another_wifi_profile(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / "network.json"
            config.write_text('{"profile":"home","ssid":"Home","passphrase":"home-secret","channel":7}')
            config.chmod(0o600)

            def nmcli(args, **_):
                if "GENERAL.CONNECTION" in args:
                    return "vehicle\n"
                if "802-11-wireless.mode" in args:
                    return "infrastructure\n"
                if "802-11-wireless.ssid" in args:
                    return "VehicleHotspot\n"
                if "802-11-wireless-security.psk" in args:
                    return "vehicle-secret\n"
                if "IN-USE,CHAN" in args:
                    return "*:36\n"
                raise AssertionError(args)

            with patch("pi_carplay_broker.subprocess.check_output", side_effect=nmcli):
                self.assertEqual(ap_details("192.0.2.10", "wlan0", config)["ssid"], "VehicleHotspot")


if __name__ == "__main__":
    unittest.main()
