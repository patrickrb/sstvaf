//! The receive state machine: drive a decoder session with captured audio and
//! turn its polled status into events the UI can render.
//!
//! Deliberately free of threads, audio and IPC — it takes sample blocks in and
//! returns events out, so every transition below is unit-tested against a fake
//! session. Ported from `SstvSignalListener` on Android, including the two
//! subtle behaviours that make the RX screen usable:
//!
//! 1. **Rows stream incrementally.** Each poll emits only the rows decoded
//!    since the last one, so the image paints line by line as it arrives.
//! 2. **Terminal states are held.** After DONE/ABORTED the decoder is reset
//!    back to hunting, but the resulting IDLE must not overwrite the
//!    Complete/Aborted the operator is looking at — the next transmission
//!    clears it.

// Serialize only: these types travel outward to the UI, and `ModeInfo`'s
// &'static str fields can't round-trip through Deserialize anyway.
use serde::Serialize;

use crate::dsp::decoder::DecodeStatus;
use crate::modes::ModeInfo;
use crate::rx::session::SstvSession;

/// What the receiver is doing, as shown on the RX screen.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(tag = "kind", rename_all = "lowercase")]
pub enum RxState {
    /// Hunting for a leader tone.
    Idle,
    /// A leader or VIS code is being read; a transmission is starting.
    Leader,
    /// Image lines are arriving.
    Decoding {
        mode: ModeInfo,
        rows: usize,
        total_rows: usize,
        quality: f32,
        slant_ppm: f32,
    },
    /// A full image was decoded. Held until the next transmission begins.
    Complete {
        mode: ModeInfo,
        rows: usize,
        quality: f32,
    },
    /// The signal was lost mid-image; whatever decoded is kept.
    Aborted {
        mode: Option<ModeInfo>,
        rows: usize,
    },
}

/// Everything the engine reports upward. `Rows` carries pixels; the rest is
/// status the UI shows as text.
///
/// Adjacently tagged (`{"event": ..., "data": ...}`) rather than internally
/// tagged: serde cannot serialize an internally-tagged newtype variant that
/// wraps a string, so `Info`/`Error` would fail at runtime under `tag` alone.
#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(tag = "event", content = "data", rename_all = "lowercase")]
pub enum RxEvent {
    State(RxState),
    /// Newly decoded scan lines, row-major ARGB, `width` pixels per row.
    Rows {
        first_row: usize,
        width: u32,
        pixels: Vec<u32>,
    },
    Info(String),
    Error(String),
}

pub struct RxEngine<S: SstvSession> {
    session: S,
    state: RxState,
    /// Set after a terminal state so the post-reset IDLE doesn't clobber it.
    hold_terminal: bool,
    /// How many rows have already been sent to the UI this transmission.
    rows_sent: usize,
    vis_logged: bool,
}

impl<S: SstvSession> RxEngine<S> {
    pub fn new(session: S) -> RxEngine<S> {
        RxEngine {
            session,
            state: RxState::Idle,
            hold_terminal: false,
            rows_sent: 0,
            vis_logged: false,
        }
    }

    pub fn state(&self) -> &RxState {
        &self.state
    }

    /// Feed captured audio blocks and poll the decoder once. Returns the events
    /// produced, in the order the UI should apply them.
    pub fn feed(&mut self, blocks: &[Vec<f32>]) -> Vec<RxEvent> {
        for b in blocks {
            self.session.push(b);
        }
        let mut out = Vec::new();
        self.poll(&mut out);
        out
    }

    /// Poll the decoder without feeding it — used by the decode loop when a
    /// wait times out, so status still refreshes on a quiet channel.
    pub fn poll_only(&mut self) -> Vec<RxEvent> {
        let mut out = Vec::new();
        self.poll(&mut out);
        out
    }

