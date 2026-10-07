# Source endpoints and the Super PO eligibility formula

> **Read this first (plain English).** This page answers one question: *where does
> the data come from?* The Olea-Dowsure oracle proves facts about a seller's
> Amazon business. Those facts live behind Amazon's **SP-API (Selling Partner
> API** - Amazon's seller data API), plus a set of Chinese identity and judicial
> services used for **KYC (Know Your Customer** - the checks that confirm who a
> borrower is). This page maps each kind of data to the exact Amazon operation
> that supplies it, warns about one easy mistake (Order Metrics is **not** the
> financing data source), and records the Super PO (Super Purchase Order) money
> formula word for word. For status facts (what is built vs planned) see the
> [project status matrix](./PROJECT_STATUS_MATRIX.md). For how these endpoints are
> used end to end, see [the flows](./FLOWS.md).

## Acronyms used on this page (expanded on first use)

- **SP-API (Selling Partner API)** - Amazon's data API for sellers. All Amazon
  data below is read through it.
- **LWA (Login with Amazon)** - the sign-in and authorization step a seller
  completes so Dowsure can read that seller's SP-API data on their behalf.
- **PO (Purchase Order)** - an order record. **Super PO (Super Purchase Order)** -
  one aggregated financing record that groups many underlying orders from a single
  drawdown.
- **KYC (Know Your Customer)** - the identity, enterprise, and judicial checks that
  confirm who a borrower is and whether they carry legal risk.
- **TLS+TEE** - a Trusted Execution Environment (a tamper-proof enclave) reached
  over an encrypted TLS connection. See [the flows](./FLOWS.md) for the full trust
  path.

---

## 1. The source map (four data categories plus KYC)

Each row below is one category of data and the single Amazon operation (or external
service) that is the source of truth for it. There is **no raw HTML** here on
purpose - just the operation, the fields that matter, and a plain note.

