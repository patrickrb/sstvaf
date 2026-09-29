//! Auto-save of completed decodes: turn the engine's full-frame snapshot into
//! a PNG on disk, off the decode thread.
//!
//! Mirrors the Android `RxAutoSaveController` semantics: only COMPLETE frames
//! are written (the engine never snapshots an abort), each frame is written
//! exactly once (`RxEngine::take_completed_frame` hands it over destructively),
//! and the file write runs on its own short-lived thread so the audio/decode
//! loop never blocks on disk I/O.

use std::path::{Path, PathBuf};
use std::sync::mpsc::Sender;

use chrono::{DateTime, Datelike, Timelike, Utc};

use crate::modes::ModeInfo;
use crate::rx::engine::RxEvent;

/// A finished frame, snapshotted by the engine at DONE — the decoder reset that
/// follows discards the image, so this copy is the only one that survives.
#[derive(Debug, Clone, PartialEq)]
pub struct CompletedFrame {
    pub mode: ModeInfo,
    /// Rows actually decoded (the full height for a normal complete).
    pub rows: usize,
    /// Row-major ARGB (0xAARRGGBB), `rows * mode.width` pixels.
    pub pixels: Vec<u32>,
    pub quality: f32,
}

/// `sstv-YYYYMMDD-HHMMSS-<mode short code>.png`. The timestamp is UTC — the
/// ham convention, and the same clock the Android image store names files with.
pub fn frame_filename(when: DateTime<Utc>, short_code: &str) -> String {
    format!(
        "sstv-{:04}{:02}{:02}-{:02}{:02}{:02}-{}.png",
        when.year(),
        when.month(),
        when.day(),
        when.hour(),
        when.minute(),
        when.second(),
        short_code
    )
}

/// The path to write, dodging collisions: two frames finishing within the same
/// second (or a re-run over yesterday's files) get `-2`, `-3`, … suffixes
/// rather than overwriting — "never save the same frame twice" also means
/// never letting a new frame destroy an old one.
pub fn unique_path(dir: &Path, filename: &str) -> PathBuf {
    let first = dir.join(filename);
    if !first.exists() {
        return first;
    }
    let stem = filename.strip_suffix(".png").unwrap_or(filename);
    for n in 2.. {
        let candidate = dir.join(format!("{stem}-{n}.png"));
        if !candidate.exists() {
            return candidate;
        }
    }
    unreachable!("the counter loop always returns");
}

/// Encode `rows` scan lines of ARGB pixels as an RGB8 PNG at `path`. Alpha is
/// dropped (forced opaque), matching how the UI canvas renders the same pixels.
pub fn write_png(path: &Path, width: u32, rows: usize, pixels: &[u32]) -> anyhow::Result<()> {
    if width == 0 || rows == 0 {
        anyhow::bail!("refusing to write an empty image ({width}x{rows})");
    }
    if pixels.len() != width as usize * rows {
        anyhow::bail!(
            "pixel buffer is {} but {width}x{rows} needs {}",
            pixels.len(),
            width as usize * rows
        );
    }
    let mut rgb = Vec::with_capacity(pixels.len() * 3);
    for p in pixels {
        rgb.push((p >> 16) as u8);
        rgb.push((p >> 8) as u8);
        rgb.push(*p as u8);
    }
    let file = std::fs::File::create(path)?;
    let mut encoder = png::Encoder::new(std::io::BufWriter::new(file), width, rows as u32);
    encoder.set_color(png::ColorType::Rgb);
    encoder.set_depth(png::BitDepth::Eight);
    let mut writer = encoder.write_header()?;
    writer.write_image_data(&rgb)?;
    Ok(())
}

