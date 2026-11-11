//! Optional BLE provisioning channel. The GATT write requires an encrypted link.
//! Credentials are applied to NetworkManager and never echoed to the journal.
use bluer::{
    adv::Advertisement,
    agent::Agent,
    gatt::local::{
        Application, Characteristic, CharacteristicWrite, CharacteristicWriteMethod, ReqError,
        Service,
    },
};
use futures::FutureExt;
use serde_json::Value;
use std::process::Command;
use uuid::Uuid;

pub const SERVICE: Uuid = Uuid::from_u128(0x9f2af86a_9c35_4428_995e_3df4dcad1150);
pub const CREDENTIALS: Uuid = Uuid::from_u128(0x9f2af86a_9c35_4428_995e_3df4dcad1151);

pub(crate) fn configure(data: &[u8]) -> Result<(), String> {
    if data.len() > 220 {
        return Err("provisioning packet too long".into());
    }
    let value: Value = serde_json::from_slice(data).map_err(|_| "invalid provisioning JSON")?;
    let ssid = value["ssid"].as_str().ok_or("missing SSID")?;
    let password = value["passphrase"].as_str().ok_or("missing passphrase")?;
    if ssid.is_empty()
        || ssid.len() > 32
        || ssid.chars().any(char::is_control)
        || !(8..=63).contains(&password.len())
        || password.chars().any(char::is_control)
    {
        return Err("invalid Wi-Fi credentials".into());
    }
    // Save a profile even when the vehicle is out of range during setup.
    let name = "carlink-vehicle-wifi";
    let existing = Command::new("nmcli")
        .args(["connection", "show", name])
        .output()
        .map_err(|e| e.to_string())?
        .status
        .success();
    if !existing {
        let result = Command::new("nmcli")
            .args([
                "connection",
                "add",
                "type",
                "wifi",
                "ifname",
                "wlan0",
                "con-name",
                name,
                "ssid",
                ssid,
            ])
            .output()
            .map_err(|e| e.to_string())?;
        if !result.status.success() {
            return Err("NetworkManager could not create Wi-Fi profile".into());
        }
    }
    let result = Command::new("nmcli")
        .args([
            "connection",
            "modify",
            name,
            "802-11-wireless.ssid",
            ssid,
            "802-11-wireless-security.key-mgmt",
            "wpa-psk",
            "802-11-wireless-security.psk",
            password,
            "connection.autoconnect",
            "yes",
            "connection.autoconnect-priority",
            "100",
        ])
        .output()
        .map_err(|e| e.to_string())?;
    if !result.status.success() {
        return Err("NetworkManager could not save Wi-Fi credentials".into());
    }
    eprintln!("[pi-provision] vehicle Wi-Fi profile saved; credentials omitted from log");
    std::thread::spawn(|| {
        std::thread::sleep(std::time::Duration::from_secs(2));
        let _ = Command::new("nmcli")
            .args([
                "connection",
                "up",
                "carlink-vehicle-wifi",
                "ifname",
                "wlan0",
            ])
            .output();
    });
    Ok(())
}

pub fn run() -> Result<(), String> {
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .map_err(|e| e.to_string())?;
    runtime.block_on(async {
        let session = bluer::Session::new().await.map_err(|e| e.to_string())?;
        let agent = Agent {
            request_default: true,
            request_confirmation: Some(Box::new(|request| {
                async move {
                    eprintln!(
                        "[pi-bt] numeric comparison auto-confirmed address={}",
                        request.device
                    );
                    Ok(())
                }
                .boxed()
            })),
            ..Default::default()
        };
        let _agent = session
            .register_agent(agent)
            .await
            .map_err(|e| format!("could not register Bluetooth pairing agent: {e}"))?;
        eprintln!("[pi-bt] BlueZ default agent ready; numeric comparison will be auto-confirmed");
        let adapter = session.default_adapter().await.map_err(|e| e.to_string())?;
        adapter.set_powered(true).await.map_err(|e| e.to_string())?;
        adapter
            .set_pairable_timeout(0)
            .await
            .map_err(|e| e.to_string())?;
        adapter
            .set_discoverable_timeout(0)
            .await
            .map_err(|e| e.to_string())?;
        adapter
            .set_pairable(true)
            .await
            .map_err(|e| e.to_string())?;
        adapter
            .set_discoverable(true)
            .await
            .map_err(|e| e.to_string())?;
        let advertisement = Advertisement {
            service_uuids: [SERVICE].into_iter().collect(),
            discoverable: Some(true),
            local_name: Some("Carlink-Pi-Setup".into()),
            ..Default::default()
        };
        let _advertisement = adapter
            .advertise(advertisement)
            .await
            .map_err(|e| e.to_string())?;
        let application = Application {
            services: vec![Service {
                uuid: SERVICE,
                primary: true,
                characteristics: vec![Characteristic {
                    uuid: CREDENTIALS,
                    write: Some(CharacteristicWrite {
                        write: true,
                        encrypt_write: true,
                        method: CharacteristicWriteMethod::Fun(Box::new(|bytes, request| {
                            async move {
                                if request.offset != 0 {
                                    return Err(ReqError::InvalidOffset);
                                }
                                // Keep the D-Bus callback responsive while NetworkManager joins Wi-Fi.
                                tokio::task::spawn_blocking(move || configure(&bytes))
                                    .await
                                    .map_err(|_| ReqError::Failed)?
                                    .map_err(|_| ReqError::Failed)?;
                                Ok(())
                            }
                            .boxed()
                        })),
                        ..Default::default()
                    }),
                    ..Default::default()
                }],
                ..Default::default()
            }],
            ..Default::default()
        };
        let _application = adapter
            .serve_gatt_application(application)
            .await
            .map_err(|e| e.to_string())?;
        eprintln!("[pi-provision] BLE Wi-Fi setup ready");
        futures::future::pending::<()>().await;
        #[allow(unreachable_code)]
        Ok::<(), String>(())
    })
}
