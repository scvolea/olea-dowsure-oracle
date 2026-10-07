# Engagement context — how this project started and why TLS+TEE

> **Read this first (plain English).** The rest of the docs explain *what* the
> oracle does and *how* it works. This page explains *why it exists at all*: the
> business conversation between Olea and Dowsure that chose a TLS + TEE (Trusted
> Execution Environment) data-integrity solution, the alternatives that were
> rejected, who owns which action, and the ground-truth business flows Dowsure
> shared. It is the origin record several existing decisions quietly rest on - in
> particular [why repayment is TEE-only](./FLOWS.md#flow-3-repayment-and-why-it-is-tee-only)
> and [why KYC is a confirmed Phase-2 consumer](./FLOWS.md#flow-5-kyc-phase-2-tlstee-consumer).
>
> This page paraphrases an internal Olea/Dowsure email thread and three
> business-flow references. No credentials, tokens, or personal data are
> reproduced; seller-identifying values are shown as placeholders.

## Acronyms used on this page (expanded on first use)

- **TLS+TEE** - a Trusted Execution Environment (a tamper-proof enclave) reached
  over an encrypted TLS connection; the common solution chosen below.
- **SP-API (Selling Partner API)** - Amazon's seller data API.
- **KYC (Know Your Customer)** - identity, enterprise, and judicial checks on a
  borrower.
- **FR Invoice** - Financing Request Invoice. **Super PO (Super Purchase Order)** -
  one aggregated financing record grouping many order-level records from a single
  drawdown.

---

## 1. The problem Olea raised

Today Olea receives Amazon SP-API data, third-party KYC data, and the repayment
schedule Dowsure sends to Amazon **through Dowsure**. A Dowsure signature proves
Dowsure sent the data; it does not prove the data genuinely came from Amazon (or
the KYC provider) unaltered and complete. Olea wanted a way to check the integrity
of third-party data for itself rather than trusting the intermediary's word.

## 2. The three options considered (21 September 2026 call)

Olea and Dowsure reviewed three approaches to third-party data-integrity checking:

1. **A common TLS + TEE solution** covering every data-integrity check - Amazon
   SP-API data, third-party KYC data, and the repayment schedule Dowsure sends to
   Amazon. **This was the preferred option for both teams.**
2. **A Dowsure-side review role:** Dowsure grants Olea a user role under Dowsure's
   third-party account so Olea can inspect the history of records between Dowsure
   and the third party and compare them against what Dowsure forwards to Olea.
3. **An Olea-owned third-party account:** Olea creates its own account on the
   third-party platform, and Dowsure uses Olea's account to call the third-party
   API and run the KYC checks.

**Decision:** both teams preferred option 1, the common TLS + TEE solution, because
one mechanism can cover all three data-integrity needs instead of a per-source
patchwork. This is the engagement-level reason the whole oracle exists.

## 3. Action items from the call

Owners and target dates as agreed on the call (names kept at role level where they
matter for accountability):

| # | Item | Team | Target |
| --- | --- | --- | --- |
| 1 | Share the list of Amazon APIs Dowsure currently uses, the process mappings, available data details, sample payloads, and API documentation links | Dowsure | 2026-09-23 |
| 2 | Confirm with the wider group whether to apply the common TLS + TEE solution for all data-integrity checks | Olea | 2026-09-22 |
| 3 | If item 2 is confirmed, prepare sample code and a design document for the TLS + TEE solution and share it with Dowsure to review | Olea | 2026-09-22 |
| 4 | Check with Dowsure's commercial team whether the current data Dowsure passes to Olea in the financing flow - which API call and which parameters - can be shared | Dowsure | 2026-09-23 |
| 5 | After Olea provides the TLS + TEE technical solution, evaluate the engineering effort and provide an estimated timeline | Dowsure | 2026-09-23 |

**Status of item 3 (the Olea deliverable):** done. Olea produced the design and
flow document for the TLS + TEE solution - the verifiable-data-oracle approach
built on AWS Nitro Enclaves with challenge-response validation, attestation, and
evidence submission - and shared it with Dowsure for review. Anudeep (Olea DevOps)
was added to the thread as the technical owner on the Olea side. The follow-up
(items 1, 4, 5) and the joint integration schedule sit with Dowsure; see the
dependency note in
[the status matrix](./PROJECT_STATUS_MATRIX.md#plain-english-summary) and
[Decision 9 in the opinions log](../opinions.md#decision-9---the-integration-environment-dependency-on-dowsure-not-reassigned).

## 4. Ground-truth business flows Dowsure shared

Dowsure provided three business-flow references plus an architecture diagram. They
are the ground truth the technical flows in [FLOWS.md](./FLOWS.md) and the source
map in [SOURCE_ENDPOINTS.md](./SOURCE_ENDPOINTS.md) are traced against. The raw
HTML lives in [`api-mocks/`](../api-mocks); the distilled meaning is below (every
seller value is a placeholder).

### 4a. Amazon US order / finance onboarding

Three onboarding datasets, each with a real source operation and a documented
transformation:

- **Order Metrics** - `GET /sales/v1/orderMetrics` (`getOrderMetrics`): daily
  shop-level sales aggregate (`unitCount`, `orderCount`, `averageUnitPrice`,
  `totalSales`). A **sales signal, not the financing input** - it was the bounded
  first probe and is now one of the 7 live source calls.
- **Financial Event Groups** - `GET /finances/v0/financialEventGroups`
  (`listFinancialEventGroups`): shop-level settlement / fund-transfer groups
  (`FinancialEventGroupId`, `OriginalTotal`, `FundTransferStatus`,
  `FundTransferDate`).
- **Transactions** - `GET /finances/2024-06-19/transactions` (`listTransactions`):
  per-transaction detail with a nested `breakdowns` tree (Sales, Expenses,
  AmazonFees / Commission, Tax, Shipping) flattened into order-level business
  records. **This is the real PO-level financing source.**

The field-by-field transformations for all three are already recorded in
[SOURCE_ENDPOINTS.md](./SOURCE_ENDPOINTS.md#2-what-each-amazon-source-looks-like-distilled).

### 4b. Super PO financing-request flow

After Dowsure approves a merchant's credit, the merchant draws down; Dowsure pulls
seller-authorized SP-API **Transactions** data, selects eligible orders until their
principal covers the remaining financing target, submits an **FR Invoice** batch to
Olea, and - once order-level records are confirmed - aggregates them into one
**Super PO** per drawdown. The relationship is: **one drawdown -> one Super PO ->
many order-level records.** The exact eligibility / funding math is recorded word
for word in
[the Super PO eligibility formula](./SOURCE_ENDPOINTS.md#3-super-po-eligibility-formula-recorded-exactly).

### 4c. Repayment flow (the reason repayment is TEE-only)

When a supplier initiates a pay-in on Dowsure, a repayment schedule is generated;
Dowsure sends it to Amazon daily and Amazon deducts funds against it, then notifies
and returns a deducted payload. Reconciliation keys line up across the stages
(the notification `repaymentId` matches the schedule's `repaymentScheduled`; the
deducted payload's `referenceResourceId` matches the notification `repaymentId`;
the `reconciliationId` matches the bank reconciliation ID).

**Why this forces the enclave:** Amazon's repayment-plan API is **private**, and
Amazon does **not** permit Dowsure to expose it to a third party. Olea may only
view repayment results **inside a combined TLS + TEE environment** - Dowsure cannot
hand Olea an ordinary API feed for this category. The Nitro Enclave is therefore a
**hard contractual requirement** here, not an optimization. This is the business
basis for
[Decision 5 in the opinions log](../opinions.md#decision-5---repayment-must-be-tee-only)
and [Flow 3](./FLOWS.md#flow-3-repayment-and-why-it-is-tee-only).

### 4d. Amazon SP trusted data-delivery architecture (diagram)

Dowsure also shared a reference architecture for a shared "Amazon SP Data Fetch
Service": a single fetch service used by both Dowsure and Olea that handles
permission validation, access-token handling, the SP-API client, and raw
response pass-through, with Redis for unified rate limiting / token counting /
distributed locking, and **separate Dowsure and Olea databases** so raw data is
stored independently on each side. Its stated properties are HTTPS transport,
token encryption, access control and audit logging, data isolation, multi-node
high availability, end-to-end traceability, and multi-region support. This is
Dowsure's delivery-architecture framing; the Olea verifiable-oracle design is the
**integrity** layer that sits on top of whatever delivery path is used.

## 5. How this maps to what is built

- The engagement chose **one common TLS + TEE mechanism** for all three data
  categories (Amazon SP-API, KYC, repayment). The repo proves that mechanism via
  **TLS-in-TEE**: the Nitro enclave terminates TLS to each source itself (the
  external TLSNotary source-proof slice described in §6a was built first and is now
  historical reference). See [the status matrix](./PROJECT_STATUS_MATRIX.md).
- **All 7 source calls** (3 Amazon SP-API + 4 KYC) are proven live with 202
  ACCEPTED; Order Metrics was the bounded first probe and is now one of the 7. See
  [Decision 3](../opinions.md#decision-3---use-getordermetrics-as-the-first-bounded-probe-then-re-aim-at-transactions-and-financial-event-groups).
- **KYC** is a confirmed Phase-2 consumer of the same solution, documented at
  flow / decision-model level only. See
  [Decision 10](../opinions.md#decision-10---kyc-is-a-confirmed-phase-2-tlstee-consumer-documented-at-flow-level-only).
- **Repayment** is TEE-only by contract, as explained in 4c above.

## 6. The two candidate trust architectures (CTO solutioning)

Before implementation, two candidate architectures for the integrity layer were
worked through at the CTO level. Both share the same shape - Amazon on the left, a
**Dowsure-hosted environment** in the middle, **Olea** verifying at the bottom -
and both are **fail-closed**: if the trust anchor is unreachable or the check does
not pass, the data is rejected rather than silently accepted unverified. They
differ in *what provides the proof*. This section records that design reasoning;
it contains **no data transformations** (those live only in section 4a and
[SOURCE_ENDPOINTS.md](./SOURCE_ENDPOINTS.md)).

> Caveat recorded at the time: the leading open implementation of the zkTLS
> pattern (the `tlsn` / TLSNotary project) is a young protocol, strongest on
> TLS 1.2-style flows. The guidance was to treat the design as a blueprint and run
> a feasibility spike against the real Amazon endpoint before committing build
> effort - which is exactly what the MPC-TLS compatibility finding later did (the
> finding is kept in local notes only, not committed).

### 6a. Approach A - zkTLS / TLSNotary (a notary witnesses the TLS session)

A **prover** inside the **Dowsure environment** calls the Amazon API through the
TLSNotary flow instead of a plain fetch, producing a **proof bundle** (the response
data plus a notarized session transcript plus the notary's signature). An
**Olea-hosted notary server** participates in the MPC-TLS handshake and signs the
transcript hash; an **Olea verifier** checks every incoming bundle.

- **Trust anchor:** the **notary's public key**, published independently of any
  session. The verifier checks every bundle against it - that is what stops a
  client from forging its own "notary." (In the diagram this is the dashed line.)
- **The verifier's three checks, all required:** (1) notary signature is valid;
  (2) the certificate shows the session was with the expected Amazon domain;
  (3) the signature came from *our* pinned notary key specifically. Skipping any
  one reopens a gap.
- **Olea builds/operates:** the notary server (always-on - the client's call blocks
  on it each request), an HSM-backed notary signing key with a rotation schedule, a
  TLS-forwarding proxy the prover routes through, the verifier/ingestion service,
  independent publication of the notary public key, and pinned Amazon certificate
  details.
- **Dowsure does:** replace the plain Amazon call with a prover-wrapped call (a real
  code change), point the prover at Olea's notary + proxy URLs, send the proof
  bundle on an agreed schema, accept the added latency and the live-connection /
  fail-closed requirement, and route **all** relevant Amazon traffic through this
  path (no unnotarized side path).
- **In this repo:** this approach was built first (the Rust prover sidecar, the
  Olea-hosted notary, and the nonce-bound verifier) and is now **historical
  reference** — it was superseded by Approach B taken to its conclusion (the enclave
  terminating TLS itself, TLS-in-TEE). See
  [Decision 4](../opinions.md#decision-4---tlsnotary-is-now-historical-reference-superseded-by-tls-in-tee),
  [Decision 11](../opinions.md#decision-11---converted-from-mpc-tlsnotary-to-tls-in-tee-enclave-terminates-tls-itself),
  and [archive/TLSNOTARY.md](../archive/TLSNOTARY.md).

### 6b. Approach B - Nitro TEE (a sealed enclave fetches and signs)

Instead of a notary witnessing the session, a **sealed Nitro Enclave** does the
fetching and processing itself, and **AWS KMS is the trust anchor**: KMS releases
the signing key **only** to an enclave whose measured code hash (PCR) matches a
pre-approved value.

- **Trust anchor:** the **attestation-gated KMS key policy**. The enclave's measured
  code (PCR0/1/2) is bound into a KMS condition; if the code is modified - or it is
  not genuinely a Nitro enclave - KMS refuses to release the key, so there is no
  valid signature and no accepted payload.
- **Flow:** the enclave fetches Amazon over the parent instance's `vsock-proxy`
  (its only path out - no direct networking, no persistent storage, no shell),
  hashes and processes the response, asks KMS to sign (the attestation document
  rides along automatically), and emits `{data, signature, attestation}`. The
  **Olea verifier** checks the attestation against AWS's published Nitro root
  certificate and the approved PCR values before trusting anything.
- **Olea builds/operates:** the enclave application code (the single most important
  control - the PCRs lock exactly this code in place), the reproducible EIF build +
  recorded PCRs per release, the KMS key and its attestation-gated policy, and the
  verifier.
- **Dowsure does (if Dowsure hosts the enclave):** provision a Nitro-enabled EC2
  instance, run `vsock-proxy` on the parent, launch Olea's **exact unmodified**
  signed EIF (enforced technically - any change alters the PCR and KMS withholds the
  key), grant the parent role permission to call the KMS key for attested signing,
  keep Amazon credentials available to the enclave, and route **all** relevant
  Amazon traffic through the enclave.
- **Hosting choice (explicit decision needed):** Olea-hosted enclave is closest to
  delegated access (strongest trust picture, removes the Dowsure provisioning
  steps - Dowsure then forwards only a scoped credential in); Dowsure-hosted still
  gives strong code-integrity guarantees but leaves more of the surrounding trust
  picture on the Dowsure side. The responsibility split is also recorded in
  [IMPLEMENTATION_AGENT_HANDOFF.md](../IMPLEMENTATION_AGENT_HANDOFF.md).

### 6c. Why the repo ended up with both, bound together

The engagement chose the **common TLS + TEE** mechanism (section 2), and the two
approaches above target two properties: Approach A proves **source authenticity**
(the bytes really came from Amazon over TLS) and Approach B proves **execution
authenticity** (approved, unmodified code processed them). The repo first built both
and bound them to the same bytes; it then converted to **TLS-in-TEE**, where
Approach B's enclave terminates TLS itself and therefore provides *both* properties
from a single boundary — so the external notary (Approach A) is no longer needed on
the live path. This is the business/architecture basis for
[Decision 1 (use a Nitro Enclave with attestation at all)](../opinions.md#decision-1---use-a-nitro-enclave-with-attestation-at-all),
which spells out why a plain signature gives only attribution, not origin or
execution identity.

## See also

- [README.md](../README.md) - repository overview and document index.
- [PROJECT_STATUS_MATRIX.md](./PROJECT_STATUS_MATRIX.md) - single source of truth
  for status facts.
- [SOURCE_ENDPOINTS.md](./SOURCE_ENDPOINTS.md) - the data source map and the
  Super PO eligibility formula.
- [FLOWS.md](./FLOWS.md) - the end-to-end flows these business flows are traced
  against.
- [opinions.md](../opinions.md) - the decision and reasoning log.
- [`api-mocks/`](../api-mocks) - the raw ground-truth HTML flow references.
