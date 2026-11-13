# Release pipeline

Three platforms (Android, desktop, iOS) build and release **independently** —
each has its own workflow, its own path filter, its own tag namespace, and its
own required status-check gate. A change to one platform never rebuilds the
others (except a change to the shared native C core under
`sstvaf/app/src/main/cpp/**`, which all three depend on).

## Branch lifecycle

The `staging` branch is retired (2026-11-13), as in FT8AF: it added a second
promotion that tested nothing the `main` push doesn't.

```
feature/* ──PR──▶ dev ──PR──▶ main ──(Patrick, Play Console / tag)──▶ production
                  │            │
                  │            └─ INTERNAL build → prerelease + Play internal (rolled out)
                  └────────────── CI only, NO release
```

- **feature → dev** — every work item. CI runs (tests, build check) but nothing
  is published. Dev builds **never** reach the Releases page. The software
  factory (`.claude/skills/sstvaf-factory/SKILL.md`) merges these itself once
  `pr-gate` is green.
- **dev → main** — the release. Merging it (a push to `main`) cuts an
  `android-dev.<run#>` prerelease and publishes it to the Play **internal**
  track with status `completed` (rolled out to internal testers), plus a
  `desktop-dev.<run#>` desktop GUI prerelease. The factory opens this PR at
  most once a day and merges it only when every check on it passed
  (`.claude/skills/sstvaf-release/SKILL.md`).
- **Production is Patrick's alone:** he tests the internal build on his phone
  and promotes it in Play Console, or pushes an `android-v*` / `desktop-v*`
  tag. No branch push creates a production tag or publishes to production.

Gates:

- PRs to `main` must come from `dev` (`Main branch source gate` →
  `enforce-source-is-dev`).
- `pr-gate` (`.github/workflows/pr-gate.yml`) is green on a PR head only when
  every workflow that ran on that commit finished without failing. The factory
  waits for it (and re-evaluates it) before merging.

## Tag / release namespaces

Per-platform prefixes keep the Releases page unambiguous:

| Platform | Production tag (pushed by Patrick) | Internal (prerelease) tag | Trigger of internal build |
|----------|-----------------------|---------------------------|----------------------|
| Android  | `android-v<x.y.z>`    | `android-dev.<run#>`      | push to `main`       |
| Desktop  | `desktop-v<x.y.z>`    | `desktop-dev.<run#>`      | push to `main`       |
| iOS      | _(no release yet)_    | _(no release yet)_        | —                    |

- Production tags are deliberate; branch pushes never auto-bump or create them.
- Android versions are chosen by Claude (see below). The first Android tag is the
  two-part `android-v0.1` form; it normalises to `0.1.0` so numbering stays
  continuous. Android seeds from a legacy bare `v*` tag if no `android-v*`
  exists yet. Because production is promoted in Play Console rather than by
  tag, the latest `android-v*` tag stays the semver baseline until one is cut.
- iOS is CI-only: it builds the FT8AFKit test suite and an unsigned simulator
  build to prove it compiles. A distributable `.ipa` needs an Apple Developer
  cert + provisioning profile / TestFlight, which are not wired up yet.

## Android versioning + release notes (AI-assisted)

Ported from Sorrel's `play-beta.yml`. On a push to `main` the Android
`build` job:

1. Takes the latest `android-v*` tag as the baseline and collects everything
   from there to `HEAD`: merged PRs (number, branch, title), the commit log,
   and size signals (commit count, `sstvaf/` diff stat, changed files by area).
2. If nothing under `sstvaf/` changed (a desktop-only release) it
   builds but **cuts no release** — no empty versions.
3. On the first run attempt only, asks Claude (`claude-opus-5`, structured output) for the semver
   bump — `major` / `minor` / `patch`, judged from both content and size, with
   changes outside `sstvaf/` not counting — and for ≤400-character Play release
   notes aimed at ham operators.
