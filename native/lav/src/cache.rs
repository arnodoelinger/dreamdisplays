//! Bounded, keyframe-aligned ring of encoded video packets.

use std::collections::VecDeque;

/// One cached encoded packet plus the metadata the replay decoder needs to place it on the timeline.
#[derive(Clone, Debug)]
pub struct CachedPacket {
    /// Raw encoded packet payload (one `AVPacket`'s data).
    pub data: Vec<u8>,
    /// Normalized presentation timestamp in nanoseconds, or [`NO_PTS`] when libav gave none.
    pub pts_nanos: i64,
    /// Normalized decode timestamp in nanoseconds, or [`NO_PTS`] when unavailable.
    pub dts_nanos: i64,
    /// True for keyframes (GOP boundaries); the ring may only start at one.
    pub keyframe: bool,
}

/// Sentinel for "no timestamp", mirroring `session::NO_PTS_NANOS`.
pub const NO_PTS: i64 = i64::MIN;

/// Decoder parameters captured alongside the packet ring so a replay session can rebuild the exact
/// decoder without a container/demuxer. Mirrors the fields of `AVCodecParameters` we need.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct CodecParams {
    /// `AVCodecID` as i32.
    pub codec_id: i32,
    pub width: i32,
    pub height: i32,
    /// Stream time base (so packet PTS/DTS in ticks can be reproduced if needed).
    pub time_base_num: i32,
    pub time_base_den: i32,
    /// Codec extradata (SPS/PPS etc.) required to open many decoders (H.264/HEVC).
    pub extradata: Vec<u8>,
}

/// Magic prefix for a serialized ring snapshot (`"DDR1"`).
const SNAPSHOT_MAGIC: u32 = 0x44_44_52_31;

impl CachedPacket {
    /// Best available ordering timestamp: PTS when present, else DTS, else [`NO_PTS`].
    fn order_ts(&self) -> i64 {
        if self.pts_nanos != NO_PTS {
            self.pts_nanos
        } else {
            self.dts_nanos
        }
    }
}

/// A duration- and byte-bounded ring of encoded packets, kept keyframe-aligned for clean replay.
pub struct PacketRing {
    window_nanos: i64,
    max_bytes: usize,
    bytes: usize,
    packets: VecDeque<CachedPacket>,
}

impl PacketRing {
    /// Creates an empty ring retaining up to `window_nanos` of stream (clamped to >= 0) and never
    /// more than `max_bytes` (clamped to >= 1) of packet payload.
    pub fn new(window_nanos: i64, max_bytes: usize) -> PacketRing {
        PacketRing {
            window_nanos: window_nanos.max(0),
            max_bytes: max_bytes.max(1),
            bytes: 0,
            packets: VecDeque::new(),
        }
    }

    /// Number of retained packets.
    pub fn len(&self) -> usize {
        self.packets.len()
    }

    /// True when no packets are retained.
    pub fn is_empty(&self) -> bool {
        self.packets.is_empty()
    }

    /// Total retained packet payload in bytes.
    pub fn total_bytes(&self) -> usize {
        self.bytes
    }

    /// Timestamp span (newest — oldest order timestamp) currently retained, in nanoseconds.
    /// Zero when fewer than two timestamped packets are present.
    pub fn span_nanos(&self) -> i64 {
        let first = self.packets.iter().find_map(|p| ts_opt(p.order_ts()));
        let last = self.packets.iter().rev().find_map(|p| ts_opt(p.order_ts()));
        match (first, last) {
            (Some(a), Some(b)) if b >= a => b - a,
            _ => 0,
        }
    }

    /// PTS of the newest retained packet, or [`NO_PTS`] when empty/untimed.
    pub fn newest_ts(&self) -> i64 {
        self.packets
            .iter()
            .rev()
            .find_map(|p| ts_opt(p.order_ts()))
            .unwrap_or(NO_PTS)
    }

