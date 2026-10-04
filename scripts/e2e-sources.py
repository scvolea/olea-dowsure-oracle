"""End-to-end harness for the 7 data-source contract mocks.

Two modes:

LIVE (default)
--------------
Reads the deployed API Gateway base URLs (the per-provider ``*_BASE_URL`` env
vars, set from each stack's ``ApiUrl`` output) and ``MOCK_API_KEY`` from the
environment, then calls all 7 deployed endpoints through ``coordinator.sources``
with the ``x-api-key`` header. For each endpoint it asserts HTTP 200 and that the
response body carries the expected verbatim shape (top-level keys / known
fields). It then maps the Amazon ``listTransactions`` ORDER_ID related-identifiers
into a ``SuperPoRequestDto``-shaped dict and asserts ``order_ids[]`` is populated,
and exercises the three KYC payloads. The live path is NOT run by agents - it
needs the deployed stacks + the API key. See docs/E2E-PLAN.md.

SELF-TEST (``--self-test``)
---------------------------
Runs FULLY OFFLINE using the in-process verbatim fixtures below (the same bodies
the mock stacks return - the Amazon captures from evidence/sandbox-calls and the
KYC sample bodies from the kyc mock template). No network, no credentials. It runs
the exact same shape assertions, SuperPo mapping, and KYC checks against the
fixtures, prints a per-endpoint PASS/FAIL summary plus the constructed SuperPo
mapping dict, and exits non-zero on any failure.

Credential safety: this script never reads or echoes the repo-root ``.env``. In
mock/self-test mode ZERO credentials are used. The ``x-api-key`` value, when
present on the live path, is read from ``MOCK_API_KEY`` and never printed.

USAGE
-----
    python scripts/e2e-sources.py --self-test   # OFFLINE, local fixtures, no creds
    python scripts/e2e-sources.py               # LIVE, needs deployed URLs + MOCK_API_KEY
"""

import argparse
import json
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from coordinator.sources import (  # noqa: E402
    alicloud_tel_three,
    get_order_metrics,
    gutu_panorama_check,
    list_financial_event_groups,
    list_transactions,
    qichacha_check,
    source_mode,
)

# ---------------------------------------------------------------------------
# Verbatim fixtures (identical to what the deployed mocks return)
#   Amazon bodies: evidence/sandbox-calls/*.json `body` objects.
#   KYC bodies: the sample bodies locked in FEAT-002 / the kyc mock template.
# These let --self-test run the full assertion suite with no network.
# ---------------------------------------------------------------------------

FIX_GET_ORDER_METRICS = {
    "payload": [
        {
            "interval": "2019-08-01T00:00-07:00--2018-08-03T00:00-07:00",
            "unitCount": 2,
            "orderItemCount": 2,
            "orderCount": 2,
            "averageUnitPrice": {"amount": "12.5", "currencyCode": "USD"},
            "totalSales": {"amount": "25", "currencyCode": "USD"},
        }
    ]
}

FIX_LIST_FINANCIAL_EVENT_GROUPS = {
    "payload": {
        "NextToken": "3493805734095308457308475",
        "FinancialEventGroupList": [
            {
                "FinancialEventGroupId": "1",
                "ProcessingStatus": "PROCESSED",
                "FundTransferStatus": "TRANSFERED",
                "OriginalTotal": {"CurrencyCode": "USD", "CurrencyAmount": 10.34},
                "ConvertedTotal": {"CurrencyCode": "USD", "CurrencyAmount": 39.43},
                "FundTransferDate": "2020-02-07T14:38:42.128Z",
                "TraceId": "34550454504545",
                "AccountTail": "4854564857",
                "BeginningBalance": {"CurrencyCode": "USD", "CurrencyAmount": 55.33},
                "FinancialEventGroupStart": "2020-02-07T14:38:42.128Z",
                "FinancialEventGroupEnd": "2020-02-07T14:38:42.128Z",
            }
        ],
    }
}

FIX_LIST_TRANSACTIONS = {
    "payload": {
        "nextToken": "Next token value",
        "transactions": [
            {
                "sellingPartnerMetadata": {
                    "sellingPartnerId": "A3TH9S8BH6GOGM",
                    "accountType": "PAYABLE",
                    "marketplaceId": "ATIV93840DER",
                },
                "relatedIdentifiers": [
                    {
                        "relatedIdentifierName": "FINANCIAL_EVENT_GROUP_ID",
                        "relatedIdentifierValue": "4MVaHcsAfaYAlwlaPWrXJxrfUiKfYZ2ooWtY7528FUA",
                    },
                    {
                        "relatedIdentifierName": "ORDER_ID",
                        "relatedIdentifierValue": "8129762527551",
                    },
                ],
                "transactionType": "Shipment",
                "postedDate": "2020-07-14T03:35:13.214Z",
                "totalAmount": {"currencyAmount": 10, "currencyCode": "USD"},
            }
        ],
    }
}

