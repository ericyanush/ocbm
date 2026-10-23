//! iap2/session.rs — the CarPlay session-start handshake pair: the `CarPlayStartSession` (0x4301)
//! body BUILDER and the `CarPlayAvailability` (0x4300) body PARSER.
//!
//! These are the two messages Apple defines for negotiating a CarPlay session start over iAP2 once
//! identify + auth complete. Names/ids/types are Apple-authoritative (decoded from
//! `CarPlaySimulator.devicekitplugin`, `iap2messages.json`):
//!
//! - `CarPlayAvailability` (0x4300, source=Device): the iPhone tells the accessory which CarPlay
//!   bearers are available (Wired / Wireless / ThemeAssets), each an `Available` bool plus the
//!   transport identifier the accessory advertised in its IdentificationInformation transport
//!   component. We PARSE this.
//! - `CarPlayStartSession` (0x4301, source=Accessory): the accessory asks the device to start a
//!   CarPlay session on a specific bearer, carrying the WirelessAttributes (SSID/passphrase/channel/
//!   IP/security), Port, DeviceIdentifier, PublicKey, versions, AssetInformation, and the
//!   SupportsMutualAuthentication flag. We BUILD this.
//!
//! The live drivers do not send this message. Previous tests declared the session-start pair
//! without implementing the resulting handshake and stalled session setup (see `message.rs`
//! and docs/carplay/02_SESSION_LIFECYCLE.md). The current wired bearer uses the USB transport's
//! `TransportSupportsCarPlay` flag; wireless uses the 0x5702/0x5703 credential exchange and mDNS.
//! Completing this serializer does not enable either driver's 0x4301 handshake. Those historical
//! declaration-only tests do not establish that a complete wireless StartSession flow cannot work.
//!
//! Param ids below are Apple's; they are not (yet) mirrored in `spec.rs` (which only carries the
//! IdentificationInformation / NowPlaying / RouteGuidance / CallState param submodules) — see the
//! coordinator request in the round-2 report.

use crate::message::Tlv;
use std::net::{IpAddr, Ipv6Addr};

/// Parameter ids of `CarPlayStartSession` (0x4301, source=Accessory). Apple's authoritative names.
pub mod start_session {
    /// WiredAttributes — `group`.
    pub const WIRED_ATTRIBUTES: u16 = 0;
    /// WirelessAttributes — `group`.
    pub const WIRELESS_ATTRIBUTES: u16 = 1;
    /// Port — `uint32`.
    pub const PORT: u16 = 2;
    /// DeviceIdentifier — `utf8`.
    pub const DEVICE_IDENTIFIER: u16 = 3;
    /// PublicKey — `utf8`.
    pub const PUBLIC_KEY: u16 = 4;
    /// SourceVersion — `utf8`.
    pub const SOURCE_VERSION: u16 = 5;
    /// CarPlaySDKVersion — `utf8`.
    pub const CAR_PLAY_SDK_VERSION: u16 = 6;
    /// AssetInformation — `group`.
    pub const ASSET_INFORMATION: u16 = 7;
    /// SupportsMutualAuthentication — `bool`.
    pub const SUPPORTS_MUTUAL_AUTHENTICATION: u16 = 8;

