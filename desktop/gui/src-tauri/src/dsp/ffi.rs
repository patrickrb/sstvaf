//! Hand-written FFI to the clean-room SSTV codec (`cpp/sstv_lib/sstv.h`).
//!
//! The C API is handle-based and passes only scalars, pointers and plain
//! buffers, so — unlike the FT8AF-era ft8_lib binding — there are no `#[repr(C)]`
//! layouts to keep in sync. `sstv_decoder_t` stays opaque on this side.
#![allow(non_camel_case_types)]

use std::os::raw::c_int;

// --- decoder status (sstv.h SSTV_STATUS_*) ----------------------------------
pub const SSTV_STATUS_IDLE: c_int = 0;
pub const SSTV_STATUS_LEADER: c_int = 1;
pub const SSTV_STATUS_VIS: c_int = 2;
pub const SSTV_STATUS_IMAGE: c_int = 3;
pub const SSTV_STATUS_DONE: c_int = 4;
pub const SSTV_STATUS_ABORTED: c_int = 5;

// --- error codes (sstv.h SSTV_ERR_*) ----------------------------------------
pub const SSTV_ERR_BAD_MODE: c_int = -1;
pub const SSTV_ERR_BAD_ARGS: c_int = -2;
pub const SSTV_ERR_CAPACITY: c_int = -3;
pub const SSTV_ERR_RANGE: c_int = -4;
pub const SSTV_ERR_NOMEM: c_int = -5;

// --- encode flags -----------------------------------------------------------
/// Transmit Robot 36's alternating chroma starting with B-Y instead of R-Y.
/// Real-world encoders do both; exists so tests can exercise the decoder's
/// separator-tone-keyed pairing. Normal callers pass 0.
pub const SSTV_ENCODE_SWAP_ROBOT36_PARITY: c_int = 0x1;

/// Opaque `sstv_decoder_t`. Never constructed on the Rust side — only ever held
/// behind the pointer `sstv_decoder_create` returns.
#[repr(C)]
pub struct sstv_decoder_t {
    _private: [u8; 0],
}

extern "C" {
    // encoder
    pub fn sstv_encode_num_samples(mode_id: c_int, sample_rate: c_int) -> c_int;
    pub fn sstv_encode(
        mode_id: c_int,
        argb: *const u32,
        width: c_int,
        height: c_int,
        sample_rate: c_int,
        amplitude: f32,
        out: *mut f32,
        out_capacity: c_int,
    ) -> c_int;
    pub fn sstv_encode_ex(
        mode_id: c_int,
        argb: *const u32,
        width: c_int,
        height: c_int,
        sample_rate: c_int,
        amplitude: f32,
        flags: c_int,
        out: *mut f32,
        out_capacity: c_int,
    ) -> c_int;

    // decoder
    pub fn sstv_decoder_create(sample_rate: c_int) -> *mut sstv_decoder_t;
    pub fn sstv_decoder_push(d: *mut sstv_decoder_t, samples: *const f32, n: c_int);
    pub fn sstv_decoder_status(d: *const sstv_decoder_t) -> c_int;
    pub fn sstv_decoder_mode(d: *const sstv_decoder_t) -> c_int;
    pub fn sstv_decoder_rows_ready(d: *const sstv_decoder_t) -> c_int;
    pub fn sstv_decoder_read_rows(
        d: *const sstv_decoder_t,
        first_row: c_int,
        n_rows: c_int,
        argb: *mut u32,
    ) -> c_int;
    pub fn sstv_decoder_slant_ppm(d: *const sstv_decoder_t) -> f32;
    pub fn sstv_decoder_quality(d: *const sstv_decoder_t) -> f32;
    pub fn sstv_decoder_reset(d: *mut sstv_decoder_t);
    pub fn sstv_decoder_destroy(d: *mut sstv_decoder_t);
}
