//! Read-only Linux resource sampling, independent of USB/socket servicing.
//!
//! One snapshot per second; bounded five-second reports replace an unread report rather than
//! accumulating stale history while CH_LOG is disabled. No child processes or persistent writes.
use std::collections::{BTreeMap, BTreeSet};
use std::fs::{self, File};
use std::io::Read;
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

const PERIOD: Duration = Duration::from_secs(1);
const REPORT_PERIOD: Duration = Duration::from_secs(5);
const MAX_PROCESSES: usize = 256;
const MAX_THREADS: usize = 128;
const MAX_FILE: u64 = 64 * 1024;

pub struct Monitor {
    latest: Arc<Mutex<Option<Vec<String>>>>,
}

impl Monitor {
    pub fn start() -> Self {
        let latest = Arc::new(Mutex::new(None));
        if !cfg!(target_os = "linux") {
            *latest.lock().unwrap() = Some(vec![format!(
                "[resource-perf] implementation=proc-v1 supported=false os={}",
                std::env::consts::OS
            )]);
            return Self { latest };
        }
        let weak = Arc::downgrade(&latest);
        let result = thread::Builder::new()
            .name("ocbm-resources".into())
            .spawn(move || {
                let hz = positive_sysconf(libc::_SC_CLK_TCK);
                let page_bytes = positive_sysconf(libc::_SC_PAGESIZE);
                let mut previous = Snapshot::read(Path::new("/"));
                let mut window = Window::new(previous.at);
                let mut deadline = Instant::now() + PERIOD;
                loop {
                    thread::sleep(deadline.saturating_duration_since(Instant::now()));
                    if weak.strong_count() == 0 {
                        return;
                    }
                    let late = Instant::now().saturating_duration_since(deadline);
                    // A /proc task census is substantially more expensive than the system
                    // counters on this ARM board. Refresh it only for each five-second report.
                    let refresh_tasks = window.samples + 1 >= 5
                        || Instant::now().duration_since(window.since) >= REPORT_PERIOD;
                    let snapshot = Snapshot::read_with_tasks(
                        Path::new("/"),
                        if refresh_tasks { None } else { Some(&previous) },
                    );
                    window.observe(&previous, &snapshot, hz, late);
                    if window.samples >= 5
                        || snapshot.at.duration_since(window.since) >= REPORT_PERIOD
                    {
                        let lines = window.report(&snapshot, hz, page_bytes);
                        let Some(latest) = weak.upgrade() else {
                            return;
                        };
                        if let Ok(mut slot) = latest.lock() {
                            *slot = Some(lines);
                        }
                        window = Window::new(snapshot.at);
                    }
                    previous = snapshot;
                    deadline += PERIOD;
                    // A delayed sampler must not try to catch up by doing a burst of /proc scans.
                    if deadline < Instant::now() {
                        deadline = Instant::now() + PERIOD;
                    }
                }
            });
        if let Err(error) = result {
            *latest.lock().unwrap() = Some(vec![format!(
                "[resource-perf] implementation=proc-v1 sampler=unavailable error={}",
                clean(&error.to_string())
            )]);
        }
        Self { latest }
    }

    /// Never wait for a sampler, sysfs read, or another thread on the USB dispatch path.
    pub fn take_report(&self) -> Option<Vec<String>> {
        self.latest.try_lock().ok()?.take()
    }
}

fn positive_sysconf(key: libc::c_int) -> Option<u64> {
    let value = unsafe { libc::sysconf(key) };
    (value > 0).then_some(value as u64)
}

fn read(path: impl AsRef<Path>) -> Option<String> {
    let mut text = String::new();
    File::open(path)
        .ok()?
        .take(MAX_FILE + 1)
        .read_to_string(&mut text)
        .ok()?;
    (text.len() <= MAX_FILE as usize).then_some(text)
}

fn clean(value: &str) -> String {
    value
        .chars()
        .take(40)
        .map(|c| {
            if c.is_ascii_alphanumeric() || "-_:/.".contains(c) {
                c
            } else {
                '_'
            }
        })
        .collect()
}
fn number(value: Option<u64>) -> String {
    value.map(|v| v.to_string()).unwrap_or_else(|| "na".into())
}

fn key_values(text: &str) -> BTreeMap<String, u64> {
    text.lines()
        .filter_map(|line| {
            let mut fields = line.split_whitespace();
            Some((
                fields.next()?.trim_end_matches(':').to_owned(),
                fields.next()?.parse().ok()?,
            ))
        })
        .collect()
}

#[derive(Clone, Debug)]
struct Cpu {
    ticks: [u64; 8],
    cores: usize,
    running: Option<u64>,
    blocked: Option<u64>,
}
impl Cpu {
    fn parse(text: &str) -> Option<Self> {
        let mut fields = text
            .lines()
            .find(|l| l.starts_with("cpu "))?
            .split_whitespace()
            .skip(1);
        let mut ticks = [0; 8];
        // Older kernels can omit steal; guest and guest_nice must NOT be added again.
        for (i, value) in ticks.iter_mut().enumerate() {
            match fields.next() {
                Some(s) => *value = s.parse().ok()?,
                None if i >= 7 => {}
                None => return None,
            }
        }
        let counters = key_values(text);
        Some(Self {
            ticks,
            cores: text
                .lines()
                .filter(|l| {
                    l.strip_prefix("cpu")
                        .is_some_and(|s| s.starts_with(|c: char| c.is_ascii_digit()))
                })
                .count(),
            running: counters.get("procs_running").copied(),
            blocked: counters.get("procs_blocked").copied(),
        })
    }
    fn delta(&self, old: &Self) -> Option<[u64; 8]> {
        let mut result = [0; 8];
        for (i, slot) in result.iter_mut().enumerate() {
            *slot = self.ticks[i].checked_sub(old.ticks[i])?;
        }
        Some(result)
    }
}