    /// Sub-parameter ids of WiredAttributes (param 0).
    pub mod wired {
        /// IPAddress — repeated `utf8` (accessory IPv6 addresses, no zone index).
        pub const IP_ADDRESS: u16 = 0;
    }
    /// Sub-parameter ids of WirelessAttributes (param 1).
    pub mod wireless {
        /// WiFiSSID — `utf8`.
        pub const WIFI_SSID: u16 = 0;
        /// Passphrase — `utf8`.
        pub const PASSPHRASE: u16 = 1;
        /// Channel — `uint8` (primary Wi-Fi channel for 40/80MHz, operating channel for 20MHz).
        pub const CHANNEL: u16 = 2;
        /// IPAddress — repeated `utf8` (accessory Wi-Fi addresses, no zone index).
        pub const IP_ADDRESS: u16 = 3;
        /// SecurityType — `enum` (`CarPlayStartSessionWiFiSecurityType`).
        pub const SECURITY_TYPE: u16 = 4;
        /// SecurityType::WPA3 Personal Transition Mode (values 0..=2 are Reserved).
        pub const SECURITY_TYPE_WPA3_PERSONAL_TRANSITION: u8 = 3;
        /// SecurityType::WPA3 Personal Only.
        pub const SECURITY_TYPE_WPA3_PERSONAL_ONLY: u8 = 4;
    }
    /// Sub-parameter ids of AssetInformation (param 7).
    pub mod asset {
        /// AssetIdentifier — `utf8`.
        pub const ASSET_IDENTIFIER: u16 = 0;
        /// AssetVersion — `int32`.
        pub const ASSET_VERSION: u16 = 1;
    }
}

/// Parameter ids of `CarPlayAvailability` (0x4300, source=Device). Apple's authoritative names.
pub mod availability {
    /// WiredAttributes — `group`.
    pub const WIRED_ATTRIBUTES: u16 = 0;
    /// WirelessAttributes — `group`.
    pub const WIRELESS_ATTRIBUTES: u16 = 1;
    /// ThemeAssetsAttributes — `group`.
    pub const THEME_ASSETS_ATTRIBUTES: u16 = 2;

    /// Sub-parameter ids of WiredAttributes (param 0).
    pub mod wired {
        /// Available — `bool`.
        pub const AVAILABLE: u16 = 0;
        /// USBTransportIdentifier — `utf8`.
        pub const USB_TRANSPORT_IDENTIFIER: u16 = 1;
    }
    /// Sub-parameter ids of WirelessAttributes (param 1).
    pub mod wireless {
        /// Available — `bool`.
        pub const AVAILABLE: u16 = 0;
        /// BluetoothTransportIdentifier — `utf8`.
        pub const BLUETOOTH_TRANSPORT_IDENTIFIER: u16 = 1;
    }
    /// Sub-parameter ids of ThemeAssetsAttributes (param 2).
    pub mod theme_assets {
        /// Available — `bool`.
        pub const AVAILABLE: u16 = 0;
    }
}

// ============================================================================================
// CarPlayStartSession (0x4301) BUILDER — WIRELESS session-start / future use only (see header).
// ============================================================================================

/// WirelessAttributes group (`CarPlayStartSession` param 1) — the Wi-Fi bearer the accessory offers.
#[derive(Debug, Clone)]
pub struct WirelessAttributes<'a> {
    /// WiFiSSID (sub 0).
    pub wifi_ssid: &'a str,
    /// Passphrase (sub 1).
    pub passphrase: &'a str,
    /// Channel (sub 2) — primary/operating Wi-Fi channel.
    pub channel: u8,
    /// IPAddress (sub 3) — one or more receiver addresses reachable on the Wi-Fi interface.
    /// Each address is emitted as its own UTF-8 TLV, without an IPv6 zone index. Address
    /// selection is the caller's responsibility; never include unrelated interfaces.
    pub ip_addresses: &'a [IpAddr],
    /// SecurityType (sub 4). Preserve the supplied byte, including deployed WPA2 value 2;
    /// this message's enum is not the same as the 0x5703 security enum.
    pub security_type: u8,
}

/// AssetInformation group (`CarPlayStartSession` param 7).
#[derive(Debug, Clone)]
pub struct AssetInformation<'a> {
    /// AssetIdentifier (sub 0).
    pub asset_identifier: &'a str,
    /// AssetVersion (sub 1).
    pub asset_version: i32,
}