| # | Data category | Source operation | Returns (key fields) | Status note |
| --- | --- | --- | --- | --- |
| a | **Shop-level FINANCIALS** | Financial Event Groups: `GET /finances/v0/financialEventGroups` (`listFinancialEventGroups`) | Settlement / fund-transfer groups: `FinancialEventGroupId`, `OriginalTotal`, `FundTransferStatus`, `FundTransferDate` | **Proven live** (202 ACCEPTED) via TLS-in-TEE. Supplies shop-level money movement. |
| b | **Shop-level SALES** | Order Metrics: `GET /sales/v1/orderMetrics` (`getOrderMetrics`) | Daily aggregate: `unitCount`, `orderCount`, `averageUnitPrice`, `totalSales` | **Proven live** (202 ACCEPTED). Was the bounded first probe; now one of the 7 live calls. A sales signal, not the financing input. |
| c | **PO-level data** (the `canonical_data` field in financing requests) | Transactions: `GET /finances/2024-06-19/transactions` (`listTransactions`) | Per-transaction detail with a `breakdowns` tree (Sales, Expenses, AmazonFees / Commission, Tax, Shipping), transformed into business records: `seller_id`, `transaction_id`, `sales_amount`, `expenses_amount`, `commission_amount`, `order_id` | **Proven live** (202 ACCEPTED). This is the real financing data source. |
| d | **REPAYMENT** | Amazon repayment-plan API (**PRIVATE**) | Repayment schedule and deduction results | **The repayment-plan API is PRIVATE. Amazon does NOT allow Dowsure to share it with any third party. Olea can ONLY view repayment results inside a TLS+TEE (Trusted Execution Environment). The Nitro Enclave is therefore a HARD CONTRACTUAL requirement for this category - not an optional optimization.** Flow documented; not yet built. See [Repayment flow](./FLOWS.md#flow-3-repayment-and-why-it-is-tee-only). |
| e | **KYC (Know Your Customer)** | AliCloud three-element verification (`qrymobile.market.alicloudapi.com`); Qichacha enterprise + judicial checks (`EnterpriseInfo` / `Verify`, `ShixinCheck`, `ZhixingCheck`, `SumptuaryCheck`, `BankruptcyCheck`); Gutu / valuemap judicial panorama (`turningapi.valuemap.cn` panorama-checks) | Identity match, enterprise status, and judicial-risk signals (credentials stripped) | **Source calls proven live** (4 calls, 202 ACCEPTED each): `alicloudTelThree`, `qichachaEnterpriseVerify`, `qichachaShixinCheck`, `gutuPanoramaChecks`. The ~16-rule decision engine remains Phase 2. |

> **Do NOT confuse (b) with (c).** Order Metrics (`getOrderMetrics`) alone is
> **NOT the financing data source.** PO-level financing uses the
> **Transactions API** (`listTransactions`); shop financials use
> **Financial Event Groups**. The repo
> first probed Order Metrics only as a *bounded test* of the enclave path, not
> because Order Metrics is the financing input.

---

## 2. What each Amazon source looks like (distilled)

These summaries are distilled from the integration references. Every example value
is a placeholder (`<sellerId>`, `<marketplaceId>`, `<amount>`). No real seller ID,
personal data, or sample day-by-day numbers are reproduced here.

### 2a. Transactions - `listTransactions` (PO-level, the real financing source)

- **Operation:** `GET /finances/2024-06-19/transactions`
- **Purpose:** per-transaction detail for a seller, which Dowsure turns into
  order-level financing records.
- **Transform:** each transaction carries a `breakdowns` tree. The categories in
  that tree are summed into business fields:

  | `breakdowns` category | Business field |
  | --- | --- |
  | Sales | `sales_amount` |
  | Expenses | `expenses_amount` |
  | AmazonFees / Commission | `commission_amount` |
  | Tax, Shipping | folded into the per-order economics |

- **Output record (placeholders):**

  ```json
  {
    "seller_id": "<sellerId>",
    "transaction_id": "<transactionId>",
    "order_id": "<orderId>",
    "sales_amount": "<amount>",
    "expenses_amount": "<amount>",
    "commission_amount": "<amount>"
  }
  ```

### 2b. Financial Event Groups - `listFinancialEventGroups` (shop financials)

- **Operation:** `GET /finances/v0/financialEventGroups`
- **Purpose:** settlement and fund-transfer groups at the shop level - how money
  actually moved to the seller.
- **Key fields:** `FinancialEventGroupId`, `OriginalTotal`, `FundTransferStatus`,
  `FundTransferDate`.

### 2c. Order Metrics - `getOrderMetrics` (shop sales - the bounded PoC probe)

- **Operation:** `GET /sales/v1/orderMetrics`
- **Purpose:** daily aggregate sales for a shop. It was the bounded first probe and
  is now one of the 7 live source calls (see
  [the status matrix](./PROJECT_STATUS_MATRIX.md)); it is a sales signal, **not** the
  financing input.
- **Request shape:** a `GET` with query parameters only (no JSON body) -
  `marketplaceIds=<marketplaceId>`, an `interval` (start inclusive, end exclusive),
  `granularity=Day`, `granularityTimeZone=UTC`, `buyerType=All`. The seller is
  identified by the SP-API authorization context, not a query parameter.
- **Transform:** each daily element is flattened into one record. Illustrative
  shape with placeholders:

  | Source field | Output field | Note |
  | --- | --- | --- |
  | authorization context | `seller_id` | from the LWA (Login with Amazon) context, not a parameter |
  | `marketplaceIds[0]` | `marketplace_id` | retained |
  | start of `interval` | `sale_date` | first UTC date, `YYYY-MM-DD` |
  | `unitCount` | `unit_count` | direct |
  | `orderItemCount` | `order_item_count` | direct |
  | `orderCount` | `order_count` | direct |
  | `averageUnitPrice.amount` | `average_unit_price` | string to decimal |
  | `averageUnitPrice.currencyCode` | `average_unit_price_currency` | ISO code kept separately |
  | `totalSales.amount` | `total_sales` | string to decimal |
  | `totalSales.currencyCode` | `total_sales_currency` | ISO code kept separately |

  ```json
  {
    "seller_id": "<sellerId>",
    "marketplace_id": "<marketplaceId>",
    "sale_date": "<YYYY-MM-DD>",
    "unit_count": "<count>",
    "order_count": "<count>",
    "average_unit_price": "<amount>",
    "total_sales": "<amount>"
  }
  ```

---

## 3. Super PO eligibility formula (recorded exactly)

This is the money math that decides which orders a drawdown can be financed
against. It is reproduced here exactly; [the Super PO flow](./FLOWS.md#flow-2-financing-request-and-super-po)
links back to this section instead of restating it.

**Per-order eligible principal:**

```
EligiblePrincipal(i) = round((OrderAmount(i) - Shipping(i)) * (1 - ReturnRate) * DisbursementRate * DilutionRate, 2)
```

**DisbursementRate:**

- **Standard:** `DisbursementRate = 1.00`.
- **Deferred:** `DisbursementRate = min(1.00, (Q1 Disbursements + Q2 Disbursements) / (Q1 Sales + Q2 Sales))`.
  If total sales are 0, the rate is 0.

**Selection and funding:**

- `Target = remaining financing amount`.
- Select orders until `sum(EligiblePrincipal(i)) >= Target`.
- `FundedAmount(i) = min(EligiblePrincipal(i), remaining Target)`.

**Worked example (from the source):**

- `Target = 100`.
- Eligible principal across the selected orders: `40 + 35 + 30 = 105`.
- Funded amounts: `40 + 35 + 25 = 100` (the third order is capped so the total
  lands exactly on the target).
- If the selected orders do **not** cover the target, Dowsure does **not** submit
  an Olea order batch in that cycle.

---

## 4. Open questions (not yet answered - do not overclaim)

These are genuinely open and must not be presented as settled:

- **Recalculation frequency is NOT yet answered by Dowsure.** It is unknown whether
  the eligibility check / return-rate / disbursement-rate / limit recalculation runs
  **weekly (Monday)** or **daily**.
- **Exact API-to-onboarding-dataset mapping is pending Dowsure confirmation** -
  which SP-API operation maps to which onboarding dataset is not finally confirmed.
- End-to-end onboarding examples for the two named sample suppliers
  (*Quanzhou Feile E-commerce Co., Ltd* and *Xiamen Yunyu Tianji Network Technology
  Co., Ltd* - these are **test-case labels only**, no other data about them) are
  still outstanding.

---

## See also

- [FLOWS.md](./FLOWS.md) - every flow end to end, each step tagged
  IMPLEMENTED / PLANNED / BLOCKED.
- [PROJECT_STATUS_MATRIX.md](./PROJECT_STATUS_MATRIX.md) - the single source of
  truth for status facts (enclave fingerprints, host, verified IDs).
