# SSTVAF software factory (jigglebilly)

The factory loop (`.claude/skills/sstvaf-factory/SKILL.md`) takes `factory:ready`
spec issues, builds and tests them, merges them into `dev`, releases dev → main
to the Play internal track (`.claude/skills/sstvaf-release/`), and announces
what Patrick promotes to production (`.claude/skills/feature-announce/`).
Ported from Sorrel's factory; it runs beside Sorrel's loop under a different
name (`/sstvaf-factory`), so neither launcher mistakes the other for itself.

| File | What it does |
|---|---|
| `install-autostart.ps1` | registers the **"SSTVAF factory loop"** scheduled task: at logon + 2 min, as you, only while signed in. `-Remove` unregisters it |
| `start-factory.ps1` | what the task runs: no second SSTVAF factory if one is up; waits up to 10 min for the network and a working `gh` login; nudges WSL; fast-forwards the main checkout to `origin/dev` when it is on `dev` and clean; opens `run-loop.cmd` in a terminal tab. `-DryRun` prints instead of launching. Log: `%LOCALAPPDATA%\sstvaf-factory\autostart.log` |
| `run-loop.cmd` | the session: sets `SSTVAF_FACTORY=1` and runs `claude --permission-mode acceptEdits "/loop /sstvaf-factory"` |
| `guard.py` | PreToolUse hook (Bash + PowerShell), on only when `SSTVAF_FACTORY=1`: blocks force-push, `reset --hard`, `git clean`, discarding checkouts, `branch -D`, `gh secret`/`variable`/`auth`, repo/release delete, API DELETE, **production tags** (`android-v*` / `desktop-v*` via git, `gh release create`, `gh workflow run`, the refs API) and `gh pr merge --admin`. `--self-test` checks its cases |
| `gradle.sh` | the AGENTS.md Windows build as one allowlisted command (Android Studio JBR, isolated Gradle home `~/.gradle-sstvaf`), copying the gitignored hamlib prebuilts into a fresh worktree from the main checkout |
| `rerun.sh` | the only way the loop re-runs a workflow (`gh run rerun` is blocked): re-runs failed jobs, refusing runs on an `android-v*` / `desktop-v*` tag and `workflow_dispatch` runs |

## Run it

All from PowerShell on jigglebilly. It is its own loop: Sorrel's factory
(`/loop /software-factory` in `C:\Users\burns\Projects\sorrel`) keeps running
beside it, untouched.

**1. The loop's own checkout.** The SSTVAF loop runs from a dedicated
worktree on `dev`, `C:\Users\burns\Projects\sstvaf-factory-loop`, not from
the primary checkout `C:\Users\burns\Projects\sstvaf` (which is free for
your own branches and uncommitted work). The launcher fast-forwards the loop
worktree to `origin/dev` on every start when it is on `dev` with no tracked
changes (untracked files are fine). Create it once:

```powershell
cd C:\Users\burns\Projects\sstvaf
git fetch origin
git branch -f dev origin/dev            # only if dev isn't checked out anywhere
git worktree add ..\sstvaf-factory-loop dev
```

**2. Install the logon task** (once; re-running replaces it). It starts the
loop two minutes after you sign in.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\factory\install-autostart.ps1
Get-ScheduledTask -TaskName "SSTVAF factory loop" | Select-Object TaskName, State
```

**3. Start it now** without waiting for a sign-in (opens a Windows Terminal
tab titled "SSTVAF factory"; does nothing if one is already running):

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\factory\start-factory.ps1
# check what it would do without launching:
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\factory\start-factory.ps1 -DryRun
# or run the session directly in the current terminal:
scripts\factory\run-loop.cmd
```

**Watch / stop / remove**

```powershell
Get-Content "$env:LOCALAPPDATA\sstvaf-factory\autostart.log" -Tail 20     # launcher log
# is it running?
Get-CimInstance Win32_Process -Filter "Name='claude.exe'" |
  Where-Object { $_.CommandLine -match '/sstvaf-factory' } | Select-Object ProcessId, CommandLine
# stop it: close the "SSTVAF factory" tab (or Ctrl+C in it)
# stop it starting at sign-in:
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\factory\install-autostart.ps1 -Remove
```

**Self-checks** (no network, nothing published):

```powershell
python scripts\factory\guard.py --self-test
python -I .claude\skills\feature-announce\scripts\post_socials.py --self-test
wsl -d Ubuntu -- bash -lc "cd /mnt/c/Users/burns/Projects/sstvaf-factory-loop && python3 -m unittest discover -s .github/scripts -p 'test_*.py'"
```

Give it work by filing an issue with the **Factory spec** form and labelling
it `factory:ready`; with an empty queue each cycle only checks for a
production promotion to announce and for unreleased work on `dev`.

## Permissions

Allowlist + acceptEdits, never `--dangerously-skip-permissions`.
`.claude/settings.json` allows what the loop uses (git, gh, the repo scripts,
Gradle via `gradle.sh`, adb/emulator, node/npm for sstvaf-site and desktop,
edits in the main checkout, `sstvaf-*` worktrees and `sstvaf-site*`) and
denies force-push, `reset --hard`, `git clean`, production tags, `--admin`
merges, `gh secret`/`gh variable`/`gh auth` and API deletes. Deny rules match by
prefix only, so `guard.py` is the real stop; the rules are the second layer.
Anything else stops at a prompt in the tab and waits for you.

`defaultMode` is deliberately not set in the checked-in settings: it would
change your own interactive sessions in this repo too. The launcher passes the
mode on the command line instead.

## Social credentials

`.claude/skills/feature-announce/scripts/post_socials.py` reads SSTVAF's
tokens only from `%LOCALAPPDATA%\sstvaf-factory\social.env` (never committed;
keys in the script's docstring). Without them the announce skill stages the
posts in Chrome instead, which needs Chrome signed in to the SSTVAF Facebook
page and the @SSTVAF X account.

## What it can't do

- Start before you sign in to Windows.
- Fix a dead GitHub login. `gh` must hold a token that doesn't get rotated
  out (a personal access token), with access to **both** `patrickrb/sstvaf`
  and `patrickrb/sstvaf-site`. A fine-grained token can't read check runs or
  branch protection; the loop reads workflow runs and the `pr-gate` status.
- Test RF. Anything that keys a radio, CAT, USB audio or serial is validated
  only by your phone test of the internal build.
- Promote to production. That is yours, in Play Console.
