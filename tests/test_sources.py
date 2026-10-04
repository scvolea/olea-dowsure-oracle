import hashlib
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from coordinator.sources import (
    MODE_MOCK,
    MODE_SANDBOX,
    SANDBOX_BASE_URLS,
    build_amazon_params,
    build_auth_headers,
    qichacha_token,
    resolve_base_url,
    source_mode,
)

PROVIDERS = ("amazon", "alicloud", "qichacha", "gutu")


class SourceModeTest(unittest.TestCase):
    def test_default_is_mock(self):
        self.assertEqual(source_mode({}), MODE_MOCK)

    def test_non_sandbox_value_is_mock(self):
        self.assertEqual(source_mode({"SOURCE_MODE": "anything"}), MODE_MOCK)

    def test_sandbox_selected(self):
        self.assertEqual(source_mode({"SOURCE_MODE": "sandbox"}), MODE_SANDBOX)


class MockModeNeedsZeroCredsTest(unittest.TestCase):
    def test_auth_headers_empty_for_all_providers_with_empty_env(self):
        for provider in PROVIDERS:
            self.assertEqual(build_auth_headers(provider, MODE_MOCK, {}), {})

    def test_mock_mode_resolves_to_mock_base_urls(self):
        # No per-provider override and no sandbox -> harmless local stub, never a real host.
        for provider in PROVIDERS:
            url = resolve_base_url(provider, MODE_MOCK, {})
            self.assertNotIn(url, SANDBOX_BASE_URLS.values())

    def test_mock_mode_honours_deployed_mock_url_override(self):
        env = {"AMAZON_SP_BASE_URL": "https://abc123.execute-api.ap-southeast-1.amazonaws.com/mock"}
        self.assertEqual(
            resolve_base_url("amazon", MODE_MOCK, env),
            "https://abc123.execute-api.ap-southeast-1.amazonaws.com/mock",
        )


class SandboxBaseUrlTest(unittest.TestCase):
    def test_sandbox_resolves_to_real_hosts(self):
        self.assertEqual(resolve_base_url("amazon", MODE_SANDBOX, {}), "https://sandbox.sellingpartnerapi-na.amazon.com")
        self.assertEqual(resolve_base_url("alicloud", MODE_SANDBOX, {}), "https://qrymobile.market.alicloudapi.com")
        self.assertEqual(resolve_base_url("qichacha", MODE_SANDBOX, {}), "https://api.qichacha.com")
        self.assertEqual(resolve_base_url("gutu", MODE_SANDBOX, {}), "https://turningapi.valuemap.cn")

    def test_override_wins_over_sandbox_default(self):
        env = {"GUTU_BASE_URL": "https://custom.example.com/"}
        self.assertEqual(resolve_base_url("gutu", MODE_SANDBOX, env), "https://custom.example.com")


class AmazonFixtureParamsTest(unittest.TestCase):
    def test_sandbox_params_match_fixtures(self):
        params = build_amazon_params(MODE_SANDBOX)
        self.assertEqual(params["getOrderMetrics"], {
            "marketplaceIds": "ATVPDKIKX0DER",
            "interval": "2022-08-01T00:00:00-07:00--2022-08-02T00:00:00-07:00",
            "granularity": "Total",
        })
        self.assertEqual(params["listFinancialEventGroups"], {
            "MaxResultsPerPage": "1",
            "FinancialEventGroupStartedAfter": "2019-10-13",
            "FinancialEventGroupStartedBefore": "2019-10-31",
        })
        self.assertEqual(params["listTransactions"], {
            "postedAfter": "2023-03-07",
            "nextToken": "jehgri34yo7jr9e8f984tr9i4o",
        })

    def test_mock_params_are_empty(self):
        params = build_amazon_params(MODE_MOCK)
        self.assertEqual(params, {"getOrderMetrics": {}, "listFinancialEventGroups": {}, "listTransactions": {}})


class QichachaTokenTest(unittest.TestCase):
    def test_md5_of_appkey_timespan_secret(self):
        app_key, timespan, secret = "APPKEY123", "1700000000", "SECRET456"
        expected = hashlib.md5((app_key + timespan + secret).encode("utf-8")).hexdigest()
        self.assertEqual(qichacha_token(app_key, timespan, secret), expected)


class SandboxAuthHeaderShapeTest(unittest.TestCase):
    def test_amazon_header_shape_without_network(self):
        # Supply a pre-exchanged token so no token-exchange network call happens.
        headers = build_auth_headers("amazon", MODE_SANDBOX, {}, amazon_access_token="FAKE-TOKEN")
        self.assertIn("x-amz-access-token", headers)
        self.assertEqual(headers["x-amz-access-token"], "FAKE-TOKEN")

    def test_amazon_sigv4_region_hook_only_when_present(self):
        with_region = build_auth_headers("amazon", MODE_SANDBOX, {"AWS_REGION": "us-east-1"}, amazon_access_token="T")
        self.assertEqual(with_region.get("x-amz-sigv4-region"), "us-east-1")
        without = build_auth_headers("amazon", MODE_SANDBOX, {}, amazon_access_token="T")
        self.assertNotIn("x-amz-sigv4-region", without)

    def test_alicloud_appcode_prefix(self):
        headers = build_auth_headers("alicloud", MODE_SANDBOX, {"ALICLOUD_APPCODE": "FAKEAPPCODE"})
        self.assertEqual(headers["Authorization"], "APPCODE FAKEAPPCODE")

    def test_qichacha_signed_headers(self):
        env = {"QICHACHA_APP_KEY": "AK", "QICHACHA_SECRET_KEY": "SK"}
        headers = build_auth_headers("qichacha", MODE_SANDBOX, env, timespan="1700000000")
        self.assertEqual(headers["key"], "AK")
        self.assertEqual(headers["Timespan"], "1700000000")
        self.assertEqual(headers["Token"], qichacha_token("AK", "1700000000", "SK"))

    def test_gutu_bearer_prefix(self):
        headers = build_auth_headers("gutu", MODE_SANDBOX, {"GUTU_TOKEN": "FAKETOKEN"})
        self.assertEqual(headers["Authorization"], "Bearer FAKETOKEN")


if __name__ == "__main__":
    unittest.main()
