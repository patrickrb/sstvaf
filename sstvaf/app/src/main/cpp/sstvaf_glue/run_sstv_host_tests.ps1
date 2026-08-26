<#
.SYNOPSIS
  Build and run the native SSTV codec tests on the host (no device needed).

.DESCRIPTION
  Compiles the sstvaf_glue/test_sstv_*.c suites against the clean-room codec
  in cpp/sstv_lib with a host clang and runs them. Exit code 0 == all suites
  pass. Companion to run_sstv_host_tests.sh (POSIX); mirrors the style of
  ft8af_glue/run_host_tests.ps1 (which it deliberately does not touch).

  Suites:
    test_sstv_modes          — mode timing table vs hand-computed goldens
    test_sstv_golden         — frozen encoder checksums + frequency spot checks
    test_sstv_vis            — VIS detect: clean/offset/noise/reject cases
    test_sstv_roundtrip      — encode->decode PSNR floors, abort, reset
    test_sstv_slant          — clock-slant tracking + sync freewheeling
    test_sstv_robot36_chroma — separator-keyed chroma pairing robustness
    test_wefax               — WeFax/radiofax helpers, phasing lock, roundtrip

.PARAMETER Clang
  Path to a host clang.exe. Defaults to a search of common install locations.
  The Android NDK clang does NOT work here — use a desktop LLVM
  (winget install LLVM.LLVM) or MSVC clang.

.PARAMETER Regen
  Run test_sstv_golden with --emit: prints paste-ready checksum literals
  computed live from the current encoder. Use ONLY when the encoder output
  changes intentionally, then paste the block over kGoldenChecksums in
  test_sstv_golden.c. Never hand-edit an entry.

.EXAMPLE
  ./run_sstv_host_tests.ps1
.EXAMPLE
  ./run_sstv_host_tests.ps1 -Clang "C:\Program Files\LLVM\bin\clang.exe"
#>
param(
    [string]$Clang = "",
    [switch]$Regen
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Definition
$cpp  = Split-Path -Parent $here            # .../cpp
$lib  = Join-Path $cpp "sstv_lib"

if (-not $Clang) {
    $probe = @(
        "C:\Program Files\LLVM\bin\clang.exe",
        "${env:ProgramFiles(x86)}\LLVM\bin\clang.exe",
        (Get-Command clang -ErrorAction SilentlyContinue).Source
    )
    $candidates = @($probe | Where-Object { $_ -and (Test-Path $_) })
    if ($candidates.Count -eq 0) {
        Write-Error "No host clang found. Install LLVM (winget install LLVM.LLVM) or pass -Clang <path>."
    }
    $Clang = $candidates[0]
}
Write-Host "Using clang: $Clang"

# The whole codec links into every suite; it is small and the tests cross
# encoder/decoder boundaries anyway.
$libSrcs = @(
    "sstv_modes.c","sstv_vis.c","sstv_osc.c","sstv_color.c",
    "sstv_encode.c","sstv_demod.c","sstv_decode.c","wefax.c"
) | ForEach-Object { Join-Path $lib $_ }

$common = @("-std=c11","-O2","-D_CRT_SECURE_NO_WARNINGS",
            "-Wall","-Wno-unused-function",
            "-I", $lib)

$suites = @(
    "test_sstv_modes",
    "test_sstv_golden",
    "test_sstv_vis",
    "test_sstv_roundtrip",
    "test_sstv_slant",
    "test_sstv_robot36_chroma",
    "test_wefax"
)

if ($Regen) {
    $src = Join-Path $here "test_sstv_golden.c"
    $out = Join-Path $env:TEMP "sstv_golden_test.exe"
    & $Clang @common $src @libSrcs -o $out
    if ($LASTEXITCODE -ne 0) { Write-Error "Compile failed (test_sstv_golden)." }
    & $out --emit
    exit $LASTEXITCODE
}

$failed = 0
foreach ($suite in $suites) {
    $src = Join-Path $here "$suite.c"
    $out = Join-Path $env:TEMP "$suite.exe"
    & $Clang @common $src @libSrcs -o $out
    if ($LASTEXITCODE -ne 0) { Write-Error "Compile failed ($suite)." }
    & $out
    if ($LASTEXITCODE -ne 0) {
        Write-Host "SUITE FAILED: $suite" -ForegroundColor Red
        $failed = 1
    }
}

if ($failed) { exit 1 }
Write-Host "all SSTV host test suites passed"
exit 0
