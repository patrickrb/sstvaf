#!/usr/bin/env bash
#
# Cross-compile the SSTVAF desktop CLI for 32-bit Windows and package it as an
# MSI — entirely from a Linux host, no Windows machine or Visual Studio needed.
#
# Toolchain (Debian/Ubuntu package names):
#   * i686-w64-mingw32-gcc  (gcc-mingw-w64-i686)  — the 32-bit Windows C compiler
#   * wixl                  (wixl / msitools)     — builds MSIs on Linux
#
# The produced sstvaf.exe is a self-contained i386 PE (statically linked libgcc,
# no MSVCRT redistributable), so it runs on stock 32-bit Windows.
#
# Usage:
#   build_win32_msi.sh [VERSION]      # VERSION like 1.2.3 (default 0.1.0)
#
# Outputs (under desktop/build/win32/):
#   sstvaf.exe
#   sstvaf-<VERSION>-win32.msi

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/.." && pwd)"
cpp="$repo/sstvaf/app/src/main/cpp"
lib="$cpp/sstv_lib"

VERSION="${1:-0.1.0}"
CROSS="${CROSS:-i686-w64-mingw32}"
CC="${CC:-${CROSS}-gcc}"
WIXL="${WIXL:-wixl}"

out_dir="$here/build/win32"
mkdir -p "$out_dir"
exe="$out_dir/sstvaf.exe"
msi="$out_dir/sstvaf-${VERSION}-win32.msi"

if ! command -v "$CC" >/dev/null 2>&1; then
    echo "error: $CC not found. Install gcc-mingw-w64-i686 (Debian/Ubuntu):" >&2
    echo "  sudo apt-get install -y gcc-mingw-w64-i686" >&2
    exit 1
fi

lib_srcs=(
    "$lib/sstv_modes.c"
    "$lib/sstv_vis.c"
    "$lib/sstv_osc.c"
    "$lib/sstv_color.c"
    "$lib/sstv_encode.c"
    "$lib/sstv_demod.c"
    "$lib/sstv_decode.c"
)

echo "==> Cross-compiling sstvaf.exe for 32-bit Windows ($CROSS)"
"$CC" -std=c11 -O2 -Wall -Wno-unused-function \
    -m32 -static -static-libgcc \
    -I "$lib" -I "$here" \
    "$here/main.c" "$here/sstvaf_cli.c" "${lib_srcs[@]}" \
    -lm -o "$exe"

file "$exe" || true
echo "==> Built $exe"

if ! command -v "$WIXL" >/dev/null 2>&1; then
    echo "warning: $WIXL not found — skipping MSI packaging." >&2
    echo "Install msitools (Debian/Ubuntu: sudo apt-get install -y wixl)." >&2
    exit 0
fi

echo "==> Packaging $msi with wixl"
"$WIXL" -a x86 \
    -D Version="$VERSION" \
    -D SourceExe="$exe" \
    "$here/packaging/sstvaf.wxs" \
    -o "$msi"

echo "==> Built $msi"
ls -l "$exe" "$msi"
