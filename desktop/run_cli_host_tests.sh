#!/usr/bin/env bash
#
# Build and run the SSTVAF desktop CLI host tests on a POSIX host (Linux/macOS),
# no device/emulator needed. Compiles desktop/test_sstvaf_cli.c against the CLI
# helpers (desktop/sstvaf_cli.c) and the clean-room codec in cpp/sstv_lib, then
# exits 0 only when every check passes. Sibling of the codec's
# cpp/sstvaf_glue/run_sstv_host_tests.sh.
#
# Usage:
#   run_cli_host_tests.sh          # build + run
#   CC=gcc run_cli_host_tests.sh   # override the compiler (default: clang)

set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo="$(cd "$here/.." && pwd)"
cpp="$repo/ft8af/app/src/main/cpp"
lib="$cpp/sstv_lib"
glue="$cpp/sstvaf_glue"
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
trap 'rm -rf "$tmp"' EXIT

# -I the codec headers, the CLI dir, and the glue dir (for sstv_test_util.h).
cflags=(-std=c11 -O2 -Wall -Wno-unused-function -I "$lib" -I "$here" -I "$glue")

# glibc keeps libm separate; MSVC-target clang / MSYS has no -lm.
ldlibs=(-lm)
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) ldlibs=() ;;
esac

out="$tmp/test_sstvaf_cli"
"$CC" "${cflags[@]}" "$here/test_sstvaf_cli.c" "$here/sstvaf_cli.c" \
    "${lib_srcs[@]}" "${ldlibs[@]}" -o "$out"

# Run in the temp dir so the file-round-trip test's scratch files never touch
# the working tree.
( cd "$tmp" && "$out" )

echo "SSTVAF desktop CLI host tests passed"
