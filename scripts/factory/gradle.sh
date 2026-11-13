#!/usr/bin/env bash
# Runs the Android Gradle build the way AGENTS.md -> Build & Deploy says, as one
# allowlisted command, so the unattended factory loop doesn't stall on a
# permission prompt for a `cmd.exe //c` wrapper or an env-var prefix.
#
#   bash scripts/factory/gradle.sh testDebugUnitTest assembleDebug
#
# Run it from a checkout or worktree root; it builds that tree's sstvaf/ project.
# The isolated Gradle home keeps the factory's daemons apart from interactive
# builds (a zombie daemon in the default home can hold locks until reboot).
#
# A fresh worktree lacks the gitignored hamlib static libs
# (sstvaf/app/src/main/cpp/hamlib/prebuilt/, ~55 MB, built once under WSL) and
# fails configureCMake without them; they are copied in when missing, from the
# SSTVAF main checkout (SSTVAF_MAIN_CHECKOUT) or else FT8AF's main checkout,
# which pins the same Hamlib 4.7.2 and holds byte-identical archives. Never
# rebuild them here.
set -euo pipefail
top="$(git rev-parse --show-toplevel)"
prebuilt="sstvaf/app/src/main/cpp/hamlib/prebuilt"
main="${SSTVAF_MAIN_CHECKOUT:-/c/Users/burns/Projects/sstvaf}"
ft8af="/c/Users/burns/Projects/NEXT-FT8CN/ft8af/app/src/main/cpp/hamlib/prebuilt"
if [ ! -f "$top/$prebuilt/arm64-v8a/libhamlib.a" ]; then
  for from in "$main/$prebuilt" "$ft8af"; do
    if [ -f "$from/arm64-v8a/libhamlib.a" ]; then
      mkdir -p "$top/$prebuilt"
      cp -r "$from/." "$top/$prebuilt/"
      echo "gradle.sh: copied hamlib prebuilts from $from"
      break
    fi
  done
fi
cd "$top/sstvaf"
export JAVA_HOME="${SSTVAF_JAVA_HOME:-C:\\Program Files\\Android\\Android Studio\\jbr}"
export GRADLE_USER_HOME="${SSTVAF_GRADLE_USER_HOME:-C:\\Users\\burns\\.gradle-sstvaf}"
exec cmd.exe //c ".\\gradlew.bat $*"