#[derive(Clone, Copy, Debug, Default)]
struct Sched {
    runtime: u64,
    wait: u64,
    slices: u64,
}
impl Sched {
    fn parse(text: &str) -> Option<Self> {
        let values: Vec<u64> = text
            .split_whitespace()
            .map(str::parse)
            .collect::<Result<_, _>>()
            .ok()?;
        (values.len() == 3).then(|| Self {
            runtime: values[0],
            wait: values[1],
            slices: values[2],
        })
    }
    fn delta(self, old: Self) -> Option<Self> {
        Some(Self {
            runtime: self.runtime.checked_sub(old.runtime)?,
            wait: self.wait.checked_sub(old.wait)?,
            slices: self.slices.checked_sub(old.slices)?,
        })
    }
    fn nonzero(self) -> bool {
        self.runtime != 0 || self.wait != 0 || self.slices != 0
    }
}

#[derive(Clone, Debug)]
struct Task {
    name: String,
    state: String,
    ticks: u64,
    start: u64,
    rss_pages: u64,
    threads: u64,
    blkio_ticks: Option<u64>,
    sched: Option<Sched>,
}
impl Task {
    fn parse(text: &str) -> Option<Self> {
        let left = text.find('(')?;
        let right = text.rfind(')')?;
        if right <= left {
            return None;
        }
        let fields: Vec<&str> = text[right + 1..].split_whitespace().collect();
        let n = |i: usize| fields.get(i)?.parse::<u64>().ok();
        Some(Self {
            name: clean(&text[left + 1..right]),
            state: clean(fields.first()?),
            ticks: n(11)?.checked_add(n(12)?)?,
            start: n(19)?,
            rss_pages: fields.get(21)?.parse::<i64>().ok()?.max(0) as u64,
            threads: n(17)?,
            blkio_ticks: n(39),
            sched: None,
        })
    }
}

// Process and per-thread /stat have different CPU accounting; keep their identities separate.
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
struct TaskKey {
    pid: u32,
    tid: u32,
    start: u64,
    thread: bool,
}

fn numeric_dirs(path: &Path, cap: usize) -> (Vec<u32>, bool) {
    let Ok(entries) = fs::read_dir(path) else {
        return (Vec::new(), false);
    };
    // BTreeSet retains only a bounded number of names, even on a pathological host.
    let mut ids = BTreeSet::new();
    let mut truncated = false;
    for entry in entries.flatten() {
        if let Some(id) = entry
            .file_name()
            .to_str()
            .and_then(|n| n.parse::<u32>().ok())
        {
            ids.insert(id);
            if ids.len() > cap {
                ids.pop_last();
                truncated = true;
            }
        }
    }
    (ids.into_iter().collect(), truncated)
}

fn protocol_counters(text: &str) -> BTreeMap<String, u64> {
    let lines: Vec<_> = text.lines().collect();
    let mut out = BTreeMap::new();
    for pair in lines.chunks_exact(2) {
        let headers: Vec<_> = pair[0].split_whitespace().collect();
        let values: Vec<_> = pair[1].split_whitespace().collect();
        if headers.len() != values.len() || headers.first() != values.first() {
            continue;
        }
        for (key, value) in headers.iter().skip(1).zip(values.iter().skip(1)) {
            if let Ok(value) = value.parse() {
                out.insert(format!("{}{}", headers[0], key), value);
            }
        }
    }
    out
}

fn sum_counter(values: &BTreeMap<String, u64>, key: &str) -> Option<u64> {
    if let Some(value) = values.get(key) {
        return Some(*value);
    }
    // Old kernels expose zone-specific reclaim counters instead of the aggregate.
    let mut found = false;
    let mut sum = 0u64;
    for (name, value) in values {
        if name
            .strip_prefix(key)
            .is_some_and(|s| matches!(s, "_dma" | "_dma32" | "_normal" | "_high" | "_movable"))
        {
            found = true;
            sum = sum.saturating_add(*value);
        }
    }
    found.then_some(sum)
}

fn softnet(text: &str) -> Option<Vec<[u32; 3]>> {
    let rows: Option<Vec<_>> = text
        .lines()
        .map(|line| {
            let mut fields = line.split_whitespace();
            Some([
                u32::from_str_radix(fields.next()?, 16).ok()?,
                u32::from_str_radix(fields.next()?, 16).ok()?,
                u32::from_str_radix(fields.next()?, 16).ok()?,
            ])
        })
        .collect();
    rows.filter(|r| !r.is_empty())
}
fn softnet_delta(now: &[[u32; 3]], old: &[[u32; 3]]) -> Option<[u64; 3]> {
    if now.len() != old.len() {
        return None;
    }
    let mut sum = [0; 3];
    for (now, old) in now.iter().zip(old) {
        for i in 0..3 {
            sum[i] += now[i].wrapping_sub(old[i]) as u64;
        }
    }
    Some(sum)
}

