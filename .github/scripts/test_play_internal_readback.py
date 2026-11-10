import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import yaml

import play_internal_readback as readback


def release(code, state="draft", track="internal"):
    return {
        "track": track,
        "releaseName": "SSTVAF candidate",
        "activeArtifacts": [{"versionCode": code}],
        "releaseLifecycleState": readback.STATES[state],
    }


class Session:
    """GET-only boundary: any attempt to create edits/upload/commit fails tests."""
    def __init__(self, payload=None, error=None):
        self.payload = payload
        self.error = error

    def get(self, url, timeout):
        if url != (
            "https://androidpublisher.googleapis.com/androidpublisher/v3/"
            "applications/radio.ks3ckc.sstvaf/tracks/internal/releases"
        ) or timeout != 30:
            raise AssertionError("Wrong package/track endpoint or unbounded request")
        return self

    def raise_for_status(self):
        if self.error:
            raise self.error

    def json(self):
        return self.payload

    def __enter__(self):
        return self

    def __exit__(self, *args):
        pass


class EditSession(Session):
    """Only edit creation, internal GET and deletion are permitted; never commit."""
    def __init__(self, payload, get_error=None, delete_error=None, post_error=None):
        super().__init__(payload)
        self.get_error = get_error
        self.delete_error = delete_error
        self.post_error = post_error
        self.deleted = False

    def post(self, url, timeout):
        if url != readback.EDITS_URL or timeout != 30:
            raise AssertionError("Only inspection-edit creation is allowed")
        return Session({"id": "inspection-id"}, self.post_error)

    def get(self, url, timeout):
        if url != f"{readback.EDITS_URL}/inspection-id/tracks/internal" or timeout != 30:
            raise AssertionError("Only the internal track may be read")
        return Session(self.payload, self.get_error)

    def delete(self, url, timeout):
        if url != f"{readback.EDITS_URL}/inspection-id" or timeout != 30:
            raise AssertionError("Only the inspection edit may be deleted")
        self.deleted = True
        return Session(error=self.delete_error)


