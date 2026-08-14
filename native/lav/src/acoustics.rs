//! Environmental-acoustics DSP.

use std::f64::consts::PI as PI64;
use std::sync::{Mutex, OnceLock};
use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};

static GLOBALS: OnceLock<AcousticsGlobals> = OnceLock::new();

pub fn globals() -> &'static AcousticsGlobals {
    GLOBALS.get_or_init(AcousticsGlobals::new)
}

const TARGET_LUFS: f32 = -16.0;
const MAX_LOUDNESS_BOOST_DB: f32 = 12.0;
const MAX_LOUDNESS_CUT_DB: f32 = 3.0;
const MAX_LOUDNESS_SLEW_DB_PER_SEC: f32 = 0.5;
const GAIN_SMOOTH_SECONDS: f32 = 0.08;
const AZIMUTH_SMOOTH_SECONDS: f32 = 0.06;
const OCCLUSION_SMOOTH_SECONDS: f32 = 0.10;
const REVERB_SMOOTH_SECONDS: f32 = 0.15;
const MAX_CUTOFF_HZ: f32 = 18_000.0;
const MIN_OCCLUSION_CUTOFF_HZ: f32 = 550.0;
const OCCLUSION_MIN_GAIN: f32 = 0.5;
const AIR_ABSORPTION_REF_DISTANCE: f32 = 16.0;
const AIR_ABSORPTION_MIN_CUTOFF_HZ: f32 = 6_000.0;
const REVERB_MAX_DECAY_SECONDS: f32 = 3.0;

#[derive(Clone, Copy, Default)]
pub struct Vec3 {
    pub x: f64,
    pub y: f64,
    pub z: f64,
}

impl Vec3 {
    fn sub(self, o: Vec3) -> Vec3 {
        Vec3 { x: self.x - o.x, y: self.y - o.y, z: self.z - o.z }
    }
    fn scale(self, s: f64) -> Vec3 {
        Vec3 { x: self.x * s, y: self.y * s, z: self.z * s }
    }
    fn dot(self, o: Vec3) -> f64 {
        self.x * o.x + self.y * o.y + self.z * o.z
    }
    fn cross(self, o: Vec3) -> Vec3 {
        Vec3 {
            x: self.y * o.z - self.z * o.y,
            y: self.z * o.x - self.x * o.z,
            z: self.x * o.y - self.y * o.x,
        }
    }
    fn length(self) -> f64 {
        self.dot(self).sqrt()
    }
    fn normalized(self) -> Vec3 {
        let len = self.length();
        if len < 1e-9 { Vec3::default() } else { self.scale(1.0 / len) }
    }
}

/// Two-emitter (L / R) planar-screen area source.
mod emitter_layout {
    use super::Vec3;

    pub fn left_emitter(center: Vec3, u_axis: Vec3, width: f64) -> Vec3 {
        center.sub(u_axis.scale(width * 0.25))
    }

    pub fn right_emitter(center: Vec3, u_axis: Vec3, width: f64) -> Vec3 {
        Vec3 { x: center.x + u_axis.x * (width * 0.25), y: center.y + u_axis.y * (width * 0.25), z: center.z + u_axis.z * (width * 0.25) }
    }

    /// `AL_INVERSE_DISTANCE_CLAMPED`-style: flat inside `ref_distance`, rolling off past it.
    pub fn distance_gain(distance: f64, ref_distance: f64, rolloff: f64) -> f64 {
        let ref_d = ref_distance.max(0.1);
        ref_d / (ref_d + rolloff * (distance - ref_d).max(0.0))
    }

    pub fn directivity_gain(normal: Vec3, to_listener_dir: Vec3, back_floor: f64, exponent: f64) -> f64 {
        let cos_theta = normal.dot(to_listener_dir).clamp(-1.0, 1.0);
        let front = ((cos_theta + 1.0) / 2.0).powf(exponent);
        back_floor + (1.0 - back_floor) * front
    }
}

/// One-pole exponential smoother for block-rate control parameters.
struct ParamSmoother {
    time_constant_seconds: f32,
    value: f32,
}

impl ParamSmoother {
    fn new(time_constant_seconds: f32, initial: f32) -> Self {
        Self { time_constant_seconds, value: initial }
    }
    fn next(&mut self, target: f32, dt_seconds: f32) -> f32 {
        if dt_seconds <= 0.0 {
            return self.value;
        }
        let alpha = 1.0 - (-dt_seconds / self.time_constant_seconds).exp();
        self.value += (target - self.value) * alpha;
        self.value
    }
    fn snap(&mut self, v: f32) {
        self.value = v;
    }
}

#[derive(Clone, Copy)]
enum BiquadType {
    LowPass,
    HighPass,
    HighShelf,
}

/// Direct Form I biquad, RBJ Audio EQ Cookbook coefficients.
struct Biquad {
    b0: f32,
    b1: f32,
    b2: f32,
    a1: f32,
    a2: f32,
    x1: f32,
    x2: f32,
    y1: f32,
    y2: f32,
}

impl Biquad {
    fn new() -> Self {
        Self { b0: 1.0, b1: 0.0, b2: 0.0, a1: 0.0, a2: 0.0, x1: 0.0, x2: 0.0, y1: 0.0, y2: 0.0 }
    }

