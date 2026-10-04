"""Source-client adapter for the dowsure-oracle PoC.

One function per data-source endpoint plus a SOURCE_MODE switch between
``mock`` (default) and ``sandbox``. Mock mode needs ZERO credentials and talks
to the deployed API Gateway contract mocks; sandbox mode talks to the real
provider hosts and attaches auth read from the environment at call time.

Style matches ``coordinator/coordinator.py``: stdlib only (urllib.request +
json), small typed helpers, no third-party deps. Pure selection helpers
(``resolve_base_url``, ``build_amazon_params``, ``build_auth_headers``,
``qichacha_token``) carry no network I/O so they are unit-testable offline.

Credential safety: NO secret value is hardcoded here. Sandbox auth is read
from os.environ only when SOURCE_MODE == 'sandbox'. In mock mode none of the
credential env vars are read or required.
"""

import hashlib
import json
import os
import urllib.parse
import urllib.request
from typing import Any, Dict, Optional, Tuple

# ---------------------------------------------------------------------------
# Mode + base URL selection
# ---------------------------------------------------------------------------

MODE_MOCK = "mock"
MODE_SANDBOX = "sandbox"

# Real provider hosts used as the sandbox-mode base URL defaults.
SANDBOX_BASE_URLS = {
    "amazon": "https://sandbox.sellingpartnerapi-na.amazon.com",
    "alicloud": "https://qrymobile.market.alicloudapi.com",
    "qichacha": "https://api.qichacha.com",
    "gutu": "https://turningapi.valuemap.cn",
}

# Harmless local default for mock mode when the deployed mock ApiUrl env var is
# not set (e.g. local stubs / unit tests). The real mock URL is injected via the
# per-provider *_BASE_URL env vars from the deployed stack ApiUrl output.
MOCK_BASE_URL_DEFAULT = "http://localhost:4010"

# Env var holding the per-provider base URL override.
PROVIDER_ENV = {
    "amazon": "AMAZON_SP_BASE_URL",
    "alicloud": "ALICLOUD_BASE_URL",
    "qichacha": "QICHACHA_BASE_URL",
    "gutu": "GUTU_BASE_URL",
}

# Amazon LWA token exchange endpoint (public, no secret).
LWA_TOKEN_URL = "https://api.amazon.com/auth/o2/token"


def source_mode(env: Optional[Dict[str, str]] = None) -> str:
    """Return the active mode. Any value other than 'sandbox' means mock."""
    env = os.environ if env is None else env
    return MODE_SANDBOX if env.get("SOURCE_MODE", MODE_MOCK) == MODE_SANDBOX else MODE_MOCK


def resolve_base_url(provider: str, mode: str, env: Optional[Dict[str, str]] = None) -> str:
    """Select the base URL for a provider given the mode.

    Precedence: explicit per-provider env override > mode default. In mock mode
    the default is a harmless local stub URL and NO credential is required.
    """
    env = os.environ if env is None else env
    override = env.get(PROVIDER_ENV[provider])
    if override:
        return override.rstrip("/")
    if mode == MODE_SANDBOX:
        return SANDBOX_BASE_URLS[provider]
    return MOCK_BASE_URL_DEFAULT


# ---------------------------------------------------------------------------
# Amazon sandbox fixture params (exact values from evidence/sandbox-calls)
# ---------------------------------------------------------------------------

def build_amazon_params(mode: str) -> Dict[str, Dict[str, str]]:
    """Return the per-endpoint default query params.

    In sandbox mode these are the exact Amazon sandbox fixture params so the
    sandbox returns 200 out of the box. In mock mode the mock ignores query
    params, so empty dicts are returned.
    """
    if mode != MODE_SANDBOX:
        return {"getOrderMetrics": {}, "listFinancialEventGroups": {}, "listTransactions": {}}
    return {
        "getOrderMetrics": {
            "marketplaceIds": "ATVPDKIKX0DER",
            "interval": "2022-08-01T00:00:00-07:00--2022-08-02T00:00:00-07:00",
            "granularity": "Total",
        },
        "listFinancialEventGroups": {
            "MaxResultsPerPage": "1",
            "FinancialEventGroupStartedAfter": "2019-10-13",
            "FinancialEventGroupStartedBefore": "2019-10-31",
        },
        "listTransactions": {
            "postedAfter": "2023-03-07",
            "nextToken": "jehgri34yo7jr9e8f984tr9i4o",
        },
    }


# ---------------------------------------------------------------------------
# Auth header selection (sandbox only; read from env, never hardcoded)
# ---------------------------------------------------------------------------

def qichacha_token(app_key: str, timespan: str, secret_key: str) -> str:
    """Qichacha signing: md5(app_key + Timespan + secret_key), lowercase hex."""
    return hashlib.md5((app_key + timespan + secret_key).encode("utf-8")).hexdigest()


def build_amazon_access_token(env: Optional[Dict[str, str]] = None) -> str:
    """Exchange the LWA refresh token for an access token (sandbox only).

    Reads LWA_CLIENT_ID / LWA_CLIENT_SECRET / LWA_REFRESH_TOKEN from env and
    posts to the LWA token endpoint. Returns the access token string for the
    ``x-amz-access-token`` header. Never called in mock mode.
    """
    env = os.environ if env is None else env
    data = urllib.parse.urlencode({
        "grant_type": "refresh_token",
        "refresh_token": env["LWA_REFRESH_TOKEN"],
        "client_id": env["LWA_CLIENT_ID"],
        "client_secret": env["LWA_CLIENT_SECRET"],
    }).encode()
    request = urllib.request.Request(
        LWA_TOKEN_URL,
        data=data,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
        method="POST",
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read())["access_token"]