FIX_ALICLOUD_TEL_THREE = {
    "code": "0",
    "msg": "\u6210\u529f",
    "data": {
        "result": 1,
        "desc": "\u4e00\u81f4",
        "orderNo": "ALI20231013094512003",
        "mobile": "13800138000",
        "name": "\u5f20\u4f1f",
        "idcard": "110101199003078573",
    },
}

FIX_QICHACHA_ENTERPRISE = {
    "Status": "200",
    "Message": "\u67e5\u8be2\u6210\u529f",
    "OrderNumber": "QCC2001202310130001",
    "Result": {
        "KeyNo": "g9f3c0e1a2b74d6e8f0a1b2c3d4e5f60",
        "Name": "\u6cc9\u5dde\u5e02\u98de\u4e50\u7535\u5b50\u5546\u52a1\u6709\u9650\u516c\u53f8",
        "CreditCode": "91350500MA2XYABC1K",
        "OperName": "\u5f20\u4f1f",
        "Status": "\u5b58\u7eed",
        "StartDate": "2016-05-12",
    },
}

FIX_QICHACHA_SHIXIN = {
    "Status": "200",
    "Message": "\u67e5\u8be2\u6210\u529f",
    "OrderNumber": "QCC0740202310130001",
    "Result": [],
    "Paging": {"PageSize": 10, "PageIndex": 1, "TotalRecords": 0},
}

FIX_GUTU_PANORAMA = {
    "code": 200,
    "message": "success",
    "requestId": "GUTU20231013094512abc",
    "data": {
        "name": "\u5f20\u4f1f",
        "idCardNo": "110101199003078573",
        "mobile": "13800138000",
        "serviceTier": "standard",
        "riskLevel": "MEDIUM",
        "judicialSummary": {
            "dishonestCount": 0,
            "enforcementCount": 1,
            "sumptuaryCount": 0,
            "bankruptcyCount": 0,
            "caseList": [
                {
                    "caseNo": "(2021)\u95fd0505\u6267123\u53f7",
                    "caseType": "\u6267\u884c",
                    "court": "\u6cc9\u5dde\u5e02\u4e30\u6cfd\u533a\u4eba\u6c11\u6cd5\u9662",
                    "amount": "50000",
                    "filingDate": "2021-06-18",
                }
            ],
        },
    },
}

# Deployed endpoint paths (match the mock templates / adapter wiring).
QICHACHA_ENTERPRISE_PATH = "/EnterpriseInfo/Verify"
QICHACHA_SHIXIN_PATH = "/ShixinCheck/GetList"
GUTU_PANORAMA_PATH = "/api/v1/judicial/panorama-checks"

# KYC request payloads (fed to the adapter in live mode; shape-checked offline).
KYC_PAYLOADS = {
    "alicloud_tel_three": {
        "mobile": "13800138000",
        "name": "\u5f20\u4f1f",
        "idcard": "110101199003078573",
    },
    "gutu_panorama": {
        "name": "\u5f20\u4f1f",
        "idCardNo": "110101199003078573",
        "mobile": "13800138000",
        "serviceTier": "standard",
    },
}


# ---------------------------------------------------------------------------
# Shape assertions (identical in both modes)
# ---------------------------------------------------------------------------

class ShapeError(AssertionError):
    """Raised when a response body does not match the expected verbatim shape."""


def _require(cond, message):
    if not cond:
        raise ShapeError(message)


def assert_get_order_metrics(body):
    _require(isinstance(body, dict) and isinstance(body.get("payload"), list),
             "getOrderMetrics: expected payload[] list")
    _require(len(body["payload"]) >= 1 and "totalSales" in body["payload"][0],
             "getOrderMetrics: expected payload[0].totalSales")


def assert_list_financial_event_groups(body):
    payload = body.get("payload") if isinstance(body, dict) else None
    _require(isinstance(payload, dict) and isinstance(payload.get("FinancialEventGroupList"), list),
             "listFinancialEventGroups: expected payload.FinancialEventGroupList[]")
    _require(len(payload["FinancialEventGroupList"]) >= 1
             and "FinancialEventGroupId" in payload["FinancialEventGroupList"][0],
             "listFinancialEventGroups: expected a FinancialEventGroupId")