    fn configure(&mut self, kind: BiquadType, sample_rate: f32, freq_hz: f32, q: f32, gain_db: f64) {
        let w0 = 2.0 * PI64 * (freq_hz / sample_rate).clamp(0.0001, 0.499) as f64;
        let cos_w0 = w0.cos();
        let sin_w0 = w0.sin();
        let alpha = sin_w0 / (2.0 * q as f64);
        let (b0d, b1d, b2d, a0d, a1d, a2d) = match kind {
            BiquadType::LowPass => (
                (1.0 - cos_w0) / 2.0,
                1.0 - cos_w0,
                (1.0 - cos_w0) / 2.0,
                1.0 + alpha,
                -2.0 * cos_w0,
                1.0 - alpha,
            ),
            BiquadType::HighPass => (
                (1.0 + cos_w0) / 2.0,
                -(1.0 + cos_w0),
                (1.0 + cos_w0) / 2.0,
                1.0 + alpha,
                -2.0 * cos_w0,
                1.0 - alpha,
            ),
            BiquadType::HighShelf => {
                let a = 10f64.powf(gain_db / 40.0);
                let s = 1.0;
                let alpha_s = sin_w0 / 2.0 * ((a + 1.0 / a) * (1.0 / s - 1.0) + 2.0).sqrt();
                let two_sqrt_a_alpha = 2.0 * a.sqrt() * alpha_s;
                (
                    a * ((a + 1.0) + (a - 1.0) * cos_w0 + two_sqrt_a_alpha),
                    -2.0 * a * ((a - 1.0) + (a + 1.0) * cos_w0),
                    a * ((a + 1.0) + (a - 1.0) * cos_w0 - two_sqrt_a_alpha),
                    (a + 1.0) - (a - 1.0) * cos_w0 + two_sqrt_a_alpha,
                    2.0 * ((a - 1.0) - (a + 1.0) * cos_w0),
                    (a + 1.0) - (a - 1.0) * cos_w0 - two_sqrt_a_alpha,
                )
            }
        };
        self.b0 = (b0d / a0d) as f32;
        self.b1 = (b1d / a0d) as f32;
        self.b2 = (b2d / a0d) as f32;
        self.a1 = (a1d / a0d) as f32;
        self.a2 = (a2d / a0d) as f32;
    }

    fn process(&mut self, x: f32) -> f32 {
        let y = self.b0 * x + self.b1 * self.x1 + self.b2 * self.x2 - self.a1 * self.y1 - self.a2 * self.y2;
        self.x2 = self.x1;
        self.x1 = x;
        self.y2 = self.y1;
        self.y1 = y;
        y
    }

    fn reset(&mut self) {
        self.x1 = 0.0;
        self.x2 = 0.0;
        self.y1 = 0.0;
        self.y2 = 0.0;
    }
}

/// Linear-interpolated delay line for sub-sample ITD delays.
struct FractionalDelayLine {
    buf: Vec<f32>,
    write_idx: usize,
}

impl FractionalDelayLine {
    fn new(capacity: usize) -> Self {
        Self { buf: vec![0.0; capacity], write_idx: 0 }
    }
    fn push(&mut self, x: f32) {
        let cap = self.buf.len();
        self.buf[self.write_idx] = x;
        self.write_idx = (self.write_idx + 1) % cap;
    }
    fn read(&self, delay_samples: f32) -> f32 {
        let cap = self.buf.len();
        let d = delay_samples.clamp(0.0, (cap - 2) as f32);
        let pos = self.write_idx as f32 - 1.0 - d;
        let mut wrapped = pos % cap as f32;
        if wrapped < 0.0 {
            wrapped += cap as f32;
        }
        let i0 = wrapped.floor() as usize;
        let frac = wrapped - i0 as f32;
        let i1 = (i0 + 1) % cap;
        self.buf[i0] * (1.0 - frac) + self.buf[i1] * frac
    }
    fn reset(&mut self) {
        self.buf.fill(0.0);
        self.write_idx = 0;
    }
}

/// Constant-power stereo pan (non-binaural output profile).
struct StereoPanner {
    last_l: f32,
    last_r: f32,
}

impl StereoPanner {
    fn new() -> Self {
        Self { last_l: 0.0, last_r: 0.0 }
    }
    fn pan(&mut self, sample: f32, azimuth_rad: f64) {
        let half_pi = PI64 / 2.0;
        let t = (azimuth_rad.clamp(-half_pi, half_pi) / half_pi + 1.0) / 2.0;
        let angle = t * half_pi;
        self.last_l = sample * angle.cos() as f32;
        self.last_r = sample * angle.sin() as f32;
    }
}

/// Zero-added-latency peak limiter (fast attack, slower release, no lookahead).
struct Limiter {
    ceiling: f32,
    peak_decay_coeff: f32,
    attack_coeff: f32,
    release_coeff: f32,
    peak_env: f32,
    gain: f32,
    last_l: f32,
    last_r: f32,
}

impl Limiter {
    fn new(sample_rate: f32) -> Self {
        Self {
            ceiling: 0.891,
            peak_decay_coeff: (-1.0 / (0.050 * sample_rate)).exp(),
            attack_coeff: (-1.0 / (0.001 * sample_rate)).exp(),
            release_coeff: (-1.0 / (0.080 * sample_rate)).exp(),
            peak_env: 0.0,
            gain: 1.0,
            last_l: 0.0,
            last_r: 0.0,
        }
    }
    fn process(&mut self, l: f32, r: f32) {
        let instant = l.abs().max(r.abs());
        self.peak_env = instant.max(self.peak_env * self.peak_decay_coeff);
        let target_gain = if self.peak_env > 1e-9 { (self.ceiling / self.peak_env).min(1.0) } else { 1.0 };
        self.gain = if target_gain < self.gain {
            target_gain + (self.gain - target_gain) * self.attack_coeff
        } else {
            target_gain + (self.gain - target_gain) * self.release_coeff
        };
        self.last_l = (l * self.gain).clamp(-self.ceiling, self.ceiling);
        self.last_r = (r * self.gain).clamp(-self.ceiling, self.ceiling);
    }
    fn reset(&mut self) {
        self.peak_env = 0.0;
        self.gain = 1.0;
    }
}

