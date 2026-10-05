# Amazon SP-API Sandbox - Live Call Evidence (3/3 HTTP 200)

Plain-English summary: We proved our Amazon credentials work and that we can call
the real Amazon Selling Partner API (SP-API) sandbox and get a full HTTP 200
response from all three endpoints Dowsure uses for shop onboarding and financing.

- SP-API = Selling Partner API (Amazon's seller data API).
- LWA = Login with Amazon (the OAuth login that mints the access token).
- Static sandbox = a test endpoint that returns canned sample data only when the
  request EXACTLY matches a stored sample request defined in the API model file.

## Result: all three live, all HTTP 200

| # | Operation | Path | Feeds | Live result |
|---|-----------|------|-------|-------------|
| 1 | `getOrderMetrics` | `GET /sales/v1/orderMetrics` | Shop-level SALES | **HTTP 200** |
| 2 | `listFinancialEventGroups` | `GET /finances/v0/financialEventGroups` | Shop-level FINANCIALS | **HTTP 200** |
| 3 | `listTransactions` | `GET /finances/2024-06-19/transactions` | PO-level `canonical_data` | **HTTP 200** |

Each call's exact request, status, Amazon `x-amzn-RequestId`, and full response
body are in the matching `*.json` file. `_summary.json` lists all three with their
Amazon request IDs.

App: `test-sp-api` (Application ID `amzn1.sp.solution.9b0d04ac-4bf9-4a7d-810b-cea0243f80a0`).
Base host: `https://sandbox.sellingpartnerapi-na.amazon.com` (North America / US).
Auth: LWA refresh-token -> access-token exchange, then `x-amz-access-token` header.
Credentials are read from a local, git-ignored `.env`; no secret values are stored here.

## The exact sandbox fixture requests (the key to getting 200)

Amazon's static sandbox matches on the exact request parameters defined in each
API model file, under the `x-amzn-api-sandbox.static.request.parameters` object.
Real/arbitrary dates do NOT work - only these exact fixture values return 200:

- **getOrderMetrics**: `marketplaceIds=ATVPDKIKX0DER`,
  `interval=2022-08-01T00:00:00-07:00--2022-08-02T00:00:00-07:00`, `granularity=Total`
- **listFinancialEventGroups**: `MaxResultsPerPage=1`,
  `FinancialEventGroupStartedAfter=2019-10-13`, `FinancialEventGroupStartedBefore=2019-10-31`
  (source: `finances-api-model/financesV0.json`)
- **listTransactions**: `postedAfter=2023-03-07`,
  `nextToken=jehgri34yo7jr9e8f984tr9i4o`
  (source: `finances-api-model/finances_2024-06-19.json`)

Note: `listFinancialEventGroups` with ONLY `MaxResultsPerPage=10` is deliberately
the sandbox's HTTP 400 "Date range is invalid" fixture - that is why early attempts
with that shape failed. The model file is the source of truth for the 200 fixture.

## Two different things are both called "sandbox" (do not confuse them)

1. **Developer Hub "Try It!" documentation mock.** The reference-page code sample
   (e.g. `?MaxResultsPerPage=10` with only an `accept` header and NO
   `x-amz-access-token`) returns a placeholder schema body (`"string"`, `0`) for
   any input. This is the docs' renderer, not the API, and proves nothing.
2. **Real SP-API static sandbox** (`https://sandbox.sellingpartnerapi-na.amazon.com`).
   Requires a real LWA access token and matches the model-file fixtures above. The
   `*.json` captures in this folder are from this real sandbox.

## What this validates

- Live LWA authentication and the full signed call path work for all three
  operations (HTTP 200, each with an Amazon request id).
- The `listTransactions` 200 body carries `ORDER_ID` and `FINANCIAL_EVENT_GROUP_ID`
  related identifiers plus `totalAmount` - the same fields that map into the
  financing-request / Super PO contract (`SuperPoRequestDto.orderIds`,
  `totalOrderAmount`, etc.). The financing request itself is produced and uploaded
  by Dowsure; the real Dowsure sample payloads (served by the `sam/mocks/amazon-sp`
  mocks) carry production-shaped values for full field validation. The sandbox 200s
  prove the live source call path; the mocks carry the Dowsure field data.

## How to reproduce

Fill `.env` with `LWA_CLIENT_ID`, `LWA_CLIENT_SECRET`, `LWA_REFRESH_TOKEN`,
`AMAZON_SP_BASE_URL=https://sandbox.sellingpartnerapi-na.amazon.com`, then issue the
three fixture requests above with an `x-amz-access-token` minted from the LWA
refresh-token exchange at `https://api.amazon.com/auth/o2/token`.
