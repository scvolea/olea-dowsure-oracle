# Source contract mocks & the sandbox swap

Operator guide for the two API-Gateway-only contract-mock stacks and the Python
source-client adapter (`coordinator/sources.py`) that calls them. Everything here
is for the **PoC data-source layer** - the KYC flow and the Amazon flow - swapped
between a **mock** and a **sandbox** backend by a single environment variable.

> This file documents the `sam/mocks/*` stacks. It does NOT replace `sam/README.md`
> (the enclave/oracle SAM docs) - that file is unchanged.

---

## 1. The 7 endpoints

Two independent stacks. Six GET endpoints and one POST endpoint are exercised by
the adapter and the smoke script (`scripts/source-smoke.py`).

| # | Provider | Method | Path | Operation / purpose | Adapter function |
|---|----------|--------|------|---------------------|------------------|
| 1 | Amazon SP-API | GET  | `/sales/v1/orderMetrics`            | getOrderMetrics - order/sales metrics        | `get_order_metrics` |
| 2 | Amazon SP-API | GET  | `/finances/v0/financialEventGroups` | listFinancialEventGroups - settlement groups | `list_financial_event_groups` |
| 3 | Amazon SP-API | GET  | `/finances/2024-06-19/transactions` | listTransactions - financial transactions    | `list_transactions` |
| 4 | AliCloud      | GET  | `/lundear/telThree`                 | three-element (name/mobile/id) verification  | `alicloud_tel_three` |
| 5 | Qichacha      | GET  | `/EnterpriseInfo/Verify`            | enterprise registration verification         | `qichacha_check` |
| 6 | Qichacha      | GET  | `/ShixinCheck/GetList`              | dishonest-list (shixin) judicial check       | `qichacha_check` |
| 7 | Gutu/Valuemap | POST | `/api/v1/judicial/panorama-checks`  | judicial panorama check                       | `gutu_panorama_check` |

The KYC stack also defines additional Qichacha judicial paths
(`/ZhixingCheck/GetList`, `/SumptuaryCheck/GetList`, `/BankruptcyCheck/GetList`);
they return canned bodies too but are outside the 7-endpoint smoke set. Call them
with `qichacha_check("<path>")` if needed.

Endpoint 1-3 live in the **amazon-sp** stack; 4-7 (and the extra Qichacha paths)
live in the **kyc** stack.

---

## 2. SOURCE_MODE - the mock <-> sandbox flip

A single env var selects the backend. Any value other than `sandbox` is treated
as `mock`.

| `SOURCE_MODE` | Backend | Credentials |
|---------------|---------|-------------|
| `mock` (default) | The deployed API Gateway contract mocks (or a local stub) | **None** |
| `sandbox` | The real provider hosts (Amazon SP-API sandbox, AliCloud, Qichacha, Gutu) | Required (see env list) |

- **Mock mode needs ZERO credentials.** It returns the verbatim fixture bodies
  baked into the mock templates. This is the only mode agents run.
- **Sandbox mode** attaches provider auth read from the environment at call time.
  If a provider's required creds are absent, the smoke script **skips** that call
  with a clear message rather than crashing.

```powershell
# Mock (default) - no creds needed
$env:SOURCE_MODE = "mock"
python scripts\source-smoke.py --self-test     # local in-process stub
python scripts\source-smoke.py                 # deployed mocks via *_BASE_URL

# Sandbox - real hosts, needs the creds from .env
$env:SOURCE_MODE = "sandbox"
python scripts\source-smoke.py
```

---

## 3. Environment variables (where to get each)

Copy `.env.example` to `.env` (gitignored) and fill in only what sandbox mode
needs. Mock mode leaves them empty.

