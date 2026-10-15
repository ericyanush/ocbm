#!/usr/bin/env python3
"""Select the verified two-contact touchscreen descriptor in the static `/info` plist.

The JNI app serves this plist verbatim, rather than generating it per connection. The native HID
sender therefore has to agree with this descriptor: single contact is 5 bytes, multi-touch is one
12-byte report containing two Finger collections. The byte template and geometry offsets below
match `receiver::info::touchscreen_multi_descriptor`.

    python3 tools/info_plist_multitouch.py           # apply
    python3 tools/info_plist_multitouch.py --check    # verify
    python3 tools/info_plist_multitouch.py --revert   # restore 5-byte single-touch descriptor
"""

import plistlib
import sys
from pathlib import Path

PLIST = Path(__file__).resolve().parent.parent / "netprobe_app/app/src/main/assets/info.bplist"


def descriptor(width: int, height: int, multi: bool) -> bytes:
    single = bytearray([
        0x05, 0x0D, 0x09, 0x04, 0xA1, 0x01,
        0x05, 0x0D, 0x09, 0x22, 0xA1, 0x02,
        0x05, 0x0D, 0x09, 0x33, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x01, 0x81, 0x02,
        0x75, 0x07, 0x95, 0x01, 0x81, 0x01,
        0x05, 0x01, 0x09, 0x30, 0x15, 0x00, 0x26, 0xFF, 0x7F, 0x75, 0x10, 0x95, 0x01, 0x81, 0x02,
        0x09, 0x31, 0x15, 0x00, 0x26, 0xFF, 0x7F, 0x75, 0x10, 0x95, 0x01, 0x81, 0x02,
        0xC0, 0xC0,
    ])
    if not multi:
        for offset, value in ((39, width), (52, height)):
            single[offset] = value & 0xFF
            single[offset + 1] = value >> 8
        return bytes(single)

    finger = bytearray([
        0x05, 0x0D, 0x09, 0x22, 0xA1, 0x02,
        0x05, 0x0D, 0x09, 0x38, 0x75, 0x08, 0x95, 0x01, 0x81, 0x02,
        0x09, 0x33, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x01, 0x81, 0x02,
        0x75, 0x07, 0x95, 0x01, 0x81, 0x01,
        0x05, 0x01, 0x09, 0x30, 0x15, 0x00, 0x26, 0xFF, 0x7F, 0x75, 0x10, 0x95, 0x01, 0x81, 0x02,
        0x09, 0x31, 0x15, 0x00, 0x26, 0xFF, 0x7F, 0x75, 0x10, 0x95, 0x01, 0x81, 0x02,
        0xC0,
    ])
    if len(finger) != 63:
        raise ValueError(f"unexpected Finger descriptor length: {len(finger)}")
    result = bytearray([0x05, 0x0D, 0x09, 0x04, 0xA1, 0x01]) + finger + finger + bytearray([0xC0])
    for lo, hi, value in ((0x2F, 0x30, width), (0x3C, 0x3D, height),
                          (0x6E, 0x6F, width), (0x7B, 0x7C, height)):
        if result[lo:hi + 1] != b"\xff\x7f":
            raise ValueError(f"descriptor geometry patch site {lo:#x} changed")
        result[lo] = value & 0xFF
        result[hi] = value >> 8
    if len(result) != 133:
        raise ValueError(f"unexpected multi-touch descriptor length: {len(result)}")
    return bytes(result)


def main() -> int:
    if not PLIST.exists():
        print(f"FATAL: {PLIST} not found", file=sys.stderr)
        return 2
    with PLIST.open("rb") as f:
        info = plistlib.load(f)
    devices = info.get("hidDevices", [])
    touchscreen = next((d for d in devices if d.get("name") == "CarLink Touchscreen"), None)
    if touchscreen is None:
        print("FATAL: CarLink Touchscreen HID entry not found", file=sys.stderr)
        return 2
    display = info.get("displays", [{}])[0]
    width, height = int(display["widthPixels"]), int(display["heightPixels"])
    want_multi = "--revert" not in sys.argv
    wanted = descriptor(width, height, want_multi)
    current = touchscreen.get("hidDescriptor")
    if "--check" in sys.argv:
        ok = current == wanted
        print(f"touchscreen descriptor: {len(current or b'')} B; expected {len(wanted)} B — "
              f"{'OK' if ok else 'MISMATCH'}")
        return 0 if ok else 1
    if current == wanted:
        print(f"already {'multi-touch' if want_multi else 'single-touch'} ({len(wanted)} B)")
        return 0
    touchscreen["hidDescriptor"] = wanted
    with PLIST.open("wb") as f:
        plistlib.dump(info, f, fmt=plistlib.FMT_BINARY, sort_keys=True)
    with PLIST.open("rb") as f:
        written = plistlib.load(f)["hidDevices"]
    check = next(d for d in written if d.get("name") == "CarLink Touchscreen")
    if check["hidDescriptor"] != wanted:
        print("FATAL: descriptor verification after write failed", file=sys.stderr)
        return 2
    print(f"wrote {'multi-touch' if want_multi else 'single-touch'} descriptor "
          f"({len(wanted)} B) to {PLIST}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