    fn poll(&mut self, out: &mut Vec<RxEvent>) {
        match self.session.status() {
            DecodeStatus::Idle => {
                if !self.hold_terminal {
                    self.set_state(RxState::Idle, out);
                }
            }
            DecodeStatus::Leader | DecodeStatus::Vis => {
                self.hold_terminal = false;
                self.set_state(RxState::Leader, out);
            }
            DecodeStatus::Image => {
                self.hold_terminal = false;
                let Some(mode) = self.session.mode() else {
                    // IMAGE without a locked mode shouldn't happen; treat it as
                    // "still starting" rather than inventing dimensions.
                    self.set_state(RxState::Leader, out);
                    return;
                };
                if !self.vis_logged {
                    self.vis_logged = true;
                    out.push(RxEvent::Info(format!(
                        "VIS lock — {} (vis={}, {}x{})",
                        mode.name, mode.vis_code, mode.width, mode.height
                    )));
                }
                self.emit_new_rows(mode, out);
                let state = RxState::Decoding {
                    mode: *mode,
                    rows: self.session.rows_ready(),
                    total_rows: mode.height as usize,
                    quality: self.session.quality(),
                    slant_ppm: self.session.slant_ppm(),
                };
                self.set_state(state, out);
            }
            DecodeStatus::Done => self.finish(true, out),
            DecodeStatus::Aborted => self.finish(false, out),
        }
    }

    /// Emit the rows decoded since the last poll, if any.
    fn emit_new_rows(&mut self, mode: &ModeInfo, out: &mut Vec<RxEvent>) {
        let ready = self.session.rows_ready().min(mode.height as usize);
        if ready <= self.rows_sent {
            return;
        }
        let n = ready - self.rows_sent;
        let mut pixels = vec![0u32; n * mode.width as usize];
        let copied = self.session.read_rows(self.rows_sent, n, &mut pixels);
        if copied == 0 {
            return;
        }
        pixels.truncate(copied * mode.width as usize);
        out.push(RxEvent::Rows {
            first_row: self.rows_sent,
            width: mode.width,
            pixels,
        });
        self.rows_sent += copied;
    }

    /// Terminal handling: flush the remaining rows and read every metric BEFORE
    /// resetting the decoder — reset discards the frame.
    fn finish(&mut self, complete: bool, out: &mut Vec<RxEvent>) {
        let mode = self.session.mode();
        if let Some(m) = mode {
            self.emit_new_rows(m, out);
        }
        let rows = self.session.rows_ready();
        let quality = self.session.quality();

        let state = if complete {
            match mode {
                Some(m) => {
                    out.push(RxEvent::Info(format!(
                        "image complete — {} rows={rows} quality={quality:.2}",
                        m.name
                    )));
                    RxState::Complete {
                        mode: *m,
                        rows,
                        quality,
                    }
                }
                // DONE with no mode is not a decodable frame; report it as an
                // abort so the UI never claims a complete image it can't show.
                None => RxState::Aborted { mode: None, rows },
            }
        } else {
            out.push(RxEvent::Info(format!(
                "decode aborted — {} partial rows={rows}",
                mode.map(|m| m.name).unwrap_or("no mode")
            )));
            RxState::Aborted {
                mode: mode.copied(),
                rows,
            }
        };
        self.set_state(state, out);

        self.session.reset();
        self.hold_terminal = true;
        self.rows_sent = 0;
        self.vis_logged = false;
    }