/// Real-time loudness estimator + slow makeup-gain controller (BS.1770/R128-flavored).
struct LoudnessMeter {
    shelf: Biquad,
    high_pass: Biquad,
    mean_square: f32,
    integration_seconds: f32,
    gain_db_smoother: ParamSmoother,
}

impl LoudnessMeter {
    fn new(sample_rate: f32) -> Self {
        let mut shelf = Biquad::new();
        shelf.configure(BiquadType::HighShelf, sample_rate, 1500.0, 0.7, 4.0);
        let mut high_pass = Biquad::new();
        high_pass.configure(BiquadType::HighPass, sample_rate, 60.0, 0.5, 0.0);
        Self {
            shelf,
            high_pass,
            mean_square: 1e-9,
            integration_seconds: 3.0,
            gain_db_smoother: ParamSmoother::new(0.5, 0.0),
        }
    }
    fn observe(&mut self, sample_l: f32, sample_r: f32, dt_per_sample: f32) {
        let mono = (sample_l + sample_r) * 0.5;
        let weighted = self.high_pass.process(self.shelf.process(mono));
        let alpha = 1.0 - (-dt_per_sample / self.integration_seconds).exp();
        self.mean_square += (weighted * weighted - self.mean_square) * alpha;
    }
    fn loudness_lufs(&self) -> f32 {
        -0.691 + 10.0 * self.mean_square.max(1e-9).log10()
    }
    fn makeup_gain(&mut self, target_lufs: f32, max_boost_db: f32, max_cut_db: f32, max_slew_db_per_second: f32, dt_seconds: f32) -> f32 {
        let desired_db = (target_lufs - self.loudness_lufs()).clamp(-max_cut_db, max_boost_db);
        let max_step = max_slew_db_per_second * dt_seconds;
        let current = self.gain_db_smoother.value;
        let next = (desired_db - current).clamp(-max_step, max_step) + current;
        self.gain_db_smoother.snap(next);
        10f32.powf(next / 20.0)
    }
    /// Same target as [`LoudnessMeter::makeup_gain`] but instant, ignoring the slew rate — for a
    /// discrete mode switch, where a multi-second glide reads as broken rather than smooth.
    fn makeup_gain_snap(&mut self, target_lufs: f32, max_boost_db: f32, max_cut_db: f32) -> f32 {
        let desired_db = (target_lufs - self.loudness_lufs()).clamp(-max_cut_db, max_boost_db);
        self.gain_db_smoother.snap(desired_db);
        10f32.powf(desired_db / 20.0)
    }
    fn reset(&mut self) {
        self.shelf.reset();
        self.high_pass.reset();
        self.mean_square = 1e-9;
        self.gain_db_smoother.snap(0.0);
    }
}

/// Parametric ITD (Woodworth) + ILD binaural renderer for one mono emitter.
struct ParametricBinaural {
    delay_line: FractionalDelayLine,
    shadow_filter: Biquad,
    itd_samples: f32,
    far_is_right: bool,
    far_gain: f32,
    sample_rate: f32,
    last_l: f32,
    last_r: f32,
}

impl ParametricBinaural {
    const HEAD_RADIUS_M: f64 = 0.0875;
    const SPEED_OF_SOUND: f64 = 343.0;
    const MAX_ILD_ATTEN: f32 = 0.35;
    const OPEN_CUTOFF_HZ: f32 = 9000.0;
    const SHADOWED_CUTOFF_HZ: f32 = 2200.0;

    fn new(sample_rate: f32) -> Self {
        let mut shadow_filter = Biquad::new();
        shadow_filter.configure(BiquadType::LowPass, sample_rate, Self::OPEN_CUTOFF_HZ, 0.7, 0.0);
        Self {
            delay_line: FractionalDelayLine::new(64),
            shadow_filter,
            itd_samples: 0.0,
            far_is_right: true,
            far_gain: 1.0,
            sample_rate,
            last_l: 0.0,
            last_r: 0.0,
        }
    }

    fn update_params(&mut self, azimuth_rad: f64) {
        let half_pi = PI64 / 2.0;
        let clamped = azimuth_rad.clamp(-half_pi, half_pi);
        let itd_seconds = (Self::HEAD_RADIUS_M / Self::SPEED_OF_SOUND) * (clamped + clamped.sin());
        self.itd_samples = (itd_seconds.abs() * self.sample_rate as f64) as f32;
        self.far_is_right = clamped < 0.0;
        let mag = clamped.sin().abs();
        self.far_gain = (1.0 - Self::MAX_ILD_ATTEN as f64 * mag) as f32;
        let cutoff = (Self::OPEN_CUTOFF_HZ as f64 - (Self::OPEN_CUTOFF_HZ - Self::SHADOWED_CUTOFF_HZ) as f64 * mag) as f32;
        self.shadow_filter.configure(BiquadType::LowPass, self.sample_rate, cutoff, 0.7, 0.0);
    }

