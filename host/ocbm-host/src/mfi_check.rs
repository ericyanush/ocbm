//! Explicit, bounded hardware exercise. Does not subscribe, pair a phone, or change chip reset.
use crate::{p, Link};
use std::{
    collections::HashSet,
    fs::{self, DirBuilder},
    os::unix::fs::DirBuilderExt,
    path::PathBuf,
    process::Command,
    time::{Duration, Instant},
};

struct Verifier {
    dir: PathBuf,
}
impl Verifier {
    fn new(certificate: &[u8]) -> Result<Self, String> {
        let nonce = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map_err(|e| e.to_string())?
            .as_nanos();
        let dir =
            std::env::temp_dir().join(format!("ocbm-mfi-verify-{}-{nonce}", std::process::id()));
        DirBuilder::new()
            .mode(0o700)
            .create(&dir)
            .map_err(|e| e.to_string())?;
        let verifier = Self { dir };
        fs::write(verifier.dir.join("certificate.der"), certificate).map_err(|e| e.to_string())?;
        Self::openssl(
            Command::new("openssl")
                .arg("pkcs7")
                .arg("-inform")
                .arg("DER")
                .arg("-in")
                .arg(verifier.dir.join("certificate.der"))
                .arg("-print_certs")
                .arg("-out")
                .arg(verifier.dir.join("certificate.pem")),
        )?;
        Self::openssl(
            Command::new("openssl")
                .arg("x509")
                .arg("-in")
                .arg(verifier.dir.join("certificate.pem"))
                .arg("-pubkey")
                .arg("-noout")
                .arg("-out")
                .arg(verifier.dir.join("public-key.pem")),
        )?;
        Ok(verifier)
    }
    fn openssl(command: &mut Command) -> Result<(), String> {
        let output = command
            .output()
            .map_err(|e| format!("OpenSSL is required for MFi verification: {e}"))?;
        if output.status.success() {
            Ok(())
        } else {
            Err(format!(
                "OpenSSL verification failed: {}",
                String::from_utf8_lossy(&output.stderr).trim()
            ))
        }
    }
    fn verify(&self, challenge: &[u8], signature: &[u8]) -> Result<(), String> {
        fs::write(self.dir.join("digest.bin"), challenge).map_err(|e| e.to_string())?;
        fs::write(self.dir.join("signature.bin"), signature).map_err(|e| e.to_string())?;
        Self::openssl(
            Command::new("openssl")
                .arg("pkeyutl")
                .arg("-verify")
                .arg("-pubin")
                .arg("-inkey")
                .arg(self.dir.join("public-key.pem"))
                .arg("-sigfile")
                .arg(self.dir.join("signature.bin"))
                .arg("-in")
                .arg(self.dir.join("digest.bin"))
                .arg("-pkeyopt")
                .arg("digest:sha1")
                .arg("-pkeyopt")
                .arg("rsa_padding_mode:pkcs1"),
        )
    }
}
impl Drop for Verifier {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.dir);
    }
}

fn reply(payload: &[u8], tag: u8) -> Result<Option<Vec<u8>>, String> {
    if payload.len() < 4 {
        return Err("short/untagged MFi reply; update adapter firmware".into());
    }
    if payload.last() != Some(&tag) {
        return Ok(None);
    }
    let n = usize::from(u16::from_be_bytes([payload[1], payload[2]]));
    if payload.len() != n + 4 {
        return Err("MFi reply length mismatch".into());
    }
    if payload[0] != 0 {
        return Err(format!("MFi error status={} tag={tag}", payload[0]));
    }
    Ok(Some(payload[3..3 + n].to_vec()))
}

fn exchange(link: &mut Link, op: u8, input: &[u8], tag: &mut u8) -> Result<Vec<u8>, String> {
    *tag = tag.wrapping_add(1);
    let mut request = vec![op, (input.len() >> 8) as u8, input.len() as u8];
    request.extend_from_slice(input);
    request.push(*tag);
    let start = Instant::now();
    if !link.send(p::CH_MFI, p::F_SOM | p::F_EOM, &request) {
        return Err("MFi USB write failed".into());
    }
    let deadline = start + Duration::from_secs(15); // Includes the shared chip lock's 10s ceiling.
    let mut out = vec![0; p::MAX_PAYLOAD];
    while let Some((channel, _, n)) = link.recv(&mut out, deadline) {
        if channel != p::CH_MFI {
            continue;
        }
        if let Some(data) = reply(&out[..n], *tag)? {
            println!(
                "[mfi-check] op={op} tag={tag} bytes={} elapsed_ms={}",
                data.len(),
                start.elapsed().as_millis()
            );
            return Ok(data);
        }
    }
    Err(format!("MFi reply timed out op={op} tag={tag}"))
}

