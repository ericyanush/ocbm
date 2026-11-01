//! Diagnostic continuity counters only. Never reorders, drops or retransmits audio.
#[derive(Default)]
pub(crate) struct AudioRtpTiming {
    sequence: Option<u16>, timestamp: Option<u32>, ssrc: Option<u32>,
    observed: u64, gaps: u64, missing: u64, late: u64, duplicates: u64,
    source_changes: u64, max_timestamp_step: u32,
}
impl AudioRtpTiming {
    pub(crate) fn observe(&mut self, packet: &[u8]) {
        if packet.len() < 12 || packet[0] >> 6 != 2 { return; }
        self.observed += 1;
        let seq = u16::from_be_bytes([packet[2], packet[3]]);
        let timestamp = u32::from_be_bytes(packet[4..8].try_into().unwrap());
        let ssrc = u32::from_be_bytes(packet[8..12].try_into().unwrap());
        if self.ssrc.is_some_and(|previous| previous != ssrc) {
            self.source_changes += 1; self.sequence=None; self.timestamp=None;
        }
        self.ssrc=Some(ssrc);
        if let Some(previous) = self.sequence {
            let step = seq.wrapping_sub(previous);
            if step == 0 { self.duplicates += 1; return; }
            if step >= 0x8000 { self.late += 1; return; }
            if step > 1 { self.gaps += 1; self.missing += (step-1) as u64; }
            if let Some(previous) = self.timestamp { self.max_timestamp_step = self.max_timestamp_step.max(timestamp.wrapping_sub(previous)); }
        }
        self.sequence=Some(seq); self.timestamp=Some(timestamp);
    }
    pub(crate) fn summary(&mut self) -> String {
        let summary = format!("rtp_packets={} rtp_gap_events={} rtp_missing_estimate={} rtp_late={} rtp_duplicates={} rtp_source_changes={} rtp_max_timestamp_step={}", self.observed, self.gaps, self.missing, self.late, self.duplicates, self.source_changes, self.max_timestamp_step);
        self.observed=0; self.gaps=0; self.missing=0; self.late=0; self.duplicates=0; self.source_changes=0; self.max_timestamp_step=0;
        summary
    }
}


#[cfg(test)]
mod tests {
    use super::*;
    fn packet(sequence: u16, timestamp: u32, source: u32) -> [u8; 12] {
        let mut bytes = [0u8; 12];
        bytes[0] = 0x80;
        bytes[2..4].copy_from_slice(&sequence.to_be_bytes());
        bytes[4..8].copy_from_slice(&timestamp.to_be_bytes());
        bytes[8..12].copy_from_slice(&source.to_be_bytes());
        bytes
    }
    #[test]
    fn wrap_missing_duplicates_and_late_packets_do_not_rewind_the_baseline() {
        let mut timing = AudioRtpTiming::default();
        timing.observe(&packet(65535, u32::MAX-1023, 1));
        timing.observe(&packet(0, 0, 1));
        timing.observe(&packet(2, 2048, 1));
        timing.observe(&packet(2, 2048, 1));
        timing.observe(&packet(1, 1024, 1));
        timing.observe(&packet(3, 3072, 1));
        let line = timing.summary();
        assert!(line.contains("rtp_missing_estimate=1"));
        assert!(line.contains("rtp_duplicates=1"));
        assert!(line.contains("rtp_late=1"));
        assert!(line.contains("rtp_max_timestamp_step=2048"));
        timing.observe(&packet(5, 5120, 1));
        assert!(timing.summary().contains("rtp_missing_estimate=1"));
    }
    #[test]
    fn source_changes_reset_continuity_and_non_rtp_is_ignored() {
        let mut timing = AudioRtpTiming::default();
        timing.observe(&[0u8; 12]);
        timing.observe(&[0x80]);
        timing.observe(&packet(500, 10000, 1));
        timing.observe(&packet(1, 200, 2));
        let line = timing.summary();
        assert!(line.contains("rtp_packets=2"));
        assert!(line.contains("rtp_source_changes=1"));
        assert!(line.contains("rtp_missing_estimate=0"));
    }
}
