//! The nine SSTV modes implemented by the native codec (`cpp/sstv_lib`).
//!
//! `id` MUST match the `SSTV_MODE_*` enum in `cpp/sstv_lib/sstv.h` — it crosses
//! the FFI boundary verbatim. The rest mirrors the mode table in
//! `cpp/sstv_lib/sstv_modes.c` and the Android app's `SstvMode.kt`; the tests
//! below pin every value against the same hardcoded expectations `SstvModeTest`
//! uses, so a drift between the C table, the Kotlin enum and this one fails the
//! build on whichever side moved.

// Serialize only: the table is a compile-time constant that travels outward to
// the UI, and its &'static str fields can't be deserialized into.
use serde::Serialize;

#[derive(Debug, Clone, Copy, PartialEq, Serialize)]
pub struct ModeInfo {
    pub id: i32,
    pub name: &'static str,
    pub short_code: &'static str,
    pub width: u32,
    pub height: u32,
    pub vis_code: u8,
    /// Full transmission length: 0.91 s calibration header + image time.
    pub tx_seconds: f64,
}

impl ModeInfo {
    /// Pixels in a full frame — the size of the ARGB buffer the codec fills.
    pub fn pixel_count(&self) -> usize {
        self.width as usize * self.height as usize
    }
}

pub const MODES: [ModeInfo; 9] = [
    ModeInfo { id: 0, name: "Robot 36", short_code: "R36", width: 320, height: 240, vis_code: 8, tx_seconds: 36.91 },
    ModeInfo { id: 1, name: "Robot 72", short_code: "R72", width: 320, height: 240, vis_code: 12, tx_seconds: 72.91 },
    ModeInfo { id: 2, name: "Martin 1", short_code: "M1", width: 320, height: 256, vis_code: 44, tx_seconds: 115.200176 },
    ModeInfo { id: 3, name: "Martin 2", short_code: "M2", width: 320, height: 256, vis_code: 40, tx_seconds: 58.970288 },
    ModeInfo { id: 4, name: "Scottie 1", short_code: "S1", width: 320, height: 256, vis_code: 60, tx_seconds: 110.54332 },
    ModeInfo { id: 5, name: "Scottie 2", short_code: "S2", width: 320, height: 256, vis_code: 56, tx_seconds: 72.008152 },
    ModeInfo { id: 6, name: "PD 50", short_code: "PD50", width: 320, height: 256, vis_code: 93, tx_seconds: 50.59448 },
    ModeInfo { id: 7, name: "PD 90", short_code: "PD90", width: 320, height: 256, vis_code: 99, tx_seconds: 90.89912 },
    ModeInfo { id: 8, name: "PD 120", short_code: "PD120", width: 640, height: 496, vis_code: 95, tx_seconds: 127.01304 },
];

/// Look up a mode by its native id; `None` for -1 (no VIS lock yet) or an id
/// outside the table.
pub fn mode_by_id(id: i32) -> Option<&'static ModeInfo> {
    MODES.iter().find(|m| m.id == id)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ids_are_dense_and_in_declaration_order() {
        // The id is the C enum ordinal; a gap or reorder here means the table
        // no longer matches sstv.h.
        for (i, m) in MODES.iter().enumerate() {
            assert_eq!(m.id, i as i32, "mode {} has the wrong id", m.name);
        }
    }

    #[test]
    fn matches_the_kotlin_mode_table() {
        // Same expectations SstvModeTest pins on the Android side.
        let expected: [(i32, &str, &str, u32, u32, u8); 9] = [
            (0, "Robot 36", "R36", 320, 240, 8),
            (1, "Robot 72", "R72", 320, 240, 12),
            (2, "Martin 1", "M1", 320, 256, 44),
            (3, "Martin 2", "M2", 320, 256, 40),
            (4, "Scottie 1", "S1", 320, 256, 60),
            (5, "Scottie 2", "S2", 320, 256, 56),
            (6, "PD 50", "PD50", 320, 256, 93),
            (7, "PD 90", "PD90", 320, 256, 99),
            (8, "PD 120", "PD120", 640, 496, 95),
        ];
        for (i, e) in expected.iter().enumerate() {
            let m = &MODES[i];
            assert_eq!((m.id, m.name, m.short_code, m.width, m.height, m.vis_code), *e);
        }
    }

    #[test]
    fn vis_codes_are_unique() {
        // The decoder keys mode selection off the VIS code, so a duplicate
        // would make two modes indistinguishable on the air.
        for (i, a) in MODES.iter().enumerate() {
            for b in MODES.iter().skip(i + 1) {
                assert_ne!(a.vis_code, b.vis_code, "{} and {} share a VIS code", a.name, b.name);
            }
        }
    }

    #[test]
    fn lookup_by_id_round_trips() {
        assert_eq!(mode_by_id(0).unwrap().name, "Robot 36");
        assert_eq!(mode_by_id(8).unwrap().short_code, "PD120");
    }

    #[test]
    fn lookup_rejects_unknown_ids() {
        // -1 is what sstv_decoder_mode() returns before a VIS lock.
        assert!(mode_by_id(-1).is_none());
        assert!(mode_by_id(9).is_none());
    }

    #[test]
    fn pixel_count_is_width_times_height() {
        assert_eq!(MODES[0].pixel_count(), 320 * 240);
        assert_eq!(MODES[8].pixel_count(), 640 * 496);
    }
}