    fn render_sample(&mut self, sample: f32) {
        self.delay_line.push(sample);
        let near = self.delay_line.read(0.0);
        let far = self.shadow_filter.process(self.delay_line.read(self.itd_samples)) * self.far_gain;
        if self.far_is_right {
            self.last_l = near;
            self.last_r = far;
        } else {
            self.last_l = far;
            self.last_r = near;
        }
    }

    fn reset(&mut self) {
        self.delay_line.reset();
        self.shadow_filter.reset();
        self.itd_samples = 0.0;
        self.far_gain = 1.0;
    }
}

struct CombFilter {
    buf: Vec<f32>,
    idx: usize,
    store: f32,
    feedback: f32,
    damp: f32,
}

impl CombFilter {
    fn new(size: usize) -> Self {
        Self { buf: vec![0.0; size], idx: 0, store: 0.0, feedback: 0.0, damp: 0.0 }
    }
    fn process(&mut self, x: f32) -> f32 {
        let out = self.buf[self.idx];
        self.store = out * (1.0 - self.damp) + self.store * self.damp;
        self.buf[self.idx] = x + self.store * self.feedback;
        self.idx += 1;
        if self.idx >= self.buf.len() {
            self.idx = 0;
        }
        out
    }
    fn reset(&mut self) {
        self.buf.fill(0.0);
        self.store = 0.0;
        self.idx = 0;
    }
}

struct AllpassFilter {
    buf: Vec<f32>,
    idx: usize,
    feedback: f32,
}

impl AllpassFilter {
    fn new(size: usize, feedback: f32) -> Self {
        Self { buf: vec![0.0; size], idx: 0, feedback }
    }
    fn process(&mut self, x: f32) -> f32 {
        let bufout = self.buf[self.idx];
        let out = -x + bufout;
        self.buf[self.idx] = x + bufout * self.feedback;
        self.idx += 1;
        if self.idx >= self.buf.len() {
            self.idx = 0;
        }
        out
    }
    fn reset(&mut self) {
        self.buf.fill(0.0);
        self.idx = 0;
    }
}

/// Schröder / Freeverb-style algorithmic reverb (Jezar's tuning, scaled to `sample_rate`).
struct Reverb {
    combs_l: Vec<CombFilter>,
    combs_r: Vec<CombFilter>,
    allpass_l: Vec<AllpassFilter>,
    allpass_r: Vec<AllpassFilter>,
    last_l: f32,
    last_r: f32,
}

impl Reverb {
    const COMB_TUNING: [usize; 8] = [1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617];
    const ALLPASS_TUNING: [usize; 4] = [556, 441, 341, 225];
    const STEREO_SPREAD: usize = 23;
    const ALLPASS_FEEDBACK: f32 = 0.5;
    const ROOM_SCALE: f32 = 0.28;
    const ROOM_OFFSET: f32 = 0.7;
    const DAMP_SCALE: f32 = 0.4;
    const GAIN: f32 = 0.015;

    fn new(sample_rate: f32) -> Self {
        let scale = sample_rate / 44100.0;
        let scaled = |n: usize| ((n as f32 * scale).round() as usize).max(1);
        Self {
            combs_l: Self::COMB_TUNING.iter().map(|&n| CombFilter::new(scaled(n))).collect(),
            combs_r: Self::COMB_TUNING.iter().map(|&n| CombFilter::new(scaled(n + Self::STEREO_SPREAD))).collect(),
            allpass_l: Self::ALLPASS_TUNING.iter().map(|&n| AllpassFilter::new(scaled(n), Self::ALLPASS_FEEDBACK)).collect(),
            allpass_r: Self::ALLPASS_TUNING
                .iter()
                .map(|&n| AllpassFilter::new(scaled(n + Self::STEREO_SPREAD), Self::ALLPASS_FEEDBACK))
                .collect(),
            last_l: 0.0,
            last_r: 0.0,
        }
    }

    fn update_params(&mut self, room_size: f32, damping: f32) {
        let feedback = room_size.clamp(0.0, 1.0) * Self::ROOM_SCALE + Self::ROOM_OFFSET;
        let damp = damping.clamp(0.0, 1.0) * Self::DAMP_SCALE;
        for c in self.combs_l.iter_mut().chain(self.combs_r.iter_mut()) {
            c.feedback = feedback;
            c.damp = damp;
        }
    }

    fn process(&mut self, input: f32) {
        let fed = input * Self::GAIN;
        let mut l = 0.0;
        let mut r = 0.0;
        for c in self.combs_l.iter_mut() {
            l += c.process(fed);
        }
        for c in self.combs_r.iter_mut() {
            r += c.process(fed);
        }
        for ap in self.allpass_l.iter_mut() {
            l = ap.process(l);
        }
        for ap in self.allpass_r.iter_mut() {
            r = ap.process(r);
        }
        self.last_l = l;
        self.last_r = r;
    }

    fn reset(&mut self) {
        for c in self.combs_l.iter_mut().chain(self.combs_r.iter_mut()) {
            c.reset();
        }
        for ap in self.allpass_l.iter_mut().chain(self.allpass_r.iter_mut()) {
            ap.reset();
        }
    }
}

#[derive(Clone, Copy)]
pub struct SourcePlane {
    pub center: Vec3,
    pub normal: Vec3,
    pub u_axis: Vec3,
    pub width: f64,
    pub height: f64,
}

