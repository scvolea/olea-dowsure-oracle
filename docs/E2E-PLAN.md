# E2E Plan — 7 data-source contract mocks

Plain-English intro: this document explains how to prove, end to end, that the
seven data-source endpoints Dowsure depends on return the right data in the right
shape. There are two ways to run the check. One is **offline** and needs nothing
but Python — it uses local copies of the exact response bodies. The other is
**live** and calls the mock APIs we deployed to AWS, using an API key. The offline
check is what you run day to day; the live check is what you run after a deploy to
confirm the real API Gateway stacks answer correctly.

The seven endpoints are 6 GETs and 1 POST, split across two mock stacks:

- **Amazon SP-API mock** (`sam/mocks/amazon-sp/template.yaml`): 3 GETs.
- **KYC mock** (`sam/mocks/kyc/template.yaml`): 2 Qichacha GETs + 1 AliCloud GET + 1 Gutu POST.

Each mock returns a verbatim, unmasked sample body (the Amazon bodies come from the
real sandbox captures in `evidence/sandbox-calls/`; the KYC bodies are the locked
sample bodies). The harness `scripts/e2e-sources.py` asserts HTTP 200 and the
expected body shape for all seven, maps the Amazon `listTransactions` ORDER_ID
identifiers into a `SuperPoRequestDto`-shaped dict, and exercises the KYC payloads.

---

## The 7-endpoint checklist

| # | Provider | Method | Path | Operation | Shape asserted |
|---|----------|--------|------|-----------|----------------|
| 1 | Amazon   | GET  | `/sales/v1/orderMetrics`            | getOrderMetrics            | `payload[]` with `totalSales` |
| 2 | Amazon   | GET  | `/finances/v0/financialEventGroups` | listFinancialEventGroups   | `payload.FinancialEventGroupList[]` |
| 3 | Amazon   | GET  | `/finances/2024-06-19/transactions` | listTransactions           | `payload.transactions[].relatedIdentifiers[]` |
| 4 | AliCloud | GET  | `/lundear/telThree`                 | three-element verify       | `data.result == 1` |
| 5 | Qichacha | GET  | `/EnterpriseInfo/Verify`            | enterprise verify          | `Status` + `Result` present |
| 6 | Qichacha | GET  | `/ShixinCheck/GetList`              | dishonesty list            | `Status` + `Result` present |
| 7 | Gutu     | POST | `/api/v1/judicial/panorama-checks`  | judicial panorama          | `data.riskLevel` present |

---

## Offline check (`--self-test`) — run this any time

Fully offline. No network, no credentials, no deployed stacks. It uses the
in-process verbatim fixtures embedded in `scripts/e2e-sources.py` (the same bodies
the mocks return) and runs the identical assertions.

```powershell
# From the worktree root
python scripts\e2e-sources.py --self-test
```

Expected: a `PASS` line for each of the 7 endpoints, the printed SuperPo mapping
dict with a non-empty `order_ids` list, `RESULT: ALL PASS`, and exit code 0.

---

## SuperPo field-mapping expectation

The harness builds a `SuperPoRequestDto`-shaped dict (snake_case, matching
`com.olea.invoice.domain.dto.SuperPoRequestDto` — reference only, no Java import)
from the `listTransactions` body:

- `order_ids[]` is populated from every `relatedIdentifiers` entry whose
  `relatedIdentifierName == "ORDER_ID"` (asserted non-empty, each value non-blank).
- `total_order_amount` and `currency` come from the first transaction's
  `totalAmount` (`currencyAmount` / `currencyCode`).
- The remaining required fields — `super_po_id`, `request_date`,
  `total_financing_amount`, `buyer`, `seller`, `seller_merchant_id`, `order_type`,
  `seller_oleaid` — are PoC-fixed placeholders; the mapping test only asserts the
  full field set is present and non-blank. The data of interest (the order ids and
  amount) is the part sourced from the live/fixture response.

Full field set asserted: `super_po_id`, `request_date`, `total_order_amount`,
`total_financing_amount`, `currency`, `buyer`, `seller`, `seller_merchant_id`,
`order_type`, `seller_oleaid`, `order_ids[]`.

---

## Deploy the two mock stacks to preprod

Both stacks deploy to **account 706179786846**, region **ap-southeast-1**
(Singapore), AWS profile **preprod**. In the office keep the proxy OFF for AWS.
Agents document these commands but do NOT run them.

```powershell
# Amazon SP-API mock
sam deploy --template-file sam\mocks\amazon-sp\template.yaml `
  --stack-name amazon-sp-contract-mock `
  --profile preprod --region ap-southeast-1 `
  --capabilities CAPABILITY_IAM --resolve-s3

# KYC mock
sam deploy --template-file sam\mocks\kyc\template.yaml `
  --stack-name kyc-contract-mock `
  --profile preprod --region ap-southeast-1 `
  --capabilities CAPABILITY_IAM --resolve-s3
```

Each stack outputs an `ApiUrl` (the deployed base URL) and an `ApiKeyId` (the ID of
the usage-plan API key — never the key value itself).

### Fetch the API key value

The key value is not an output. Retrieve it from the `ApiKeyId` output:

```powershell
aws apigateway get-api-key --api-key <ApiKeyId> --include-value `
  --profile preprod --region ap-southeast-1 --query value --output text
```

Keep this value out of git and out of logs. Put it in your local `.env` as
`MOCK_API_KEY` (see `.env.example` for where each env var comes from).

---

## Live check — run after a deploy

The live path calls all 7 deployed endpoints with the `x-api-key` header and
asserts HTTP 200 + shape. Point each provider base URL at the deployed `ApiUrl`
(both Amazon paths share the Amazon stack's `ApiUrl`; the three KYC providers share
the KYC stack's `ApiUrl`), set `SOURCE_MODE=mock`, and supply `MOCK_API_KEY`.

```powershell
$env:SOURCE_MODE        = "mock"
$env:MOCK_API_KEY       = "<value from get-api-key above>"
$env:AMAZON_SP_BASE_URL = "<amazon-sp-contract-mock ApiUrl>"
$env:ALICLOUD_BASE_URL  = "<kyc-contract-mock ApiUrl>"
$env:QICHACHA_BASE_URL  = "<kyc-contract-mock ApiUrl>"
$env:GUTU_BASE_URL      = "<kyc-contract-mock ApiUrl>"

python scripts\e2e-sources.py
```

Mock mode uses ZERO provider credentials — only the `x-api-key` that fronts the
API Gateway usage plan. Same expected output as the offline run (7 `PASS`, SuperPo
mapping with non-empty `order_ids`, exit 0).

---

## Note: the Amazon sandbox is NOT a valid TLSNotary target

The Amazon SP-API **sandbox** returns canned fixture data only when a request
exactly matches a stored fixture. It is not real production data. A TLSNotary proof
is a provenance gate: it attests that a specific response genuinely came from a
specific real production endpoint. A TLSNotary session recorded against the sandbox
would only attest to fixture data, which proves nothing about a real seller.

Therefore **TLSNotary remains a real-production-only gate**. The sandbox 200s (and
these mocks) validate the live source-call path and the field mapping; they are
deliberately out of scope for TLSNotary provenance.
