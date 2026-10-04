"""Smoke test for the 7 data-source endpoints via coordinator.sources.

Calls all seven endpoints (6 GET + 1 POST) through the adapter and prints one
status line per endpoint: label, HTTP status, and a short body summary
(top-level keys + element count where the body is a list).

MODE
----
Reads SOURCE_MODE (mock|sandbox); default mock. Mock mode runs end-to-end with
ZERO credentials. Agents run this ONLY in mock mode.

    SOURCE_MODE=mock   -> talks to the deployed API Gateway contract mocks, or
                          to a local stub when --self-test is used. No creds.
    SOURCE_MODE=sandbox-> talks to the real provider hosts and needs the sandbox
                          credentials in the environment (see .env.example). If
                          required creds are absent the call is SKIPPED with a
                          clear message rather than crashing.

SELF-TEST
---------
`python scripts/source-smoke.py --self-test` starts a tiny in-process HTTP stub
that returns fixed JSON, points every *_BASE_URL env var at it, forces
SOURCE_MODE=mock, runs all 7 endpoints, prints 7 lines and exits 0. No network
leaves the machine and no credentials are needed.

USAGE
-----
    python scripts/source-smoke.py                 # mock, uses *_BASE_URL from env
    python scripts/source-smoke.py --self-test     # mock, local in-process stub
    SOURCE_MODE=sandbox python scripts/source-smoke.py   # live sandbox (needs creds)
"""

import argparse
import json
import os
import sys
import threading
import urllib.error
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from coordinator.sources import (  # noqa: E402
    MODE_SANDBOX,
    PROVIDER_ENV,
    alicloud_tel_three,
    get_order_metrics,
    gutu_panorama_check,
    list_financial_event_groups,
    list_transactions,
    qichacha_check,
    source_mode,
)

# Qichacha paths exercised by the smoke run (the 2 GETs that make the 6-GET set).
QICHACHA_ENTERPRISE_PATH = "/EnterpriseInfo/Verify"
QICHACHA_SHIXIN_PATH = "/ShixinCheck/GetList"

# Credentials each provider needs in sandbox mode. Mock mode needs none.
SANDBOX_REQUIRED_CREDS = {
    "amazon": ("LWA_CLIENT_ID", "LWA_CLIENT_SECRET", "LWA_REFRESH_TOKEN"),
    "alicloud": ("ALICLOUD_APPCODE",),
    "qichacha": ("QICHACHA_APP_KEY", "QICHACHA_SECRET_KEY"),
    "gutu": ("GUTU_TOKEN",),
}


def _summarize(status, body):
    """Short, non-sensitive body summary: top-level keys and list count."""
    if isinstance(body, dict):
        keys = ",".join(sorted(body)[:6])
        return "keys=[" + keys + "]"
    if isinstance(body, list):
        return "list len=" + str(len(body))
    return "type=" + type(body).__name__


def _missing_creds(provider, env):
    return [name for name in SANDBOX_REQUIRED_CREDS[provider] if not env.get(name)]


# The 7 endpoints: (label, provider, callable). Each callable takes the env dict.
def _endpoints():
    return [
        ("amazon getOrderMetrics          [GET]", "amazon",
         lambda env: get_order_metrics(env)),
        ("amazon listFinancialEventGroups [GET]", "amazon",
         lambda env: list_financial_event_groups(env)),
        ("amazon listTransactions         [GET]", "amazon",
         lambda env: list_transactions(env)),
        ("alicloud telThree               [GET]", "alicloud",
         lambda env: alicloud_tel_three("13800138000", "ZhangWei", "110101199003078573", env=env)),
        ("qichacha EnterpriseInfo/Verify  [GET]", "qichacha",
         lambda env: qichacha_check(QICHACHA_ENTERPRISE_PATH, env=env)),
        ("qichacha ShixinCheck/GetList    [GET]", "qichacha",
         lambda env: qichacha_check(QICHACHA_SHIXIN_PATH, env=env)),
        ("gutu panorama-checks            [POST]", "gutu",
         lambda env: gutu_panorama_check({"name": "ZhangWei", "idCardNo": "110101199003078573"},
                                         path="/api/v1/judicial/panorama-checks", env=env)),
    ]


def run(env):
    """Run all 7 endpoints against the current env. Returns an exit code."""
    mode = source_mode(env)
    print("SOURCE_MODE=" + mode)
    failures = 0
    for label, provider, call in _endpoints():
        if mode == MODE_SANDBOX:
            missing = _missing_creds(provider, env)
            if missing:
                print("SKIP  " + label + "  (missing creds: " + ",".join(missing) + ")")
                continue
        try:
            status, body = call(env)
            print("OK    " + label + "  status=" + str(status) + "  " + _summarize(status, body))
        except urllib.error.HTTPError as exc:
            failures += 1
            print("FAIL  " + label + "  status=" + str(exc.code) + "  http-error")
        except Exception as exc:  # noqa: BLE001 - surface any transport error per line
            failures += 1
            print("FAIL  " + label + "  error=" + type(exc).__name__ + ": " + str(exc))
    return 1 if failures else 0


# ---------------------------------------------------------------------------
# Local in-process stub for --self-test (mock mode, zero creds, no egress)
# ---------------------------------------------------------------------------

_STUB_GET_BODY = {"payload": {"ok": True}, "Status": "200", "code": "0"}
_STUB_POST_BODY = {"code": 200, "message": "success", "data": {"riskLevel": "MEDIUM"}}


class _StubHandler(BaseHTTPRequestHandler):
    def _respond(self, body):
        payload = json.dumps(body).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):  # noqa: N802 - BaseHTTPRequestHandler API
        self._respond(_STUB_GET_BODY)

    def do_POST(self):  # noqa: N802 - BaseHTTPRequestHandler API
        length = int(self.headers.get("Content-Length", 0))
        if length:
            self.rfile.read(length)
        self._respond(_STUB_POST_BODY)

    def log_message(self, *args):  # silence per-request logging
        return


def self_test():
    """Start a local stub, point all providers at it, run the 7 endpoints."""
    server = HTTPServer(("127.0.0.1", 0), _StubHandler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    base = "http://127.0.0.1:" + str(server.server_address[1])
    # Isolated env: force mock, point every provider base URL at the local stub,
    # and carry NO credentials.
    env = {"SOURCE_MODE": "mock"}
    for provider_env in PROVIDER_ENV.values():
        env[provider_env] = base
    try:
        code = run(env)
    finally:
        server.shutdown()
    return code


def main(argv=None):
    parser = argparse.ArgumentParser(description="Smoke-test the 7 data-source endpoints.")
    parser.add_argument("--self-test", action="store_true",
                        help="Run against a local in-process stub (mock mode, no creds).")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    return run(dict(os.environ))


if __name__ == "__main__":
    sys.exit(main())