    /// Publish a state, suppressing repeats so the UI isn't spammed 4x/second
    /// with an unchanged status.
    fn set_state(&mut self, next: RxState, out: &mut Vec<RxEvent>) {
        if next == self.state {
            return;
        }
        self.state = next.clone();
        out.push(RxEvent::State(next));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::modes::MODES;

    /// A scriptable stand-in for the native decoder.
    struct FakeSession {
        status: DecodeStatus,
        mode: Option<&'static ModeInfo>,
        rows_ready: usize,
        quality: f32,
        slant: f32,
        pushed: usize,
        resets: usize,
        /// Fill value for read_rows, so tests can tell rows apart.
        fill: u32,
        /// When set, read_rows copies this many rows regardless of the request.
        short_read: Option<usize>,
    }

    impl FakeSession {
        fn new() -> FakeSession {
            FakeSession {
                status: DecodeStatus::Idle,
                mode: None,
                rows_ready: 0,
                quality: 0.0,
                slant: 0.0,
                pushed: 0,
                resets: 0,
                fill: 0xFFAABBCC,
                short_read: None,
            }
        }

        fn decoding(mode: &'static ModeInfo, rows: usize) -> FakeSession {
            FakeSession {
                status: DecodeStatus::Image,
                mode: Some(mode),
                rows_ready: rows,
                ..FakeSession::new()
            }
        }
    }

    impl SstvSession for FakeSession {
        fn push(&mut self, samples: &[f32]) {
            self.pushed += samples.len();
        }
        fn status(&self) -> DecodeStatus {
            self.status
        }
        fn mode(&self) -> Option<&'static ModeInfo> {
            self.mode
        }
        fn rows_ready(&self) -> usize {
            self.rows_ready
        }
        fn read_rows(&self, _first: usize, n: usize, out: &mut [u32]) -> usize {
            let n = self.short_read.unwrap_or(n);
            let w = self.mode.map(|m| m.width as usize).unwrap_or(0);
            for i in 0..(n * w).min(out.len()) {
                out[i] = self.fill;
            }
            n
        }
        fn quality(&self) -> f32 {
            self.quality
        }
        fn slant_ppm(&self) -> f32 {
            self.slant
        }
        fn reset(&mut self) {
            self.resets += 1;
            self.status = DecodeStatus::Idle;
            self.mode = None;
            self.rows_ready = 0;
        }
    }