#[derive(Clone, Copy)]
pub struct AcousticEnvironment {
    pub occlusion: f32,
    pub reverb_decay_seconds: f32,
    pub reverb_wet_gain: f32,
    pub reverb_damping: f32,
}

#[derive(Clone, Copy)]
pub struct SourceAcousticState {
    pub plane: SourcePlane,
    pub user_volume: f32,
    pub muted: bool,
    pub bypass_spatial: bool,
    pub acoustics_enabled: bool,
    pub environment: AcousticEnvironment,
}

#[derive(Clone, Copy)]
pub struct ListenerPose {
    pub position: Vec3,
    pub forward: Vec3,
    pub up: Vec3,
}

impl Default for ListenerPose {
    fn default() -> Self {
        ListenerPose {
            position: Vec3::default(),
            forward: Vec3 { x: 0.0, y: 0.0, z: -1.0 },
            up: Vec3 { x: 0.0, y: 1.0, z: 0.0 },
        }
    }
}

#[repr(C)]
pub struct ListenerPoseFfi {
    pub x: f64,
    pub y: f64,
    pub z: f64,
    pub forward_x: f64,
    pub forward_y: f64,
    pub forward_z: f64,
    pub up_x: f64,
    pub up_y: f64,
    pub up_z: f64,
}

impl From<&ListenerPoseFfi> for ListenerPose {
    fn from(f: &ListenerPoseFfi) -> Self {
        ListenerPose {
            position: Vec3 { x: f.x, y: f.y, z: f.z },
            forward: Vec3 { x: f.forward_x, y: f.forward_y, z: f.forward_z },
            up: Vec3 { x: f.up_x, y: f.up_y, z: f.up_z },
        }
    }
}

#[repr(C)]
pub struct AcousticStateFfi {
    pub center_x: f64,
    pub center_y: f64,
    pub center_z: f64,
    pub normal_x: f64,
    pub normal_y: f64,
    pub normal_z: f64,
    pub u_axis_x: f64,
    pub u_axis_y: f64,
    pub u_axis_z: f64,
    pub width: f64,
    pub height: f64,
    pub user_volume: f32,
    pub muted: u8,
    pub bypass_spatial: u8,
    pub acoustics_enabled: u8,
    pub _pad: u8,
    pub occlusion: f32,
    pub reverb_decay_seconds: f32,
    pub reverb_wet_gain: f32,
    pub reverb_damping: f32,
}