/// A `CarPlayStartSession` (0x4301) body. Port, DeviceIdentifier, PublicKey and SourceVersion
/// (params 2–5) are required, as documented in docs/carplay/02_SESSION_LIFECYCLE.md §4.2.
/// The bearer groups and params 6–8 are optional. Every present bearer group must contain
/// at least one IPAddress. Use [`Self::new`] to supply the mandatory fields, then set the
/// optional groups needed by the chosen bearer. This builder does not initiate a session.
#[derive(Debug, Clone)]
pub struct CarPlayStartSession<'a> {
    /// WiredAttributes.IPAddress (param 0 → repeated sub 0), with no IPv6 zone index.
    pub wired_ip_addresses: Option<&'a [Ipv6Addr]>,
    /// WirelessAttributes (param 1), if declaring a wireless bearer.
    pub wireless: Option<WirelessAttributes<'a>>,
    /// Port (param 2) — receiver TCP port; emitted as uint32.
    pub port: u32,
    /// DeviceIdentifier (param 3).
    pub device_identifier: &'a str,
    /// PublicKey (param 4).
    pub public_key: &'a str,
    /// SourceVersion (param 5).
    pub source_version: &'a str,
    /// CarPlaySDKVersion (param 6).
    pub car_play_sdk_version: Option<&'a str>,
    /// AssetInformation (param 7).
    pub asset: Option<AssetInformation<'a>>,
    /// SupportsMutualAuthentication (param 8).
    pub supports_mutual_authentication: Option<bool>,
}

/// Invalid input to the session-start serializer. Errors name fields, never credential values.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum BuildError {
    InvalidPort(u32),
    EmptyField(&'static str),
    InteriorNul(&'static str),
    MissingIpAddresses(&'static str),
    TooLong(&'static str),
}

impl std::fmt::Display for BuildError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::InvalidPort(port) => write!(f, "invalid receiver TCP port: {port}"),
            Self::EmptyField(field) => write!(f, "{field} must not be empty"),
            Self::InteriorNul(field) => write!(f, "{field} contains an embedded NUL"),
            Self::MissingIpAddresses(field) => {
                write!(f, "{field} requires at least one IP address")
            }
            Self::TooLong(field) => write!(f, "{field} exceeds the iAP2 length limit"),
        }
    }
}

impl std::error::Error for BuildError {}

impl<'a> CarPlayStartSession<'a> {
    pub fn new(
        port: u32,
        device_identifier: &'a str,
        public_key: &'a str,
        source_version: &'a str,
    ) -> Self {
        Self {
            wired_ip_addresses: None,
            wireless: None,
            port,
            device_identifier,
            public_key,
            source_version,
            car_play_sdk_version: None,
            asset: None,
            supports_mutual_authentication: None,
        }
    }