struct Snapshot {
    at: Instant,
    tasks_at: Instant,
    collect_us: u128,
    uptime_ms: Option<u64>,
    cpu: Option<Cpu>,
    load1: Option<String>,
    mem: BTreeMap<String, u64>,
    counters: BTreeMap<&'static str, u64>,
    softnet: Option<Vec<[u32; 3]>>,
    tasks: BTreeMap<TaskKey, Task>,
    processes_truncated: bool,
    threads_truncated: bool,
    freq: Option<u64>,
    freq_limit: Option<u64>,
    freq_hardware_max: Option<u64>,
    governor: String,
    temperatures: Vec<(String, i64)>,
}
impl Snapshot {
    fn read(root: &Path) -> Self {
        Self::read_with_tasks(root, None)
    }

    fn read_with_tasks(root: &Path, cached: Option<&Self>) -> Self {
        let at = Instant::now();
        let proc = root.join("proc");
        let cpu = read(proc.join("stat")).and_then(|s| Cpu::parse(&s));
        let uptime_ms = read(proc.join("uptime"))
            .and_then(|s| s.split_whitespace().next()?.parse::<f64>().ok())
            .filter(|v| v.is_finite() && *v >= 0.0)
            .map(|v| (v * 1000.0) as u64);
        let load1 = read(proc.join("loadavg")).and_then(|s| s.split_whitespace().next().map(clean));
        let mem = read(proc.join("meminfo"))
            .map(|s| key_values(&s))
            .unwrap_or_default();
        let vm = read(proc.join("vmstat"))
            .map(|s| key_values(&s))
            .unwrap_or_default();
        let mut counters = BTreeMap::new();
        for key in [
            "pgmajfault",
            "allocstall",
            "pgscan_direct",
            "pgscan_kswapd",
            "pgsteal_direct",
            "pgsteal_kswapd",
            "oom_kill",
        ] {
            if let Some(value) = sum_counter(&vm, key) {
                counters.insert(key, value);
            }
        }
        let mut net = read(proc.join("net/snmp"))
            .map(|s| protocol_counters(&s))
            .unwrap_or_default();
        net.extend(
            read(proc.join("net/netstat"))
                .map(|s| protocol_counters(&s))
                .unwrap_or_default(),
        );
        for (name, key) in [
            ("udp_in_errors", "Udp:InErrors"),
            ("udp_rcvbuf_errors", "Udp:RcvbufErrors"),
            ("udp_sndbuf_errors", "Udp:SndbufErrors"),
            ("tcp_retrans_segs", "Tcp:RetransSegs"),
            ("tcp_memory_pressures", "TcpExt:TCPMemoryPressures"),
            ("tcp_abort_on_memory", "TcpExt:TCPAbortOnMemory"),
        ] {
            if let Some(value) = net.get(key) {
                counters.insert(name, *value);
            }
        }
        if let Some(text) = read(proc.join("softirqs")) {
            for (name, prefix) in [
                ("net_rx_softirqs", "NET_RX:"),
                ("net_tx_softirqs", "NET_TX:"),
            ] {
                if let Some(line) = text.lines().find(|l| l.trim_start().starts_with(prefix)) {
                    if let Some(values) = line
                        .split_whitespace()
                        .skip(1)
                        .map(|s| s.parse::<u64>().ok())
                        .collect::<Option<Vec<_>>>()
                    {
                        counters.insert(name, values.iter().sum());
                    }
                }
            }
        }
        let softnet = read(proc.join("net/softnet_stat")).and_then(|s| softnet(&s));
        let (mut pids, mut processes_truncated) = if cached.is_none() {
            numeric_dirs(&proc, MAX_PROCESSES)
        } else {
            (Vec::new(), false)
        };
        // Retain our daemon even when a crowded /proc hits the discovery cap.
        let me = std::process::id();
        if cached.is_none() && !pids.contains(&me) {
            if pids.len() == MAX_PROCESSES {
                pids.pop();
            }
            pids.push(me);
        }
        let mut tasks = BTreeMap::new();
        let mut thread_count = 0;
        let mut threads_truncated = false;
        for pid in pids {
            let base = proc.join(pid.to_string());
            let Some(mut task) = read(base.join("stat")).and_then(|s| Task::parse(&s)) else {
                continue;
            };
            let detailed = pid == me || matches!(task.name.as_str(), "ocbmd" | "btd" | "carplayd");
            if task.threads == 1 {
                task.sched = read(base.join("schedstat")).and_then(|s| Sched::parse(&s));
            }
            tasks.insert(
                TaskKey {
                    pid,
                    tid: pid,
                    start: task.start,
                    thread: false,
                },
                task,
            );
            if !detailed {
                continue;
            }
            let remaining = MAX_THREADS.saturating_sub(thread_count);
            let (tids, truncated) = numeric_dirs(&base.join("task"), remaining);
            threads_truncated |= truncated;
            for tid in tids {
                let dir = base.join("task").join(tid.to_string());
                if let Some(mut task) = read(dir.join("stat")).and_then(|s| Task::parse(&s)) {
                    task.sched = read(dir.join("schedstat")).and_then(|s| Sched::parse(&s));
                    tasks.insert(
                        TaskKey {
                            pid,
                            tid,
                            start: task.start,
                            thread: true,
                        },
                        task,
                    );
                    thread_count += 1;
                }
            }
        }
        let tasks_at = if let Some(cached) = cached {
            tasks = cached.tasks.clone();
            processes_truncated = cached.processes_truncated;
            threads_truncated = cached.threads_truncated;
            cached.tasks_at
        } else {
            at
        };
        let cpufreq = root.join("sys/devices/system/cpu/cpu0/cpufreq");
        let integer = |path: PathBuf| read(path).and_then(|s| s.trim().parse::<u64>().ok());
        let freq = integer(cpufreq.join("scaling_cur_freq"))
            .or_else(|| integer(cpufreq.join("cpuinfo_cur_freq")));
        let freq_limit = integer(cpufreq.join("scaling_max_freq"));
        let freq_hardware_max = integer(cpufreq.join("cpuinfo_max_freq"));
        let governor = read(cpufreq.join("scaling_governor"))
            .map(|s| clean(s.trim()))
            .unwrap_or_else(|| "na".into());
        let mut temperatures = Vec::new();
        if let Ok(entries) = fs::read_dir(root.join("sys/class/thermal")) {
            let mut zones: Vec<_> = entries
                .flatten()
                .filter(|e| e.file_name().to_string_lossy().starts_with("thermal_zone"))
                .map(|e| e.path())
                .collect();
            zones.sort();
            for path in zones.into_iter().take(8) {
                if let Some(temp) =
                    read(path.join("temp")).and_then(|s| s.trim().parse::<i64>().ok())
                {
                    let name = path.file_name().unwrap().to_string_lossy();
                    let kind = read(path.join("type"))
                        .map(|s| clean(s.trim()))
                        .unwrap_or_else(|| "unknown".into());
                    temperatures.push((format!("{name}_{kind}"), temp));
                }
            }
        }
        Self {
            at,
            tasks_at,
            collect_us: at.elapsed().as_micros(),
            uptime_ms,
            cpu,
            load1,
            mem,
            counters,
            softnet,
            tasks,
            processes_truncated,
            threads_truncated,
            freq,
            freq_limit,
            freq_hardware_max,
            governor,
            temperatures,
        }
    }
}

