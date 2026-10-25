//! One-purpose stock USB bridge for the CPC200-CCPA root-shell bootstrap.
//! Wire layout follows LIVI's bootstrap.rs / wire.rs; only fixed OCBM paths are writable.

use rusb::{Context, Direction, TransferType, UsbContext};
use std::thread::sleep;
use std::time::Duration;

const VID: u16 = 0x1314;
const PIDS: [u16; 2] = [0x1520, 0x1521];
const OPEN: u32 = 0x01;
const SEND_FILE: u32 = 0x99;
const PROFILE: &str = include_str!("../../../tools/stock_shell/profile");
const BOOT: &str = include_str!("../../../tools/stock_shell/boot.sh");

fn frame(kind: u32, payload: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(16 + payload.len());
    for word in [0x55aa55aa_u32, payload.len() as u32, kind, !kind] {
        out.extend_from_slice(&word.to_le_bytes());
    }
    out.extend_from_slice(payload);
    out
}

fn open_payload() -> Vec<u8> {
    [800_u32, 480, 30, 5, 49152, 2, 2]
        .into_iter()
        .flat_map(u32::to_le_bytes)
        .collect()
}

fn send_file_payload(path: &str, contents: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(9 + path.len() + contents.len());
    out.extend_from_slice(&((path.len() + 1) as u32).to_le_bytes());
    out.extend_from_slice(path.as_bytes());
    out.push(0);
    out.extend_from_slice(&(contents.len() as u32).to_le_bytes());
    out.extend_from_slice(contents);
    out
}

fn write_fixed_file(context: &Context, path: &str, contents: &[u8]) -> Result<(), String> {
    let devices = context.devices().map_err(|e| e.to_string())?;
    let matches: Vec<_> = devices
        .iter()
        .filter(|dev| {
            dev.device_descriptor()
                .is_ok_and(|d| d.vendor_id() == VID && PIDS.contains(&d.product_id()))
        })
        .collect();
    let [device] = matches.as_slice() else {
        return Err(format!("expected one stock CCPA on USB, found {}", matches.len()));
    };
    let desc = device.active_config_descriptor().map_err(|e| e.to_string())?;
    let (iface, ep_out) = desc
        .interfaces()
        .flat_map(|iface| iface.descriptors())
        .find_map(|alt| {
            let out = alt.endpoint_descriptors().find(|ep| {
                ep.transfer_type() == TransferType::Bulk && ep.direction() == Direction::Out
            })?;
            let has_in = alt.endpoint_descriptors().any(|ep| {
                ep.transfer_type() == TransferType::Bulk && ep.direction() == Direction::In
            });
            has_in.then_some((alt.interface_number(), out.address()))
        })
        .ok_or("stock accessory bulk interface not found")?;
    let handle = device.open().map_err(|e| format!("open: {e}"))?;
    handle
        .claim_interface(iface)
        .map_err(|e| format!("claim interface {iface}: {e}"))?;
    for (kind, data) in [
        (OPEN, open_payload()),
        (SEND_FILE, send_file_payload(path, contents)),
    ] {
        let bytes = frame(kind, &data);
        let written = handle
            .write_bulk(ep_out, &bytes, Duration::from_secs(5))
            .map_err(|e| format!("USB write {path}: {e}"))?;
        if written != bytes.len() {
            return Err(format!("short USB write {path}: {written}/{}", bytes.len()));
        }
    }
    sleep(Duration::from_millis(500));
    println!("sent {} bytes to {path}", contents.len());
    Ok(())
}

fn main() -> Result<(), String> {
    let mut args = std::env::args().skip(1);
    let command = args.next();
    if args.next().is_some() || !matches!(command.as_deref(), Some("scan" | "bootstrap")) {
        return Err("usage: ocbm-stock-shell scan|bootstrap".into());
    }
    let context = Context::new().map_err(|e| e.to_string())?;
    if command.as_deref() == Some("scan") {
        let devices = context.devices().map_err(|e| e.to_string())?;
        for device in devices.iter() {
            let d = device.device_descriptor().map_err(|e| e.to_string())?;
            if d.vendor_id() == VID && PIDS.contains(&d.product_id()) {
                println!("{:04x}:{:04x} stock CCPA", d.vendor_id(), d.product_id());
            }
        }
        return Ok(());
    }
    // The boot script is written first. If the second write fails, stock boot is unaffected.
    write_fixed_file(&context, "/script/ocbm_stock_shell.sh", BOOT.as_bytes())?;
    write_fixed_file(&context, "/etc/profile", PROFILE.as_bytes())?;
    println!("replug the adapter to start its OCBM root shell on 192.168.50.2:23");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn frames_match_stock_wire_layout() {
        assert_eq!(&frame(0x99, b"x")[..16], &[0xaa, 0x55, 0xaa, 0x55, 1, 0, 0, 0, 0x99, 0, 0, 0, 0x66, 0xff, 0xff, 0xff]);
        let file = send_file_payload("/tmp/x", b"hi");
        assert_eq!(u32::from_le_bytes(file[..4].try_into().unwrap()), 7);
        assert_eq!(&file[4..11], b"/tmp/x\0");
    }
}