    /// Serialize to the `CarPlayStartSession` (0x4301) body. Params are emitted in ascending id
    /// order; absent options are skipped. Invalid inputs return an error instead of emitting
    /// missing mandatory values, empty address groups, or truncated 16-bit lengths.
    ///
    /// The live drivers do not call this builder; enabling a session-start handshake is separate.
    pub fn build(&self) -> Result<Vec<u8>, BuildError> {
        use start_session as p;
        if self.port == 0 || self.port > u16::MAX as u32 {
            return Err(BuildError::InvalidPort(self.port));
        }
        let mut body = Tlv::new();

        if let Some(addresses) = self.wired_ip_addresses {
            if addresses.is_empty() {
                return Err(BuildError::MissingIpAddresses("WiredAttributes"));
            }
            let mut g = Tlv::new();
            for address in addresses {
                g.str(p::wired::IP_ADDRESS, &address.to_string()); // repeated sub 0
            }
            checked_group(&mut body, p::WIRED_ATTRIBUTES, &g, "WiredAttributes")?;
        }
        if let Some(w) = &self.wireless {
            if w.ip_addresses.is_empty() {
                return Err(BuildError::MissingIpAddresses("WirelessAttributes"));
            }
            let mut g = Tlv::new();
            checked_str(
                &mut g,
                p::wireless::WIFI_SSID,
                w.wifi_ssid,
                "WiFiSSID",
                false,
            )?;
            checked_str(
                &mut g,
                p::wireless::PASSPHRASE,
                w.passphrase,
                "Passphrase",
                true,
            )?;
            g.u8v(p::wireless::CHANNEL, w.channel); // sub 2
            for address in w.ip_addresses {
                g.str(p::wireless::IP_ADDRESS, &address.to_string()); // repeated sub 3
            }
            g.u8v(p::wireless::SECURITY_TYPE, w.security_type); // sub 4 (enum → 1 byte)
            checked_group(&mut body, p::WIRELESS_ATTRIBUTES, &g, "WirelessAttributes")?;
        }
        body.u32v(p::PORT, self.port); // param 2 — REQUIRED, always emitted
        checked_str(
            &mut body,
            p::DEVICE_IDENTIFIER,
            self.device_identifier,
            "DeviceIdentifier",
            false,
        )?;
        checked_str(
            &mut body,
            p::PUBLIC_KEY,
            self.public_key,
            "PublicKey",
            false,
        )?;
        checked_str(
            &mut body,
            p::SOURCE_VERSION,
            self.source_version,
            "SourceVersion",
            false,
        )?;
        if let Some(v) = self.car_play_sdk_version {
            checked_str(
                &mut body,
                p::CAR_PLAY_SDK_VERSION,
                v,
                "CarPlaySDKVersion",
                false,
            )?;
        }
        if let Some(a) = &self.asset {
            let mut g = Tlv::new();
            checked_str(
                &mut g,
                p::asset::ASSET_IDENTIFIER,
                a.asset_identifier,
                "AssetIdentifier",
                false,
            )?;
            g.i32v(p::asset::ASSET_VERSION, a.asset_version); // sub 1
            checked_group(&mut body, p::ASSET_INFORMATION, &g, "AssetInformation")?;
        }
        if let Some(b) = self.supports_mutual_authentication {
            body.boolv(p::SUPPORTS_MUTUAL_AUTHENTICATION, b); // param 8
        }
        // Link::build_msg adds a 6-byte control header and 10 bytes of link framing. Check
        // the outer length too: individually valid TLVs can still overflow a link frame.
        if body.0.len() > u16::MAX as usize - 16 {
            return Err(BuildError::TooLong("CarPlayStartSession"));
        }
        Ok(body.0)
    }
}

fn checked_str(
    out: &mut Tlv,
    id: u16,
    value: &str,
    field: &'static str,
    allow_empty: bool,
) -> Result<(), BuildError> {
    if !allow_empty && value.is_empty() {
        return Err(BuildError::EmptyField(field));
    }
    if value.contains('\0') {
        return Err(BuildError::InteriorNul(field));
    }
    // Four bytes of TLV header plus the string's terminating NUL.
    if value.len() > u16::MAX as usize - 5 {
        return Err(BuildError::TooLong(field));
    }
    out.str(id, value);
    Ok(())
}

fn checked_group(
    out: &mut Tlv,
    id: u16,
    group: &Tlv,
    field: &'static str,
) -> Result<(), BuildError> {
    if group.0.len() > u16::MAX as usize - 4 {
        return Err(BuildError::TooLong(field));
    }
    out.bytes(id, &group.0);
    Ok(())
}

// ============================================================================================
// CarPlayAvailability (0x4300) PARSER.
// ============================================================================================

/// One CarPlay bearer's availability as reported by the device (`Available` + its transport id).
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct BearerAvailability {
    /// The `Available` bool (sub 0). `None` if the device omitted it.
    pub available: Option<bool>,
    /// The transport identifier string (WiredAttributes.USBTransportIdentifier /
    /// WirelessAttributes.BluetoothTransportIdentifier). `None` for the wired/wireless groups if
    /// omitted; always `None` for the theme-assets group (which carries no identifier).
    pub transport_identifier: Option<String>,
}

/// Parsed `CarPlayAvailability` (0x4300) body. Each group is present only if the device sent it.
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct CarPlayAvailability {
    /// WiredAttributes (param 0).
    pub wired: Option<BearerAvailability>,
    /// WirelessAttributes (param 1).
    pub wireless: Option<BearerAvailability>,
    /// ThemeAssetsAttributes (param 2) — only an `Available` bool, no transport identifier.
    pub theme_assets: Option<BearerAvailability>,
}