| Variable | Mode | Where to obtain it |
|----------|------|--------------------|
| `SOURCE_MODE` | both | `mock` or `sandbox`. Default `mock`. |
| `MOCK_API_KEY` | mock (gated) | `x-api-key` for the deployed mocks. API Gateway > API Keys, or the `ApiKeyId` stack output + `aws apigateway get-api-key ... --include-value` (see section 6). Empty for a local stub. |
| `AMAZON_SP_BASE_URL` | both | Mock: the amazon-sp stack `ApiUrl` output. Sandbox: optional override of the real host. |
| `ALICLOUD_BASE_URL` | both | Mock: the kyc stack `ApiUrl` output. Sandbox: optional override. |
| `QICHACHA_BASE_URL` | both | Mock: the kyc stack `ApiUrl` output. Sandbox: optional override. |
| `GUTU_BASE_URL` | both | Mock: the kyc stack `ApiUrl` output. Sandbox: optional override. |
| `LWA_CLIENT_ID` | sandbox | Amazon SP-API developer console > your app > LWA credentials. |
| `LWA_CLIENT_SECRET` | sandbox | Same screen as the client id. |
| `LWA_REFRESH_TOKEN` | sandbox | From completing the app authorization (OAuth) flow for the seller account. |
| `AWS_REGION` | sandbox (optional) | Region of the SP-API role; only enables the SigV4 hook. |
| `ALICLOUD_APPCODE` | sandbox | Aliyun console > API Market > your subscription > AppCode. |
| `QICHACHA_APP_KEY` | sandbox | Qichacha open-platform console > My Data > Key Management. |
| `QICHACHA_SECRET_KEY` | sandbox | Same screen as the app key. |
| `QICHACHA_TIMESPAN` | sandbox (optional) | Epoch-seconds pin for the signature; usually generated per request. |
| `GUTU_TOKEN` | sandbox | Gutu/Valuemap tenant console > API access token. |

In sandbox mode the base URLs default to the real hosts when left empty: Amazon
`https://sandbox.sellingpartnerapi-na.amazon.com`, AliCloud
`https://qrymobile.market.alicloudapi.com`, Qichacha `https://api.qichacha.com`,
Gutu `https://turningapi.valuemap.cn`.

---

## 4. Validate the templates

Run from the worktree root (SAM CLI 1.157.1):

```powershell
sam validate --lint --template-file sam\mocks\amazon-sp\template.yaml
sam validate --lint --template-file sam\mocks\kyc\template.yaml
```

---

## 5. Deploy both stacks to PREPROD

Target: **account 706179786846, region ap-southeast-1 (Singapore), AWS profile
`preprod`**. In the office keep the proxy OFF for AWS (`gpoff`). These are
documented commands - agents do NOT run them.

```powershell
# Amazon SP-API contract mock
sam deploy --template-file sam\mocks\amazon-sp\template.yaml `
  --stack-name amazon-sp-contract-mock `
  --profile preprod --region ap-southeast-1 `
  --capabilities CAPABILITY_IAM --resolve-s3

# KYC contract mock (AliCloud + Qichacha + Gutu)
sam deploy --template-file sam\mocks\kyc\template.yaml `
  --stack-name kyc-contract-mock `
  --profile preprod --region ap-southeast-1 `
  --capabilities CAPABILITY_IAM --resolve-s3
```

Each stack prints an `ApiUrl` output (the mock base URL - feed it into the
`*_BASE_URL` env vars) and an `ApiKeyId` output (the API key ID - never the key
value).

---

## 6. Passing the API key (x-api-key)

Both mock APIs require an API key (`ApiKeyRequired: true`). The adapter sends the
`x-api-key` header from the `MOCK_API_KEY` env var when it is set; an empty value
is fine for an un-gated local stub.

Fetch the key **value** from the `ApiKeyId` stack output (the output exposes only
the ID, never the secret value):

```powershell
aws apigateway get-api-key `
  --api-key <ApiKeyId-from-stack-output> `
  --include-value `
  --profile preprod --region ap-southeast-1
```

Put the returned `value` into `MOCK_API_KEY` in your `.env`. Never commit it.

---

## 7. Sandbox is NOT a TLSNotary target

The Amazon SP-API **sandbox returns canned fixtures**, not real seller data. A
TLSNotary proof over a sandbox session therefore attests only to **fixture data**
and is meaningless as evidence - it proves the provenance of a dummy response,
not of a real production fact.

**TLSNotary proves the provenance of REAL production data.** It remains the
real-production-only gate. Use mock/sandbox for contract shape and integration
wiring; never generate or trust a TLSNotary attestation produced against the
sandbox (or against these mocks).