    /// Target retention window in nanoseconds.
    pub fn window_nanos(&self) -> i64 {
        self.window_nanos
    }

    /// Appends `packet`, then evicts whole leading GOPs to honor the window and byte budget while
    /// keeping the ring keyframe-aligned.
    pub fn push(&mut self, packet: CachedPacket) {
        self.bytes += packet.data.len();
        self.packets.push_back(packet);
        self.evict();
    }

    /// Drops whole leading GOPs while either (a) the second keyframe still leaves >= `window_nanos`
    /// covered, or (b) the byte budget is exceeded. Never drops below a single GOP, so the ring stays
    /// decodable from its front.
    fn evict(&mut self) {
        let newest = self.newest_ts();
        loop {
            // Index of the second keyframe; everything before it is the droppable leading GOP
            let Some(second_kf) = self.second_keyframe_index() else {
                break; // 0 or 1 keyframes: nothing we can drop without losing the decodable prefix
            };

            let over_bytes = self.bytes > self.max_bytes;
            // The leading GOP is only redundant for the window if the *next* keyframe is already old
            // enough that dropping everything before it still leaves >= window_nanos covered.
            let next_kf_ts = self.packets[second_kf].order_ts();
            let window_redundant = newest != NO_PTS
                && next_kf_ts != NO_PTS
                && newest - next_kf_ts >= self.window_nanos;

            if !over_bytes && !window_redundant {
                break;
            }
            for _ in 0..second_kf {
                if let Some(p) = self.packets.pop_front() {
                    self.bytes -= p.data.len();
                }
            }
        }
    }

    /// Index of the second keyframe in the ring (the start of the second GOP), or `None` when the
    /// ring holds fewer than two keyframes.
    fn second_keyframe_index(&self) -> Option<usize> {
        self.packets
            .iter()
            .enumerate()
            .filter(|(_, p)| p.keyframe)
            .nth(1)
            .map(|(i, _)| i)
    }

    /// Returns the packets needed to resume playback at `position_nanos`: everything from a safe
    /// keyframe at or before `position_nanos` to the end. For codecs that may use open GOPs, this
    /// intentionally backs up one extra keyframe when available; replay discards the decoded pre-roll
    /// before `position_nanos`. When `position_nanos` precedes the ring, replay starts at the first
    /// retained keyframe. Returns empty when the ring has no keyframe.
    pub fn drain_from(&self, position_nanos: i64) -> Vec<CachedPacket> {
        let start = self.start_index_for(position_nanos);
        match start {
            Some(i) => self.packets.iter().skip(i).cloned().collect(),
            None => Vec::new(),
        }
    }

    /// Index of the keyframe to start replay from for `position_nanos` (see [`drain_from`]).
    fn start_index_for(&self, position_nanos: i64) -> Option<usize> {
        let mut previous: Option<usize> = None;
        let mut chosen: Option<usize> = None;
        let mut chosen_at_or_before = false;
        for (i, p) in self.packets.iter().enumerate() {
            if !p.keyframe {
                continue;
            }
            let ts = p.order_ts();
            // First keyframe is the floor; then advance while keyframes stay at / under the position
            if chosen.is_none() {
                chosen = Some(i);
                chosen_at_or_before = ts == NO_PTS || ts <= position_nanos;
            } else if ts != NO_PTS && ts <= position_nanos {
                previous = chosen;
                chosen = Some(i);
                chosen_at_or_before = true;
            }
            if ts != NO_PTS && ts > position_nanos {
                break;
            }
        }
        if chosen_at_or_before {
            previous.or(chosen)
        } else {
            chosen
        }
    }

    /// Drops all retained packets.
    pub fn clear(&mut self) {
        self.packets.clear();
        self.bytes = 0;
    }
}