4. Builds `versionName = <x.y.z>-dev.<run#>`, tags `android-dev.<run#>`, and
   publishes the notes to the GitHub prerelease body **and** the Play internal
   track's "Release notes" (`whatsNewDirectory`). The prerelease body also
   carries hidden markers (`<!-- sstvaf-version: x.y.z -->` and
   `<!-- sstvaf-notes-start/end -->`).

Retries never call the paid API again: they reuse the same run's
`android-dev.<run#>` version/notes markers if that release already exists;
otherwise they use the deterministic patch/PR-title fallback. If the first
attempt failed before saving release markers, its AI result is not recoverable
and the fallback may choose a different bump. Tag/other lanes make no paid call.

`versionCode` is unchanged: still `GITHUB_RUN_NUMBER + 1000`.

The helper `.github/scripts/android-next-version.sh` does the bump arithmetic
and has a self-test (`--self-test`).

**Secret:** `ANTHROPIC_API_KEY` (repository secret). Without it, or if the API
call fails, the run annotates a warning, takes a **patch** bump, and uses the
PR titles as the notes — a release is never blocked on the AI step. The first
internal request uses only the primary model, without server-side paid failover;
refusal also uses the local deterministic fallback, not another model call.

## Branch protection (applied via the API)

- `dev` requires `SSTV codec host tests`; `main` requires
  `enforce-source-is-dev`, `android-gate`, `desktop-gui-gate` and
  `SSTV codec host tests`. Neither requires an approving review: the factory
  pushes as Patrick's account, which can't approve its own PRs, and its trust
  comes from `pr-gate`, the one Copilot review and the scenario tests instead.
  Both block force-pushes and deletions.
- Re-apply with `gh api -X PUT repos/patrickrb/sstvaf/branches/<branch>/protection`
  if the check names ever change.
- **The `staging` branch** can be deleted once no open PR targets it, with its
  protection rule.
- **Play Console:** the `PLAY_SERVICE_ACCOUNT_JSON` service account only needs
  release permission on the **internal** track for CI; the `android-v*` tag
  lane is the only one that uses production.

## Internal committed-state read-back

After a successful internal upload from a `main` push, Android CI
independently reads the committed SSTVAF internal track using the existing
hosted `PLAY_SERVICE_ACCOUNT_JSON`, in `edit-track` mode (it opens an
inspection edit, reads the track and deletes the edit; it never commits). It
verifies the exact built version code (`GITHUB_RUN_NUMBER + 1000`) is on the
internal track with status `completed` and uploads the sanitized
`play-internal-readback` JSON artifact, even if validation fails. Failure makes
the job fail; it does not retry an upload or roll anything out. Production
and desktop lanes are unchanged.

The **Play internal read-back** workflow also offers manual inspection of an
existing internal version code and expected lifecycle, with no build/upload.
Its PR test job uses fixtures only and has no credentials. GitHub may require
the workflow to be present on the default branch before manual dispatch is
available.

In `release-summary` mode the script uses only the authenticated GET endpoint
`applications/radio.ks3ckc.sstvaf/tracks/internal/releases`; it never creates
edits, commits edits, uploads, promotes, or queries production. The API does not
echo a package field: correct-package evidence is the fixed authenticated
request URL in the report, not a fabricated response field. The report records
the expected/observed version code and lifecycle, release name, read timestamp,
and workflow source commit (not independent proof of the app binary's source).
Release name is not Android `versionName`, which this endpoint does not return.

API reference:
https://developers.google.com/android-publisher/api-ref/rest/v3/applications.tracks.releases/list

`PUBLISHED` includes partial or halted releases and does not prove a specific
tester/device can install. Reports therefore always mark tester/device
availability unverified. The endpoint returns at most 20 non-obsolete releases;
an absent version fails verification, not proof that the version never existed.
Authentication/API/response failures produce a failed report without tokens,
credentials or raw error bodies.

Run the fixture tests with the same `google-auth requests` dependencies used by
the existing watcher:

```sh
python -m unittest discover -s .github/scripts -p 'test_play_internal_readback.py' -v
```

Production stays Patrick's decision after he tests the internal build; a
read-back artifact is not approval or physical-device/USB/CAT/PTT/RF
validation.
