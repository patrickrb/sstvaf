# Release pipeline

Three platforms (Android, desktop, iOS) build and release **independently** —
each has its own workflow, its own path filter, its own tag namespace, and its
own required status-check gate. A change to one platform never rebuilds the
others (except a change to the shared native C core under
`sstvaf/app/src/main/cpp/**`, which all three depend on).

## Branch lifecycle

```
feature/* ──PR──▶ dev ──PR──▶ staging ──PR──▶ main
                  │            │               │
                  │            │               └─ PRODUCTION build → Releases + Play production
                  │            └───────────────── DEV (prerelease) build → Releases + Play internal
                  └────────────────────────────── CI only, NO release
```

- **feature → dev** — every work item. CI runs (tests, build check) but nothing
  is published. Dev builds **never** reach the Releases page.
- **dev → staging** — bundle up many dev PRs and promote them together. Merging
  this PR (a push to `staging`) cuts a **dev / prerelease** build of every
  platform: prerelease GitHub Releases + Play **internal** track for Android.
- **staging → main** — promote the validated staging build. Merging this PR (a
  push to `main`) cuts the **production** build: full GitHub Releases + Play
  **production** track for Android.

Source gates (enforced as required status checks):

- PRs to `staging` must come from `dev` (`Staging branch source gate`).
- PRs to `main` must come from `staging` (`Main branch source gate`).

## Tag / release namespaces

Per-platform prefixes keep the Releases page unambiguous:

| Platform | Production tag        | Dev (prerelease) tag      | Trigger of dev build |
|----------|-----------------------|---------------------------|----------------------|
| Android  | `android-v<x.y.z>`    | `android-dev.<run#>`      | push to `staging`    |
| Desktop  | `desktop-v<x.y.z>`    | `desktop-dev.<run#>`      | push to `staging`    |
| iOS      | _(no release yet)_    | _(no release yet)_        | —                    |

- Desktop production tags are auto-bumped on a push to `main` from the latest
  matching tag.
- Android versions are chosen by Claude (see below). The first Android tag is the
  two-part `android-v0.1` form; it normalises to `0.1.0` so numbering stays
  continuous. Android seeds from a legacy bare `v*` tag if no `android-v*`
  exists yet.
- iOS is CI-only: it builds the FT8AFKit test suite and an unsigned simulator
  build to prove it compiles. A distributable `.ipa` needs an Apple Developer
  cert + provisioning profile / TestFlight, which are not wired up yet.

## Android versioning + release notes (AI-assisted)

Ported from Sorrel's `play-beta.yml`. On a push to `staging` the Android
`build` job:

1. Takes the latest `android-v*` tag as the baseline and collects everything
   from there to `HEAD`: merged PRs (number, branch, title), the commit log,
   and size signals (commit count, `sstvaf/` diff stat, changed files by area).
2. If nothing under `sstvaf/` changed (a desktop-only promotion) it
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

On the later push to `main` the job looks for the newest `android-dev.*` tag
that is an ancestor of `HEAD` and not already shipped, reads the version and
notes back out of those markers, and releases `android-v<x.y.z>` with the same
notes on the production track. If no such candidate exists (or it pre-dates the
markers), a deterministic patch bump and PR-title notes are used, with no paid
API call. An `android-v<x.y.z>` that already exists is stepped
by a patch until free.

Retries never call the paid API again: they reuse the same run's
`android-dev.<run#>` version/notes markers if that release already exists;
otherwise they use the deterministic patch/PR-title fallback. If the first
attempt failed before saving release markers, its AI result is not recoverable
and the fallback may choose a different bump. Tag/other lanes make no paid call.
The existing internal draft/manual-rollout gate and production approval hold
remain unchanged; this workflow policy does not authorize production delivery.

`versionCode` is unchanged: still `GITHUB_RUN_NUMBER + 1000`.

The helper `.github/scripts/android-next-version.sh` does the bump arithmetic
and has a self-test (`--self-test`).

**Secret:** `ANTHROPIC_API_KEY` (repository secret). Without it, or if the API
call fails, the run annotates a warning, takes a **patch** bump, and uses the
PR titles as the notes — a release is never blocked on the AI step. The first
internal request uses only the primary model, without server-side paid failover;
refusal also uses the local deterministic fallback, not another model call.

## One-time setup on GitHub (manual)

These cannot be done from a workflow file — do them in the repo settings:

1. **Create the `staging` branch** from `dev`:
   `git checkout dev && git pull && git checkout -b staging && git push -u origin staging`.
2. **Branch protection for `staging`** → Require status checks →
   add `enforce-source-is-dev` plus the always-run platform gates
   `android-gate`, `desktop-gui-gate` and `SSTV codec host tests`.
3. **Branch protection for `main`** → Require status checks →
   add `enforce-source-is-staging` plus the same three platform gates.
   `dev` requires just `SSTV codec host tests`. All three branches require one
   approving review and block force-pushes/deletions (admins exempt), mirroring
   FT8AF. Applied 2026-08-26 via the branch-protection API; re-apply with
   `gh api -X PUT repos/<owner>/sstvaf/branches/<branch>/protection` if the
   check names ever change.
4. **Play Console** → confirm the `PLAY_SERVICE_ACCOUNT_JSON` service account has
   release permission on the **production** track (it previously only needed
   internal). `main` merges now publish there.

## Internal-only committed-state read-back

After a successful staging internal upload, Android CI independently reads the
committed SSTVAF internal releases using the existing hosted
`PLAY_SERVICE_ACCOUNT_JSON`. It verifies the exact built version code
(`GITHUB_RUN_NUMBER + 1000`) has lifecycle `DRAFT` and uploads the sanitized
`play-internal-readback` JSON artifact, even if response validation fails.
Failure makes the staging job fail; it does not retry an upload or roll anything
out. Production and desktop lanes are unchanged.

The **Play internal read-back** workflow also offers manual inspection of an
existing internal version code and expected lifecycle, with no build/upload.
Its PR test job uses fixtures only and has no credentials. GitHub may require
the workflow to be present on the default branch before manual dispatch is
available; do not promote to production just to register it. The automatic
staging read-back does not depend on manual workflow registration.

The script is pinned to `radio.ks3ckc.sstvaf` and track `internal`, using only
the authenticated GET endpoint
`applications/radio.ks3ckc.sstvaf/tracks/internal/releases`. It never creates
edits, commits edits, uploads, promotes, or queries production. The API does not
echo a package field: correct-package evidence is the fixed authenticated
request URL in the report, not a fabricated response field. The report records
the expected/observed version code and lifecycle, release name, read timestamp,
and workflow source commit (not independent proof of the app binary's source).
Release name is not Android `versionName`, which this endpoint does not return.

API reference:
https://developers.google.com/android-publisher/api-ref/rest/v3/applications.tracks.releases/list

A draft is **not** tester rollout. `PUBLISHED` includes partial or halted
releases and does not prove a specific tester/device can install. Reports
therefore always mark tester/device availability unverified. The endpoint
returns at most 20 non-obsolete releases; an absent version fails verification,
not proof that the version never existed. Authentication/API/response failures
produce a failed report without tokens, credentials or raw error bodies.

Run the fixture tests with the same `google-auth requests` dependencies used by
the existing watcher:

```sh
python -m unittest discover -s .github/scripts -p 'test_play_internal_readback.py' -v
```

All five radio apps remain INTERNAL TESTING ONLY until Patrick tests all five
and subsequently explicitly approves production. Required independent reviews
and branch protections still apply; a read-back artifact is not approval or
physical-device/USB/CAT/PTT/RF validation.