/// Serializes `params` plus the `packets` (typically `ring.drain_from(pos)`) into a self-contained
/// little-endian blob the JVM can retain across a soft unload and hand back to a replay session.
///
/// Layout: magic u32, codec_id i32, width i32, height i32, tb_num i32, tb_den i32,
/// extradata_len u32 + bytes, packet_count u32, then per packet:
/// pts i64, dts i64, flags u8 (bit0 = keyframe), data_len u32 + bytes.
pub fn serialize_snapshot(params: &CodecParams, packets: &[CachedPacket]) -> Vec<u8> {
    let mut out = Vec::new();
    out.extend_from_slice(&SNAPSHOT_MAGIC.to_le_bytes());
    out.extend_from_slice(&params.codec_id.to_le_bytes());
    out.extend_from_slice(&params.width.to_le_bytes());
    out.extend_from_slice(&params.height.to_le_bytes());
    out.extend_from_slice(&params.time_base_num.to_le_bytes());
    out.extend_from_slice(&params.time_base_den.to_le_bytes());
    out.extend_from_slice(&(params.extradata.len() as u32).to_le_bytes());
    out.extend_from_slice(&params.extradata);
    out.extend_from_slice(&(packets.len() as u32).to_le_bytes());
    for p in packets {
        out.extend_from_slice(&p.pts_nanos.to_le_bytes());
        out.extend_from_slice(&p.dts_nanos.to_le_bytes());
        out.push(if p.keyframe { 1 } else { 0 });
        out.extend_from_slice(&(p.data.len() as u32).to_le_bytes());
        out.extend_from_slice(&p.data);
    }
    out
}

/// Inverse of [`serialize_snapshot`]. Returns `None` on a bad magic or a truncated / oversized blob.
pub fn deserialize_snapshot(bytes: &[u8]) -> Option<(CodecParams, Vec<CachedPacket>)> {
    let mut r = Reader { bytes, pos: 0 };
    if r.u32()? != SNAPSHOT_MAGIC {
        return None;
    }
    let params = CodecParams {
        codec_id: r.i32()?,
        width: r.i32()?,
        height: r.i32()?,
        time_base_num: r.i32()?,
        time_base_den: r.i32()?,
        extradata: {
            let n = r.u32()? as usize;
            r.bytes(n)?.to_vec()
        },
    };
    let count = r.u32()? as usize;
    let mut packets = Vec::with_capacity(count.min(1 << 20));
    for _ in 0..count {
        let pts_nanos = r.i64()?;
        let dts_nanos = r.i64()?;
        let keyframe = r.u8()? & 1 != 0;
        let n = r.u32()? as usize;
        let data = r.bytes(n)?.to_vec();
        packets.push(CachedPacket {
            data,
            pts_nanos,
            dts_nanos,
            keyframe,
        });
    }
    Some((params, packets))
}

/// Returns a keyframe-aligned suffix of [packets] that can decode [position_nanos].
pub fn packets_from_position(packets: &[CachedPacket], position_nanos: i64) -> Vec<CachedPacket> {
    let Some(start) = start_index_for_packets(packets, position_nanos) else {
        return Vec::new();
    };
    packets.iter().skip(start).cloned().collect()
}

/// Finds a safe keyframe at or before [position_nanos] in a packet slice, backing up one extra
/// keyframe when possible to satisfy open-GOP references.
fn start_index_for_packets(packets: &[CachedPacket], position_nanos: i64) -> Option<usize> {
    let mut previous: Option<usize> = None;
    let mut chosen: Option<usize> = None;
    let mut chosen_at_or_before = false;
    for (i, p) in packets.iter().enumerate() {
        if !p.keyframe {
            continue;
        }
        let ts = p.order_ts();
        if chosen.is_none() {
            chosen = Some(i);
            chosen_at_or_before = ts == NO_PTS || ts <= position_nanos;
        } else if ts != NO_PTS && ts <= position_nanos {
            previous = chosen;
            chosen = Some(i);
            chosen_at_or_before = true;
        }
        if ts != NO_PTS && ts > position_nanos {
            break;
        }
    }
    if chosen_at_or_before {
        previous.or(chosen)
    } else {
        chosen
    }
}