class ReadbackTest(unittest.TestCase):
    def test_selects_expected_version_not_first_release(self):
        evidence = readback.verify_internal(Session({"releases": [
            release(1282, "published"), release(1283),
        ]}), 1283, "draft")
        self.assertEqual(evidence["versionCode"], 1283)
        self.assertEqual(evidence["releaseLifecycleState"], "RELEASE_LIFECYCLE_STATE_DRAFT")

    def test_published_is_distinct_from_draft(self):
        session = Session({"releases": [release(1283, "published")]})
        with self.assertRaises(ValueError):
            readback.verify_internal(session, 1283, "draft")
        self.assertEqual(readback.verify_internal(session, 1283, "published")[
            "releaseLifecycleState"], "RELEASE_LIFECYCLE_STATE_PUBLISHED")

    def test_rejects_missing_duplicate_wrong_track_and_malformed_versions(self):
        for releases in [[], [release(1282)], [release(1283), release(1283)],
                         [release(1283, track="production")], [release("1283")],
                         [release(True)], [release(0)]]:
            with self.subTest(releases=releases), self.assertRaises(ValueError):
                readback.verify_internal(Session({"releases": releases}), 1283, "draft")

    def test_rejects_unspecified_status_and_http_failure(self):
        item = release(1283)
        item["releaseLifecycleState"] = "RELEASE_LIFECYCLE_STATE_UNSPECIFIED"
        with self.assertRaisesRegex(readback.ValidationFailure, "unrecognized"):
            readback.verify_internal(Session({"releases": [item]}), 1283, "draft")
        with self.assertRaises(PermissionError):
            readback.verify_internal(Session(error=PermissionError("denied")), 1283, "draft")

    def test_cli_report_success_and_failure_with_authenticated_transport(self):
        for item, expected_exit in [(release(1283), 0), (release(1283, "published"), 1)]:
            with self.subTest(item=item), tempfile.TemporaryDirectory() as temp:
                path = Path(temp) / "report.json"
                with patch.dict("os.environ", {"PLAY_SERVICE_ACCOUNT_JSON": "{}"}), \
                     patch("google.oauth2.service_account.Credentials.from_service_account_info"), \
                     patch("google.auth.transport.requests.AuthorizedSession",
                           return_value=Session({"releases": [item]})):
                    status = readback.main(["--version-code", "1283", "--expected-status", "draft",
                                            "--report", str(path)])
                report = json.loads(path.read_text())
                self.assertEqual(status, expected_exit)
                self.assertEqual(report["verified"], expected_exit == 0)
                self.assertEqual(report["track"], "internal")
                self.assertEqual(report["testerDeviceAvailability"], "not verified")
                if expected_exit == 0:
                    self.assertEqual(report["observed"]["versionCode"], 1283)
                    self.assertEqual(report["observed"]["releaseLifecycleState"],
                                     "RELEASE_LIFECYCLE_STATE_DRAFT")
                    self.assertEqual(report["stage"], "verified")
                else:
                    self.assertNotIn("observed", report)
                    self.assertIn("RELEASE_LIFECYCLE_STATE_PUBLISHED", report["validationError"])
                    self.assertEqual(report["stage"], "request/validation")

    def test_cli_writes_failure_report_without_secret_exception_text(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "report.json"
            with patch.dict("os.environ", {"PLAY_SERVICE_ACCOUNT_JSON": "secret-not-json"}):
                status = readback.main(["--version-code", "1283", "--expected-status", "draft",
                                        "--report", str(path)])
            self.assertEqual(status, 1)
            report = json.loads(path.read_text())
            self.assertFalse(report["verified"])
            self.assertEqual(report["packageName"], "radio.ks3ckc.sstvaf")
            self.assertEqual(report["testerDeviceAvailability"], "not verified")
            self.assertNotIn("secret-not-json", path.read_text())
            self.assertNotIn("validationError", report)
            self.assertEqual(report["stage"], "credentials")

    def edit_report(self, session, expected="completed"):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "report.json"
            with patch.dict("os.environ", {"PLAY_SERVICE_ACCOUNT_JSON": "{}"}), \
                 patch("google.oauth2.service_account.Credentials.from_service_account_info"), \
                 patch("google.auth.transport.requests.AuthorizedSession", return_value=session):
                status = readback.main(["--version-code", "1300", "--expected-track-status", expected,
                                        "--report", str(path)])
            return status, json.loads(path.read_text())

    def test_edit_status_is_not_lifecycle_and_cleanup_is_required(self):
        for actual in ("completed", "draft", "inProgress", "halted", "unknown"):
            for expected in ("completed", "draft"):
                with self.subTest(actual=actual, expected=expected):
                    session = EditSession({"track": "internal", "releases": [
                        {"name": "other", "versionCodes": ["1299"], "status": expected},
                        {"name": "android-dev.300", "versionCodes": ["1300"],
                         "status": actual, "userFraction": 0.25},
                    ]})
                    status, report = self.edit_report(session, expected)
                    self.assertEqual(status, 0 if actual == expected else 1)
                    self.assertEqual(report["verified"], actual == expected)
                    self.assertEqual(report["inspectionMode"], "edit-track")
                    self.assertEqual(report["observed"]["releaseName"], "android-dev.300")
                    self.assertEqual(report["observed"]["trackStatus"],
                                     actual if actual != "unknown" else "unrecognized")
                    self.assertEqual(report["observed"]["userFraction"], 0.25)
                    self.assertEqual(report["editCleanup"], {"attempted": True, "succeeded": True})
                    self.assertTrue(session.deleted)
                    self.assertEqual(report["testerDeviceAvailability"], "not verified")

    def test_edit_get_and_delete_failures_fail_closed_without_secret_error_text(self):
        release = {"name": "android-dev.300", "versionCodes": ["1300"], "status": "completed"}
        for payload, errors, deleted, cleanup_ok in [
            ({"track": "internal", "releases": [release]}, {"post_error": PermissionError("secret")}, False, False),
            ({"track": "internal", "releases": [release]}, {"get_error": PermissionError("secret")}, True, True),
            ({"track": "internal", "releases": [release]}, {"delete_error": PermissionError("secret")}, True, False),
            ({"track": "production", "releases": [release]}, {}, True, True),
            ({"track": "internal", "releases": []}, {}, True, True),
            ({"track": "internal", "releases": [release, release]}, {}, True, True),
        ]:
            with self.subTest(payload=payload, errors=errors):
                session = EditSession(payload, **errors)
                status, report = self.edit_report(session)
                self.assertEqual(status, 1)
                self.assertFalse(report["verified"])
                self.assertEqual(session.deleted, deleted)
                if deleted:
                    self.assertEqual(report["editCleanup"]["succeeded"], cleanup_ok)
                if errors.get("delete_error"):
                    self.assertEqual(report["editCleanup"]["errorType"], "PermissionError")
                    self.assertEqual(report["observed"]["trackStatus"], "completed")
                self.assertNotIn("secret", json.dumps(report))

    def test_hosted_workflow_selects_only_one_inspection_mode(self):
        workflow = yaml.safe_load((Path(__file__).parents[1] / "workflows/play-internal-readback.yml").read_text())
        inspect = workflow["jobs"]["inspect"]
        self.assertEqual(inspect["if"], "github.event_name == 'workflow_dispatch'")
        script = next(step["run"] for step in inspect["steps"] if "env" in step)
        for mode, flag, expected in [("release-summary", "--expected-status", "published"),
                                     ("edit-track", "--expected-track-status", "completed")]:
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temp:
                stub = Path(temp) / "python"
                stub.write_text('#!/bin/bash\nprintf "%s\\n" "$@"\n')
                stub.chmod(0o755)
                env = dict(os.environ, PATH=f"{temp}:{os.environ['PATH']}", INSPECTION_MODE=mode,
                           VERSION_CODE="1300", EXPECTED_STATUS="published",
                           EXPECTED_TRACK_STATUS="completed", RUNNER_TEMP=temp)
                args = subprocess.check_output(["bash", "-c", script], env=env, text=True).splitlines()
                self.assertEqual(args, [".github/scripts/play_internal_readback.py", "--version-code",
                                        "1300", flag, expected, "--report", f"{temp}/play-internal-readback.json"])


if __name__ == "__main__":
    unittest.main()
