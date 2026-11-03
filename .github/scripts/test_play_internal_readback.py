import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

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
        with self.assertRaises(ValueError):
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
                else:
                    self.assertNotIn("observed", report)

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


if __name__ == "__main__":
    unittest.main()
