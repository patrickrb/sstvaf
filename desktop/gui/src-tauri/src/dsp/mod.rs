//! The SSTV codec layer: safe Rust over the C core in `cpp/sstv_lib`.
//!
//! `ffi` is the raw binding; `encode`/`decoder` are the safe wrappers the rest
//! of the app uses. Nothing outside this module touches `ffi` directly.

pub mod decoder;
pub mod encode;
pub mod ffi;

use std::fmt;

/// A negative return from the C codec, mapped to its `SSTV_ERR_*` meaning.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SstvError {
    BadMode,
    BadArgs,
    Capacity,
    Range,
    NoMem,
    /// Something the C API isn't documented to return — kept rather than
    /// collapsed so a codec change surfaces as a distinct error, not silence.
    Unknown(i32),
    /// Rust-side precondition (checked before the call reaches C).
    Invalid(String),
}

impl SstvError {
    /// Map a negative C return code. Non-negative input is a caller bug, so it
    /// is reported as `Unknown` rather than silently treated as success.
    pub fn from_code(code: i32) -> SstvError {
        match code {
            ffi::SSTV_ERR_BAD_MODE => SstvError::BadMode,
            ffi::SSTV_ERR_BAD_ARGS => SstvError::BadArgs,
            ffi::SSTV_ERR_CAPACITY => SstvError::Capacity,
            ffi::SSTV_ERR_RANGE => SstvError::Range,
            ffi::SSTV_ERR_NOMEM => SstvError::NoMem,
            other => SstvError::Unknown(other),
        }
    }
}

impl fmt::Display for SstvError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            SstvError::BadMode => write!(f, "unknown SSTV mode id"),
            SstvError::BadArgs => write!(f, "invalid arguments to the SSTV codec"),
            SstvError::Capacity => write!(f, "output buffer too small"),
            SstvError::Range => write!(f, "row range outside the decoded image"),
            SstvError::NoMem => write!(f, "SSTV codec allocation failed"),
            SstvError::Unknown(c) => write!(f, "SSTV codec error {c}"),
            SstvError::Invalid(m) => write!(f, "{m}"),
        }
    }
}

impl std::error::Error for SstvError {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn maps_every_documented_error_code() {
        assert_eq!(SstvError::from_code(-1), SstvError::BadMode);
        assert_eq!(SstvError::from_code(-2), SstvError::BadArgs);
        assert_eq!(SstvError::from_code(-3), SstvError::Capacity);
        assert_eq!(SstvError::from_code(-4), SstvError::Range);
        assert_eq!(SstvError::from_code(-5), SstvError::NoMem);
    }

    #[test]
    fn keeps_undocumented_codes_distinct() {
        assert_eq!(SstvError::from_code(-99), SstvError::Unknown(-99));
        assert_eq!(SstvError::from_code(7), SstvError::Unknown(7));
    }

    #[test]
    fn errors_render_a_human_message() {
        assert!(SstvError::Capacity.to_string().contains("too small"));
        assert_eq!(
            SstvError::Invalid("bad size".into()).to_string(),
            "bad size"
        );
    }
}
