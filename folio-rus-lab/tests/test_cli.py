from __future__ import annotations

import importlib.machinery
import importlib.util
import io
import json
import os
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from http.client import IncompleteRead, RemoteDisconnected
from pathlib import Path
from unittest.mock import Mock, patch
from urllib.error import HTTPError, URLError


CLI_PATH = Path(__file__).resolve().parents[1] / "bin" / "folio-rus-lab"


def load_cli_module():
    loader = importlib.machinery.SourceFileLoader("folio_rus_lab_cli", str(CLI_PATH))
    spec = importlib.util.spec_from_loader(loader.name, loader)
    if spec is None:
        raise RuntimeError("unable to load CLI module")
    module = importlib.util.module_from_spec(spec)
    loader.exec_module(module)
    return module


cli = load_cli_module()


class FakeResponse:
    def __init__(
        self,
        body: bytes = b"",
        *,
        status: int = 200,
        read_error: BaseException | None = None,
    ) -> None:
        self.status = status
        self.body = body
        self.read_error = read_error

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback) -> bool:
        return False

    def read(self) -> bytes:
        if self.read_error is not None:
            raise self.read_error
        return self.body


class FolioRusLabCliOutcomeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.token = "cli-test-" + ("T" * 64)
        self.sql_marker = "PRIVATE_SQL_PAYLOAD_MUST_NOT_BE_PRINTED"
        self.transport_marker = "PRIVATE_TRANSPORT_DETAILS_MUST_NOT_BE_PRINTED"

    def invoke(
        self,
        argv: list[str],
        *,
        open_error: BaseException | None = None,
        response: FakeResponse | None = None,
    ) -> tuple[int, dict, str, str]:
        opener = Mock()
        if open_error is not None:
            opener.open.side_effect = open_error
        else:
            opener.open.return_value = response or FakeResponse()
        stdout = io.StringIO()
        stderr = io.StringIO()

        with (
            patch.object(cli, "LOCAL_OPENER", opener),
            patch.object(sys, "argv", argv),
            patch.dict(os.environ, {"FOLIO_RUS_API_TOKEN": self.token}),
            redirect_stdout(stdout),
            redirect_stderr(stderr),
        ):
            exit_code = cli.main()

        rendered_stdout = stdout.getvalue()
        return exit_code, json.loads(rendered_stdout), rendered_stdout, stderr.getvalue()

    def assert_no_sensitive_output(self, stdout: str, stderr: str) -> None:
        rendered = stdout + stderr
        self.assertNotIn(self.token, rendered)
        self.assertNotIn(self.sql_marker, rendered)
        self.assertNotIn(self.transport_marker, rendered)

    def execute_argv(self, sql_file: Path, mode: str = "ROLLBACK") -> list[str]:
        argv = [
            "folio-rus-lab",
            "execute",
            "--file",
            str(sql_file),
            "--mode",
            mode,
        ]
        if mode != "ROLLBACK":
            argv.append("--allow-persistent-changes")
        return argv

    def execution_body(
        self,
        state: str,
        mode: str,
        *,
        warnings: list[str] | None = None,
        error: dict | None = None,
    ) -> bytes:
        response = {
            "runId": "10000000-0000-0000-0000-000000000001",
            "state": state,
            "database": "Paint_Rus",
            "mode": mode,
            "startedAt": "2026-08-12T10:00:00Z",
            "durationMs": 1,
            "sqlSha256": "0" * 64,
            "transactionBefore": 0,
            "transactionAfter": 0,
            "rowCount": 0,
            "estimatedOutputBytes": 0,
            "results": [],
            "warnings": warnings or [],
            "error": error,
        }
        return json.dumps(response).encode("utf-8")

    def test_execute_transport_interrupts_return_distinct_unknown_outcomes(self) -> None:
        failures = (
            lambda: URLError(self.transport_marker + self.token + self.sql_marker),
            lambda: TimeoutError(self.transport_marker + self.token + self.sql_marker),
            lambda: KeyboardInterrupt(self.transport_marker + self.token + self.sql_marker),
        )
        expected_codes = {
            "ROLLBACK": "ROLLBACK_EXECUTION_OUTCOME_UNKNOWN",
            "COMMIT": "PERSISTENT_OUTCOME_UNKNOWN",
        }
        observed_codes: dict[str, set[str]] = {mode: set() for mode in expected_codes}

        with tempfile.TemporaryDirectory() as directory:
            sql_file = Path(directory) / "experiment.sql"
            sql_file.write_text(
                f"SELECT '{self.sql_marker}' AS private_value\n",
                encoding="utf-8",
            )

            for mode, expected_code in expected_codes.items():
                for make_failure in failures:
                    with self.subTest(mode=mode, failure=type(make_failure()).__name__):
                        argv = self.execute_argv(sql_file, mode)
                        exit_code, response, stdout, stderr = self.invoke(
                            argv,
                            open_error=make_failure(),
                        )

                        self.assertEqual(3, exit_code)
                        self.assertEqual(expected_code, response["error"]["code"])
                        self.assertIn("DO_NOT_RETRY", response["error"]["details"])
                        self.assertIn(
                            "RUN_FRESH_READ_ONLY_POSTCONDITION_CHECK",
                            response["error"]["details"],
                        )
                        self.assert_no_sensitive_output(stdout, stderr)
                        observed_codes[mode].add(response["error"]["code"])

        self.assertEqual({expected_codes["ROLLBACK"]}, observed_codes["ROLLBACK"])
        self.assertEqual({expected_codes["COMMIT"]}, observed_codes["COMMIT"])
        self.assertNotEqual(
            next(iter(observed_codes["ROLLBACK"])),
            next(iter(observed_codes["COMMIT"])),
        )

    def test_preflight_network_failure_remains_redacted_client_error(self) -> None:
        exit_code, response, stdout, stderr = self.invoke(
            ["folio-rus-lab", "preflight"],
            open_error=URLError(self.transport_marker + self.token),
        )

        self.assertEqual(2, exit_code)
        self.assertEqual("CLIENT_ERROR", response["error"]["code"])
        self.assertEqual("API недоступен или не ответил вовремя", response["error"]["message"])
        self.assert_no_sensitive_output(stdout, stderr)

    def test_execute_low_level_connection_failures_are_unknown_and_redacted(self) -> None:
        failures = (
            lambda: IncompleteRead(
                partial=(self.transport_marker + self.token + self.sql_marker).encode(),
                expected=1024,
            ),
            lambda: RemoteDisconnected(
                self.transport_marker + self.token + self.sql_marker
            ),
            lambda: ConnectionResetError(
                self.transport_marker + self.token + self.sql_marker
            ),
        )

        with tempfile.TemporaryDirectory() as directory:
            sql_file = Path(directory) / "experiment.sql"
            sql_file.write_text(
                f"SELECT '{self.sql_marker}' AS private_value\n",
                encoding="utf-8",
            )

            for mode in ("ROLLBACK", "COMMIT"):
                for make_failure in failures:
                    failure = make_failure()
                    with self.subTest(mode=mode, failure=type(failure).__name__):
                        exit_code, response, stdout, stderr = self.invoke(
                            self.execute_argv(sql_file, mode),
                            response=FakeResponse(read_error=failure),
                        )

                        self.assertEqual(3, exit_code)
                        self.assertIn("OUTCOME_UNKNOWN", response["error"]["code"])
                        self.assert_no_sensitive_output(stdout, stderr)

    def test_execute_invalid_http_200_bodies_are_unknown_and_redacted(self) -> None:
        generically_invalid_bodies = (
            b"",
            (
                b"not-json "
                + self.transport_marker.encode()
                + self.token.encode()
                + self.sql_marker.encode()
            ),
            json.dumps(
                {
                    "state": "NOT_A_REAL_EXECUTION_STATE",
                    "debug": self.transport_marker + self.token + self.sql_marker,
                }
            ).encode("utf-8"),
        )

        with tempfile.TemporaryDirectory() as directory:
            sql_file = Path(directory) / "experiment.sql"
            sql_file.write_text(
                f"SELECT '{self.sql_marker}' AS private_value\n",
                encoding="utf-8",
            )

            for mode in ("ROLLBACK", "COMMIT"):
                expected_success_state = "ROLLED_BACK" if mode == "ROLLBACK" else "COMMITTED"
                invalid_bodies = generically_invalid_bodies + (
                    json.dumps(
                        {
                            "state": expected_success_state,
                            "database": "Paint_Rus",
                            "mode": mode,
                            "warnings": [],
                        }
                    ).encode("utf-8"),
                )
                for body in invalid_bodies:
                    with self.subTest(mode=mode, body_length=len(body)):
                        exit_code, response, stdout, stderr = self.invoke(
                            self.execute_argv(sql_file, mode),
                            response=FakeResponse(body),
                        )

                        self.assertEqual(3, exit_code)
                        self.assertIn("OUTCOME_UNKNOWN", response["error"]["code"])
                        self.assert_no_sensitive_output(stdout, stderr)

    def test_server_unknown_states_and_persistence_warning_return_exit_three(self) -> None:
        cases = (
            (
                "ROLLBACK",
                "TX_BOUNDARY_BROKEN",
                [],
                {"code": "TRANSACTION_BOUNDARY_BROKEN", "message": "boundary changed"},
            ),
            (
                "COMMIT",
                "COMMIT_OUTCOME_UNKNOWN",
                ["DO_NOT_RETRY_VERIFY_POSTCONDITIONS"],
                {"code": "COMMIT_OUTCOME_UNKNOWN", "message": "commit unknown"},
            ),
            (
                "ROLLBACK",
                "ROLLED_BACK",
                ["ROLLBACK_OUTCOME_UNKNOWN_CHANGES_MAY_HAVE_PERSISTED"],
                None,
            ),
        )

        with tempfile.TemporaryDirectory() as directory:
            sql_file = Path(directory) / "experiment.sql"
            sql_file.write_text("SELECT 1\n", encoding="utf-8")

            for mode, state, warnings, error in cases:
                with self.subTest(mode=mode, state=state, warnings=warnings):
                    exit_code, response, stdout, stderr = self.invoke(
                        self.execute_argv(sql_file, mode),
                        response=FakeResponse(
                            self.execution_body(
                                state,
                                mode,
                                warnings=warnings,
                                error=error,
                            )
                        ),
                    )

                    self.assertEqual(3, exit_code)
                    self.assertEqual(state, response["state"])
                    self.assert_no_sensitive_output(stdout, stderr)

    def test_safe_policy_rejection_remains_exit_one(self) -> None:
        policy_error = {
            "error": {
                "code": "SQL_POLICY_REJECTED",
                "message": "The SQL batch crosses the Paint_Rus laboratory boundary",
                "details": ["USE is not permitted"],
            }
        }
        http_error = HTTPError(
            "http://127.0.0.1:18081/api/v1/sql/execute",
            400,
            "Bad Request",
            None,
            io.BytesIO(json.dumps(policy_error).encode("utf-8")),
        )

        with tempfile.TemporaryDirectory() as directory:
            sql_file = Path(directory) / "experiment.sql"
            sql_file.write_text("USE master\n", encoding="utf-8")
            exit_code, response, stdout, stderr = self.invoke(
                self.execute_argv(sql_file),
                open_error=http_error,
            )

        self.assertEqual(1, exit_code)
        self.assertEqual("SQL_POLICY_REJECTED", response["error"]["code"])
        self.assert_no_sensitive_output(stdout, stderr)

    def test_confirmed_rollback_and_commit_return_zero(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            sql_file = Path(directory) / "experiment.sql"
            sql_file.write_text("SELECT 1\n", encoding="utf-8")

            for mode, state in (("ROLLBACK", "ROLLED_BACK"), ("COMMIT", "COMMITTED")):
                with self.subTest(mode=mode, state=state):
                    exit_code, response, stdout, stderr = self.invoke(
                        self.execute_argv(sql_file, mode),
                        response=FakeResponse(self.execution_body(state, mode)),
                    )

                    self.assertEqual(0, exit_code)
                    self.assertEqual(state, response["state"])
                    self.assert_no_sensitive_output(stdout, stderr)


if __name__ == "__main__":
    unittest.main()
