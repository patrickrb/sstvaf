// Compile the clean-room SSTV codec (cpp/sstv_lib) into a static `sstvcore`
// lib that the Rust FFI layer links.
//
// We reference the C sources in place inside the Android tree
// (sstvaf/app/src/main/cpp/sstv_lib) rather than duplicating them, so the
// desktop GUI always tracks the same codec the phone runs — the same thing
// desktop/build_win32_msi.sh does for the CLI. sstv_lib is pure C11 with no
// Android/JNI dependency (it defines its own M_PI and uses no VLAs or
// POSIX-only functions), so unlike the FT8AF-era ft8_lib it needs no clang-cl
// and no compat shim — MSVC's cl.exe compiles it as-is.

use std::path::PathBuf;

fn main() {
    let manifest = PathBuf::from(std::env::var("CARGO_MANIFEST_DIR").unwrap());
    // src-tauri -> gui -> desktop -> repo root
    let cpp = manifest.join("../../../sstvaf/app/src/main/cpp");
    let sstv = cpp.join("sstv_lib");

    let mut build = cc::Build::new();
    build.include(&sstv);

    // The SSTV family only. wefax.c lives in the same directory but is a
    // separate codec with its own API and no cross-references from these files
    // (the CLI's build_win32_msi.sh omits it for the same reason); it gets
    // added here when the GUI grows a WeFax receive path.
    for f in [
        "sstv_color.c",
        "sstv_decode.c",
        "sstv_demod.c",
        "sstv_encode.c",
        "sstv_modes.c",
        "sstv_osc.c",
        "sstv_vis.c",
    ] {
        build.file(sstv.join(f));
    }

    // MSVC needs _USE_MATH_DEFINES for M_PI in <math.h>; sstv_lib also defines
    // its own fallback, but the define keeps the two paths consistent.
    build.define("_USE_MATH_DEFINES", None);
    build.warnings(false);
    build.compile("sstvcore");

    println!("cargo:rerun-if-changed={}", sstv.display());
    println!("cargo:rerun-if-changed=build.rs");

    // Generate the Tauri build context (reads tauri.conf.json).
    tauri_build::build();
}
