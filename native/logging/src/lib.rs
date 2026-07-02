//! Shared stderr logger for the `Dream Displays` native libraries.
//!
//! Both cdylibs (`dreamdisplays_native` and `dreamdisplays_lav`) link this crate statically and
//! call [`init`] from their ABI entry points; the JVM launcher captures the process stderr, so
//! log lines land in the regular game / launcher log next to the `FFmpeg` output.
//!
//! The level is controlled by the `DD_NATIVE_LOG` environment variable
//! (`off` / `error` / `warn` / `info` / `debug` / `trace`, default `info`).

use std::io::Write;
use std::sync::Once;
use std::time::{SystemTime, UNIX_EPOCH};

use log::{Level, LevelFilter, Log, Metadata, Record};

/// Environment variable selecting the log level for the native libraries.
pub const LEVEL_ENV_VAR: &str = "DD_NATIVE_LOG";

static INIT: Once = Once::new();
static LOGGER: StderrLogger = StderrLogger;

/// Installs the stderr logger and applies the `DD_NATIVE_LOG` level. Idempotent and cheap, so
/// every ABI entry point may call it defensively.
pub fn init() {
    INIT.call_once(|| {
        let level = std::env::var(LEVEL_ENV_VAR)
            .ok()
            .and_then(|v| v.trim().parse::<LevelFilter>().ok())
            .unwrap_or(LevelFilter::Info);
        // set_logger here fails only when another logger is already installed in this image;
        // in that case just adopt the requested max level and let the existing sink run.
        let _ = log::set_logger(&LOGGER);
        log::set_max_level(level);
    });
}

/// Extracts a human-readable message from a `catch_unwind` payload (panics carry either a
/// `&'static str` or a `String`; anything else gets a placeholder).
pub fn panic_message(payload: &(dyn std::any::Any + Send)) -> &str {
    payload
        .downcast_ref::<&'static str>()
        .copied()
        .or_else(|| payload.downcast_ref::<String>().map(String::as_str))
        .unwrap_or("non-string panic payload")
}

/// Minimal logger writing `[HH:MM:SS.mmm] [thread/LEVEL] (dd-native) target: message` to stderr.
struct StderrLogger;

impl Log for StderrLogger {
    fn enabled(&self, metadata: &Metadata) -> bool {
        metadata.level() <= log::max_level()
    }

    fn log(&self, record: &Record) {
        if !self.enabled(record.metadata()) {
            return;
        }
        let (h, m, s, ms) = utc_time_of_day();
        let thread = std::thread::current();
        let thread_name = thread.name().unwrap_or("?").to_owned();
        let level = level_label(record.level());
        // Single write keeps concurrent lines from interleaving mid-line.
        let line = format!(
            "[{h:02}:{m:02}:{s:02}.{ms:03}] [{thread_name}/{level}] (dd-native) {}: {}\n",
            record.target(),
            record.args()
        );
        let _ = std::io::stderr().write_all(line.as_bytes());
    }

    fn flush(&self) {
        let _ = std::io::stderr().flush();
    }
}

/// Current UTC time of day as (hours, minutes, seconds, milliseconds).
fn utc_time_of_day() -> (u64, u64, u64, u64) {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default();
    let secs = now.as_secs() % 86_400;
    (
        secs / 3_600,
        (secs / 60) % 60,
        secs % 60,
        u64::from(now.subsec_millis()),
    )
}

/// Uppercase level label matching the launcher-log convention.
fn level_label(level: Level) -> &'static str {
    match level {
        Level::Error => "ERROR",
        Level::Warn => "WARN",
        Level::Info => "INFO",
        Level::Debug => "DEBUG",
        Level::Trace => "TRACE",
    }
}
