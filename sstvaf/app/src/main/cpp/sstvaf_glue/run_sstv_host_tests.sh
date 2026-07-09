#!/usr/bin/env bash
#
# Build and run the native SSTV codec tests on a POSIX host (Linux/macOS),
# no device/emulator needed. Companion to run_sstv_host_tests.ps1 (Windows);
# both compile the sstvaf_glue/test_sstv_*.c suites against the clean-room
# codec in cpp/sstv_lib and exit 0 only when every suite passes.
#
# Usage:
#   run_sstv_host_tests.sh           # build + run all suites
#   run_sstv_host_tests.sh --emit    # re-emit golden checksums (paste into
#                                    # test_sstv_golden.c; never hand-edit)
#   CC=gcc run_sstv_host_tests.sh    # override the compiler (default: clang)

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cpp="$(dirname "$here")"
lib="$cpp/sstv_lib"
CC="${CC:-clang}"

lib_srcs=(
    "$lib/sstv_modes.c"
    "$lib/sstv_vis.c"
    "$lib/sstv_osc.c"
    "$lib/sstv_color.c"
    "$lib/sstv_encode.c"
    "$lib/sstv_demod.c"
    "$lib/sstv_decode.c"
)

tmp="$(mktemp -d)"
# Clean up the temp build dir on exit; set -e propagates a failing suite's
# exit status as the script's exit status.
trap 'rm -rf "$tmp"' EXIT

cflags=(-std=c11 -O2 -Wall -Wno-unused-function -I "$lib")

# -lm is needed on Linux (glibc keeps libm separate); it doesn't exist for an
# MSVC-target clang, so drop it when running under Git Bash / MSYS on Windows.
ldlibs=(-lm)
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) ldlibs=() ;;
esac

# --emit (golden re-gen) only applies to the golden binary.
if [ "${1:-}" = "--emit" ]; then
    out="$tmp/test_sstv_golden"
    "$CC" "${cflags[@]}" "$here/test_sstv_golden.c" "${lib_srcs[@]}" "${ldlibs[@]}" -o "$out"
    "$out" --emit
    exit "$?"
fi

suites=(
    test_sstv_modes
    test_sstv_golden
    test_sstv_vis
    test_sstv_roundtrip
    test_sstv_slant
    test_sstv_robot36_chroma
)

for suite in "${suites[@]}"; do
    out="$tmp/$suite"
    "$CC" "${cflags[@]}" "$here/$suite.c" "${lib_srcs[@]}" "${ldlibs[@]}" -o "$out"
    "$out"
done

echo "all SSTV host test suites passed"