/// Write `frame` into `dir` (created on demand) and return the path used.
pub fn save_frame(
    dir: &Path,
    frame: &CompletedFrame,
    when: DateTime<Utc>,
) -> anyhow::Result<PathBuf> {
    std::fs::create_dir_all(dir)?;
    let path = unique_path(dir, &frame_filename(when, frame.mode.short_code));
    write_png(&path, frame.mode.width, frame.rows, &frame.pixels)?;
    Ok(path)
}

/// Save on a worker thread and report the outcome on the RX event channel —
/// `Saved` with the path on success, `Error` on failure — so the UI learns
/// about persistence the same way it learns about everything else.
pub fn spawn_save(dir: PathBuf, frame: CompletedFrame, events: Sender<RxEvent>) {
    let worker_events = events.clone();
    let spawned = std::thread::Builder::new()
        .name("sstvaf-image-save".into())
        .spawn(move || {
            let event = match save_frame(&dir, &frame, Utc::now()) {
                Ok(path) => RxEvent::Saved {
                    filename: path
                        .file_name()
                        .map(|f| f.to_string_lossy().into_owned())
                        .unwrap_or_default(),
                    path: path.to_string_lossy().into_owned(),
                },
                Err(e) => RxEvent::Error(format!("image auto-save failed: {e:#}")),
            };
            let _ = worker_events.send(event);
        });
    if let Err(e) = spawned {
        let _ = events.send(RxEvent::Error(format!("image auto-save failed: {e}")));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::modes::MODES;
    use chrono::TimeZone;

    /// A scratch directory unique to one test, cleaned up on drop.
    struct ScratchDir(PathBuf);

    impl ScratchDir {
        fn new(tag: &str) -> ScratchDir {
            let dir = std::env::temp_dir().join(format!(
                "sstvaf-save-test-{}-{tag}",
                std::process::id()
            ));
            // A leftover from a crashed run must not poison unique_path checks.
            let _ = std::fs::remove_dir_all(&dir);
            std::fs::create_dir_all(&dir).expect("scratch dir");
            ScratchDir(dir)
        }
    }

    impl Drop for ScratchDir {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    fn at(y: i32, mo: u32, d: u32, h: u32, mi: u32, s: u32) -> DateTime<Utc> {
        Utc.with_ymd_and_hms(y, mo, d, h, mi, s).unwrap()
    }

    #[test]
    fn filename_is_timestamp_then_short_code() {
        assert_eq!(
            frame_filename(at(2026, 9, 29, 13, 45, 2), "R36"),
            "sstv-20260929-134502-R36.png"
        );
    }

    #[test]
    fn filename_zero_pads_every_component() {
        // Single-digit month/day/hour/minute/second must not shrink the name —
        // fixed-width timestamps are what keep the directory sorted by time.
        assert_eq!(
            frame_filename(at(2026, 1, 5, 3, 7, 9), "PD120"),
            "sstv-20260105-030709-PD120.png"
        );
    }

    #[test]
    fn unique_path_uses_the_plain_name_when_free() {
        let dir = ScratchDir::new("unique-free");
        assert_eq!(
            unique_path(&dir.0, "sstv-20260101-000000-R36.png"),
            dir.0.join("sstv-20260101-000000-R36.png")
        );
    }

    #[test]
    fn unique_path_suffixes_instead_of_overwriting() {
        let dir = ScratchDir::new("unique-clash");
        let name = "sstv-20260101-000000-R36.png";
        std::fs::write(dir.0.join(name), b"x").unwrap();
        assert_eq!(
            unique_path(&dir.0, name),
            dir.0.join("sstv-20260101-000000-R36-2.png")
        );
        std::fs::write(dir.0.join("sstv-20260101-000000-R36-2.png"), b"x").unwrap();
        assert_eq!(
            unique_path(&dir.0, name),
            dir.0.join("sstv-20260101-000000-R36-3.png")
        );
    }

    #[test]
    fn png_roundtrips_the_pixels_with_alpha_dropped() {
        let dir = ScratchDir::new("roundtrip");
        let path = dir.0.join("frame.png");
        // 3x2 with distinct channels per pixel, including a transparent alpha
        // that must not survive into the file.
        let pixels: Vec<u32> = vec![
            0xFF102030, 0x00FF0000, 0xFF00FF00, //
            0xFF0000FF, 0x80FFFFFF, 0xFF000000,
        ];
        write_png(&path, 3, 2, &pixels).expect("encode");

        let decoder = png::Decoder::new(std::fs::File::open(&path).unwrap());
        let mut reader = decoder.read_info().expect("read_info");
        let mut buf = vec![0u8; reader.output_buffer_size()];
        let info = reader.next_frame(&mut buf).expect("decode");
        assert_eq!((info.width, info.height), (3, 2));
        assert_eq!(info.color_type, png::ColorType::Rgb);
        let expected: Vec<u8> = vec![
            0x10, 0x20, 0x30, 0xFF, 0x00, 0x00, 0x00, 0xFF, 0x00, //
            0x00, 0x00, 0xFF, 0xFF, 0xFF, 0xFF, 0x00, 0x00, 0x00,
        ];
        assert_eq!(&buf[..info.buffer_size()], &expected[..]);
    }

    #[test]
    fn png_rejects_a_buffer_that_does_not_match_the_geometry() {
        let dir = ScratchDir::new("badsize");
        let path = dir.0.join("frame.png");
        assert!(write_png(&path, 3, 2, &[0u32; 5]).is_err());
        assert!(write_png(&path, 0, 2, &[]).is_err());
        assert!(!path.exists(), "a rejected write must not leave a file");
    }

    #[test]
    fn save_frame_creates_the_directory_and_names_by_mode() {
        let dir = ScratchDir::new("save-frame");
        let nested = dir.0.join("Pictures").join("SSTVAF");
        let mode = MODES[0]; // Robot 36, R36, 320x240
        let frame = CompletedFrame {
            mode,
            rows: mode.height as usize,
            pixels: vec![0xFF336699; mode.pixel_count()],
            quality: 0.9,
        };
        let path = save_frame(&nested, &frame, at(2026, 9, 29, 1, 2, 3)).expect("save");
        assert_eq!(path, nested.join("sstv-20260929-010203-R36.png"));
        assert!(path.exists());
    }

    #[test]
    fn spawn_save_reports_the_saved_path_on_the_event_channel() {
        let dir = ScratchDir::new("spawn-ok");
        let mode = MODES[0];
        let frame = CompletedFrame {
            mode,
            rows: 1,
            pixels: vec![0xFFABCDEF; mode.width as usize],
            quality: 1.0,
        };
        let (tx, rx) = std::sync::mpsc::channel();
        spawn_save(dir.0.clone(), frame, tx);
        match rx.recv_timeout(std::time::Duration::from_secs(10)) {
            Ok(RxEvent::Saved { path, filename }) => {
                assert!(filename.starts_with("sstv-") && filename.ends_with("-R36.png"));
                assert!(std::path::Path::new(&path).exists());
            }
            other => panic!("expected Saved, got {other:?}"),
        }
    }

    #[test]
    fn spawn_save_reports_a_failure_as_an_error_event() {
        let dir = ScratchDir::new("spawn-fail");
        let mode = MODES[0];
        // rows=2 with one row of pixels: save_frame must fail, and the failure
        // must come back as an Error event rather than vanishing on the worker.
        let frame = CompletedFrame {
            mode,
            rows: 2,
            pixels: vec![0xFF000000; mode.width as usize],
            quality: 1.0,
        };
        let (tx, rx) = std::sync::mpsc::channel();
        spawn_save(dir.0.clone(), frame, tx);
        match rx.recv_timeout(std::time::Duration::from_secs(10)) {
            Ok(RxEvent::Error(m)) => assert!(m.contains("auto-save failed"), "got {m}"),
            other => panic!("expected Error, got {other:?}"),
        }
    }
}