impl From<&AcousticStateFfi> for SourceAcousticState {
    fn from(f: &AcousticStateFfi) -> Self {
        SourceAcousticState {
            plane: SourcePlane {
                center: Vec3 { x: f.center_x, y: f.center_y, z: f.center_z },
                normal: Vec3 { x: f.normal_x, y: f.normal_y, z: f.normal_z },
                u_axis: Vec3 { x: f.u_axis_x, y: f.u_axis_y, z: f.u_axis_z },
                width: f.width,
                height: f.height,
            },
            user_volume: f.user_volume,
            muted: f.muted != 0,
            bypass_spatial: f.bypass_spatial != 0,
            acoustics_enabled: f.acoustics_enabled != 0,
            environment: AcousticEnvironment {
                occlusion: f.occlusion,
                reverb_decay_seconds: f.reverb_decay_seconds,
                reverb_wet_gain: f.reverb_wet_gain,
                reverb_damping: f.reverb_damping,
            },
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum AcousticQuality {
    Off = 0,
    Basic = 1,
    Advanced = 2,
    Ultra = 3,
}

impl AcousticQuality {
    fn from_code(code: i32) -> AcousticQuality {
        match code {
            1 => AcousticQuality::Basic,
            2 => AcousticQuality::Advanced,
            3 => AcousticQuality::Ultra,
            _ => AcousticQuality::Off,
        }
    }
}

/// Process-global listener pose / quality tier / binaural flag, shared by every render chain.
pub struct AcousticsGlobals {
    listener: Mutex<ListenerPose>,
    quality: AtomicU8,
    binaural: AtomicBool,
}

impl AcousticsGlobals {
    pub fn new() -> Self {
        Self {
            listener: Mutex::new(ListenerPose::default()),
            quality: AtomicU8::new(AcousticQuality::Advanced as u8),
            binaural: AtomicBool::new(true),
        }
    }
    pub fn set_listener(&self, pose: ListenerPose) {
        if let Ok(mut l) = self.listener.lock() {
            *l = pose;
        }
    }
    fn listener(&self) -> ListenerPose {
        self.listener.lock().map(|l| *l).unwrap_or_default()
    }
    pub fn set_quality(&self, code: i32) {
        self.quality.store(AcousticQuality::from_code(code) as u8, Ordering::Relaxed);
    }
    pub(crate) fn quality(&self) -> AcousticQuality {
        AcousticQuality::from_code(self.quality.load(Ordering::Relaxed) as i32)
    }
    pub fn set_binaural(&self, enabled: bool) {
        self.binaural.store(enabled, Ordering::Relaxed);
    }
    fn binaural(&self) -> bool {
        self.binaural.load(Ordering::Relaxed)
    }
}

/// Per-source DSP graph: renders a deinterleaved float block as an area-source, direction-aware
/// binaural (or speaker-pan) mix, in place. One instance per session, reused across seeks / track
/// switches — only [`RenderChain::reset`] runs per fresh playback session.
pub struct RenderChain {
    sample_rate: f32,
    loudness: LoudnessMeter,
    limiter: Limiter,
    left_binaural: ParametricBinaural,
    right_binaural: ParametricBinaural,
    left_panner: StereoPanner,
    right_panner: StereoPanner,
    distance_gain_l: ParamSmoother,
    distance_gain_r: ParamSmoother,
    directivity_smoother: ParamSmoother,
    azimuth_l: ParamSmoother,
    azimuth_r: ParamSmoother,
    occlusion_filter_l: Biquad,
    occlusion_filter_r: Biquad,
    reverb: Reverb,
    occlusion_cutoff: ParamSmoother,
    occlusion_gain: ParamSmoother,
    reverb_wet: ParamSmoother,
    last_tier: Option<AcousticQuality>,
}

impl RenderChain {
    pub fn new(sample_rate: f32) -> Self {
        Self {
            sample_rate,
            loudness: LoudnessMeter::new(sample_rate),
            limiter: Limiter::new(sample_rate),
            left_binaural: ParametricBinaural::new(sample_rate),
            right_binaural: ParametricBinaural::new(sample_rate),
            left_panner: StereoPanner::new(),
            right_panner: StereoPanner::new(),
            distance_gain_l: ParamSmoother::new(GAIN_SMOOTH_SECONDS, 1.0),
            distance_gain_r: ParamSmoother::new(GAIN_SMOOTH_SECONDS, 1.0),
            directivity_smoother: ParamSmoother::new(GAIN_SMOOTH_SECONDS, 1.0),
            azimuth_l: ParamSmoother::new(AZIMUTH_SMOOTH_SECONDS, 0.0),
            azimuth_r: ParamSmoother::new(AZIMUTH_SMOOTH_SECONDS, 0.0),
            occlusion_filter_l: Biquad::new(),
            occlusion_filter_r: Biquad::new(),
            reverb: Reverb::new(sample_rate),
            occlusion_cutoff: ParamSmoother::new(OCCLUSION_SMOOTH_SECONDS, MAX_CUTOFF_HZ),
            occlusion_gain: ParamSmoother::new(GAIN_SMOOTH_SECONDS, 1.0),
            reverb_wet: ParamSmoother::new(REVERB_SMOOTH_SECONDS, 0.0),
            last_tier: None,
        }
    }

    /// Keeps the loudness meter tracking real content even while bypassed, so switching into an
    /// active tier finds it already converged instead of starting from silence.
    fn observe_bypass(&mut self, l: &[f32], r: &[f32]) {
        let dt_sample = 1.0 / self.sample_rate;
        for i in 0..l.len().min(r.len()) {
            self.loudness.observe(l[i], r[i], dt_sample);
        }
    }

    pub fn reset(&mut self) {
        self.loudness.reset();
        self.limiter.reset();
        self.left_binaural.reset();
        self.right_binaural.reset();
        self.distance_gain_l.snap(1.0);
        self.distance_gain_r.snap(1.0);
        self.directivity_smoother.snap(1.0);
        self.azimuth_l.snap(0.0);
        self.azimuth_r.snap(0.0);
        self.occlusion_filter_l.reset();
        self.occlusion_filter_r.reset();
        self.reverb.reset();
        self.occlusion_cutoff.snap(MAX_CUTOFF_HZ);
        self.occlusion_gain.snap(1.0);
        self.reverb_wet.snap(0.0);
    }

    /// `legacy_gain` is the bypass-path volume multiplier; the active chain instead uses
    /// `state.user_volume`.
    pub fn process(&mut self, l: &mut [f32], r: &mut [f32], legacy_gain: f32, state: Option<&SourceAcousticState>, globals: &AcousticsGlobals) {
        let tier = globals.quality();
        let Some(st) = state else {
            self.observe_bypass(l, r);
            apply_legacy_gain(l, r, legacy_gain);
            return;
        };
        // Tier `Off` deliberately does NOT bypass here: the spatial chain keeps running so its
        // ring stays warm and ready, letting the real-time callback (see `audio::fill`) pick
        // between it and a continuously-computed legacy-gain ring with zero latency. Baking the
        // Off/On choice in here instead would mean whichever choice was made up to ~2s ago (the
        // ring's lookahead) is what's still audible, which is what made switching feel slow.
        if st.bypass_spatial || !st.acoustics_enabled {
            self.observe_bypass(l, r);
            apply_legacy_gain(l, r, legacy_gain);
            self.last_tier = Some(tier);
            return;
        }

        let tier_changed = self.last_tier != Some(tier);
        self.last_tier = Some(tier);

        let frames = l.len().min(r.len());
        if frames == 0 {
            return;
        }

        let listener = globals.listener();
        let plane = st.plane;
        let listener_pos = listener.position;
        let listener_forward = listener.forward;
        let listener_right = listener_forward.cross(listener.up).normalized();

        let dt_block = frames as f32 / self.sample_rate;
        let ref_distance = 4.0f64.max((plane.width * plane.height).sqrt() * 0.5);
        let to_center_dir = listener_pos.sub(plane.center).normalized();
        let directivity = self
            .directivity_smoother
            .next(emitter_layout::directivity_gain(plane.normal, to_center_dir, 0.6, 1.0) as f32, dt_block);

        let left_emitter = emitter_layout::left_emitter(plane.center, plane.u_axis, plane.width);
        let right_emitter = emitter_layout::right_emitter(plane.center, plane.u_axis, plane.width);

        let g_l =
            self.distance_gain_l.next(emitter_layout::distance_gain(listener_pos.sub(left_emitter).length(), ref_distance, 0.7) as f32, dt_block) * directivity;
        let g_r =
            self.distance_gain_r.next(emitter_layout::distance_gain(listener_pos.sub(right_emitter).length(), ref_distance, 0.7) as f32, dt_block) * directivity;

        let az_l = self.azimuth_l.next(azimuth_of(left_emitter, listener_pos, listener_forward, listener_right), dt_block);
        let az_r = self.azimuth_r.next(azimuth_of(right_emitter, listener_pos, listener_forward, listener_right), dt_block);

        let binaural = globals.binaural() && tier != AcousticQuality::Basic;
        if binaural {
            self.left_binaural.update_params(az_l as f64);
            self.right_binaural.update_params(az_r as f64);
        }

        let advanced = tier == AcousticQuality::Advanced || tier == AcousticQuality::Ultra;
        let user_gain = if st.muted { 0.0 } else { st.user_volume };
        let makeup = if advanced {
            if tier_changed {
                self.loudness.makeup_gain_snap(TARGET_LUFS, MAX_LOUDNESS_BOOST_DB, MAX_LOUDNESS_CUT_DB)
            } else {
                self.loudness.makeup_gain(TARGET_LUFS, MAX_LOUDNESS_BOOST_DB, MAX_LOUDNESS_CUT_DB, MAX_LOUDNESS_SLEW_DB_PER_SEC, dt_block)
            }
        } else {
            1.0
        };

        let env = st.environment;
        let occ = env.occlusion.clamp(0.0, 1.0);
        let cutoff_target = if advanced {
            let occ_cutoff = MAX_CUTOFF_HZ * (MIN_OCCLUSION_CUTOFF_HZ / MAX_CUTOFF_HZ).powf(occ);
            let center_dist = AIR_ABSORPTION_REF_DISTANCE.max(listener_pos.sub(plane.center).length() as f32);
            let air_cutoff = AIR_ABSORPTION_MIN_CUTOFF_HZ.max(MAX_CUTOFF_HZ * (AIR_ABSORPTION_REF_DISTANCE / center_dist));
            occ_cutoff.min(air_cutoff)
        } else {
            MAX_CUTOFF_HZ
        };
        let cutoff = self.occlusion_cutoff.next(cutoff_target, dt_block);
        self.occlusion_filter_l.configure(BiquadType::LowPass, self.sample_rate, cutoff, 0.707, 0.0);
        self.occlusion_filter_r.configure(BiquadType::LowPass, self.sample_rate, cutoff, 0.707, 0.0);
        let occ_gain = self.occlusion_gain.next(if advanced { 1.0 - occ * (1.0 - OCCLUSION_MIN_GAIN) } else { 1.0 }, dt_block);

        let reverb_target_wet = if advanced { env.reverb_wet_gain.clamp(0.0, 1.0) } else { 0.0 };
        let wet_gain = self.reverb_wet.next(reverb_target_wet, dt_block);
        let reverb_active = wet_gain > 1e-3 || reverb_target_wet > 1e-3;
        if reverb_active {
            self.reverb.update_params((env.reverb_decay_seconds / REVERB_MAX_DECAY_SECONDS).clamp(0.0, 1.0), env.reverb_damping);
        }

        let dt_sample = 1.0 / self.sample_rate;
        for i in 0..frames {
            let raw_l = l[i];
            let raw_r = r[i];
            self.loudness.observe(raw_l, raw_r, dt_sample);

            let src_l = if advanced { self.occlusion_filter_l.process(raw_l) } else { raw_l };
            let src_r = if advanced { self.occlusion_filter_r.process(raw_r) } else { raw_r };
            let sig_l = src_l * g_l * user_gain * makeup * occ_gain;
            let sig_r = src_r * g_r * user_gain * makeup * occ_gain;

            let (mut out_l, mut out_r) = if binaural {
                self.left_binaural.render_sample(sig_l);
                self.right_binaural.render_sample(sig_r);
                (self.left_binaural.last_l + self.right_binaural.last_l, self.left_binaural.last_r + self.right_binaural.last_r)
            } else {
                self.left_panner.pan(sig_l, az_l as f64);
                self.right_panner.pan(sig_r, az_r as f64);
                (self.left_panner.last_l + self.right_panner.last_l, self.left_panner.last_r + self.right_panner.last_r)
            };

            if reverb_active {
                self.reverb.process((sig_l + sig_r) * 0.5);
                out_l += self.reverb.last_l * wet_gain;
                out_r += self.reverb.last_r * wet_gain;
            }

            if advanced {
                self.limiter.process(out_l, out_r);
                out_l = self.limiter.last_l;
                out_r = self.limiter.last_r;
            }

            l[i] = out_l;
            r[i] = out_r;
        }
    }
}

fn apply_legacy_gain(l: &mut [f32], r: &mut [f32], gain: f32) {
    if (gain - 1.0).abs() < 1e-5 {
        return;
    }
    for s in l.iter_mut() {
        *s *= gain;
    }
    for s in r.iter_mut() {
        *s *= gain;
    }
}

fn azimuth_of(emitter: Vec3, listener_pos: Vec3, forward: Vec3, right: Vec3) -> f32 {
    let rel = emitter.sub(listener_pos);
    rel.dot(right).atan2(rel.dot(forward)) as f32
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn param_smoother_converges_toward_target() {
        let mut s = ParamSmoother::new(0.1, 0.0);
        let mut v = 0.0;
        for _ in 0..500 {
            v = s.next(1.0, 0.01);
        }
        assert!(v > 0.99, "expected near-convergence, got {v}");
    }

    #[test]
    fn param_smoother_snap_is_immediate() {
        let mut s = ParamSmoother::new(0.1, 0.0);
        s.snap(5.0);
        assert_eq!(s.next(5.0, 0.0), 5.0);
    }

    #[test]
    fn biquad_low_pass_attenuates_above_cutoff() {
        let mut f = Biquad::new();
        f.configure(BiquadType::LowPass, 44100.0, 500.0, 0.707, 0.0);
        let mut peak = 0.0f32;
        for i in 0..2000 {
            let x = (2.0 * std::f32::consts::PI * 10000.0 * i as f32 / 44100.0).sin();
            let y = f.process(x);
            if i > 200 {
                peak = peak.max(y.abs());
            }
        }
        assert!(peak < 0.3, "expected strong attenuation above cutoff, got peak {peak}");
    }

    #[test]
    fn biquad_low_pass_passes_dc() {
        let mut f = Biquad::new();
        f.configure(BiquadType::LowPass, 44100.0, 500.0, 0.707, 0.0);
        let mut y = 0.0;
        for _ in 0..2000 {
            y = f.process(1.0);
        }
        assert!((y - 1.0).abs() < 0.01, "expected DC to pass through near-unity, got {y}");
    }

    #[test]
    fn distance_gain_is_unity_inside_ref_distance_and_rolls_off_beyond() {
        assert!((emitter_layout::distance_gain(2.0, 8.0, 0.7) - 1.0).abs() < 1e-9);
        assert!(emitter_layout::distance_gain(20.0, 8.0, 0.7) < 1.0);
    }

    #[test]
    fn directivity_gain_is_higher_in_front_than_behind() {
        let normal = Vec3 { x: 0.0, y: 0.0, z: 1.0 };
        let in_front = emitter_layout::directivity_gain(normal, Vec3 { x: 0.0, y: 0.0, z: 1.0 }, 0.6, 1.0);
        let behind = emitter_layout::directivity_gain(normal, Vec3 { x: 0.0, y: 0.0, z: -1.0 }, 0.6, 1.0);
        assert!(in_front > behind);
        assert!((in_front - 1.0).abs() < 1e-9);
        assert!((behind - 0.6).abs() < 1e-9);
    }

    #[test]
    fn reverb_produces_a_decaying_tail_after_an_impulse() {
        let mut rv = Reverb::new(44100.0);
        rv.update_params(1.0, 0.5);
        rv.process(1.0);
        let mut energy = 0.0f32;
        for _ in 0..4096 {
            rv.process(0.0);
            energy += rv.last_l.abs() + rv.last_r.abs();
        }
        assert!(energy > 0.0, "expected a nonzero reverb tail after an impulse");
    }

    #[test]
    fn legacy_gain_bypass_scales_samples_linearly() {
        let mut l = [1.0f32, -1.0, 0.5];
        let mut r = [1.0f32, -1.0, 0.5];
        apply_legacy_gain(&mut l, &mut r, 0.5);
        assert_eq!(l, [0.5, -0.5, 0.25]);
        assert_eq!(r, [0.5, -0.5, 0.25]);
    }

    #[test]
    fn render_chain_bypasses_to_legacy_gain_without_state() {
        let mut chain = RenderChain::new(44100.0);
        let globals = AcousticsGlobals::new();
        let mut l = [1.0f32; 8];
        let mut r = [1.0f32; 8];
        chain.process(&mut l, &mut r, 0.5, None, &globals);
        assert!(l.iter().all(|&v| (v - 0.5).abs() < 1e-6));
    }

    fn quiet_source_state() -> SourceAcousticState {
        SourceAcousticState {
            plane: SourcePlane {
                center: Vec3 { x: 0.0, y: 0.0, z: -4.0 },
                normal: Vec3 { x: 0.0, y: 0.0, z: 1.0 },
                u_axis: Vec3 { x: 1.0, y: 0.0, z: 0.0 },
                width: 2.0,
                height: 1.0,
            },
            user_volume: 1.0,
            muted: false,
            bypass_spatial: false,
            acoustics_enabled: true,
            environment: AcousticEnvironment { occlusion: 0.0, reverb_decay_seconds: 0.0, reverb_wet_gain: 0.0, reverb_damping: 0.0 },
        }
    }

    /// Regression guard: switching off -> enhanced must not audibly glide in over several seconds.
    #[test]
    fn tier_switch_snaps_makeup_gain_instead_of_gliding() {
        let mut chain = RenderChain::new(48_000.0);
        let globals = AcousticsGlobals::new();
        globals.set_quality(AcousticQuality::Off as i32);
        let state = quiet_source_state();

        let quiet = 0.01f32;
        for _ in 0..200 {
            let mut l = [quiet; 64];
            let mut r = [quiet; 64];
            chain.process(&mut l, &mut r, 1.0, Some(&state), &globals);
        }

        globals.set_quality(AcousticQuality::Advanced as i32);
        let mut l = [quiet; 64];
        let mut r = [quiet; 64];
        chain.process(&mut l, &mut r, 1.0, Some(&state), &globals);

        let out_peak = l.iter().map(|v| v.abs()).fold(0.0f32, f32::max);
        let boost_db = 20.0 * (out_peak / quiet).log10();
        assert!(
            boost_db > 8.0,
            "Expected the makeup gain to have snapped close to its +12 dB ceiling on the tier \
             switch, got only {boost_db:.2} dB of boost (peak {out_peak}) — it's still gliding.",
        );
    }
}