fn check_cert(baseline: &[u8], candidate: &[u8]) -> Result<(), String> {
    if baseline != candidate {
        return Err("certificate changed between chip reads".into());
    }
    Ok(())
}

pub(super) fn run(link: &mut Link, rounds: u64) -> Result<(), String> {
    if !(1..=1000).contains(&rounds) {
        return Err("mfi-check rounds must be 1..1000".into());
    }
    let mut tag = 0;
    let baseline = exchange(link, 1, &[], &mut tag)?;
    if baseline.is_empty() || baseline.len() > 1280 {
        return Err("invalid initial certificate length".into());
    }
    let verifier = Verifier::new(&baseline)?;
    let mut signatures = HashSet::new();
    for round in 0..rounds {
        // The first pair, and the boundary between rounds, exercise back-to-back cert reads.
        check_cert(&baseline, &exchange(link, 1, &[], &mut tag)?)?;
        let mut challenge = [0x5a; 20];
        challenge[..8].copy_from_slice(&round.to_be_bytes());
        let signature = exchange(link, 2, &challenge, &mut tag)?;
        if signature.len() != 128 {
            return Err("signature length is not 128 bytes".into());
        }
        verifier
            .verify(&challenge, &signature)
            .map_err(|e| format!("round {} RSA signature invalid: {e}", round + 1))?;
        if !signatures.insert(signature) {
            return Err("signature repeated for a different challenge".into());
        }
        check_cert(&baseline, &exchange(link, 1, &[], &mut tag)?)?;
        println!(
            "[mfi-check] round {}/{rounds} OK (certificate stable, RSA signature verified)",
            round + 1
        );
    }
    println!("[mfi-check] PASS: {rounds} rounds; {} stable certificate reads; distinct, verified RSA signatures", 1 + 2*rounds);
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn replies_must_have_the_current_tag_exact_length_and_success_status() {
        assert_eq!(reply(&[0, 0, 2, 7, 8, 1], 1).unwrap(), Some(vec![7, 8]));
        assert_eq!(reply(&[0, 0, 2, 7, 8, 1], 2).unwrap(), None);
        assert!(reply(&[0, 0, 2, 7, 1], 1).is_err());
        assert!(reply(&[1, 0, 0, 1], 1).is_err());
        assert!(reply(&[0, 0, 0], 1).is_err());
    }
    #[test]
    fn consistent_length_cannot_hide_corrupted_certificate_bytes() {
        assert!(check_cert(&[1, 2, 3], &[1, 2, 3]).is_ok());
        assert!(check_cert(&[1, 2, 3], &[1, 9, 3]).is_err());
        assert!(check_cert(&[1, 2, 3], &[1, 2]).is_err());
    }

    #[test]
    fn rsa_verification_rejects_wrong_challenges_and_corrupt_signatures() {
        let certificate = include_bytes!("../../../crates/vendor/mfi/tests/fixtures/mfi_cert.der");
        let signature =
            include_bytes!("../../../crates/vendor/mfi/tests/fixtures/mfi_sign_testvector.sig");
        // Existing captured fixture signs this synthetic SHA-1 digest.
        let challenge = [
            0x01, 0x23, 0x45, 0x67, 0x89, 0xab, 0xcd, 0xef, 0x01, 0x23, 0x45, 0x67, 0x89, 0xab,
            0xcd, 0xef, 0x01, 0x23, 0x45, 0x67,
        ];
        let verifier = Verifier::new(certificate).unwrap();
        verifier.verify(&challenge, signature).unwrap();
        let mut wrong = challenge;
        wrong[0] ^= 1;
        assert!(verifier.verify(&wrong, signature).is_err());
        let mut corrupt = signature.to_vec();
        corrupt[0] ^= 1;
        assert!(verifier.verify(&challenge, &corrupt).is_err());
        let dir = verifier.dir.clone();
        drop(verifier);
        assert!(!dir.exists());
    }
}