/// Failure reason for `parse_carplay_availability`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ParseError {
    /// A TLV group's declared length is < 4 or overruns the buffer at the given offset.
    Truncated(usize),
}

/// Walk a flat TLV buffer (`[group_len BE16][pid BE16][value(group_len-4)]`…) yielding
/// `(param_id, value)` pairs. Returns `Err` on a malformed length.
fn walk_tlvs(buf: &[u8]) -> Result<Vec<(u16, &[u8])>, ParseError> {
    let mut out = Vec::new();
    let mut i = 0;
    while i + 4 <= buf.len() {
        let glen = u16::from_be_bytes([buf[i], buf[i + 1]]) as usize;
        let pid = u16::from_be_bytes([buf[i + 2], buf[i + 3]]);
        if glen < 4 || i + glen > buf.len() {
            return Err(ParseError::Truncated(i));
        }
        out.push((pid, &buf[i + 4..i + glen]));
        i += glen;
    }
    Ok(out)
}

/// Decode an iAP2 `bool` value (single byte, non-zero = true). Empty value decodes as `None`.
fn decode_bool(v: &[u8]) -> Option<bool> {
    v.first().map(|&b| b != 0)
}

/// Decode an iAP2 NUL-terminated `utf8` value into an owned `String` (lossy on invalid UTF-8),
/// stripping the trailing NUL if present.
fn decode_str(v: &[u8]) -> String {
    let end = v.iter().position(|&b| b == 0).unwrap_or(v.len());
    String::from_utf8_lossy(&v[..end]).into_owned()
}