#[derive(Default)]
struct TaskWindow {
    last: Option<Task>,
    ticks: u64,
    peak_cpu: f64,
    peak_rss: u64,
    samples: usize,
    cpu_samples: usize,
    sched: Option<Sched>,
    max_wait_ns: u64,
    blkio_ticks: Option<u64>,
}
struct Window {
    since: Instant,
    samples: usize,
    cpu: [u64; 8],
    cpu_samples: usize,
    invalid_cpu: usize,
    peak_busy: f64,
    max_collect_us: u128,
    max_late_ms: u128,
    min_available: Option<u64>,
    max_running: Option<u64>,
    max_blocked: Option<u64>,
    freq_min: Option<u64>,
    freq_max: Option<u64>,
    counters: BTreeMap<&'static str, u64>,
    counter_resets: usize,
    softnet: Option<[u64; 3]>,
    tasks: BTreeMap<TaskKey, TaskWindow>,
    processes_truncated: bool,
    threads_truncated: bool,
    peak_temperatures: BTreeMap<String, i64>,
}
impl Window {
    fn new(since: Instant) -> Self {
        Self {
            since,
            samples: 0,
            cpu: [0; 8],
            cpu_samples: 0,
            invalid_cpu: 0,
            peak_busy: 0.0,
            max_collect_us: 0,
            max_late_ms: 0,
            min_available: None,
            max_running: None,
            max_blocked: None,
            freq_min: None,
            freq_max: None,
            counters: BTreeMap::new(),
            counter_resets: 0,
            softnet: None,
            tasks: BTreeMap::new(),
            processes_truncated: false,
            threads_truncated: false,
            peak_temperatures: BTreeMap::new(),
        }
    }
    fn observe(&mut self, old: &Snapshot, now: &Snapshot, hz: Option<u64>, late: Duration) {
        self.samples += 1;
        self.max_collect_us = self.max_collect_us.max(now.collect_us);
        self.max_late_ms = self.max_late_ms.max(late.as_millis());
        self.processes_truncated |= now.processes_truncated;
        self.threads_truncated |= now.threads_truncated;
        for (name, temp) in &now.temperatures {
            self.peak_temperatures
                .entry(name.clone())
                .and_modify(|peak| *peak = (*peak).max(*temp))
                .or_insert(*temp);
        }
        if let Some(value) = now.mem.get("MemAvailable") {
            self.min_available = Some(self.min_available.map_or(*value, |v| v.min(*value)));
        }
        if let Some(value) = now.freq {
            self.freq_min = Some(self.freq_min.map_or(value, |v| v.min(value)));
            self.freq_max = Some(self.freq_max.map_or(value, |v| v.max(value)));
        }
        if let Some(cpu) = &now.cpu {
            if let Some(value) = cpu.running {
                self.max_running = Some(self.max_running.map_or(value, |v| v.max(value)));
            }
            if let Some(value) = cpu.blocked {
                self.max_blocked = Some(self.max_blocked.map_or(value, |v| v.max(value)));
            }
        }
        if let Some(delta) = now
            .cpu
            .as_ref()
            .zip(old.cpu.as_ref())
            .and_then(|(n, o)| n.delta(o))
        {
            let total: u64 = delta.iter().sum();
            if total != 0 {
                for (sum, value) in self.cpu.iter_mut().zip(delta) {
                    *sum = sum.saturating_add(value);
                }
                self.cpu_samples += 1;
                self.peak_busy = self
                    .peak_busy
                    .max(100.0 * total.saturating_sub(delta[3] + delta[4]) as f64 / total as f64);
            }
        } else {
            self.invalid_cpu += 1;
        }
        for (key, value) in &now.counters {
            if let Some(previous) = old.counters.get(key) {
                if let Some(delta) = value.checked_sub(*previous) {
                    *self.counters.entry(key).or_default() += delta;
                } else {
                    self.counter_resets += 1;
                }
            }
        }
        if let Some(delta) = now
            .softnet
            .as_ref()
            .zip(old.softnet.as_ref())
            .and_then(|(n, o)| softnet_delta(n, o))
        {
            let sum = self.softnet.get_or_insert([0; 3]);
            for i in 0..3 {
                sum[i] += delta[i];
            }
        }
        if now.tasks_at == old.tasks_at {
            return;
        }
        let seconds = now.tasks_at.duration_since(old.tasks_at).as_secs_f64();
        for (key, task) in &now.tasks {
            let entry = self.tasks.entry(*key).or_default();
            entry.last = Some(task.clone());
            entry.peak_rss = entry.peak_rss.max(task.rss_pages);
            entry.samples += 1;
            if let Some(previous) = old.tasks.get(key) {
                if let Some(ticks) = task.ticks.checked_sub(previous.ticks) {
                    entry.ticks += ticks;
                    entry.cpu_samples += 1;
                    if let Some(hz) = hz.filter(|_| seconds > 0.0) {
                        entry.peak_cpu = entry
                            .peak_cpu
                            .max(ticks as f64 * 100.0 / hz as f64 / seconds);
                    }
                }
                if let Some(delta) = task.sched.zip(previous.sched).and_then(|(n, o)| n.delta(o)) {
                    let sum = entry.sched.get_or_insert(Sched::default());
                    sum.runtime += delta.runtime;
                    sum.wait += delta.wait;
                    sum.slices += delta.slices;
                    entry.max_wait_ns = entry.max_wait_ns.max(delta.wait);
                }
                if let Some(delta) = task
                    .blkio_ticks
                    .zip(previous.blkio_ticks)
                    .and_then(|(n, o)| n.checked_sub(o))
                {
                    *entry.blkio_ticks.get_or_insert(0) += delta;
                }
            }
        }
    }
    fn report(&self, now: &Snapshot, hz: Option<u64>, page_bytes: Option<u64>) -> Vec<String> {
        let elapsed = now.at.duration_since(self.since);
        let tag = format!(
            "sample_uptime_ms={} interval_ms={}",
            number(now.uptime_ms),
            elapsed.as_millis()
        );
        let total: u64 = self.cpu.iter().sum();
        let pct = |ticks: u64| {
            if total == 0 {
                "na".into()
            } else {
                format!("{:.1}", ticks as f64 * 100.0 / total as f64)
            }
        };
        let mem = |key: &str| number(now.mem.get(key).copied());
        let delta = |key: &str| number(self.counters.get(key).copied());
        let mut lines = vec![format!(
            "[resource-perf] implementation=proc-v1 {tag} samples={} max_collect_us={} max_sample_late_ms={} cpu_cores={} cpu_busy_pct={} cpu_user_pct={} cpu_system_pct={} cpu_irq_pct={} cpu_softirq_pct={} cpu_idle_pct={} cpu_iowait_pct={} peak_sample_busy_pct={} cpu_valid_samples={} cpu_invalid_samples={} load1={} max_runnable={} max_blocked={} process_scan_truncated={} thread_scan_truncated={} counter_resets={}",
            self.samples, self.max_collect_us, self.max_late_ms, now.cpu.as_ref().map(|c| c.cores.to_string()).unwrap_or_else(|| "na".into()),
            pct(total.saturating_sub(self.cpu[3] + self.cpu[4])), pct(self.cpu[0] + self.cpu[1]), pct(self.cpu[2]), pct(self.cpu[5]), pct(self.cpu[6]), pct(self.cpu[3]), pct(self.cpu[4]),
            if self.cpu_samples == 0 { "na".into() } else { format!("{:.1}", self.peak_busy) },
            self.cpu_samples, self.invalid_cpu, now.load1.as_deref().unwrap_or("na"), number(self.max_running), number(self.max_blocked), self.processes_truncated, self.threads_truncated, self.counter_resets
        ), format!(
            "[memory-perf] {tag} total_kb={} available_kb={} min_available_kb={} free_kb={} slab_kb={} unreclaimable_slab_kb={} shmem_kb={} dirty_kb={} pgmajfault_delta={} allocstall_delta={} pgscan_direct_delta={} pgscan_kswapd_delta={} pgsteal_direct_delta={} pgsteal_kswapd_delta={} oom_kill_delta={}",
            mem("MemTotal"), mem("MemAvailable"), number(self.min_available), mem("MemFree"), mem("Slab"), mem("SUnreclaim"), mem("Shmem"), mem("Dirty"),
            delta("pgmajfault"), delta("allocstall"), delta("pgscan_direct"), delta("pgscan_kswapd"), delta("pgsteal_direct"), delta("pgsteal_kswapd"), delta("oom_kill")
        ), format!(
            "[net-kernel-perf] {tag} softnet_processed_delta={} softnet_dropped_delta={} softnet_time_squeeze_delta={} net_rx_softirqs_delta={} net_tx_softirqs_delta={} udp_in_errors_delta={} udp_rcvbuf_errors_delta={} udp_sndbuf_errors_delta={} tcp_retrans_segs_delta={} tcp_memory_pressures_delta={} tcp_abort_on_memory_delta={}",
            number(self.softnet.map(|v| v[0])), number(self.softnet.map(|v| v[1])), number(self.softnet.map(|v| v[2])), delta("net_rx_softirqs"), delta("net_tx_softirqs"),
            delta("udp_in_errors"), delta("udp_rcvbuf_errors"), delta("udp_sndbuf_errors"), delta("tcp_retrans_segs"), delta("tcp_memory_pressures"), delta("tcp_abort_on_memory")
        )];
        let mut processes: Vec<_> = self.tasks.iter().filter(|(k, _)| !k.thread).collect();
        processes.sort_by_key(|(_, t)| std::cmp::Reverse(t.ticks));
        let mut selected: BTreeSet<TaskKey> = processes.iter().take(4).map(|(k, _)| **k).collect();
        for (key, task) in &processes {
            if key.pid == std::process::id()
                || task
                    .last
                    .as_ref()
                    .is_some_and(|t| matches!(t.name.as_str(), "btd" | "carplayd"))
            {
                selected.insert(**key);
            }
        }
        let core_pct = |value: &TaskWindow| {
            hz.filter(|_| elapsed.as_secs_f64() > 0.0 && value.cpu_samples > 0)
                .map(|hz| {
                    format!(
                        "{:.1}",
                        value.ticks as f64 * 100.0 / hz as f64 / elapsed.as_secs_f64()
                    )
                })
                .unwrap_or_else(|| "na".into())
        };
        let peak_cpu = |value: &TaskWindow| {
            if hz.is_none() || value.cpu_samples == 0 {
                "na".into()
            } else {
                format!("{:.1}", value.peak_cpu)
            }
        };
        let rss = |pages: u64| number(page_bytes.map(|size| pages.saturating_mul(size) / 1024));
        for key in selected.into_iter().take(8) {
            let value = &self.tasks[&key];
            let task = value.last.as_ref().unwrap();
            lines.push(format!("[process-perf] {tag} pid={} start_ticks={} name={} present={} state={} threads={} cpu_pct_core={} peak_sample_cpu_pct_core={} rss_kb={} peak_rss_kb={} blkio_delay_ticks={} cpu_samples={} observations={}",
                key.pid, key.start, task.name, now.tasks.contains_key(&key), task.state, task.threads, core_pct(value), peak_cpu(value),
                rss(task.rss_pages), rss(value.peak_rss), number(value.blkio_ticks), value.cpu_samples, value.samples));
        }
        let mut threads: Vec<_> = self
            .tasks
            .iter()
            .filter(|(k, t)| k.thread || t.last.as_ref().is_some_and(|t| t.threads == 1))
            .collect();
        threads.sort_by_key(|(_, t)| std::cmp::Reverse(t.ticks));
        let mut selected: BTreeSet<TaskKey> = threads.iter().take(3).map(|(k, _)| **k).collect();
        threads.sort_by_key(|(_, t)| std::cmp::Reverse(t.sched.map(|s| s.wait).unwrap_or(0)));
        selected.extend(threads.iter().take(3).map(|(k, _)| **k));
        let sched_count = now.tasks.values().filter(|t| t.sched.is_some()).count();
        let sched_nonzero = now
            .tasks
            .values()
            .filter(|t| t.sched.is_some_and(Sched::nonzero))
            .count();
        lines.push(format!("[resource-hw] {tag} cpu=0 freq_khz={} min_freq_khz={} max_freq_khz={} scaling_max_khz={} hardware_max_khz={} governor={} clk_tck={} page_bytes={} schedstat_available_tasks={} schedstat_nonzero_tasks={} thermal={}",
            number(now.freq), number(self.freq_min), number(self.freq_max), number(now.freq_limit), number(now.freq_hardware_max), now.governor, number(hz), number(page_bytes), sched_count, sched_nonzero,
            if now.temperatures.is_empty() { "na".into() } else { now.temperatures.iter().map(|(k, t)| format!("{k}:current_mC:{t},max_mC:{}", self.peak_temperatures.get(k).unwrap_or(t))).collect::<Vec<_>>().join(";") }));
        for key in selected {
            let value = &self.tasks[&key];
            let task = value.last.as_ref().unwrap();
            let schedstat_state = match task.sched {
                None => "unavailable",
                Some(s) if s.nonzero() => "active",
                Some(_) => "zero_or_disabled",
            };
            let sched = value.sched.filter(|_| schedstat_state == "active");
            lines.push(format!("[thread-perf] {tag} pid={} tid={} start_ticks={} name={} present={} state={} cpu_pct_core={} peak_sample_cpu_pct_core={} schedstat_state={schedstat_state} sched_runtime_ms={} runqueue_wait_ms={} max_sample_runqueue_wait_ms={} sched_slices={} cpu_samples={} observations={}",
                key.pid, key.tid, key.start, task.name, now.tasks.contains_key(&key), task.state, core_pct(value), peak_cpu(value),
                number(sched.map(|s| s.runtime / 1_000_000)), number(sched.map(|s| s.wait / 1_000_000)),
                number(sched.map(|_| value.max_wait_ns / 1_000_000)), number(sched.map(|s| s.slices)), value.cpu_samples, value.samples));
        }
        lines
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cpu_percentages_do_not_double_count_guest_time() {
        let cpu = Cpu::parse(
            "cpu 100 20 30 400 5 6 7 8 90 10\ncpu0 1\nprocs_running 2\nprocs_blocked 1\n",
        )
        .unwrap();
        assert_eq!(cpu.ticks.iter().sum::<u64>(), 576);
        assert_eq!(cpu.cores, 1);
        assert_eq!(cpu.running, Some(2));
        assert_eq!(cpu.blocked, Some(1));
        let older = Cpu::parse("cpu 101 20 30 400 5 6 7 8\n").unwrap();
        assert!(cpu.delta(&older).is_none());
    }

    #[test]
    fn task_parser_handles_spaces_and_parentheses_and_old_short_stats() {
        let mut fields = vec!["0"; 40];
        fields[0] = "S";
        fields[11] = "12";
        fields[12] = "3";
        fields[17] = "4";
        fields[19] = "99";
        fields[21] = "123";
        fields[39] = "7";
        let task = Task::parse(&format!("42 (worker (rx) name) {}", fields.join(" "))).unwrap();
        assert_eq!(task.name, "worker__rx__name");
        assert_eq!(task.ticks, 15);
        assert_eq!(task.rss_pages, 123);
        assert_eq!(task.start, 99);
        assert_eq!(task.blkio_ticks, Some(7));
        fields.truncate(22);
        assert_eq!(
            Task::parse(&format!("42 (old) {}", fields.join(" ")))
                .unwrap()
                .blkio_ticks,
            None
        );
        assert!(Task::parse("42 (bad) S 0").is_none());
    }

    #[test]
    fn protocol_headers_are_matched_and_missing_fields_stay_missing() {
        let values =
            protocol_counters("Udp: InErrors RcvbufErrors\nUdp: 2 3\nTcp: Broken\nUdp: 100\n");
        assert_eq!(values.get("Udp:InErrors"), Some(&2));
        assert_eq!(values.get("Udp:RcvbufErrors"), Some(&3));
        assert!(!values.contains_key("Tcp:Broken"));
        assert_eq!(number(values.get("Udp:SndbufErrors").copied()), "na");
    }

    #[test]
    fn reclaim_zone_counters_are_aggregated_without_throttle_or_double_counting() {
        let mut values = key_values(
            "pgscan_direct_dma 10\npgscan_direct_normal 20\npgscan_direct_throttle 99\n",
        );
        assert_eq!(sum_counter(&values, "pgscan_direct"), Some(30));
        values.insert("pgscan_direct".into(), 40);
        assert_eq!(sum_counter(&values, "pgscan_direct"), Some(40));
        assert_eq!(sum_counter(&values, "oom_kill"), None);
    }

    #[test]
    fn softnet_counters_wrap_per_cpu_without_becoming_negative() {
        let old = softnet("fffffffe ffffffff 00000001\n00000001 0 0\n").unwrap();
        let now = softnet("00000002 00000001 00000003\n00000004 0 1\n").unwrap();
        assert_eq!(softnet_delta(&now, &old), Some([7, 2, 3]));
        assert!(softnet_delta(&now[..1], &old).is_none());
        assert!(softnet("invalid").is_none());
    }

    #[test]
    fn schedstat_reset_is_not_reported_as_a_huge_wait() {
        let old = Sched::parse("1000000 2000000 3").unwrap();
        let now = Sched::parse("2000000 9000000 7").unwrap();
        let delta = now.delta(old).unwrap();
        assert_eq!(delta.wait, 7_000_000);
        assert_eq!(delta.slices, 4);
        assert!(old.delta(now).is_none());
        assert!(!Sched::parse("0 0 0").unwrap().nonzero());
    }

    #[test]
    fn latest_report_replaces_unread_history_and_take_never_waits() {
        let latest = Arc::new(Mutex::new(Some(vec!["old".into()])));
        let monitor = Monitor {
            latest: latest.clone(),
        };
        *latest.lock().unwrap() = Some(vec!["new".into()]);
        let guard = latest.lock().unwrap();
        assert!(monitor.take_report().is_none());
        drop(guard);
        assert_eq!(monitor.take_report(), Some(vec!["new".into()]));
        assert!(monitor.take_report().is_none());
    }

    #[test]
    fn pid_reuse_cannot_share_accounting_identity() {
        let a = TaskKey {
            pid: 10,
            tid: 10,
            start: 20,
            thread: false,
        };
        let b = TaskKey { start: 21, ..a };
        let c = TaskKey { thread: true, ..a };
        let mut keys = BTreeSet::new();
        keys.insert(a);
        keys.insert(b);
        keys.insert(c);
        assert_eq!(keys.len(), 3);
    }

    #[test]
    fn missing_proc_and_sysfs_produce_explicit_unavailable_metrics() {
        let root = Path::new("/path-that-does-not-exist/ocbm-resource-test");
        let old = Snapshot::read(root);
        let now = Snapshot::read(root);
        let mut window = Window::new(old.at);
        window.observe(&old, &now, None, Duration::ZERO);
        let lines = window.report(&now, None, None);
        assert!(lines.iter().any(|l| l.contains("available_kb=na")));
        assert!(lines.iter().any(|l| l.contains("softnet_dropped_delta=na")));
        assert!(lines.iter().any(|l| l.contains("freq_khz=na")));
        assert!(lines.iter().all(|l| l.len() < 1000));
    }

    #[test]
    fn report_accounts_for_cpu_memory_reclaim_and_scheduler_wait_in_real_units() {
        let mut old = Snapshot::read(Path::new("/nonexistent/ocbm-fixture"));
        let mut now = Snapshot::read(Path::new("/nonexistent/ocbm-fixture"));
        now.at = old.at + Duration::from_secs(5);
        now.tasks_at = now.at;
        old.cpu = Cpu::parse("cpu 100 0 0 1000 0 0 0 0\ncpu0 0\n");
        now.cpu = Cpu::parse("cpu 200 0 25 1375 0 0 0 0\ncpu0 0\n");
        old.counters.insert("allocstall", 3);
        now.counters.insert("allocstall", 5);
        now.mem.insert("MemAvailable".into(), 12345);
        let key = TaskKey {
            pid: 42,
            tid: 42,
            start: 99,
            thread: false,
        };
        let task = Task {
            name: "worker".into(),
            state: "R".into(),
            ticks: 100,
            start: 99,
            rss_pages: 8,
            threads: 1,
            blkio_ticks: Some(0),
            sched: Sched::parse("1000000 2000000 3"),
        };
        old.tasks.insert(key, task.clone());
        now.tasks.insert(
            key,
            Task {
                ticks: 150,
                rss_pages: 10,
                sched: Sched::parse("11000000 52000000 8"),
                ..task
            },
        );
        let mut window = Window::new(old.at);
        window.observe(&old, &now, Some(100), Duration::from_millis(7));
        let lines = window.report(&now, Some(100), Some(4096));
        assert!(lines[0].contains("cpu_busy_pct=25.0"));
        assert!(lines[0].contains("cpu_system_pct=5.0"));
        assert!(lines[0].contains("max_sample_late_ms=7"));
        assert!(lines
            .iter()
            .any(|l| l.contains("min_available_kb=12345") && l.contains("allocstall_delta=2")));
        assert!(lines.iter().any(|l| l.starts_with("[process-perf]")
            && l.contains("cpu_pct_core=10.0")
            && l.contains("rss_kb=40")));
        assert!(lines.iter().any(|l| l.starts_with("[thread-perf]")
            && l.contains("runqueue_wait_ms=50")
            && l.contains("sched_slices=5")));
    }

    #[test]
    fn disabled_schedstat_is_unavailable_not_a_healthy_zero_wait() {
        let mut old = Snapshot::read(Path::new("/nonexistent/ocbm-fixture"));
        let mut now = Snapshot::read(Path::new("/nonexistent/ocbm-fixture"));
        now.at = old.at + PERIOD;
        now.tasks_at = now.at;
        let key = TaskKey {
            pid: 42,
            tid: 42,
            start: 99,
            thread: false,
        };
        let task = Task {
            name: "worker".into(),
            state: "R".into(),
            ticks: 100,
            start: 99,
            rss_pages: 0,
            threads: 1,
            blkio_ticks: None,
            sched: Some(Sched::default()),
        };
        old.tasks.insert(key, task.clone());
        now.tasks.insert(key, Task { ticks: 101, ..task });
        let mut window = Window::new(old.at);
        window.observe(&old, &now, Some(100), Duration::ZERO);
        let lines = window.report(&now, Some(100), Some(4096));
        assert!(lines.iter().any(|l| l.starts_with("[thread-perf]")
            && l.contains("schedstat_state=zero_or_disabled")
            && l.contains("runqueue_wait_ms=na")));
    }

    #[test]
    fn cached_task_census_preserves_real_sampling_interval_and_does_not_invent_samples() {
        let root = Path::new("/nonexistent/ocbm-fixture");
        let mut old = Snapshot::read(root);
        let key = TaskKey {
            pid: 42,
            tid: 42,
            start: 99,
            thread: false,
        };
        let task = Task {
            name: "worker".into(),
            state: "R".into(),
            ticks: 100,
            start: 99,
            rss_pages: 8,
            threads: 1,
            blkio_ticks: None,
            sched: None,
        };
        old.tasks.insert(key, task.clone());
        let mut cached = Snapshot::read_with_tasks(root, Some(&old));
        cached.at = old.at + PERIOD;
        assert_eq!(cached.tasks_at, old.tasks_at);
        let mut window = Window::new(old.at);
        window.observe(&old, &cached, Some(100), Duration::ZERO);
        assert!(window.tasks.is_empty());
        let mut fresh = Snapshot::read(root);
        fresh.at = old.at + REPORT_PERIOD;
        fresh.tasks_at = fresh.at;
        fresh.tasks.insert(key, Task { ticks: 150, ..task });
        window.observe(&cached, &fresh, Some(100), Duration::ZERO);
        assert_eq!(window.tasks[&key].samples, 1);
        assert!((window.tasks[&key].peak_cpu - 10.0).abs() < 0.001);
        assert!(window
            .report(&fresh, Some(100), Some(4096))
            .iter()
            .any(|l| l.starts_with("[process-perf]") && l.contains("cpu_pct_core=10.0")));
    }
}