def build_auth_headers(
    provider: str,
    mode: str,
    env: Optional[Dict[str, str]] = None,
    timespan: str = "",
    amazon_access_token: Optional[str] = None,
) -> Dict[str, str]:
    """Build provider auth headers.

    Returns ``{}`` in mock mode for every provider (zero credentials). In
    sandbox mode reads the relevant credentials from ``env`` only.

    ``amazon_access_token`` lets callers (and tests) supply an already-exchanged
    LWA token so the token exchange network call stays out of pure selection.
    """
    if mode != MODE_SANDBOX:
        return {}
    env = os.environ if env is None else env

    if provider == "amazon":
        token = amazon_access_token if amazon_access_token is not None else build_amazon_access_token(env)
        headers = {"x-amz-access-token": token}
        # Optional SigV4 hook: only wire if an AWS region is present. Signing is
        # intentionally left as a hook and not required for the sandbox fixtures.
        if env.get("AWS_REGION"):
            headers["x-amz-sigv4-region"] = env["AWS_REGION"]
        return headers

    if provider == "alicloud":
        return {"Authorization": "APPCODE " + env["ALICLOUD_APPCODE"]}

    if provider == "qichacha":
        ts = timespan or env.get("QICHACHA_TIMESPAN", "")
        token = qichacha_token(env["QICHACHA_APP_KEY"], ts, env["QICHACHA_SECRET_KEY"])
        return {"key": env["QICHACHA_APP_KEY"], "Timespan": ts, "Token": token}

    if provider == "gutu":
        return {"Authorization": "Bearer " + env["GUTU_TOKEN"]}

    raise ValueError("UNKNOWN_PROVIDER:" + provider)


def _mock_api_key_headers(env: Dict[str, str]) -> Dict[str, str]:
    """x-api-key for the auth-gated deployed mock. Absent/empty is fine locally."""
    api_key = env.get("MOCK_API_KEY")
    return {"x-api-key": api_key} if api_key else {}


# ---------------------------------------------------------------------------
# Thin network wrappers (one function per endpoint)
# ---------------------------------------------------------------------------

def _get(url: str, params: Dict[str, str], headers: Dict[str, str]) -> Tuple[int, Any]:
    if params:
        url = url + "?" + urllib.parse.urlencode(params)
    request = urllib.request.Request(url, headers=headers, method="GET")
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.status, json.loads(response.read())


def _post(url: str, body: Any, headers: Dict[str, str]) -> Tuple[int, Any]:
    merged = {"Content-Type": "application/json"}
    merged.update(headers)
    request = urllib.request.Request(url, data=json.dumps(body).encode(), headers=merged, method="POST")
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.status, json.loads(response.read())


def _call_get(
    provider: str,
    path: str,
    fixture_key: Optional[str] = None,
    extra_params: Optional[Dict[str, str]] = None,
    env: Optional[Dict[str, str]] = None,
) -> Tuple[int, Any]:
    env = os.environ if env is None else env
    mode = source_mode(env)
    base = resolve_base_url(provider, mode, env)
    params: Dict[str, str] = {}
    if fixture_key:
        params.update(build_amazon_params(mode)[fixture_key])
    if extra_params:
        params.update(extra_params)
    headers = dict(build_auth_headers(provider, mode, env))
    headers.update(_mock_api_key_headers(env))
    return _get(base + path, params, headers)


def _call_post(
    provider: str,
    path: str,
    body: Any,
    env: Optional[Dict[str, str]] = None,
) -> Tuple[int, Any]:
    env = os.environ if env is None else env
    mode = source_mode(env)
    base = resolve_base_url(provider, mode, env)
    headers = dict(build_auth_headers(provider, mode, env))
    headers.update(_mock_api_key_headers(env))
    return _post(base + path, body, headers)


# ---- Amazon SP-API (3 GETs) ----

def get_order_metrics(env: Optional[Dict[str, str]] = None) -> Tuple[int, Any]:
    return _call_get("amazon", "/sales/v1/orderMetrics", fixture_key="getOrderMetrics", env=env)


def list_financial_event_groups(env: Optional[Dict[str, str]] = None) -> Tuple[int, Any]:
    return _call_get("amazon", "/finances/v0/financialEventGroups", fixture_key="listFinancialEventGroups", env=env)


def list_transactions(env: Optional[Dict[str, str]] = None) -> Tuple[int, Any]:
    return _call_get("amazon", "/finances/2024-06-19/transactions", fixture_key="listTransactions", env=env)


# ---- AliCloud (1 GET) ----

def alicloud_tel_three(mobile: str, name: str, idcard: str, env: Optional[Dict[str, str]] = None) -> Tuple[int, Any]:
    return _call_get(
        "alicloud",
        "/telThree",
        extra_params={"mobile": mobile, "name": name, "idcard": idcard},
        env=env,
    )


# ---- Qichacha (1 GET, parameterized path) ----

def qichacha_check(path: str, params: Optional[Dict[str, str]] = None, env: Optional[Dict[str, str]] = None) -> Tuple[int, Any]:
    return _call_get("qichacha", path, extra_params=params, env=env)


# ---- Gutu (1 POST) ----

def gutu_panorama_check(body: Any, path: str = "/panorama/check", env: Optional[Dict[str, str]] = None) -> Tuple[int, Any]:
    return _call_post("gutu", path, body, env=env)
