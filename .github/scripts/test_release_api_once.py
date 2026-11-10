"""Exercise release workflow decisions offline; never call a paid API or Play."""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

import yaml


ROOT = Path(__file__).resolve().parents[2]
WORKFLOW = yaml.safe_load((ROOT / ".github/workflows/android.yml").read_text())
STEPS = WORKFLOW["jobs"]["build"]["steps"]


def step(name):
    return next(item for item in STEPS if item.get("id", item["name"]) == name)


def enabled(expression, **values):
    # These guards use only string contexts, comparisons, parentheses and AND/OR.
    contexts = {
        "steps.lane.outputs.lane": values.get("lane", "dev"),
        "github.run_attempt": values.get("attempt", "1"),
        "steps.changes.outputs.android_changed": values.get("changed", "true"),
        "steps.candidate.outputs.found": values.get("found", ""),
        "steps.ai.outputs.bump": values.get("bump", ""),
    }
    expression = re.sub(r"(?:steps|github)\.[a-z_.]+", lambda m: repr(contexts[m[0]]), expression)
    return eval(expression.replace("&&", " and ").replace("||", " or "), {"__builtins__": {}}, {})


class ReleaseApiOnceTest(unittest.TestCase):
    def run_step(self, selected, **env):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            (path / ".github").symlink_to(ROOT / ".github", target_is_directory=True)
            for command, script in {
                "gh": '#!/bin/bash\nprintf "%s\\n" "$*" > gh-call\nprintf "%s\\n" "$BODY"\n',
                "git": '#!/bin/bash\nif [[ "$1" == rev-parse ]]; then exit 1; fi\nif [[ "$1" == tag ]]; then printf "%s" "$TAGS"; fi\n',
                "curl": '#!/bin/bash\nprintf "%s\\n" "$*" >> curl-call\nprintf "%s" "$RESPONSE_JSON" > response.json\nprintf "%s" "$RESPONSE_HTTP"\n',
            }.items():
                executable = path / command
                executable.write_text(script)
                executable.chmod(0o755)
            environment = dict(os.environ, PATH=f"{path}:{os.environ['PATH']}",
                               GITHUB_OUTPUT=str(path / "outputs"), PREV_TAG="", TAGS="",
                               BODY="", GITHUB_RUN_NUMBER="300")
            environment.update(env)
            subprocess.run(["bash", "-c", selected["run"]], cwd=path, env=environment,
                           check=True, capture_output=True, text=True)
            return {file.name: file.read_text() for file in path.iterdir() if file.is_file()}

    def test_paid_call_only_first_internal_attempt(self):
        guard = step("ai")["if"]
        self.assertTrue(enabled(guard))
        for values in ({"lane": "production"}, {"lane": "tag"}, {"lane": "none"},
                       {"attempt": "2"}, {"attempt": "10"}, {"changed": "false"},
                       {"found": "true"}):
            with self.subTest(values=values):
                self.assertFalse(enabled(guard, **values))
        # There is only one paid request owner; no hidden second caller or retry.
        callers = [item for item in STEPS if "api.anthropic.com" in item.get("run", "")]
        self.assertEqual([item["id"] for item in callers], ["ai"])
        self.assertNotIn("--retry", callers[0]["run"])

    def test_primary_request_without_paid_failover_and_local_failure_notes(self):
        for http, stop in (("200", "end_turn"), ("200", "refusal"), ("503", "error")):
            with self.subTest(http=http, stop=stop):
                response = {"stop_reason": stop, "content": [{"type": "text", "text": json.dumps({
                    "bump": "minor", "notes": "Improved microphone capture."})}]}
                result = self.run_step(step("ai"), ANTHROPIC_API_KEY="offline-test-only",
                                       PREV_VERSION="1.2.3", COMMIT_LOG="Fix capture",
                                       PR_LIST="- #125 fix/mic: Restart microphone", CHANGE_SIZE="1 commit",
                                       RESPONSE_HTTP=http, RESPONSE_JSON=json.dumps(response))
                request = json.loads(result["request.json"])
                self.assertEqual(request["model"], "claude-opus-5")
                self.assertEqual(request["output_config"]["format"]["type"], "json_schema")
                self.assertEqual(request["output_config"]["format"]["schema"]["required"], ["bump", "notes"])
                self.assertNotIn("fallbacks", request)
                self.assertNotIn("anthropic-beta", result["curl-call"])
                self.assertEqual(len(result["curl-call"].splitlines()), 1)
                if http == "200" and stop == "end_turn":
                    self.assertIn("bump=minor\n", result["outputs"])
                    self.assertEqual(result["notes.txt"].strip(), "Improved microphone capture.")
                else:
                    self.assertIn("bump=patch\n", result["outputs"])
                    self.assertEqual(result["notes.txt"], "Restart microphone\n")

    def test_saved_notes_reused_for_retry_and_promotion(self):
        body = "<!-- sstvaf-version: 2.7.4 -->\n<!-- sstvaf-notes-start -->\nFix first-launch RX.\n<!-- sstvaf-notes-end -->"
        for lane, attempt, tags, expected in (
            ("dev", "2", "android-dev.999\n", "android-dev.300"),
            ("production", "1", "android-dev.299\n", "android-dev.299"),
        ):
            with self.subTest(lane=lane):
                self.assertTrue(enabled(step("candidate")["if"], lane=lane, attempt=attempt))
                result = self.run_step(step("candidate"), LANE=lane, BODY=body, TAGS=tags)
                self.assertIn(f"tag={expected}\n", result["outputs"])
                self.assertIn("version=2.7.4\n", result["outputs"])
                self.assertEqual(result["notes.txt"], "Fix first-launch RX.\n")
                self.assertIn(f"release view {expected} ", result["gh-call"])

    def test_missing_saved_release_uses_deterministic_notes(self):
        fallback = step("Use deterministic notes without another API call")
        for lane, tags in (("dev", ""), ("production", ""),
                           ("production", "android-dev.299\n")):
            with self.subTest(lane=lane, tags=tags):
                result = self.run_step(step("candidate"), LANE=lane, TAGS=tags)
                self.assertIn("found=false\n", result["outputs"])
                self.assertTrue(enabled(fallback["if"], lane=lane, attempt="2", found="false"))
        for values in ({"found": "true"}, {"bump": "minor"}, {"lane": "tag"},
                       {"lane": "none"}, {"changed": "false"}):
            self.assertFalse(enabled(fallback["if"], **values))
        result = self.run_step(fallback, PR_LIST="- #125 fix/mic: Restart microphone\n- #132 feat/favorites: Save frequencies")
        self.assertEqual(result["notes.txt"], "Restart microphone\nSave frequencies\n")
        self.assertEqual(self.run_step(fallback, PR_LIST="")["notes.txt"], "Bug fixes and improvements.\n")

    def test_fallback_version_and_release_lane_remain_deterministic(self):
        selected = step("tag")
        self.assertEqual(selected["env"]["BUMP"], "${{ steps.ai.outputs.bump || 'patch' }}")
        for lane, version, track, status in (
            ("dev", "1.2.4-dev.300", "internal", "draft"),
            ("production", "1.2.4", "production", "completed"),
        ):
            with self.subTest(lane=lane):
                result = self.run_step(selected, LANE=lane, ANDROID_CHANGED="true",
                                       PREV_VERSION="1.2.3", CAND_FOUND="false",
                                       CAND_TAG="", CAND_VERSION="", BUMP="patch",
                                       BUMP_SOURCE="deterministic fallback")
                self.assertIn(f"version_name={version}\n", result["outputs"])
                self.assertIn(f"play_track={track}\n", result["outputs"])
                self.assertIn(f"play_status={status}\n", result["outputs"])


if __name__ == "__main__":
    unittest.main()