    fn robot36() -> &'static ModeInfo {
        &MODES[0]
    }

    fn states(events: &[RxEvent]) -> Vec<&RxState> {
        events
            .iter()
            .filter_map(|e| match e {
                RxEvent::State(s) => Some(s),
                _ => None,
            })
            .collect()
    }

    fn rows_events(events: &[RxEvent]) -> Vec<(usize, usize)> {
        events
            .iter()
            .filter_map(|e| match e {
                RxEvent::Rows {
                    first_row,
                    width,
                    pixels,
                } => Some((*first_row, pixels.len() / *width as usize)),
                _ => None,
            })
            .collect()
    }

    #[test]
    fn audio_reaches_the_session() {
        let mut e = RxEngine::new(FakeSession::new());
        e.feed(&[vec![0.0; 100], vec![0.0; 50]]);
        assert_eq!(e.session.pushed, 150);
    }

    #[test]
    fn idle_publishes_nothing_new() {
        let mut e = RxEngine::new(FakeSession::new());
        // The engine starts Idle, so a first poll in IDLE is not a change.
        assert!(e.feed(&[]).is_empty());
        assert_eq!(*e.state(), RxState::Idle);
    }

    #[test]
    fn leader_and_vis_both_report_leader() {
        for status in [DecodeStatus::Leader, DecodeStatus::Vis] {
            let mut s = FakeSession::new();
            s.status = status;
            let mut e = RxEngine::new(s);
            assert_eq!(states(&e.feed(&[])), vec![&RxState::Leader]);
        }
    }

    #[test]
    fn repeated_polls_do_not_republish_an_unchanged_state() {
        let mut s = FakeSession::new();
        s.status = DecodeStatus::Leader;
        let mut e = RxEngine::new(s);
        assert_eq!(e.feed(&[]).len(), 1);
        assert!(e.feed(&[]).is_empty(), "state was republished unchanged");
    }

    #[test]
    fn vis_lock_is_logged_once_per_transmission() {
        let mut e = RxEngine::new(FakeSession::decoding(robot36(), 1));
        let first = e.feed(&[]);
        assert_eq!(
            first
                .iter()
                .filter(|ev| matches!(ev, RxEvent::Info(m) if m.contains("VIS lock")))
                .count(),
            1
        );
        e.session.rows_ready = 2;
        let second = e.feed(&[]);
        assert!(!second
            .iter()
            .any(|ev| matches!(ev, RxEvent::Info(m) if m.contains("VIS lock"))));
    }

    #[test]
    fn decoding_reports_progress_and_signal_metrics() {
        let mut s = FakeSession::decoding(robot36(), 10);
        s.quality = 0.75;
        s.slant = -12.5;
        let mut e = RxEngine::new(s);
        let ev = e.feed(&[]);
        match states(&ev)[..] {
            [RxState::Decoding {
                mode,
                rows,
                total_rows,
                quality,
                slant_ppm,
            }] => {
                assert_eq!(mode.id, 0);
                assert_eq!(*rows, 10);
                assert_eq!(*total_rows, 240);
                assert_eq!(*quality, 0.75);
                assert_eq!(*slant_ppm, -12.5);
            }
            ref other => panic!("unexpected states: {other:?}"),
        }
    }

    #[test]
    fn rows_stream_incrementally_without_resending() {
        let mut e = RxEngine::new(FakeSession::decoding(robot36(), 3));
        assert_eq!(rows_events(&e.feed(&[])), vec![(0, 3)]);

        e.session.rows_ready = 7;
        // Only the four new rows, starting where the last event ended.
        assert_eq!(rows_events(&e.feed(&[])), vec![(3, 4)]);

        // No new rows -> no Rows event at all.
        assert!(rows_events(&e.feed(&[])).is_empty());
    }

    #[test]
    fn row_payloads_carry_width_by_height_pixels() {
        let mut e = RxEngine::new(FakeSession::decoding(robot36(), 2));
        let ev = e.feed(&[]);
        match ev.iter().find(|e| matches!(e, RxEvent::Rows { .. })) {
            Some(RxEvent::Rows {
                first_row,
                width,
                pixels,
            }) => {
                assert_eq!(*first_row, 0);
                assert_eq!(*width, 320);
                assert_eq!(pixels.len(), 640);
                assert!(pixels.iter().all(|p| *p == 0xFFAABBCC));
            }
            other => panic!("expected a Rows event, got {other:?}"),
        }
    }

    #[test]
    fn a_short_read_only_advances_by_what_was_copied() {
        let mut s = FakeSession::decoding(robot36(), 5);
        s.short_read = Some(2);
        let mut e = RxEngine::new(s);
        assert_eq!(rows_events(&e.feed(&[])), vec![(0, 2)]);
        // The three rows that weren't copied must be offered again, not skipped.
        e.session.short_read = None;
        assert_eq!(rows_events(&e.feed(&[])), vec![(2, 3)]);
    }

    #[test]
    fn rows_ready_beyond_the_frame_is_clamped() {
        // A decoder reporting more rows than the mode has must not make us read
        // (or allocate) past the end of the image.
        let mut e = RxEngine::new(FakeSession::decoding(robot36(), 999));
        assert_eq!(rows_events(&e.feed(&[])), vec![(0, 240)]);
    }

    #[test]
    fn done_flushes_rows_then_reports_complete_and_resets() {
        let mut s = FakeSession::decoding(robot36(), 240);
        s.status = DecodeStatus::Done;
        s.quality = 0.9;
        let mut e = RxEngine::new(s);
        let ev = e.feed(&[]);

        // Pixels must arrive before the terminal state that tells the UI to
        // snapshot the frame.
        let rows_idx = ev.iter().position(|e| matches!(e, RxEvent::Rows { .. }));
        let state_idx = ev
            .iter()
            .position(|e| matches!(e, RxEvent::State(RxState::Complete { .. })));
        assert!(rows_idx.is_some() && state_idx.is_some());
        assert!(rows_idx < state_idx, "rows must precede Complete");

        match e.state() {
            RxState::Complete { mode, rows, quality } => {
                assert_eq!(mode.id, 0);
                assert_eq!(*rows, 240);
                assert_eq!(*quality, 0.9);
            }
            other => panic!("expected Complete, got {other:?}"),
        }
        assert_eq!(e.session.resets, 1, "decoder was not reset after DONE");
    }

    #[test]
    fn complete_survives_the_post_reset_idle() {
        let mut s = FakeSession::decoding(robot36(), 240);
        s.status = DecodeStatus::Done;
        let mut e = RxEngine::new(s);
        e.feed(&[]);
        // The reset put the fake back in IDLE; the operator must still see the
        // finished image, not an immediate "Idle".
        assert!(e.feed(&[]).is_empty());
        assert!(matches!(e.state(), RxState::Complete { .. }));
    }

    #[test]
    fn a_new_transmission_clears_the_held_terminal_state() {
        let mut s = FakeSession::decoding(robot36(), 240);
        s.status = DecodeStatus::Done;
        let mut e = RxEngine::new(s);
        e.feed(&[]);
        assert!(matches!(e.state(), RxState::Complete { .. }));

        e.session.status = DecodeStatus::Leader;
        assert_eq!(states(&e.feed(&[])), vec![&RxState::Leader]);
    }

    #[test]
    fn rows_restart_at_zero_for_the_next_transmission() {
        let mut s = FakeSession::decoding(robot36(), 240);
        s.status = DecodeStatus::Done;
        let mut e = RxEngine::new(s);
        e.feed(&[]);

        e.session.status = DecodeStatus::Image;
        e.session.mode = Some(robot36());
        e.session.rows_ready = 5;
        assert_eq!(rows_events(&e.feed(&[])), vec![(0, 5)]);
    }

    #[test]
    fn abort_keeps_the_partial_image_and_resets() {
        let mut s = FakeSession::decoding(robot36(), 100);
        s.status = DecodeStatus::Aborted;
        let mut e = RxEngine::new(s);
        let ev = e.feed(&[]);
        assert_eq!(rows_events(&ev), vec![(0, 100)]);
        match e.state() {
            RxState::Aborted { mode, rows } => {
                assert_eq!(mode.map(|m| m.id), Some(0));
                assert_eq!(*rows, 100);
            }
            other => panic!("expected Aborted, got {other:?}"),
        }
        assert_eq!(e.session.resets, 1);
    }

    #[test]
    fn done_without_a_mode_is_reported_as_an_abort() {
        // A "complete" frame we have no dimensions for cannot be displayed;
        // claiming Complete would show the operator an empty image.
        let mut s = FakeSession::new();
        s.status = DecodeStatus::Done;
        let mut e = RxEngine::new(s);
        e.feed(&[]);
        assert!(matches!(e.state(), RxState::Aborted { mode: None, .. }));
    }

    #[test]
    fn image_without_a_mode_falls_back_to_leader() {
        let mut s = FakeSession::new();
        s.status = DecodeStatus::Image;
        let mut e = RxEngine::new(s);
        assert_eq!(states(&e.feed(&[])), vec![&RxState::Leader]);
    }

    #[test]
    fn every_event_variant_serializes_for_the_ui() {
        // serde's internally-tagged representation cannot serialize a newtype
        // variant wrapping a string, which is a RUNTIME error — so exercise all
        // four variants, including the two carrying plain messages.
        let events = vec![
            RxEvent::State(RxState::Idle),
            RxEvent::State(RxState::Decoding {
                mode: *robot36(),
                rows: 3,
                total_rows: 240,
                quality: 0.5,
                slant_ppm: 1.0,
            }),
            RxEvent::Rows {
                first_row: 0,
                width: 320,
                pixels: vec![0xFF112233; 320],
            },
            RxEvent::Info("hunting".into()),
            RxEvent::Error("audio queue overflow".into()),
        ];
        for ev in events {
            let json = serde_json::to_value(&ev).expect("event must serialize");
            assert!(json.get("event").is_some(), "missing tag in {json}");
            assert!(json.get("data").is_some(), "missing payload in {json}");
        }
    }

    #[test]
    fn state_payloads_are_tagged_by_kind() {
        let json = serde_json::to_value(RxEvent::State(RxState::Complete {
            mode: *robot36(),
            rows: 240,
            quality: 0.9,
        }))
        .unwrap();
        assert_eq!(json["event"], "state");
        assert_eq!(json["data"]["kind"], "complete");
        assert_eq!(json["data"]["mode"]["short_code"], "R36");
        assert_eq!(json["data"]["rows"], 240);
    }

    #[test]
    fn poll_only_refreshes_status_without_feeding_audio() {
        let mut s = FakeSession::new();
        s.status = DecodeStatus::Leader;
        let mut e = RxEngine::new(s);
        assert_eq!(states(&e.poll_only()), vec![&RxState::Leader]);
        assert_eq!(e.session.pushed, 0);
    }
}