/// Parse a `CarPlayAvailability` (0x4300) body. Unknown top-level params and unknown sub-params are
/// ignored (forward-compatible); a malformed TLV length is a hard error.
pub fn parse_carplay_availability(body: &[u8]) -> Result<CarPlayAvailability, ParseError> {
    let mut out = CarPlayAvailability::default();
    for (pid, value) in walk_tlvs(body)? {
        match pid {
            availability::WIRED_ATTRIBUTES => {
                let mut b = BearerAvailability::default();
                for (sub, sv) in walk_tlvs(value)? {
                    match sub {
                        availability::wired::AVAILABLE => b.available = decode_bool(sv),
                        availability::wired::USB_TRANSPORT_IDENTIFIER => {
                            b.transport_identifier = Some(decode_str(sv))
                        }
                        _ => {}
                    }
                }
                out.wired = Some(b);
            }
            availability::WIRELESS_ATTRIBUTES => {
                let mut b = BearerAvailability::default();
                for (sub, sv) in walk_tlvs(value)? {
                    match sub {
                        availability::wireless::AVAILABLE => b.available = decode_bool(sv),
                        availability::wireless::BLUETOOTH_TRANSPORT_IDENTIFIER => {
                            b.transport_identifier = Some(decode_str(sv))
                        }
                        _ => {}
                    }
                }
                out.wireless = Some(b);
            }
            availability::THEME_ASSETS_ATTRIBUTES => {
                let mut b = BearerAvailability::default();
                for (sub, sv) in walk_tlvs(value)? {
                    if sub == availability::theme_assets::AVAILABLE {
                        b.available = decode_bool(sv);
                    }
                }
                out.theme_assets = Some(b);
            }
            _ => {}
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn start_session_wireless_body_byte_layout() {
        let addresses = ["192.168.5.1".parse().unwrap(), "fe80::1".parse().unwrap()];
        let msg = CarPlayStartSession {
            wireless: Some(WirelessAttributes {
                wifi_ssid: "AP",
                passphrase: "pw",
                channel: 36,
                ip_addresses: &addresses,
                security_type: start_session::wireless::SECURITY_TYPE_WPA3_PERSONAL_ONLY,
            }),
            supports_mutual_authentication: Some(true),
            ..CarPlayStartSession::new(7000, "dev", "pk", "320.17")
        };
        let body = msg.build().unwrap();

        // Expected, param by param (ascending id).
        let mut exp = Vec::new();

        // param 1 WirelessAttributes group.
        let mut g = Vec::new();
        g.extend_from_slice(&[0x00, 0x07, 0x00, 0x00, b'A', b'P', 0x00]); // str(0,"AP")
        g.extend_from_slice(&[0x00, 0x07, 0x00, 0x01, b'p', b'w', 0x00]); // str(1,"pw")
        g.extend_from_slice(&[0x00, 0x05, 0x00, 0x02, 36]); // u8v(2,36)
        g.extend_from_slice(&[0x00, 0x10, 0x00, 0x03]); // repeated IPAddress: IPv4
        g.extend_from_slice(b"192.168.5.1\0");
        g.extend_from_slice(&[0x00, 0x0C, 0x00, 0x03]); // str(3,"fe80::1"): len 4+8=12
        g.extend_from_slice(b"fe80::1\0");
        g.extend_from_slice(&[0x00, 0x05, 0x00, 0x04, 0x04]); // u8v(4, WPA3 Only=4)
        exp.extend_from_slice(&[0x00, (4 + g.len()) as u8, 0x00, 0x01]); // group hdr, param 1
        exp.extend_from_slice(&g);

        // param 2 Port uint32 = 7000 (0x00001B58).
        exp.extend_from_slice(&[0x00, 0x08, 0x00, 0x02, 0x00, 0x00, 0x1B, 0x58]);
        // param 3 DeviceIdentifier "dev".
        exp.extend_from_slice(&[0x00, 0x08, 0x00, 0x03, b'd', b'e', b'v', 0x00]);
        // params 4/5 are mandatory even when no optional SDK/assets are supplied.
        exp.extend_from_slice(b"\x00\x07\x00\x04pk\0");
        exp.extend_from_slice(b"\x00\x0B\x00\x05320.17\0");
        // param 8 SupportsMutualAuthentication = true.
        exp.extend_from_slice(&[0x00, 0x05, 0x00, 0x08, 0x01]);

        assert_eq!(body, exp);
    }

    #[test]
    fn start_session_with_wired_and_asset_groups() {
        let addresses = ["fe80::2".parse().unwrap(), "fe80::3".parse().unwrap()];
        let msg = CarPlayStartSession {
            wired_ip_addresses: Some(&addresses),
            car_play_sdk_version: Some("1.0"),
            asset: Some(AssetInformation {
                asset_identifier: "a",
                asset_version: -1,
            }),
            supports_mutual_authentication: Some(false),
            ..CarPlayStartSession::new(7000, "dev", "pk", "320.17")
        };
        let body = msg.build().unwrap();

        let mut exp = Vec::new();
        // param 0 WiredAttributes { IPAddress "fe80::2" }.
        let mut g0 = Vec::new();
        g0.extend_from_slice(&[0x00, 0x0C, 0x00, 0x00]); // str(0,"fe80::2") len 12
        g0.extend_from_slice(b"fe80::2\0");
        g0.extend_from_slice(&[0x00, 0x0C, 0x00, 0x00]); // repeated IPAddress
        g0.extend_from_slice(b"fe80::3\0");
        exp.extend_from_slice(&[0x00, (4 + g0.len()) as u8, 0x00, 0x00]);
        exp.extend_from_slice(&g0);
        // param 2 Port uint32 = 7000 (0x00001B58) — REQUIRED, always emitted (#140/L5).
        exp.extend_from_slice(&[0x00, 0x08, 0x00, 0x02, 0x00, 0x00, 0x1B, 0x58]);
        exp.extend_from_slice(b"\x00\x08\x00\x03dev\0");
        exp.extend_from_slice(b"\x00\x07\x00\x04pk\0");
        exp.extend_from_slice(b"\x00\x0B\x00\x05320.17\0");
        // param 6 SDK version.
        exp.extend_from_slice(b"\x00\x08\x00\x061.0\0");
        // param 7 AssetInformation { AssetIdentifier "a", AssetVersion -1 }.
        let mut g7 = Vec::new();
        g7.extend_from_slice(&[0x00, 0x06, 0x00, 0x00, b'a', 0x00]); // str(0,"a") len 6
        g7.extend_from_slice(&[0x00, 0x08, 0x00, 0x01, 0xFF, 0xFF, 0xFF, 0xFF]); // i32(1,-1)
        exp.extend_from_slice(&[0x00, (4 + g7.len()) as u8, 0x00, 0x07]);
        exp.extend_from_slice(&g7);
        exp.extend_from_slice(&[0x00, 0x05, 0x00, 0x08, 0x00]); // explicit false is not omitted

        assert_eq!(body, exp);
    }

    #[test]
    fn start_session_required_fields_without_optional_groups() {
        let body = CarPlayStartSession::new(7011, "device", "public-key", "320.17")
            .build()
            .unwrap();
        let fields = walk_tlvs(&body).unwrap();
        assert_eq!(
            fields.iter().map(|(id, _)| *id).collect::<Vec<_>>(),
            [2, 3, 4, 5]
        );
        assert_eq!(fields[0].1, 7011u32.to_be_bytes());
        assert_eq!(fields[1].1, b"device\0");
        assert_eq!(fields[2].1, b"public-key\0");
        assert_eq!(fields[3].1, b"320.17\0");
    }

    #[test]
    fn start_session_rejects_missing_or_invalid_required_values() {
        for port in [0, 65536, u32::MAX] {
            assert_eq!(
                CarPlayStartSession::new(port, "dev", "pk", "320.17").build(),
                Err(BuildError::InvalidPort(port))
            );
        }
        for (device, key, version, field) in [
            ("", "pk", "320.17", "DeviceIdentifier"),
            ("dev", "", "320.17", "PublicKey"),
            ("dev", "pk", "", "SourceVersion"),
        ] {
            assert_eq!(
                CarPlayStartSession::new(7011, device, key, version).build(),
                Err(BuildError::EmptyField(field))
            );
        }
        assert_eq!(
            CarPlayStartSession::new(7011, "dev\0other", "pk", "320.17").build(),
            Err(BuildError::InteriorNul("DeviceIdentifier"))
        );
    }

    #[test]
    fn start_session_requires_addresses_in_each_present_bearer() {
        let mut msg = CarPlayStartSession::new(7011, "dev", "pk", "320.17");
        msg.wired_ip_addresses = Some(&[]);
        assert_eq!(
            msg.build(),
            Err(BuildError::MissingIpAddresses("WiredAttributes"))
        );
        msg.wired_ip_addresses = None;
        msg.wireless = Some(WirelessAttributes {
            wifi_ssid: "AP",
            passphrase: "pw",
            channel: 36,
            ip_addresses: &[],
            security_type: 2,
        });
        assert_eq!(
            msg.build(),
            Err(BuildError::MissingIpAddresses("WirelessAttributes"))
        );
    }

    #[test]
    fn start_session_preserves_security_value_and_empty_passphrase() {
        let addresses = ["fe80::1".parse().unwrap()];
        let mut msg = CarPlayStartSession::new(7011, "dev", "pk", "320.17");
        msg.wireless = Some(WirelessAttributes {
            wifi_ssid: "AP",
            passphrase: "",
            channel: 149,
            ip_addresses: &addresses,
            security_type: 2, // GM's deployed WPA2 value; do not substitute the 0x5703 enum.
        });
        let body = msg.build().unwrap();
        let fields = walk_tlvs(&body).unwrap();
        let wireless = walk_tlvs(fields[0].1).unwrap();
        assert_eq!(wireless[1], (1, &b"\0"[..]));
        assert_eq!(wireless[4], (4, &b"\x02"[..]));
        msg.wireless.as_mut().unwrap().passphrase = "secret\0suffix";
        assert_eq!(msg.build(), Err(BuildError::InteriorNul("Passphrase")));
    }

    #[test]
    fn start_session_rejects_overflowing_tlv_group_and_frame_lengths() {
        let oversized = "x".repeat(u16::MAX as usize - 4);
        assert_eq!(
            CarPlayStartSession::new(7011, "dev", &oversized, "320.17").build(),
            Err(BuildError::TooLong("PublicKey"))
        );

        let addresses = vec!["fe80::1".parse().unwrap(); 6000];
        let mut msg = CarPlayStartSession::new(7011, "dev", "pk", "320.17");
        msg.wireless = Some(WirelessAttributes {
            wifi_ssid: "AP",
            passphrase: "pw",
            channel: 36,
            ip_addresses: &addresses,
            security_type: 2,
        });
        assert_eq!(msg.build(), Err(BuildError::TooLong("WirelessAttributes")));

        let field = "x".repeat(32760); // each TLV fits; together they overflow the link frame.
        assert_eq!(
            CarPlayStartSession::new(7011, &field, &field, "320.17").build(),
            Err(BuildError::TooLong("CarPlayStartSession"))
        );
    }

    #[test]
    fn availability_roundtrip_all_bearers() {
        // Hand-build a 0x4300 body: wired{available=true, usbId="USB0"},
        // wireless{available=false, btId="BT1"}, themeAssets{available=true}.
        let mut body = Tlv::new();
        {
            let mut g = Tlv::new();
            g.boolv(availability::wired::AVAILABLE, true);
            g.str(availability::wired::USB_TRANSPORT_IDENTIFIER, "USB0");
            body.bytes(availability::WIRED_ATTRIBUTES, &g.0);
        }
        {
            let mut g = Tlv::new();
            g.boolv(availability::wireless::AVAILABLE, false);
            g.str(
                availability::wireless::BLUETOOTH_TRANSPORT_IDENTIFIER,
                "BT1",
            );
            body.bytes(availability::WIRELESS_ATTRIBUTES, &g.0);
        }
        {
            let mut g = Tlv::new();
            g.boolv(availability::theme_assets::AVAILABLE, true);
            body.bytes(availability::THEME_ASSETS_ATTRIBUTES, &g.0);
        }

        let parsed = parse_carplay_availability(&body.0).expect("well-formed body parses");
        assert_eq!(
            parsed.wired,
            Some(BearerAvailability {
                available: Some(true),
                transport_identifier: Some("USB0".to_string()),
            })
        );
        assert_eq!(
            parsed.wireless,
            Some(BearerAvailability {
                available: Some(false),
                transport_identifier: Some("BT1".to_string()),
            })
        );
        assert_eq!(
            parsed.theme_assets,
            Some(BearerAvailability {
                available: Some(true),
                transport_identifier: None
            })
        );
    }

    #[test]
    fn availability_absent_groups_are_none() {
        // Only the wireless group present.
        let mut body = Tlv::new();
        let mut g = Tlv::new();
        g.boolv(availability::wireless::AVAILABLE, true);
        body.bytes(availability::WIRELESS_ATTRIBUTES, &g.0);

        let parsed = parse_carplay_availability(&body.0).unwrap();
        assert!(parsed.wired.is_none());
        assert!(parsed.theme_assets.is_none());
        assert_eq!(parsed.wireless.unwrap().available, Some(true));
    }

    #[test]
    fn availability_truncated_length_is_error() {
        // group_len says 0x0020 but only a few bytes follow.
        let bad = [0x00, 0x20, 0x00, 0x00, 0x01];
        assert!(matches!(
            parse_carplay_availability(&bad),
            Err(ParseError::Truncated(0))
        ));
    }

    #[test]
    fn availability_ignores_unknown_params() {
        let mut body = Tlv::new();
        body.u16v(0x00FF, 0x1234); // unknown top-level param
        let parsed = parse_carplay_availability(&body.0).unwrap();
        assert_eq!(parsed, CarPlayAvailability::default());
    }
}