def assert_list_transactions(body):
    payload = body.get("payload") if isinstance(body, dict) else None
    _require(isinstance(payload, dict) and isinstance(payload.get("transactions"), list),
             "listTransactions: expected payload.transactions[]")
    _require(len(payload["transactions"]) >= 1
             and isinstance(payload["transactions"][0].get("relatedIdentifiers"), list),
             "listTransactions: expected transactions[0].relatedIdentifiers[]")


def assert_alicloud(body):
    _require(isinstance(body, dict) and isinstance(body.get("data"), dict),
             "alicloud telThree: expected data{}")
    _require(body["data"].get("result") == 1,
             "alicloud telThree: expected data.result == 1")


def assert_qichacha(body):
    _require(isinstance(body, dict) and "Result" in body and "Status" in body,
             "qichacha: expected Status + Result")


def assert_gutu(body):
    _require(isinstance(body, dict) and isinstance(body.get("data"), dict),
             "gutu panorama: expected data{}")
    _require(body["data"].get("riskLevel"),
             "gutu panorama: expected data.riskLevel")


# ---------------------------------------------------------------------------
# SuperPo mapping: listTransactions ORDER_ID related-identifiers -> DTO shape
# ---------------------------------------------------------------------------

# Non-source fields are PoC-fixed placeholders; the mapping test only asserts the
# full SuperPoRequestDto field set is present/non-blank and that order_ids[] is
# populated from the live/fixture ORDER_ID identifiers (and amount/currency from
# totalAmount). SuperPoRequestDto.java is the reference for the field set.
SUPER_PO_STATIC = {
    "super_po_id": "SPO-E2E-0001",
    "request_date": "2023-03-07",
    "total_financing_amount": "8.00",
    "buyer": "Dowsure",
    "seller": "Quanzhou Feile Electronic Commerce Co., Ltd.",
    "seller_merchant_id": "A3TH9S8BH6GOGM",
    "order_type": "SUPER_PO",
    "seller_oleaid": "OLEA-A3TH9S8BH6GOGM",
}

REQUIRED_SUPER_PO_FIELDS = (
    "super_po_id",
    "request_date",
    "total_order_amount",
    "total_financing_amount",
    "currency",
    "buyer",
    "seller",
    "seller_merchant_id",
    "order_type",
    "seller_oleaid",
    "order_ids",
)


def extract_order_ids(list_transactions_body):
    """Pull every ORDER_ID relatedIdentifierValue from a listTransactions body."""
    order_ids = []
    payload = (list_transactions_body or {}).get("payload", {})
    for txn in payload.get("transactions", []):
        for rid in txn.get("relatedIdentifiers", []):
            if rid.get("relatedIdentifierName") == "ORDER_ID":
                value = rid.get("relatedIdentifierValue")
                if value:
                    order_ids.append(value)
    return order_ids


def _first_total_amount(list_transactions_body):
    """Return (amount_str, currency) from the first transaction's totalAmount."""
    payload = (list_transactions_body or {}).get("payload", {})
    txns = payload.get("transactions", [])
    if not txns:
        return None, None
    amount = txns[0].get("totalAmount", {})
    value = amount.get("currencyAmount")
    return (None if value is None else str(value)), amount.get("currencyCode")


def build_super_po(list_transactions_body):
    """Build a SuperPoRequestDto-shaped dict from a listTransactions body."""
    order_ids = extract_order_ids(list_transactions_body)
    amount, currency = _first_total_amount(list_transactions_body)
    dto = dict(SUPER_PO_STATIC)
    dto["order_ids"] = order_ids
    dto["total_order_amount"] = amount if amount is not None else "0"
    dto["currency"] = currency or "USD"
    return dto


def assert_super_po(dto):
    for field in REQUIRED_SUPER_PO_FIELDS:
        _require(field in dto, "SuperPo mapping: missing field " + field)
    _require(isinstance(dto["order_ids"], list) and len(dto["order_ids"]) >= 1,
             "SuperPo mapping: order_ids[] must be non-empty")
    for order_id in dto["order_ids"]:
        _require(isinstance(order_id, str) and order_id.strip(),
                 "SuperPo mapping: blank order_id")
    for field in REQUIRED_SUPER_PO_FIELDS:
        if field == "order_ids":
            continue
        value = dto[field]
        _require(isinstance(value, str) and value.strip(),
                 "SuperPo mapping: blank required field " + field)


# ---------------------------------------------------------------------------
# Endpoint table shared by both modes
#   label, assert_fn, live_call(env) -> (status, body), fixture_body
# ---------------------------------------------------------------------------

