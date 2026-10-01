# Opinions - the decision and reasoning log

> **What this file is (read this first).** This is a plain-language log of the
> decisions behind this project: for each one it records **what** was decided or
> done, **why**, and - where it matters - **why something was deliberately NOT
> done** or **NOT reassigned**. It is a record of real decisions, not a wish list.
> Everything here is factual as of the last update. It does not describe features
> we hope to build; it describes choices we actually made and the reasons for them.
>
> **How to read an entry.** Each entry has a short **Decision** (what we chose),
> a **Why** (the reason), and sometimes a **Why not** (what we chose against, and
> why). Where a decision rests on a verified fact, the entry links to the document
> that owns that fact rather than repeating it. The single source of truth for
> status facts (the enclave fingerprint and register values) is
> [docs/PROJECT_STATUS_MATRIX.md](docs/PROJECT_STATUS_MATRIX.md); the exact
> fingerprint and register values are only defined there and are not restated here.

## Acronyms used on this page (expanded on first use)

- **PoC** - Proof of Concept (a small working demonstration, not production).
- **TEE** - Trusted Execution Environment (a protected area of a computer where
  code and data are shielded even from the machine's own operator).
- **Nitro Enclave** - Amazon's hardware-backed TEE: an isolated virtual machine
  with no network, no persistent storage, and no interactive access.
- **Attestation** - a signed document the enclave produces that proves which exact
  code is running inside it.
- **EIF** - Enclave Image File (the packaged, measurable image that boots inside
  the enclave).
- **PCR** - Platform Configuration Register (a measurement value that fingerprints
  the exact image and environment; identical code produces identical PCRs).
- **SP-API** - Selling Partner API (Amazon's official seller-data interface).
- **TLS** - Transport Layer Security (the encryption behind `https`).
- **TLSNotary** - a technique that produces a proof that a specific `https`
  response really came from a specific server, without trusting the client.
- **PO** - Purchase Order.
- **Super PO** - Super Purchase Order (one aggregated financing order built from
  many order-level records in a single drawdown).
- **FR Invoice** - Financing Request Invoice.
- **KYC** - Know Your Customer (identity and background checks).
- **SBOM** - Software Bill of Materials (a machine-readable list of everything a
  build contains).
- **vsock / AF_VSOCK** - virtual socket, the only channel in and out of a Nitro
  Enclave (there is no network).
- **CID** - Context Identifier (the vsock address number of the enclave).

---

## Decision 1 - Use a Nitro Enclave with attestation at all

**Decision.** Run the sensitive data-handling step inside an Amazon Nitro Enclave
(a hardware-backed Trusted Execution Environment) and have it emit a signed
attestation document.

**Why.** We need two different guarantees, and a plain signature only gives one.
A Dowsure signature on data proves **attribution** - that Dowsure sent it - but it
does not prove the data actually came out of Amazon. The enclave separates
**source authenticity** (the data genuinely came from Amazon over TLS) from
**execution authenticity** (a known, unmodified piece of code processed it). The
attestation document is what carries execution identity: it names the exact code
that ran. That is a property no signature-after-the-fact can provide.

**Why not just a signature.** A signature alone could be applied to any data,
including data that never came from Amazon. It cannot prove where the data
originated or that it was untouched. Hardware-backed execution identity closes
that gap.

Evidence: the verified live attestation (signature chain, measurement values,
attested public key, canonicalized user data) is recorded in
[docs/PROJECT_STATUS_MATRIX.md](docs/PROJECT_STATUS_MATRIX.md#verified-facts-defined-here-linked-everywhere-else)
and the trust flow is traced in
[docs/FLOWS.md](docs/FLOWS.md#flow-4-tlstee--nitro-attestation-trust-flow).

## Decision 2 - Move the enclave from Python to Java

**Decision.** The verified, release-registered enclave is the Java build
(`olea-orders-java`). The earlier Python enclave image is not treated as evidence
for the Java system.

**Why.** The phase-4 EIF that was actually built, measured, and registered as
ACTIVE in the preprod release registry is the Java one. Its measurement values
(the PCRs) belong to that Java image. The old Python EIF produced different
measurements, so using Python PCR values to vouch for the Java enclave would be
claiming a fingerprint for the wrong image.

**Why not keep the Python evidence.** Measurements are image-specific by design.
Mixing the Python measurement set with the Java runtime would be a false
attestation claim - the whole point of the enclave is defeated if the evidence
does not match the running code.

Evidence: the verified EIF fingerprint and PCR values are defined once in
[docs/PROJECT_STATUS_MATRIX.md](docs/PROJECT_STATUS_MATRIX.md#verified-facts-defined-here-linked-everywhere-else)
and are not restated here.

## Decision 3 - Use getOrderMetrics as the first bounded probe, then re-aim at Transactions and Financial Event Groups

**Decision.** The first end-to-end probe targeted `getOrderMetrics`
(`GET /sales/v1/orderMetrics`). The next slice must re-aim at **Transactions**
(`listTransactions`) and **Financial Event Groups** (`listFinancialEventGroups`).

**Why getOrderMetrics first.** It returns the smallest, non-personal aggregate
(daily counts and totals). That made it the quickest, lowest-risk way to prove the
whole path works - request in, data out, attestation produced - without touching
any sensitive detail.

**Why re-aim now.** Order Metrics alone is not the financing data source. The
PO-level data that financing requests are built from comes from **Transactions**;
the shop-level financial settlement data comes from **Financial Event Groups**.
Order Metrics stays useful as a sales signal, but it cannot stand in for the
financing sources.

**Why not stay on Order Metrics.** Continuing to probe only Order Metrics would
keep proving a path that does not reach the data the product actually needs.

Evidence: the source map and what each endpoint returns are in
[docs/SOURCE_ENDPOINTS.md](docs/SOURCE_ENDPOINTS.md#1-the-source-map-four-data-categories-plus-kyc);
the implemented-vs-planned split is in
[docs/FLOWS.md](docs/FLOWS.md#flow-2-financing-request-and-super-po).

## Decision 4 - Keep TLSNotary as a placeholder that fails closed

**Decision.** The TLSNotary proof step is a placeholder. The code enforces a
proof contract (it checks the proof type, the proof hash, and that the response
hash matches the raw response hash) and **refuses** to pass data through as proven
when a real proof is absent. It fails closed rather than faking success.

**Why.** There is no approved notary or prover infrastructure, and no approved
notary public key, available in this repository. Without those, a genuine
TLSNotary proof cannot be produced or verified here.

**Why not fake it.** Emitting a "valid" result for unproven data would be worse
than emitting nothing: it would make unverified data look verified. Failing closed
keeps the system honest - unproven data never gets to claim it was notarized.

Evidence: the TLSNotary placeholder is listed as still-open in
[docs/PROJECT_STATUS_MATRIX.md](docs/PROJECT_STATUS_MATRIX.md#status-table)
and the proof-contract behavior is traced in
[docs/FLOWS.md](docs/FLOWS.md#flow-4-tlstee--nitro-attestation-trust-flow).

## Decision 5 - Repayment must be TEE-only

**Decision.** Repayment data is handled only inside the TLS + TEE path. The
enclave is a hard requirement for this category, not an optimization.

**Why.** This is contractual, not a performance choice. Amazon's repayment-plan
interface is private, and Amazon does not allow Dowsure to share it with third
parties. Olea can only view repayment results inside a combined TLS + TEE
environment. So the enclave is the only lawful way to see this category at all.

**What that implies.** Because the constraint is contractual, the enclave cannot
be removed or swapped for a cheaper path for repayment. It is a prerequisite, full
stop.

**Why not treat it like the other categories.** The other categories could, in
principle, be read without a TEE (we choose the TEE for integrity). Repayment
cannot - the TEE is mandatory by agreement.

Evidence: the repayment flow and the reason it is TEE-only are in
[docs/FLOWS.md](docs/FLOWS.md#flow-3-repayment-and-why-it-is-tee-only)
and the source map marks repayment as TEE-only in
[docs/SOURCE_ENDPOINTS.md](docs/SOURCE_ENDPOINTS.md#1-the-source-map-four-data-categories-plus-kyc).

## Decision 6 - Do NOT build a local Amazon API mock for the next slice

**Decision.** The next slice uses the real Amazon SP-API sandbox, which is now
available with credentials. We will not build a new local mock of the Amazon API
for it.

**Why.** The sandbox exercises real endpoints, real TLS, and the real request
shapes. A local mock would prove none of that - it would only confirm that our own
mock matches our own expectations.

**Why not a local mock.** A mock cannot surface endpoint, TLS, or proof-
compatibility problems, which are exactly the risks the next slice needs to flush
out. (The existing bounded mock path remains only as the current implemented step;
it is not the target for the next slice.)

Evidence: the sandbox-now-available correction and the planned endpoints are
reflected in
[docs/SOURCE_ENDPOINTS.md](docs/SOURCE_ENDPOINTS.md#1-the-source-map-four-data-categories-plus-kyc)
and
[docs/FLOWS.md](docs/FLOWS.md#flow-2-financing-request-and-super-po).

## Decision 7 - The Step Functions / API Gateway parent is still a transitional fixture

**Decision.** The orchestration parent (an AWS Step Functions / API Gateway
stand-in) is a temporary fixture. It must be replaced by the real coordinator
path.

**Why.** Integrating the real coordinator is open engineering work that is not
finished. The current fixture reports completion after a single poll, which is
enough to demonstrate the shape of the flow but is not the real control path.

**Why not call it done.** The fixture does not do the real orchestration - it
short-circuits to a "complete" result. Treating it as finished would overstate
what exists. It is explicitly transitional.

Evidence: this is recorded as still-open work in
[docs/PROJECT_STATUS_MATRIX.md](docs/PROJECT_STATUS_MATRIX.md#status-table)
and the next-step guidance is in
[IMPLEMENTATION_AGENT_HANDOFF.md](IMPLEMENTATION_AGENT_HANDOFF.md).

## Decision 8 - What is intentionally left for Phase 2, and why

**Decision.** The following are deliberately deferred to Phase 2:
production multi-region hardening; broad endpoint and provider expansion;
reports and presigned download links (presigned S3 downloads); SBOM (Software
Bill of Materials) automation; and KYC (Know Your Customer) code integration.

**Why.** The goal of this phase is a bounded, honest Proof of Concept. Each
deferred item adds surface area that would dilute that focus without changing
whether the core trust path works. Keeping them out keeps the PoC small enough to
reason about and verify.

**Why not do them now.** Doing them early would spend effort hardening and
broadening a path whose core still has open items (a real notary proof, the real
coordinator, the finances reorientation). Those come first.

Evidence: the open-versus-done split is in
[docs/PROJECT_STATUS_MATRIX.md](docs/PROJECT_STATUS_MATRIX.md#status-table)
and next steps are in
[IMPLEMENTATION_AGENT_HANDOFF.md](IMPLEMENTATION_AGENT_HANDOFF.md).

## Decision 9 - The integration-environment dependency on Dowsure (not reassigned)

**Decision.** Anudeep Sai Nunna is Olea's named owner for the TLS + TEE test-
environment setup. He already has a validated Olea-internal enclave (this
repository's PoC). The joint integration and test environment is **blocked on
Dowsure**, and ownership has **not** been reassigned away from Anudeep Sai Nunna.

**Why it is not reassigned.** The blocker is external. Dowsure said they will share
a schedule only after their internal discussion on or around **10 October**, and
they have not yet assigned a technical counterpart. Nothing on the Olea side is the
bottleneck, so moving the owner would not unblock anything - the dependency sits
with Dowsure.

**Why record it this way.** Making the blocker explicitly external, with a named
owner who is ready, keeps accountability clear: the Olea-internal piece is done and
waiting; the joint environment waits on Dowsure's schedule and staffing.

Evidence: the ownership and timeline are summarized in
[docs/PROJECT_STATUS_MATRIX.md](docs/PROJECT_STATUS_MATRIX.md#plain-english-summary).

---

## Decision 10 - KYC is a confirmed Phase-2 TLS+TEE consumer, documented at flow level only

**Decision.** KYC (Know Your Customer) and judicial checks are a confirmed
Phase-2 consumer of the same TLS + TEE solution - both technical teams agreed. For
now it is documented only at the flow and decision-model level, not as a code task.

**Why document, not build.** The integration environment does not exist yet (see
Decision 9), so there is nothing to integrate against. Documenting the schema and
flow is enough to lock in the design until that environment is ready.

**Why not put sample data in the repository.** The real KYC sample payloads
contain genuine personal data (national identity numbers, phone numbers, names,
company credit codes, and litigation records). None of those literal values may
ever enter this repository. The flow is described with generic placeholders such as
`<name>`, `<idNumber>`, and `<creditCode>` instead.

Evidence: the KYC flow and rule model are in
[docs/FLOWS.md](docs/FLOWS.md#flow-5-kyc-phase-2-tlstee-consumer)
and KYC appears as a Phase-2 category in
[docs/SOURCE_ENDPOINTS.md](docs/SOURCE_ENDPOINTS.md#1-the-source-map-four-data-categories-plus-kyc).

---

## Commercial context (non-technical)

> **This is business context, not a technical claim.** Dowsure proposed a
> **US$150,000** one-time deployment fee and a **US$100,000** annual recurring fee
> for the TLS + TEE solution. These commercial terms are under negotiation and are
> separate from the technical integration described in the rest of this project.
> They are recorded here only so the full picture is in one place; they do not
> describe anything the code does or any technical commitment.

---

## See also

- [docs/PROJECT_STATUS_MATRIX.md](docs/PROJECT_STATUS_MATRIX.md) - single source of
  truth for status facts (enclave fingerprint, register values, host facts).
- [docs/FLOWS.md](docs/FLOWS.md) - the five end-to-end flows these decisions shape.
- [docs/SOURCE_ENDPOINTS.md](docs/SOURCE_ENDPOINTS.md) - the data source map and the
  Super PO eligibility formula.
- [IMPLEMENTATION_AGENT_HANDOFF.md](IMPLEMENTATION_AGENT_HANDOFF.md) - the internal
  handoff with concrete next steps.
