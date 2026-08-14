//! In-process audio decode + playback.
//!
//! Each audio track is its own independent session (own demuxer, own decode, own output-device
//! stream).

use anyhow::{Context as AnyhowContext, Result, anyhow, bail};
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use ffmpeg::codec;
use ffmpeg::format::Sample as SampleFormat;
use ffmpeg::format::context::Input;
use ffmpeg::format::sample::Type as SampleType;
use ffmpeg::media::Type as MediaType;
use ffmpeg::software::resampling;
use ffmpeg::util::channel_layout::ChannelLayout;
use ffmpeg::util::frame::audio::Audio as AudioFrame;
use ffmpeg::Dictionary;
use ffmpeg_next as ffmpeg;
use log::{error, info, warn};
use rtrb::RingBuffer;
use std::collections::HashMap;
use std::ffi::c_void;
use std::sync::atomic::{AtomicBool, AtomicI64, AtomicU32, AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::Duration;

use crate::acoustics::{self, AcousticQuality, RenderChain, SourceAcousticState};
use crate::session::{ERR_BAD_HANDLE, ERR_IO, USER_AGENT, init_ffmpeg, interrupt_cb, referer_for, url_for_log};

pub const AUDIO_ABI_VERSION: u32 = 1;

/// ~2s of lookahead at a typical 48kHz stereo device rate.
const RING_CAPACITY_SAMPLES: usize = 48_000 * 2 * 2;

const NO_SEEK: i64 = i64::MIN;

// `cpal::Stream` isn't `Send`; every access is already serialized through `AudioSession.stream`'s
// mutex, so this is safe.
struct SendStream(cpal::Stream);
unsafe impl Send for SendStream {}

enum AudioSource {
    Direct { url: String },
    /// fMP4-safe, decoded one segment at a time; live polls for new segments, VOD stops at `#EXT-X-ENDLIST`.
    Hls { playlist_url: String },
}

struct AudioShared {
    volume_bits: AtomicU32,
    paused: AtomicBool,
    interrupted: Arc<AtomicBool>,
    /// Sample-time (`AV_TIME_BASE` microseconds) for direct sources, nanos-into-playlist for HLS.
    seek_target: AtomicI64,
    /// Bumped on every seek; the cpal callback drains stale ring contents once per bump.
    seek_generation: AtomicU64,
    last_drained_generation: AtomicU64,
    /// Frames (not samples) handed to the output device, silence included — the position clock.
    frames_written: AtomicU64,
    device_sample_rate: AtomicU32,
    device_channels: AtomicU32,
    closing: AtomicBool,
    error: Mutex<String>,
    acoustics_state: Mutex<Option<SourceAcousticState>>,
    /// Rolling raw (pre-DSP) PCM, capped at [`PCM_RING_CAP_SECONDS`] — the reappearance bridge's prelude source.
    pcm_ring: Mutex<std::collections::VecDeque<f32>>,
    pcm_ring_cap: usize,
    /// Total samples ever pushed; combined with `frames_written`, gives the unplayed tail to exclude from a snapshot.
    samples_pushed: AtomicU64,
    bridge_live_url: Mutex<Option<String>>,
}

const PCM_RING_CAP_SECONDS: u32 = 30;

impl AudioShared {
    fn volume(&self) -> f32 {
        f32::from_bits(self.volume_bits.load(Ordering::Relaxed))
    }
}

struct AudioSession {
    id: AtomicI64,
    shared: Arc<AudioShared>,
    stream: Mutex<Option<SendStream>>,
    decode_thread: Mutex<Option<JoinHandle<()>>>,
}

pub struct AudioSessions {
    map: Mutex<HashMap<i64, Arc<AudioSession>>>,
    next: AtomicI64,
}

impl AudioSessions {
    pub fn new() -> AudioSessions {
        AudioSessions {
            map: Mutex::new(HashMap::new()),
            next: AtomicI64::new(1),
        }
    }

    fn get(&self, handle: i64) -> Option<Arc<AudioSession>> {
        self.map.lock().ok()?.get(&handle).cloned()
    }

    fn insert(&self, session: AudioSession) -> i64 {
        let handle = self.next.fetch_add(1, Ordering::Relaxed);
        session.id.store(handle, Ordering::Relaxed);
        if let Ok(mut map) = self.map.lock() {
            map.insert(handle, Arc::new(session));
        }
        handle
    }

    pub fn open(&self, url: &str, start_micros: i64) -> i64 {
        let source = AudioSource::Direct { url: url.to_string() };
        match AudioSession::open(source, start_micros) {
            Ok(session) => {
                let handle = self.insert(session);
                info!("Opened LAV audio session #{handle}: {}.", url_for_log(url));
                handle
            }
            Err(e) => {
                error!("Failed to open LAV audio session for {}: {e:#}.", url_for_log(url));
                0
            }
        }
    }

    pub fn open_hls(&self, playlist_url: &str, start_nanos: i64) -> i64 {
        let source = AudioSource::Hls { playlist_url: playlist_url.to_string() };
        match AudioSession::open(source, start_nanos) {
            Ok(session) => {
                let handle = self.insert(session);
                info!("Opened LAV HLS audio session #{handle}: {}.", url_for_log(playlist_url));
                handle
            }
            Err(e) => {
                error!("Failed to open LAV HLS audio session for {}: {e:#}.", url_for_log(playlist_url));
                0
            }
        }
    }

    pub fn open_bridge(&self, prelude: Vec<u8>, prelude_sample_rate: u32, prelude_channels: u32, live_edge_nanos: i64) -> i64 {
        match AudioSession::open_bridge(prelude, prelude_sample_rate, prelude_channels, live_edge_nanos) {
            Ok(session) => {
                let handle = self.insert(session);
                info!("Opened LAV audio bridge #{handle} (live edge {} ms).", live_edge_nanos / 1_000_000);
                handle
            }
            Err(e) => {
                error!("Failed to open LAV audio bridge: {e:#}.");
                0
            }
        }
    }

    pub fn provide_live(&self, handle: i64, url: &str) -> i32 {
        let Some(session) = self.get(handle) else { return ERR_BAD_HANDLE };
        if let Ok(mut u) = session.shared.bridge_live_url.lock() {
            *u = Some(url.to_string());
        }
        0
    }

    pub fn snapshot_pcm(&self, handle: i64, max_samples: usize) -> Vec<f32> {
        let Some(session) = self.get(handle) else { return Vec::new() };
        let shared = &session.shared;
        let channels = shared.device_channels.load(Ordering::Relaxed).max(1) as u64;
        let pushed = shared.samples_pushed.load(Ordering::Relaxed);
        let written_samples = shared.frames_written.load(Ordering::Relaxed).saturating_mul(channels);
        let unplayed = pushed.saturating_sub(written_samples) as usize;

        let Ok(ring) = shared.pcm_ring.lock() else { return Vec::new() };
        let audible_len = ring.len().saturating_sub(unplayed);
        let channels = channels as usize;
        let audible_len = (audible_len / channels) * channels;
        let take = (audible_len.min(max_samples) / channels) * channels;
        if take == 0 {
            return Vec::new();
        }
        let start = audible_len - take;
        ring.iter().skip(start).take(take).copied().collect()
    }

    pub fn device_format(&self, handle: i64) -> (u32, u32) {
        let Some(session) = self.get(handle) else { return (0, 0) };
        (
            session.shared.device_sample_rate.load(Ordering::Relaxed),
            session.shared.device_channels.load(Ordering::Relaxed),
        )
    }

    pub fn seek(&self, handle: i64, target: i64) -> i32 {
        let Some(session) = self.get(handle) else { return ERR_BAD_HANDLE };
        session.shared.seek_target.store(target, Ordering::SeqCst);
        session.shared.interrupted.store(true, Ordering::SeqCst);
        0
    }

    pub fn set_volume(&self, handle: i64, volume: f32) -> i32 {
        let Some(session) = self.get(handle) else { return ERR_BAD_HANDLE };
        session.shared.volume_bits.store(volume.max(0.0).to_bits(), Ordering::Relaxed);
        0
    }

    pub fn set_acoustics(&self, handle: i64, state: Option<SourceAcousticState>) -> i32 {
        let Some(session) = self.get(handle) else { return ERR_BAD_HANDLE };
        if let Ok(mut s) = session.shared.acoustics_state.lock() {
            *s = state;
        }
        0
    }

    pub fn pause(&self, handle: i64) -> i32 {
        let Some(session) = self.get(handle) else { return ERR_BAD_HANDLE };
        session.shared.paused.store(true, Ordering::Relaxed);
        if let Ok(stream) = session.stream.lock() {
            if let Some(s) = stream.as_ref() {
                let _ = s.0.pause();
            }
        }
        0
    }

    pub fn resume(&self, handle: i64) -> i32 {
        let Some(session) = self.get(handle) else { return ERR_BAD_HANDLE };
        session.shared.paused.store(false, Ordering::Relaxed);
        if let Ok(stream) = session.stream.lock() {
            if let Some(s) = stream.as_ref() {
                let _ = s.0.play();
            }
        }
        0
    }

    pub fn position_nanos(&self, handle: i64) -> i64 {
        let Some(session) = self.get(handle) else { return 0 };
        let rate = session.shared.device_sample_rate.load(Ordering::Relaxed).max(1) as u64;
        let frames = session.shared.frames_written.load(Ordering::Relaxed);
        ((frames as u128 * 1_000_000_000u128) / rate as u128) as i64
    }

    pub fn error(&self, handle: i64, dst: &mut [u8]) -> i32 {
        let Some(session) = self.get(handle) else { return ERR_BAD_HANDLE };
        let Ok(err) = session.shared.error.lock() else { return ERR_IO };
        let bytes = err.as_bytes();
        let n = bytes.len().min(dst.len());
        dst[..n].copy_from_slice(&bytes[..n]);
        n as i32
    }

    pub fn kill(&self, handle: i64) {
        if let Some(session) = self.get(handle) {
            session.shared.interrupted.store(true, Ordering::SeqCst);
        }
    }

    pub fn close(&self, handle: i64) {
        let session = {
            let Ok(mut map) = self.map.lock() else { return };
            map.remove(&handle)
        };
        let Some(session) = session else { return };
        session.shared.closing.store(true, Ordering::SeqCst);
        session.shared.interrupted.store(true, Ordering::SeqCst);
        if let Ok(mut stream) = session.stream.lock() {
            *stream = None;
        }
        if let Ok(mut thread) = session.decode_thread.lock() {
            if let Some(handle) = thread.take() {
                let _ = handle.join();
            }
        }
        info!("Closed LAV audio session #{handle}.");
    }
}

struct OpenedDevice {
    shared: Arc<AudioShared>,
    stream: cpal::Stream,
    producer: rtrb::Producer<f32>,
    legacy_producer: rtrb::Producer<f32>,
    device_sample_rate: u32,
    device_channels: u32,
}

fn open_device() -> Result<OpenedDevice> {
    init_ffmpeg()?;

    let host = cpal::default_host();
    let device = host
        .default_output_device()
        .ok_or_else(|| anyhow!("no default audio output device"))?;
    let config = device.default_output_config().context("query default output config")?;
    let device_sample_rate = config.sample_rate();
    let device_channels = config.channels() as u32;

    let (producer, consumer) = RingBuffer::<f32>::new(RING_CAPACITY_SAMPLES);
    let (legacy_producer, legacy_consumer) = RingBuffer::<f32>::new(RING_CAPACITY_SAMPLES);

    let pcm_ring_cap = (device_sample_rate as usize) * (device_channels.max(1) as usize) * (PCM_RING_CAP_SECONDS as usize);
    let shared = Arc::new(AudioShared {
        volume_bits: AtomicU32::new(1.0f32.to_bits()),
        paused: AtomicBool::new(false),
        interrupted: Arc::new(AtomicBool::new(false)),
        seek_target: AtomicI64::new(NO_SEEK),
        seek_generation: AtomicU64::new(0),
        last_drained_generation: AtomicU64::new(0),
        frames_written: AtomicU64::new(0),
        device_sample_rate: AtomicU32::new(device_sample_rate),
        device_channels: AtomicU32::new(device_channels),
        closing: AtomicBool::new(false),
        error: Mutex::new(String::new()),
        acoustics_state: Mutex::new(None),
        pcm_ring: Mutex::new(std::collections::VecDeque::with_capacity(pcm_ring_cap.min(1 << 20))),
        pcm_ring_cap,
        samples_pushed: AtomicU64::new(0),
        bridge_live_url: Mutex::new(None),
    });

    let stream = build_output_stream(&device, &config, device_channels, shared.clone(), consumer, legacy_consumer)
        .context("build cpal output stream")?;
    stream.play().context("start cpal output stream")?;

    Ok(OpenedDevice { shared, stream, producer, legacy_producer, device_sample_rate, device_channels })
}

impl AudioSession {
    fn open(source: AudioSource, start: i64) -> Result<AudioSession> {
        let opened = open_device()?;
        let (device_sample_rate, device_channels) = (opened.device_sample_rate, opened.device_channels);
        let decode_shared = opened.shared.clone();
        let producer = opened.producer;
        let legacy_producer = opened.legacy_producer;
        let decode_thread = std::thread::Builder::new()
            .name("dd-lav-audio-decode".into())
            .spawn(move || {
                run_decode_loop(source, start, device_sample_rate, device_channels, producer, legacy_producer, decode_shared);
            })
            .context("spawn audio decode thread")?;

        Ok(AudioSession {
            id: AtomicI64::new(0),
            shared: opened.shared,
            stream: Mutex::new(Some(SendStream(opened.stream))),
            decode_thread: Mutex::new(Some(decode_thread)),
        })
    }

    /// Plays `prelude` immediately, then blocks for [`AudioSessions::provide_live`] and continues
    /// decoding on the same session (same rings, same `RenderChain`), so filter / reverb state
    /// carries over the handoff.
    fn open_bridge(prelude: Vec<u8>, prelude_sample_rate: u32, prelude_channels: u32, live_edge_nanos: i64) -> Result<AudioSession> {
        let opened = open_device()?;
        let (device_sample_rate, device_channels) = (opened.device_sample_rate, opened.device_channels);
        let decode_shared = opened.shared.clone();
        let producer = opened.producer;
        let legacy_producer = opened.legacy_producer;
        let decode_thread = std::thread::Builder::new()
            .name("dd-lav-audio-bridge".into())
            .spawn(move || {
                run_bridge_loop(
                    prelude,
                    prelude_sample_rate,
                    prelude_channels,
                    live_edge_nanos,
                    device_sample_rate,
                    device_channels,
                    producer,
                    legacy_producer,
                    decode_shared,
                );
            })
            .context("spawn audio bridge thread")?;

        Ok(AudioSession {
            id: AtomicI64::new(0),
            shared: opened.shared,
            stream: Mutex::new(Some(SendStream(opened.stream))),
            decode_thread: Mutex::new(Some(decode_thread)),
        })
    }
}

/// Dispatches on the device's own sample format since `cpal` only builds against a format it
/// actually supports; the rings are always f32, converted per-sample on write.
fn build_output_stream(
    device: &cpal::Device,
    config: &cpal::SupportedStreamConfig,
    channels: u32,
    shared: Arc<AudioShared>,
    mut consumer: rtrb::Consumer<f32>,
    mut legacy_consumer: rtrb::Consumer<f32>,
) -> Result<cpal::Stream> {
    let stream_config: cpal::StreamConfig = (*config).into();
    let err_shared = shared.clone();
    let err_fn = move |e: cpal::Error| {
        if let Ok(mut err) = err_shared.error.lock() {
            *err = format!("{e}");
        }
        warn!("cpal audio stream error: {e}.");
    };

    let stream = match config.sample_format() {
        cpal::SampleFormat::F32 => device.build_output_stream(
            stream_config,
            move |out: &mut [f32], _: &cpal::OutputCallbackInfo| fill(out, channels, &shared, &mut consumer, &mut legacy_consumer, |v| v),
            err_fn,
            None,
        ),
        cpal::SampleFormat::I16 => device.build_output_stream(
            stream_config,
            move |out: &mut [i16], _: &cpal::OutputCallbackInfo| {
                fill(out, channels, &shared, &mut consumer, &mut legacy_consumer, |v| (v.clamp(-1.0, 1.0) * i16::MAX as f32) as i16)
            },
            err_fn,
            None,
        ),
        cpal::SampleFormat::U16 => device.build_output_stream(
            stream_config,
            move |out: &mut [u16], _: &cpal::OutputCallbackInfo| {
                fill(out, channels, &shared, &mut consumer, &mut legacy_consumer, |v| {
                    ((v.clamp(-1.0, 1.0) * 0.5 + 0.5) * u16::MAX as f32) as u16
                })
            },
            err_fn,
            None,
        ),
        other => bail!("unsupported cpal output sample format: {other:?}"),
    }?;
    Ok(stream)
}

/// The real-time cpal callback: real-time thread, no allocation, no locks beyond shared atomics.
/// Both rings are always drained together (they're always filled together) and the current tier
/// picks which one is actually audible — the choice is made here, at the last possible moment,
/// not baked into samples that may have been queued up to `RING_CAPACITY_SAMPLES` ago.
fn fill<S: Copy>(
    out: &mut [S],
    channels: u32,
    shared: &AudioShared,
    consumer: &mut rtrb::Consumer<f32>,
    legacy_consumer: &mut rtrb::Consumer<f32>,
    convert: impl Fn(f32) -> S,
) {
    let generation = shared.seek_generation.load(Ordering::Acquire);
    if shared.last_drained_generation.swap(generation, Ordering::AcqRel) != generation {
        while consumer.pop().is_ok() {}
        while legacy_consumer.pop().is_ok() {}
    }

    let paused = shared.paused.load(Ordering::Relaxed);
    let use_legacy = acoustics::globals().quality() == AcousticQuality::Off;
    for slot in out.iter_mut() {
        let processed = consumer.pop().unwrap_or(0.0);
        let legacy = legacy_consumer.pop().unwrap_or(0.0);
        let sample = if paused { 0.0 } else if use_legacy { legacy } else { processed };
        *slot = convert(sample);
    }
    // Paused must hold position (mirrors a stopped SourceDataLine).
    if !paused {
        shared
            .frames_written
            .fetch_add((out.len() as u64) / (channels.max(1) as u64), Ordering::Relaxed);
    }
}

/// Owns the session's [`RenderChain`] for its whole lifetime (not per HLS segment) so filter / reverb
/// state survives segment boundaries and only resets on an actual seek.
fn run_decode_loop(
    source: AudioSource,
    start: i64,
    device_sample_rate: u32,
    device_channels: u32,
    mut producer: rtrb::Producer<f32>,
    mut legacy_producer: rtrb::Producer<f32>,
    shared: Arc<AudioShared>,
) {
    let mut render_chain = RenderChain::new(device_sample_rate as f32);
    let result = match &source {
        AudioSource::Direct { url } => decode_direct(
            url, start, device_sample_rate, device_channels, &mut producer, &mut legacy_producer, &shared, &mut render_chain,
        ),
        AudioSource::Hls { playlist_url } => decode_hls(
            playlist_url, start, device_sample_rate, device_channels, &mut producer, &mut legacy_producer, &shared, &mut render_chain,
        ),
    };
    if let Err(e) = result {
        if !shared.closing.load(Ordering::Relaxed) {
            warn!("LAV audio decode loop ended: {e:#}.");
            if let Ok(mut err) = shared.error.lock() {
                *err = format!("{e:#}");
            }
        }
    }
}

fn decode_direct(
    url: &str,
    start_micros: i64,
    device_sample_rate: u32,
    device_channels: u32,
    producer: &mut rtrb::Producer<f32>,
    legacy_producer: &mut rtrb::Producer<f32>,
    shared: &Arc<AudioShared>,
    render_chain: &mut RenderChain,
) -> Result<()> {
    let mut ictx = open_input(url, shared)?;
    if start_micros > 0 {
        seek_input(&mut ictx, start_micros)?;
    }
    decode_stream(
        &mut ictx, 0, device_sample_rate, device_channels, producer, legacy_producer, shared, render_chain,
        |ictx, target| seek_input(ictx, target),
    )
}

fn run_bridge_loop(
    prelude: Vec<u8>,
    prelude_sample_rate: u32,
    prelude_channels: u32,
    live_edge_nanos: i64,
    device_sample_rate: u32,
    device_channels: u32,
    mut producer: rtrb::Producer<f32>,
    mut legacy_producer: rtrb::Producer<f32>,
    shared: Arc<AudioShared>,
) {
    let mut render_chain = RenderChain::new(device_sample_rate as f32);
    let result = (|| -> Result<()> {
        play_prelude(
            &prelude,
            prelude_sample_rate,
            prelude_channels,
            device_sample_rate,
            device_channels,
            &mut producer,
            &mut legacy_producer,
            &shared,
            &mut render_chain,
        )?;

        let url = await_live_url(&shared);
        let Some(url) = url else {
            return Ok(());
        };
        info!("LAV audio bridge: prelude done, handing off to live source (edge {} ms).", live_edge_nanos / 1_000_000);
        decode_direct(&url, 0, device_sample_rate, device_channels, &mut producer, &mut legacy_producer, &shared, &mut render_chain)
    })();
    if let Err(e) = result {
        if !shared.closing.load(Ordering::Relaxed) {
            warn!("LAV audio bridge ended: {e:#}.");
            if let Ok(mut err) = shared.error.lock() {
                *err = format!("{e:#}");
            }
        }
    }
}

fn play_prelude(
    prelude: &[u8],
    src_rate: u32,
    src_channels: u32,
    device_sample_rate: u32,
    device_channels: u32,
    producer: &mut rtrb::Producer<f32>,
    legacy_producer: &mut rtrb::Producer<f32>,
    shared: &Arc<AudioShared>,
    render_chain: &mut RenderChain,
) -> Result<()> {
    if prelude.len() < 4 {
        return Ok(());
    }
    let src_samples = bytemuck_f32(&prelude[..(prelude.len() / 4) * 4]);
    let frames = src_samples.len() / src_channels.max(1) as usize;
    if frames == 0 {
        return Ok(());
    }

    let mut discard = 0i64;
    if src_rate == device_sample_rate && src_channels == device_channels {
        let mut frame = AudioFrame::new(SampleFormat::F32(SampleType::Packed), frames, ChannelLayout::default(device_channels as i32));
        frame.set_rate(device_sample_rate);
        frame.data_mut(0)[..src_samples.len() * 4].copy_from_slice(bytemuck_bytes(src_samples));
        apply_acoustics_and_push(&frame, device_channels, &mut discard, producer, legacy_producer, shared, render_chain, false)?;
        return Ok(());
    }

    let mut src_frame = AudioFrame::new(SampleFormat::F32(SampleType::Packed), frames, ChannelLayout::default(src_channels as i32));
    src_frame.set_rate(src_rate);
    src_frame.data_mut(0)[..src_samples.len() * 4].copy_from_slice(bytemuck_bytes(src_samples));

    let mut resampler = resampling::Context::get(
        SampleFormat::F32(SampleType::Packed),
        ChannelLayout::default(src_channels as i32),
        src_rate,
        SampleFormat::F32(SampleType::Packed),
        ChannelLayout::default(device_channels as i32),
        device_sample_rate,
    )
    .context("build prelude resampler")?;
    let mut resampled = AudioFrame::empty();
    resampler.run(&src_frame, &mut resampled).context("resample prelude")?;
    apply_acoustics_and_push(&resampled, device_channels, &mut discard, producer, legacy_producer, shared, render_chain, false)
}

fn bytemuck_bytes(samples: &[f32]) -> &[u8] {
    // Safety: f32 has no padding, every byte pattern is valid.
    unsafe { std::slice::from_raw_parts(samples.as_ptr().cast::<u8>(), samples.len() * 4) }
}

fn await_live_url(shared: &Arc<AudioShared>) -> Option<String> {
    loop {
        if shared.closing.load(Ordering::Relaxed) {
            return None;
        }
        if let Ok(mut u) = shared.bridge_live_url.lock() {
            if let Some(url) = u.take() {
                return Some(url);
            }
        }
        std::thread::sleep(Duration::from_millis(20));
    }
}

fn open_input(url: &str, shared: &Arc<AudioShared>) -> Result<Input> {
    let mut opts = Dictionary::new();
    opts.set("user_agent", USER_AGENT);
    opts.set("headers", &format!("Referer: {}\r\n", referer_for(url)));
    #[cfg(not(test))]
    opts.set("protocol_whitelist", "https,tls,tcp,crypto,data,http");
    #[cfg(test)]
    opts.set("protocol_whitelist", "https,tls,tcp,crypto,data,http,file");
    opts.set("reconnect", "1");
    opts.set("reconnect_streamed", "1");
    opts.set("reconnect_delay_max", "10");
    opts.set("reconnect_on_network_error", "1");
    opts.set("reconnect_on_http_error", "5xx");
    opts.set("rw_timeout", "15000000");

    let mut ictx = ffmpeg::format::input_with_dictionary(&url, opts).context("open audio input stream")?;
    unsafe {
        let p = ictx.as_mut_ptr();
        (*p).interrupt_callback.callback = Some(interrupt_cb);
        (*p).interrupt_callback.opaque = Arc::as_ptr(&shared.interrupted) as *mut c_void;
    }
    Ok(ictx)
}

fn seek_input(ictx: &mut Input, target_micros: i64) -> Result<()> {
    if ictx.seek(target_micros, ..target_micros).is_err() {
        ictx.seek(target_micros, ..)
            .with_context(|| format!("seek to {} ms", target_micros / 1_000))?;
    }
    Ok(())
}

/// `reseek` repositions `ictx` in place for direct sources; HLS returns on a pending seek instead
/// and lets [`decode_hls`] pick a new starting segment.
fn decode_stream(
    ictx: &mut Input,
    discard_nanos: i64,
    device_sample_rate: u32,
    device_channels: u32,
    producer: &mut rtrb::Producer<f32>,
    legacy_producer: &mut rtrb::Producer<f32>,
    shared: &Arc<AudioShared>,
    render_chain: &mut RenderChain,
    mut reseek: impl FnMut(&mut Input, i64) -> Result<()>,
) -> Result<()> {
    let input = ictx.streams().best(MediaType::Audio).context("no audio stream found in input")?;
    let stream_index = input.index();
    let params = input.parameters();

    let codec = codec::decoder::find(params.id())
        .with_context(|| format!("no audio decoder for codec {:?}", params.id()))?;
    let mut decoder = codec::context::Context::from_parameters(params.clone())
        .context("build decoder context")?
        .decoder()
        .open_as(codec)
        .context("open audio decoder")?
        .audio()
        .context("codec is not audio")?;

    let dst_layout = ChannelLayout::default(device_channels as i32);
    // Built lazily from the first decoded frame's own rate, not decoder.rate() (the codec's
    // pre-decode nominal value): for HE-AAC/SBR that's half the real post-SBR rate, which played
    // everything back at 2x speed. Rebuilt if a later frame's rate ever disagrees.
    let mut resampler: Option<resampling::Context> = None;
    let mut resampler_src_rate: u32 = 0;

    let mut discard_remaining_nanos = discard_nanos.max(0);
    let mut resampled = AudioFrame::empty();

    loop {
        if shared.closing.load(Ordering::Relaxed) {
            return Ok(());
        }
        let pending_seek = shared.seek_target.swap(NO_SEEK, Ordering::SeqCst);
        if pending_seek != NO_SEEK {
            shared.interrupted.store(false, Ordering::SeqCst);
            shared.seek_generation.fetch_add(1, Ordering::AcqRel);
            reseek(ictx, pending_seek)?;
            render_chain.reset();
        }

        let mut packet = ffmpeg::Packet::empty();
        let mut drained_eof = false;
        match packet.read(ictx) {
            Ok(()) => {
                if packet.stream() != stream_index {
                    continue;
                }
                decoder.send_packet(&packet).ok();
            }
            Err(ffmpeg::Error::Eof) => {
                decoder.send_eof().ok();
                drained_eof = true;
            }
            Err(e) => {
                if shared.interrupted.load(Ordering::Relaxed) && shared.seek_target.load(Ordering::Relaxed) == NO_SEEK {
                    if shared.closing.load(Ordering::Relaxed) {
                        return Ok(());
                    }
                    return Ok(());
                }
                return Err(anyhow!(e)).context("demux audio packet");
            }
        }

        let mut decoded = AudioFrame::empty();
        while decoder.receive_frame(&mut decoded).is_ok() {
            let frame_rate = decoded.rate();
            if resampler.is_none() || frame_rate != resampler_src_rate {
                let src_layout = if decoded.channel_layout().channels() > 0 {
                    decoded.channel_layout()
                } else {
                    ChannelLayout::default(decoded.channels() as i32)
                };
                resampler = Some(
                    resampling::Context::get(
                        decoded.format(),
                        src_layout,
                        frame_rate,
                        SampleFormat::F32(SampleType::Packed),
                        dst_layout,
                        device_sample_rate,
                    )
                    .context("build audio resampler")?,
                );
                resampler_src_rate = frame_rate;
            }
            resampler.as_mut().unwrap().run(&decoded, &mut resampled).context("resample audio frame")?;
            apply_acoustics_and_push(&resampled, device_channels, &mut discard_remaining_nanos, producer, legacy_producer, shared, render_chain, true)?;
        }

        if drained_eof {
            return Ok(());
        }
    }
}

/// Deinterleaves, runs the acoustics chain, and pushes the result into `producer`; a plain
/// legacy-gain-scaled copy is pushed into `legacy_producer` in lockstep so `fill` can switch
/// between the two instantly. Only stereo output devices get the acoustics chain — mono/
/// multichannel gets legacy gain in both rings (nothing to switch between).
fn apply_acoustics_and_push(
    frame: &AudioFrame,
    channels: u32,
    discard_remaining_nanos: &mut i64,
    producer: &mut rtrb::Producer<f32>,
    legacy_producer: &mut rtrb::Producer<f32>,
    shared: &Arc<AudioShared>,
    render_chain: &mut RenderChain,
    cache_for_bridge: bool,
) -> Result<()> {
    let frame_samples = frame.samples();
    if frame_samples == 0 {
        return Ok(());
    }
    let data = frame.data(0);
    let interleaved: &[f32] = bytemuck_f32(&data[..frame_samples * channels as usize * 4]);

    let mut start_frame = 0usize;
    if *discard_remaining_nanos > 0 {
        if let Some(rate) = frame.rate().checked_mul(1).filter(|&r| r > 0) {
            let frame_duration_nanos = (frame_samples as i64) * 1_000_000_000 / rate as i64;
            if frame_duration_nanos <= *discard_remaining_nanos {
                *discard_remaining_nanos -= frame_duration_nanos;
                return Ok(());
            }
            let discard_frac = (*discard_remaining_nanos as f64 / frame_duration_nanos as f64).clamp(0.0, 1.0);
            start_frame = (frame_samples as f64 * discard_frac) as usize;
            *discard_remaining_nanos = 0;
        }
    }
    if start_frame >= frame_samples {
        return Ok(());
    }
    let kept_frames = frame_samples - start_frame;
    let kept_raw = &interleaved[start_frame * channels as usize..];

    // Cached pre-DSP, before render_chain alters it below. Skipped for the bridge's own prelude
    // playback (already-cached audio) to avoid duplicating it.
    if cache_for_bridge {
        if let Ok(mut ring) = shared.pcm_ring.lock() {
            ring.extend(kept_raw.iter().copied());
            while ring.len() > shared.pcm_ring_cap {
                ring.pop_front();
            }
        }
    }

    let legacy_gain = shared.volume();
    let legacy_output: Vec<f32> = kept_raw.iter().map(|s| s * legacy_gain).collect();

    let spatial_output: Vec<f32> = if channels == 2 {
        let mut l = vec![0f32; kept_frames];
        let mut r = vec![0f32; kept_frames];
        for i in 0..kept_frames {
            l[i] = interleaved[(start_frame + i) * 2];
            r[i] = interleaved[(start_frame + i) * 2 + 1];
        }
        let state = shared.acoustics_state.lock().ok().and_then(|g| *g);
        render_chain.process(&mut l, &mut r, legacy_gain, state.as_ref(), acoustics::globals());
        let mut interleaved_out = vec![0f32; kept_frames * 2];
        for i in 0..kept_frames {
            interleaved_out[i * 2] = l[i];
            interleaved_out[i * 2 + 1] = r[i];
        }
        interleaved_out
    } else {
        legacy_output.clone()
    };

    for i in 0..spatial_output.len() {
        loop {
            if shared.closing.load(Ordering::Relaxed) {
                return Ok(());
            }
            if shared.seek_target.load(Ordering::Relaxed) != NO_SEEK {
                return Ok(());
            }
            match producer.push(spatial_output[i]) {
                Ok(()) => break,
                Err(_) => std::thread::sleep(Duration::from_millis(2)),
            }
        }
        // Both rings are the same capacity and always pushed together, so this should never be
        // full when `producer`'s push above just succeeded; if it somehow is, drop this one
        // sample rather than block the primary ring on it.
        let _ = legacy_producer.push(legacy_output[i]);
    }
    shared.samples_pushed.fetch_add(spatial_output.len() as u64, Ordering::Relaxed);
    Ok(())
}

fn bytemuck_f32(bytes: &[u8]) -> &[f32] {
    // Safety: sub-slice of an AudioFrame's F32 data plane, length always a multiple of 4.
    unsafe { std::slice::from_raw_parts(bytes.as_ptr().cast::<f32>(), bytes.len() / 4) }
}

// ---------------------------------------------------------------------------------------------
// HLS: segment-by-segment decode (VOD or live), sidestepping the fMP4 demuxer-seek bug entirely.
// ---------------------------------------------------------------------------------------------

struct MediaPlaylist {
    media_sequence: i64,
    target_duration_nanos: i64,
    segments: Vec<(String, i64)>,
    end_list: bool,
}

fn fetch_playlist(playlist_url: &str) -> Result<MediaPlaylist> {
    let body = ureq::get(playlist_url)
        .config()
        .timeout_global(Some(Duration::from_secs(8)))
        .build()
        .call()
        .context("fetch HLS playlist")?
        .body_mut()
        .read_to_string()
        .context("read HLS playlist body")?;
    parse_playlist(&body, playlist_url)
}

fn parse_playlist(body: &str, playlist_url: &str) -> Result<MediaPlaylist> {
    if !body.trim_start().starts_with("#EXTM3U") {
        bail!("not an m3u8 playlist");
    }
    let base = url::Url::parse(playlist_url).context("parse playlist URL")?;
    let mut media_sequence = 0i64;
    let mut target_duration_nanos = 2_000_000_000i64;
    let mut end_list = false;
    let mut segments = Vec::new();
    let mut pending_duration_nanos: Option<i64> = None;

    for raw in body.lines() {
        let line = raw.trim();
        if line.is_empty() {
            continue;
        } else if let Some(rest) = line.strip_prefix("#EXT-X-MEDIA-SEQUENCE:") {
            media_sequence = rest.trim().parse().unwrap_or(media_sequence);
        } else if let Some(rest) = line.strip_prefix("#EXT-X-TARGETDURATION:") {
            if let Ok(seconds) = rest.trim().parse::<f64>() {
                target_duration_nanos = ((seconds * 1_000_000_000.0) as i64).max(500_000_000);
            }
        } else if line == "#EXT-X-ENDLIST" {
            end_list = true;
        } else if let Some(rest) = line.strip_prefix("#EXTINF:") {
            let seconds: f64 = rest.split(',').next().unwrap_or("0").trim().parse().unwrap_or(0.0);
            if seconds.is_finite() && seconds > 0.0 {
                pending_duration_nanos = Some((seconds * 1_000_000_000.0) as i64);
            }
        } else if !line.starts_with('#') {
            if let Some(duration) = pending_duration_nanos.take() {
                let resolved = base.join(line).map(|u| u.to_string()).unwrap_or_else(|_| line.to_string());
                segments.push((resolved, duration));
            }
        }
    }
    Ok(MediaPlaylist { media_sequence, target_duration_nanos, segments, end_list })
}

/// Segment index + residual nanos to discard, for `target_nanos` into the playlist.
fn select_start_segment(playlist: &MediaPlaylist, target_nanos: i64) -> (usize, i64) {
    if target_nanos <= 0 {
        return (0, 0);
    }
    let mut elapsed = 0i64;
    for (index, (_, duration)) in playlist.segments.iter().enumerate() {
        if elapsed + duration > target_nanos {
            return (index, target_nanos - elapsed);
        }
        elapsed += duration;
    }
    (playlist.segments.len().saturating_sub(1), 0)
}

fn decode_hls(
    playlist_url: &str,
    start_nanos: i64,
    device_sample_rate: u32,
    device_channels: u32,
    producer: &mut rtrb::Producer<f32>,
    legacy_producer: &mut rtrb::Producer<f32>,
    shared: &Arc<AudioShared>,
    render_chain: &mut RenderChain,
) -> Result<()> {
    const LIVE_EDGE_SEGMENTS: usize = 3;
    const MAX_PLAYLIST_FAILURES: u32 = 5;

    let mut next_sequence: i64 = -1;
    let mut pending_discard_nanos = start_nanos.max(0);
    let mut playlist_failures = 0u32;

    loop {
        if shared.closing.load(Ordering::Relaxed) {
            return Ok(());
        }

        let target = shared.seek_target.swap(NO_SEEK, Ordering::SeqCst);

        let playlist = match fetch_playlist(playlist_url) {
            Ok(p) => {
                playlist_failures = 0;
                p
            }
            Err(e) => {
                if shared.closing.load(Ordering::Relaxed) {
                    return Ok(());
                }
                playlist_failures += 1;
                if playlist_failures > MAX_PLAYLIST_FAILURES {
                    return Err(e).context("HLS playlist repeatedly unreachable");
                }
                std::thread::sleep(Duration::from_secs(1));
                continue;
            }
        };

        if target != NO_SEEK {
            shared.interrupted.store(false, Ordering::SeqCst);
            shared.seek_generation.fetch_add(1, Ordering::AcqRel);
            let (index, residual) = select_start_segment(&playlist, target);
            next_sequence = playlist.media_sequence + index as i64;
            pending_discard_nanos = residual;
            render_chain.reset();
        } else if next_sequence < playlist.media_sequence {
            if next_sequence >= 0 {
                warn!("LAV HLS audio: fell behind the live window; re-joining near the edge.");
            }
            let edge = playlist.segments.len().saturating_sub(LIVE_EDGE_SEGMENTS);
            next_sequence = playlist.media_sequence + edge as i64;
        }

        let mut wrote_any = false;
        let mut index = (next_sequence - playlist.media_sequence) as usize;
        while index < playlist.segments.len() {
            if shared.closing.load(Ordering::Relaxed) {
                return Ok(());
            }
            if shared.seek_target.load(Ordering::Relaxed) != NO_SEEK {
                break;
            }
            let (segment_url, _) = &playlist.segments[index];
            match open_input(segment_url, shared) {
                Ok(mut ictx) => {
                    let discard = std::mem::take(&mut pending_discard_nanos);
                    let outcome = decode_stream(
                        &mut ictx, discard, device_sample_rate, device_channels, producer, legacy_producer, shared, render_chain,
                        |_, _| Ok(()),
                    );
                    if let Err(e) = outcome {
                        warn!("LAV HLS audio: segment decode failed ({e:#}); skipping one.");
                    }
                    wrote_any = true;
                }
                Err(e) => {
                    warn!("LAV HLS audio: segment fetch/open failed ({e:#}); skipping one.");
                }
            }
            next_sequence += 1;
            index += 1;
        }

        if playlist.end_list && shared.seek_target.load(Ordering::Relaxed) == NO_SEEK {
            return Ok(());
        }
        if !wrote_any {
            std::thread::sleep(Duration::from_nanos((playlist.target_duration_nanos / 2).max(500_000_000) as u64));
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const VOD_PLAYLIST: &str = "#EXTM3U\n\
        #EXT-X-VERSION:7\n\
        #EXT-X-TARGETDURATION:6\n\
        #EXT-X-MEDIA-SEQUENCE:10\n\
        #EXT-X-MAP:URI=\"init.mp4\"\n\
        #EXTINF:6.0,\n\
        seg10.m4s\n\
        #EXTINF:6.0,\n\
        seg11.m4s\n\
        #EXTINF:4.5,\n\
        seg12.m4s\n\
        #EXT-X-ENDLIST\n";

    #[test]
    fn parses_media_sequence_target_duration_and_end_list() {
        let playlist = parse_playlist(VOD_PLAYLIST, "https://cdn.example/audio/playlist.m3u8").unwrap();
        assert_eq!(playlist.media_sequence, 10);
        assert_eq!(playlist.target_duration_nanos, 6_000_000_000);
        assert!(playlist.end_list);
        assert_eq!(playlist.segments.len(), 3);
    }

    #[test]
    fn resolves_segment_urls_against_the_playlist_base() {
        let playlist = parse_playlist(VOD_PLAYLIST, "https://cdn.example/audio/playlist.m3u8").unwrap();
        assert_eq!(playlist.segments[0].0, "https://cdn.example/audio/seg10.m4s");
        assert_eq!(playlist.segments[2].0, "https://cdn.example/audio/seg12.m4s");
    }

    #[test]
    fn rejects_a_body_without_the_extm3u_header() {
        assert!(parse_playlist("not a playlist", "https://cdn.example/a.m3u8").is_err());
    }

    #[test]
    fn select_start_segment_picks_the_segment_spanning_the_target_and_a_residual() {
        let playlist = parse_playlist(VOD_PLAYLIST, "https://cdn.example/audio/playlist.m3u8").unwrap();
        let (index, residual_nanos) = select_start_segment(&playlist, 9_500_000_000);
        assert_eq!(index, 1);
        assert_eq!(residual_nanos, 3_500_000_000);
    }

    #[test]
    fn select_start_segment_at_zero_starts_from_the_first_segment() {
        let playlist = parse_playlist(VOD_PLAYLIST, "https://cdn.example/audio/playlist.m3u8").unwrap();
        assert_eq!(select_start_segment(&playlist, 0), (0, 0));
    }

    #[test]
    fn select_start_segment_past_the_end_clamps_to_the_last_segment() {
        let playlist = parse_playlist(VOD_PLAYLIST, "https://cdn.example/audio/playlist.m3u8").unwrap();
        let (index, _) = select_start_segment(&playlist, 1_000_000_000_000);
        assert_eq!(index, playlist.segments.len() - 1);
    }

    fn test_shared(device_sample_rate: u32, device_channels: u32) -> Arc<AudioShared> {
        Arc::new(AudioShared {
            volume_bits: AtomicU32::new(1.0f32.to_bits()),
            paused: AtomicBool::new(false),
            interrupted: Arc::new(AtomicBool::new(false)),
            seek_target: AtomicI64::new(NO_SEEK),
            seek_generation: AtomicU64::new(0),
            last_drained_generation: AtomicU64::new(0),
            frames_written: AtomicU64::new(0),
            device_sample_rate: AtomicU32::new(device_sample_rate),
            device_channels: AtomicU32::new(device_channels),
            closing: AtomicBool::new(false),
            error: Mutex::new(String::new()),
            acoustics_state: Mutex::new(None),
            pcm_ring: Mutex::new(std::collections::VecDeque::new()),
            pcm_ring_cap: (device_sample_rate * device_channels * 30) as usize,
            samples_pushed: AtomicU64::new(0),
            bridge_live_url: Mutex::new(None),
        })
    }

    /// Regression guard for a decode loop that drops / duplicates packets.
    #[test]
    fn decodes_a_clip_to_its_real_duration_worth_of_samples() {
        const SECONDS: u32 = 2;
        const RATE: u32 = 48_000;
        const CHANNELS: u32 = 2;

        let ffmpeg_bin = std::env::var("DD_TEST_FFMPEG").unwrap_or_else(|_| "ffmpeg".into());
        let dir = std::env::temp_dir().join("dd-lav-audio-test");
        std::fs::create_dir_all(&dir).unwrap();
        let clip = dir.join("tone.m4a");
        let status = std::process::Command::new(&ffmpeg_bin)
            .args([
                "-y",
                "-f",
                "lavfi",
                "-i",
                &format!("sine=frequency=440:sample_rate={RATE}:duration={SECONDS}"),
                "-ac",
                "2",
                "-c:a",
                "aac",
                clip.to_str().unwrap(),
            ])
            .status();
        let Ok(status) = status else { return };
        if !status.success() {
            return;
        }

        init_ffmpeg().unwrap();
        let mut ictx = ffmpeg::format::input(clip.to_str().unwrap()).unwrap();
        let shared = test_shared(RATE, CHANNELS);
        let mut render_chain = RenderChain::new(RATE as f32);
        let (mut producer, _consumer) = RingBuffer::<f32>::new((RATE * CHANNELS * (SECONDS + 2)) as usize);
        let (mut legacy_producer, _legacy_consumer) = RingBuffer::<f32>::new((RATE * CHANNELS * (SECONDS + 2)) as usize);

        decode_stream(
            &mut ictx,
            0,
            RATE,
            CHANNELS,
            &mut producer,
            &mut legacy_producer,
            &shared,
            &mut render_chain,
            |_, _| Ok(()),
        )
        .expect("decode should finish cleanly at EOF");

        let pushed = shared.samples_pushed.load(Ordering::Relaxed);
        let expected = (RATE * CHANNELS * SECONDS) as u64;
        let low = expected * 9 / 10;
        let high = expected * 11 / 10;
        assert!(
            (low..=high).contains(&pushed),
            "Expected ~{expected} samples for {SECONDS}s at {RATE}Hz x{CHANNELS}, got {pushed} \
             ({:.2}x real time) — the decode loop is dropping or duplicating packets.",
            pushed as f64 / expected as f64,
        );
    }

    /// Regression guard: with both rings pushed in lockstep, flipping the global tier must change
    /// which one `fill` reads from immediately — no ring-latency delay.
    #[test]
    fn both_rings_receive_the_same_number_of_samples() {
        const RATE: u32 = 48_000;
        const CHANNELS: u32 = 2;

        let shared = test_shared(RATE, CHANNELS);
        let mut render_chain = RenderChain::new(RATE as f32);
        let (mut producer, mut consumer) = RingBuffer::<f32>::new(4096);
        let (mut legacy_producer, mut legacy_consumer) = RingBuffer::<f32>::new(4096);

        let mut frame = AudioFrame::new(SampleFormat::F32(SampleType::Packed), 64, ChannelLayout::default(2));
        frame.set_rate(RATE);
        let samples: Vec<f32> = (0..128).map(|i| (i as f32 * 0.01).sin()).collect();
        frame.data_mut(0)[..samples.len() * 4].copy_from_slice(bytemuck_bytes(&samples));

        let mut discard = 0i64;
        apply_acoustics_and_push(&frame, CHANNELS, &mut discard, &mut producer, &mut legacy_producer, &shared, &mut render_chain, true).unwrap();

        let mut spatial_count = 0;
        while consumer.pop().is_ok() {
            spatial_count += 1;
        }
        let mut legacy_count = 0;
        while legacy_consumer.pop().is_ok() {
            legacy_count += 1;
        }
        assert_eq!(spatial_count, legacy_count, "both rings must receive the same number of samples to stay in lockstep");
        assert_eq!(spatial_count, 128);
    }

    #[test]
    fn fill_switches_ring_on_the_next_call_after_a_tier_change() {
        let shared = test_shared(48_000, 2);
        let (mut producer, mut consumer) = RingBuffer::<f32>::new(64);
        let (mut legacy_producer, mut legacy_consumer) = RingBuffer::<f32>::new(64);

        for _ in 0..8 {
            producer.push(1.0).unwrap();
            legacy_producer.push(-1.0).unwrap();
        }

        acoustics::globals().set_quality(AcousticQuality::Off as i32);
        let mut out = [0f32; 4];
        fill(&mut out, 2, &shared, &mut consumer, &mut legacy_consumer, |v| v);
        assert_eq!(out, [-1.0; 4], "Off must play the legacy ring even though the spatial ring already has samples queued.");

        acoustics::globals().set_quality(AcousticQuality::Advanced as i32);
        let mut out = [0f32; 4];
        fill(&mut out, 2, &shared, &mut consumer, &mut legacy_consumer, |v| v);
        assert_eq!(out, [1.0; 4], "the very next callback after switching back on must already play the spatial ring.");
    }
}
