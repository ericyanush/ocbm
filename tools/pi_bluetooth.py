"""BlueZ pairing and RFCOMM access for the Pi CarPlay proxy.

The phone pairs with the Pi. AAOS owns iAP2 and MFi; this module only dials the
phone's RFCOMM service and forwards bytes. No audio Bluetooth profile is needed.
"""

import re
import socket
import subprocess
import threading

import dbus
import dbus.service
from dbus.mainloop.glib import DBusGMainLoop
from gi.repository import GLib


IAP2_SERVICES = (
    "02030302-1d19-415f-86f2-22a2106a0a77",
    "00000000-deca-fade-deca-deafdecacafe",
)
MAC = re.compile(r"(?i)^[0-9a-f]{2}(?::[0-9a-f]{2}){5}$")
AGENT_PATH = "/org/carlink/PiPairingAgent"


class PairingAgent(dbus.service.Object):
    @dbus.service.method("org.bluez.Agent1", in_signature="", out_signature="")
    def Release(self):
        pass

    @dbus.service.method("org.bluez.Agent1", in_signature="", out_signature="")
    def Cancel(self):
        pass

    @dbus.service.method("org.bluez.Agent1", in_signature="os", out_signature="")
    def AuthorizeService(self, device, uuid):
        # BlueZ audio profiles are disabled for this test; permit paired iAP2 service use.
        print(f"[pi-bt] service authorization device={device} uuid={uuid}", flush=True)

    @dbus.service.method("org.bluez.Agent1", in_signature="ou", out_signature="")
    def RequestConfirmation(self, device, passkey):
        print(f"[pi-bt] pairing confirmation device={device} passkey={passkey:06d}", flush=True)

    @dbus.service.method("org.bluez.Agent1", in_signature="o", out_signature="")
    def RequestAuthorization(self, device):
        print(f"[pi-bt] pairing authorized device={device}", flush=True)

    @dbus.service.method("org.bluez.Agent1", in_signature="os", out_signature="")
    def DisplayPinCode(self, device, pincode):
        print(f"[pi-bt] PIN shown device={device}", flush=True)

    @dbus.service.method("org.bluez.Agent1", in_signature="ouq", out_signature="")
    def DisplayPasskey(self, device, passkey, entered):
        print(f"[pi-bt] passkey shown device={device} digits={entered}", flush=True)

    @dbus.service.method("org.bluez.Agent1", in_signature="o", out_signature="s")
    def RequestPinCode(self, device):
        raise dbus.exceptions.DBusException("org.bluez.Error.Rejected", "PIN entry unavailable")

    @dbus.service.method("org.bluez.Agent1", in_signature="o", out_signature="u")
    def RequestPasskey(self, device):
        raise dbus.exceptions.DBusException("org.bluez.Error.Rejected", "Passkey entry unavailable")


class PiBluetooth:
    def __init__(self):
        DBusGMainLoop(set_as_default=True)
        self.bus = dbus.SystemBus()
        self.loop = GLib.MainLoop()
        self.agent = PairingAgent(self.bus, AGENT_PATH)
        self.agent_manager = dbus.Interface(
            self.bus.get_object("org.bluez", "/org/bluez"), "org.bluez.AgentManager1")

    def start(self):
        controller = self._controller()
        props = dbus.Interface(self.bus.get_object("org.bluez", controller), "org.freedesktop.DBus.Properties")
        if not bool(props.Get("org.bluez.Adapter1", "Powered")):
            props.Set("org.bluez.Adapter1", "Powered", dbus.Boolean(True))
        self.agent_manager.RegisterAgent(AGENT_PATH, "NoInputNoOutput")
        self.agent_manager.RequestDefaultAgent(AGENT_PATH)
        props.Set("org.bluez.Adapter1", "PairableTimeout", dbus.UInt32(0))
        props.Set("org.bluez.Adapter1", "DiscoverableTimeout", dbus.UInt32(0))
        props.Set("org.bluez.Adapter1", "Pairable", dbus.Boolean(True))
        props.Set("org.bluez.Adapter1", "Discoverable", dbus.Boolean(True))
        threading.Thread(target=self.loop.run, name="pi-bluez-agent", daemon=True).start()
        print(f"[pi-bt] pairable and discoverable address={self.address()}", flush=True)

    def _objects(self):
        manager = dbus.Interface(self.bus.get_object("org.bluez", "/"), "org.freedesktop.DBus.ObjectManager")
        return manager.GetManagedObjects()

    def _controller(self):
        for path, interfaces in self._objects().items():
            if "org.bluez.Adapter1" in interfaces:
                return path
        raise RuntimeError("BlueZ Bluetooth controller unavailable")

    def address(self):
        return str(self._objects()[self._controller()]["org.bluez.Adapter1"]["Address"]).upper()

    def paired(self):
        phones = []
        for _, interfaces in self._objects().items():
            device = interfaces.get("org.bluez.Device1")
            if device and bool(device.get("Paired", False)):
                phones.append({"address": str(device["Address"]).upper(),
                               "name": str(device.get("Alias", device["Address"]))})
        return sorted(phones, key=lambda item: item["address"])

    def connect_phone(self, address):
        if not MAC.fullmatch(address):
            raise ValueError("invalid phone Bluetooth MAC")
        address = address.upper()
        if address not in {item["address"] for item in self.paired()}:
            raise ValueError("phone is not paired with Pi Bluetooth")
        result = subprocess.run(["sdptool", "browse", address], text=True, capture_output=True, timeout=15)
        if result.returncode:
            raise RuntimeError(f"phone SDP browse failed rc={result.returncode}")
        channels = parse_iap2_channels(result.stdout)
        if not channels:
            raise RuntimeError("phone has no discoverable iAP2 RFCOMM service")
        last_error = None
        for channel in channels:
            link = socket.socket(socket.AF_BLUETOOTH, socket.SOCK_STREAM, socket.BTPROTO_RFCOMM)
            link.settimeout(15)
            try:
                link.connect((address, channel))
                link.settimeout(None)
                print(f"[pi-bt] RFCOMM connected phone={address} channel={channel}", flush=True)
                return link
            except OSError as error:
                last_error = error
                link.close()
        raise RuntimeError(f"RFCOMM dial failed for {len(channels)} discovered channel(s): {last_error}")

    def close(self):
        try:
            self.agent_manager.UnregisterAgent(AGENT_PATH)
        except dbus.DBusException:
            pass
        self.loop.quit()


def parse_iap2_channels(sdp: str) -> list[int]:
    """Read only the RFCOMM channel from a record containing a supported UUID."""
    channels = []
    for record in re.split(r"(?=Service RecHandle:)", sdp, flags=re.IGNORECASE):
        if not any(uuid in record.lower() for uuid in IAP2_SERVICES):
            continue
        match = re.search(r'"RFCOMM"\s*\(0x0003\).*?\bChannel:\s*(\d+)', record, re.S | re.I)
        if match:
            channel = int(match.group(1))
            if 1 <= channel <= 30 and channel not in channels:
                channels.append(channel)
    return channels