def _endpoints():
    kyc_tel = KYC_PAYLOADS["alicloud_tel_three"]
    kyc_gutu = KYC_PAYLOADS["gutu_panorama"]
    return [
        ("amazon getOrderMetrics          [GET] ", assert_get_order_metrics,
         lambda env: get_order_metrics(env), FIX_GET_ORDER_METRICS),
        ("amazon listFinancialEventGroups [GET] ", assert_list_financial_event_groups,
         lambda env: list_financial_event_groups(env), FIX_LIST_FINANCIAL_EVENT_GROUPS),
        ("amazon listTransactions         [GET] ", assert_list_transactions,
         lambda env: list_transactions(env), FIX_LIST_TRANSACTIONS),
        ("alicloud telThree               [GET] ", assert_alicloud,
         lambda env: alicloud_tel_three(kyc_tel["mobile"], kyc_tel["name"], kyc_tel["idcard"], env=env),
         FIX_ALICLOUD_TEL_THREE),
        ("qichacha EnterpriseInfo/Verify  [GET] ", assert_qichacha,
         lambda env: qichacha_check(QICHACHA_ENTERPRISE_PATH, env=env), FIX_QICHACHA_ENTERPRISE),
        ("qichacha ShixinCheck/GetList    [GET] ", assert_qichacha,
         lambda env: qichacha_check(QICHACHA_SHIXIN_PATH, env=env), FIX_QICHACHA_SHIXIN),
        ("gutu panorama-checks            [POST]", assert_gutu,
         lambda env: gutu_panorama_check(kyc_gutu, path=GUTU_PANORAMA_PATH, env=env), FIX_GUTU_PANORAMA),
    ]


# ---------------------------------------------------------------------------
# Runners
# ---------------------------------------------------------------------------

def _run(get_result, require_200):
    """Shared driver. ``get_result(endpoint) -> (status, body)``.

    Returns (failures, list_transactions_body).
    """
    failures = 0
    list_transactions_body = None
    for label, assert_fn, live_call, fixture_body in _endpoints():
        try:
            status, body = get_result((label, assert_fn, live_call, fixture_body))
            if require_200:
                _require(status == 200, label.strip() + ": expected HTTP 200, got " + str(status))
            assert_fn(body)
            if assert_fn is assert_list_transactions:
                list_transactions_body = body
            print("PASS  " + label + "  status=" + str(status))
        except Exception as exc:  # noqa: BLE001 - one line per endpoint
            failures += 1
            print("FAIL  " + label + "  " + type(exc).__name__ + ": " + str(exc))
    return failures, list_transactions_body


def run_live(env):
    """LIVE: call the deployed endpoints with x-api-key, assert 200 + shape."""
    print("MODE=live  SOURCE_MODE=" + source_mode(env))
    print("(calling deployed API Gateway mocks via *_BASE_URL + x-api-key)")

    def get_result(endpoint):
        _label, _assert_fn, live_call, _fixture = endpoint
        return live_call(env)

    return _finish(_run(get_result, require_200=True))


def run_self_test():
    """OFFLINE: run the full suite against the in-process verbatim fixtures."""
    print("MODE=self-test  (OFFLINE - local fixtures, no network, no creds)")

    def get_result(endpoint):
        _label, _assert_fn, _live_call, fixture_body = endpoint
        return 200, fixture_body

    return _finish(_run(get_result, require_200=True))


def _finish(run_result):
    failures, list_transactions_body = run_result

    print("")
    print("SuperPo field mapping (from listTransactions ORDER_ID identifiers):")
    try:
        _require(list_transactions_body is not None,
                 "SuperPo mapping: no listTransactions body captured")
        super_po = build_super_po(list_transactions_body)
        assert_super_po(super_po)
        print(json.dumps(super_po, indent=2, ensure_ascii=False))
        print("order_ids populated: " + str(super_po["order_ids"]))
    except Exception as exc:  # noqa: BLE001
        failures += 1
        print("FAIL  SuperPo mapping  " + type(exc).__name__ + ": " + str(exc))

    print("")
    print(("RESULT: ALL PASS" if failures == 0 else "RESULT: " + str(failures) + " FAILURE(S)"))
    return 1 if failures else 0


def main(argv=None):
    parser = argparse.ArgumentParser(
        description="E2E harness for the 7 data-source contract mocks.")
    parser.add_argument("--self-test", action="store_true",
                        help="Run fully OFFLINE against local fixtures (no network, no creds).")
    args = parser.parse_args(argv)
    if args.self_test:
        return run_self_test()
    return run_live(dict(os.environ))


if __name__ == "__main__":
    sys.exit(main())