/// Minimal bounds-checked little-endian reader for [`deserialize_snapshot`].
struct Reader<'a> {
    bytes: &'a [u8],
    pos: usize,
}

impl<'a> Reader<'a> {
    fn bytes(&mut self, n: usize) -> Option<&'a [u8]> {
        let end = self.pos.checked_add(n)?;
        let slice = self.bytes.get(self.pos..end)?;
        self.pos = end;
        Some(slice)
    }
    fn u8(&mut self) -> Option<u8> {
        self.bytes(1).map(|b| b[0])
    }
    fn u32(&mut self) -> Option<u32> {
        self.bytes(4)
            .map(|b| u32::from_le_bytes(b.try_into().unwrap()))
    }
    fn i32(&mut self) -> Option<i32> {
        self.bytes(4)
            .map(|b| i32::from_le_bytes(b.try_into().unwrap()))
    }
    fn i64(&mut self) -> Option<i64> {
        self.bytes(8)
            .map(|b| i64::from_le_bytes(b.try_into().unwrap()))
    }
}

/// Maps an order timestamp to `Some` unless it is the [`NO_PTS`] sentinel.
fn ts_opt(ts: i64) -> Option<i64> {
    if ts == NO_PTS {
        None
    } else {
        Some(ts)
    }
}

#[cfg(test)] mod tests {
    use super::*;

    const MS: i64 = 1_000_000;

    fn pkt(pts_ms: i64, keyframe: bool, bytes: usize) -> CachedPacket {
        CachedPacket {
            data: vec![0u8; bytes],
            pts_nanos: pts_ms * MS,
            dts_nanos: pts_ms * MS,
            keyframe,
        }
    }

    /// One GOP every 5 frames (30 fps ⇒ ~166 ms each), `bytes` per packet.
    fn fill(ring: &mut PacketRing, frames: i64, bytes: usize) {
        for i in 0..frames {
            ring.push(pkt(i * 33, i % 5 == 0, bytes));
        }
    }

    #[test] fn retains_window_and_stays_keyframe_aligned() {
        // 1 s window; feed 10 s at 30 fps
        let mut ring = PacketRing::new(1_000 * MS, usize::MAX);
        fill(&mut ring, 300, 100);
        // Front is always a keyframe
        assert!(
            ring.packets.front().unwrap().keyframe,
            "ring must start at a keyframe."
        );
        // Retains at least the window, but not the whole 10 s
        assert!(ring.span_nanos() >= 1_000 * MS, "should cover >= window.");
        assert!(
            ring.span_nanos() < 2_000 * MS,
            "should not hoard far beyond the window."
        );
        assert!(ring.len() < 300, "old GOPs must be evicted.");
    }

    #[test] fn enforces_byte_budget_by_dropping_gops() {
        // Window huge, but cap bytes so the budget is the binding constraint
        let mut ring = PacketRing::new(i64::MAX / 4, 1_000);
        fill(&mut ring, 300, 100);
        assert!(ring.total_bytes() <= 1_000, "must honor byte budget.");
        assert!(
            ring.packets.front().unwrap().keyframe,
            "still keyframe-aligned under byte pressure."
        );
        assert!(!ring.is_empty(), "keeps at least one decodable GOP.");
    }

    #[test] fn never_drops_below_one_gop() {
        // Absurdly tight budget; a single GOP (5 frames) still exceeds it but must be retained
        let mut ring = PacketRing::new(0, 1);
        fill(&mut ring, 7, 100);
        assert!(!ring.is_empty(), "cannot drop the only decodable GOP.");
        assert!(ring.packets.front().unwrap().keyframe);
        // At most the current partial GOP plus the previous one's tail is kept
        assert!(ring.len() <= 7);
    }

    #[test] fn drain_from_backs_up_one_keyframe_for_decoder_context() {
        let mut ring = PacketRing::new(i64::MAX / 4, usize::MAX);
        fill(&mut ring, 30, 100); // keyframes at frame 0,5,10,15,20,25 ⇒ 0,165,330,495,660,825 ms
                                  // Resume at 500 ms -> last keyframe <= 500 ms is frame 15 (495 ms), but replay backs up
                                  // one GOP for H.264/open-GOP decoder references.
        let out = ring.drain_from(500 * MS);
        assert!(!out.is_empty());
        assert!(out[0].keyframe, "replay must begin on a keyframe.");
        assert_eq!(
            out[0].pts_nanos,
            330 * MS,
            "should include one extra GOP of pre-roll."
        );
        assert!(out.iter().all(|p| p.pts_nanos >= 330 * MS));
    }

    #[test] fn packets_from_position_matches_ring_drain() {
        let mut ring = PacketRing::new(i64::MAX / 4, usize::MAX);
        fill(&mut ring, 30, 100);
        let all = ring.drain_from(i64::MIN + 1);
        let sliced = packets_from_position(&all, 500 * MS);
        assert_eq!(sliced[0].pts_nanos, 330 * MS);
        assert_eq!(sliced.len(), ring.drain_from(500 * MS).len());
    }

    #[test] fn drain_from_before_ring_starts_at_first_keyframe() {
        let mut ring = PacketRing::new(i64::MAX / 4, usize::MAX);
        fill(&mut ring, 30, 100);
        let first_kf = ring.packets.front().unwrap().pts_nanos;
        let out = ring.drain_from(i64::MIN + 1);
        assert_eq!(
            out[0].pts_nanos, first_kf,
            "position before ring ⇒ start at first keyframe."
        );
    }

    #[test] fn empty_ring_drains_nothing() {
        let ring = PacketRing::new(1_000 * MS, usize::MAX);
        assert!(ring.drain_from(0).is_empty());
        assert_eq!(ring.span_nanos(), 0);
    }

    #[test] fn snapshot_round_trips() {
        let params = CodecParams {
            codec_id: 27, // AV_CODEC_ID_H264
            width: 1920,
            height: 1080,
            time_base_num: 1,
            time_base_den: 30000,
            extradata: vec![1, 2, 3, 4, 5],
        };
        let mut ring = PacketRing::new(i64::MAX / 4, usize::MAX);
        for i in 0..12 {
            ring.push(CachedPacket {
                data: vec![i as u8; 7 + i as usize],
                pts_nanos: i * 33 * MS,
                dts_nanos: i * 33 * MS,
                keyframe: i % 4 == 0,
            });
        }
        let packets = ring.drain_from(0);
        let blob = serialize_snapshot(&params, &packets);
        let (p2, pk2) = deserialize_snapshot(&blob).expect("round-trip");
        assert_eq!(p2, params);
        assert_eq!(pk2.len(), packets.len());
        for (a, b) in packets.iter().zip(pk2.iter()) {
            assert_eq!(a.data, b.data);
            assert_eq!(a.pts_nanos, b.pts_nanos);
            assert_eq!(a.dts_nanos, b.dts_nanos);
            assert_eq!(a.keyframe, b.keyframe);
        }
    }

    #[test] fn deserialize_rejects_garbage() {
        assert!(deserialize_snapshot(&[]).is_none());
        assert!(deserialize_snapshot(&[0, 1, 2, 3]).is_none()); // Magic
        let mut blob = serialize_snapshot(&CodecParams::default(), &[]);
        blob.truncate(blob.len() - 1); // Truncated tail; empty packet list survives truncation only
                                       // if nothing was after the count; force a packet.
        let with_pkt = serialize_snapshot(
            &CodecParams::default(),
            &[CachedPacket {
                data: vec![9; 4],
                pts_nanos: 0,
                dts_nanos: 0,
                keyframe: true,
            }],
        );
        let mut t = with_pkt.clone();
        t.truncate(t.len() - 2);
        assert!(
            deserialize_snapshot(&t).is_none(),
            "truncated packet data must be rejected."
        );
    }
}
