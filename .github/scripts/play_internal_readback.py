#!/usr/bin/env python3
"""Inspect SSTVAF internal releases; optionally open/read/delete an uncommitted edit."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path

PACKAGE = "radio.ks3ckc.sstvaf"
URL = (
    "https://androidpublisher.googleapis.com/androidpublisher/v3/"
    f"applications/{PACKAGE}/tracks/internal/releases"
)
EDITS_URL = URL.removesuffix("/tracks/internal/releases") + "/edits"
TRACK_STATUSES = ("draft", "completed", "inProgress", "halted")
STATES = {
    "draft": "RELEASE_LIFECYCLE_STATE_DRAFT",
    "notSentForReview": "RELEASE_LIFECYCLE_STATE_NOT_SENT_FOR_REVIEW",
    "inReview": "RELEASE_LIFECYCLE_STATE_IN_REVIEW",
    "approvedNotPublished": "RELEASE_LIFECYCLE_STATE_APPROVED_NOT_PUBLISHED",
    "notApproved": "RELEASE_LIFECYCLE_STATE_NOT_APPROVED",
    "published": "RELEASE_LIFECYCLE_STATE_PUBLISHED",
}


class ValidationFailure(ValueError):
    """Controlled non-secret diagnostics safe to include in the evidence report."""


def verify_internal(session, version_code, expected_status):
    """Return sanitized evidence; fail closed if version/status/track differ."""
    response = session.get(URL, timeout=30)
    response.raise_for_status()
    payload = response.json()
    releases = payload.get("releases", [])
    matches = []
    for release in releases:
        if release["track"] != "internal":
            raise ValidationFailure("Response contains a non-internal track")
        codes = [artifact["versionCode"] for artifact in release.get("activeArtifacts", [])]
        if any(type(code) is not int or code <= 0 for code in codes):
            raise ValidationFailure("Response contains an invalid version code")
        if version_code in codes:
            matches.append(release)
    if len(matches) != 1:
        raise ValidationFailure("Expected version must occur in exactly one internal release")
    release = matches[0]
    if release["releaseLifecycleState"] != STATES[expected_status]:
        lifecycle = release["releaseLifecycleState"]
        if lifecycle not in STATES.values():
            lifecycle = "unrecognized"
        raise ValidationFailure(f"Observed lifecycle {lifecycle} does not match expected status")
    return {
        "versionCode": version_code,
        "releaseName": release.get("releaseName", ""),
        "releaseLifecycleState": release["releaseLifecycleState"],
    }


def inspect_edit_track(session, version_code, expected_status, report):
    """Open an inspection edit, read INTERNAL only, and delete even on failure."""
    edit = session.post(EDITS_URL, timeout=30)
    edit.raise_for_status()
    edit_url = f"{EDITS_URL}/{edit.json()['id']}"
    report["editCleanup"] = {"attempted": False, "succeeded": False}
    try:
        response = session.get(f"{edit_url}/tracks/internal", timeout=30)
        response.raise_for_status()
        track = response.json()
        if track.get("track") != "internal":
            raise ValidationFailure("Response is not the internal track")
        matches = [release for release in track.get("releases", [])
                   if str(version_code) in release.get("versionCodes", [])]
        if len(matches) != 1:
            raise ValidationFailure("Expected version must occur in exactly one internal release")
        release = matches[0]
        status = release.get("status")
        report["observed"] = {
            "versionCode": version_code,
            "releaseName": release.get("name", ""),
            "trackStatus": status if status in TRACK_STATUSES else "unrecognized",
            "userFraction": release.get("userFraction"),
        }
        if status != expected_status:
            raise ValidationFailure("Observed track status does not match expected status")
    finally:
        report["editCleanup"]["attempted"] = True
        try:
            session.delete(edit_url, timeout=30).raise_for_status()
            report["editCleanup"]["succeeded"] = True
        except Exception as error:
            report["editCleanup"]["errorType"] = type(error).__name__
            raise


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version-code", type=int, required=True)
    expected = parser.add_mutually_exclusive_group(required=True)
    expected.add_argument("--expected-status", choices=STATES)
    expected.add_argument("--expected-track-status", choices=TRACK_STATUSES)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args(argv)
    report = {
        "packageName": PACKAGE,
        "track": "internal",
        "requestUrl": f"{EDITS_URL}/{{editId}}/tracks/internal" if args.expected_track_status else URL,
        "inspectionMode": "edit-track" if args.expected_track_status else "release-summary",
        "checkedAt": datetime.now(timezone.utc).isoformat(),
        "workflowSourceCommit": os.environ.get("GITHUB_SHA", ""),
        "expectedVersionCode": args.version_code,
        "expectedStatus": args.expected_track_status or STATES[args.expected_status],
        "verified": False,
        "stage": "credentials",
        "testerDeviceAvailability": "not verified",
        "note": "Published can include partial or halted releases; draft is not tester rollout.",
    }
    try:
        if args.version_code <= 0:
            raise ValueError("Expected version code must be positive")
        # Same existing hosted service account/dependencies as play_track_watch.py.
        from google.auth.transport.requests import AuthorizedSession
        from google.oauth2 import service_account

        credentials = service_account.Credentials.from_service_account_info(
            json.loads(os.environ["PLAY_SERVICE_ACCOUNT_JSON"]),
            scopes=["https://www.googleapis.com/auth/androidpublisher"],
        )
        report["stage"] = "request/validation"
        with AuthorizedSession(credentials) as session:
            if args.expected_track_status:
                inspect_edit_track(session, args.version_code, args.expected_track_status, report)
            else:
                report["observed"] = verify_internal(session, args.version_code, args.expected_status)
        report["verified"] = True
        report["stage"] = "verified"
    except Exception as error:
        # Never serialize credentials, tokens, HTTP bodies or arbitrary exception text.
        report["errorType"] = type(error).__name__
        if isinstance(error, ValidationFailure):
            report["validationError"] = str(error)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n")
    print(f"SSTVAF internal read-back verified={report['verified']}; device availability not verified")
    return 0 if report["verified"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
