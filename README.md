# Olea-Dowsure Verifiable Data Oracle

This repository is a working Proof of Concept (PoC) for a **verifiable data oracle**:
a way to fetch data, process it inside a tamper-proof computer, and hand Olea a
cryptographic receipt it can check without trusting the party that fetched the data.

## Plain-English summary (read this first)

Imagine a sealed, tamper-proof box (an **AWS Nitro Enclave** — an isolated virtual
machine with no storage and no normal network) that opens its own TLS connection to
each upstream source, fetches the data, transforms it, and signs a receipt proving
exactly which code ran and what data it received. Olea can verify that receipt against
Amazon's own trust material. This is **TLS-in-TEE**: the enclave terminates TLS
itself, so the host never sees plaintext — it is a transparent vsock→TCP byte relay
carrying only ciphertext.

**7 source calls have been proven live, each returning 202 ACCEPTED** via the custom
domain `oracle.oleainternal.com`: 3 Amazon SP-API calls (getOrderMetrics,
listFinancialEventGroups, listTransactions) and 4 KYC vendor calls
(alicloudTelThree, qichachaEnterpriseVerify, qichachaShixinCheck,
gutuPanoramaChecks).

The previous approach — using an external TLSNotary/MPC-TLS notary to prove source
authenticity separately — is now **historical reference** only. TLS-in-TEE supersedes
it by collapsing source authenticity and execution trust into a single enclave
boundary. The notary code is kept in the repo for reference (`tls-notary/`,
`archive/TLSNOTARY.md`).

For the exact, up-to-date status (what is verified, what is open), there is **one**
source of truth: [docs/PROJECT_STATUS_MATRIX.md](docs/PROJECT_STATUS_MATRIX.md). For the
step-by-step narrative see [docs/flow-steps/](docs/flow-steps/) and the detailed
mechanism in [docs/TLS_IN_TEE_EXPLAINED.md](docs/TLS_IN_TEE_EXPLAINED.md). This README
does not repeat the hard numbers; it links to the matrix instead.

## What is in this repository (every folder)

| Folder | What lives there |
| --- | --- |
| [sam/](sam/README.md) | AWS Serverless Application Model (SAM) apps: the real Olea verifier, a mock Dowsure orchestration layer, and a mock upstream Application Programming Interface (API). |
| [nitro-enclave/](nitro-enclave/README.md) | The Java AWS Nitro Enclave application, its Dockerfile, Maven build, and Enclave Image File (EIF) / runtime notes. |
| [coordinator/](coordinator) | The Java (JDK 21) coordinator that drives the challenge, the enclave over a virtual socket (vsock), and the evidence envelope. (Migrated from Python.) |
| [infra/](infra) | CloudFormation infrastructure, including the Nitro EC2 host template. |
| [scripts/](scripts) | Node.js and PowerShell helpers for evidence capture and validation. |
| [tests/](tests) | Test harnesses (Python coordinator tests, Node verifier tests). |
| [archive/](archive) | Historical design notes kept for traceability only - clearly banner-marked ARCHIVED. |
| [docs/](docs) | The living documentation set (status matrix, flows, source endpoints, quickstart, architecture diagram). |
| [api-mocks/](api-mocks) | Ground-truth HTML flow references (onboarding, Super Purchase Order, repayment), tracked in this branch. |

## Documentation index (every document)

Start with the status matrix, then the flows, then the design docs.

**Status and orientation**

- [docs/PROJECT_STATUS_MATRIX.md](docs/PROJECT_STATUS_MATRIX.md) - the single source of truth for status facts. Everything else links here.
- [docs/ENGAGEMENT_CONTEXT.md](docs/ENGAGEMENT_CONTEXT.md) - how the project started: the Olea/Dowsure decision to use TLS+TEE, the rejected alternatives, action-item ownership, and the ground-truth business flows.
- [docs/QUICKSTART.md](docs/QUICKSTART.md) - a short "start here" index.
- [docs/FLOWS.md](docs/FLOWS.md) - the five end-to-end flows traced against the actual code.
- [docs/SOURCE_ENDPOINTS.md](docs/SOURCE_ENDPOINTS.md) - which Amazon Selling Partner API (SP-API) endpoint maps to which data category, plus the Super Purchase Order eligibility formula.
- [docs/ARCHITECTURE_DIAGRAM.md](docs/ARCHITECTURE_DIAGRAM.md) - the architecture diagram with a plain-language walkthrough.
- [docs/TLS_IN_TEE_EXPLAINED.md](docs/TLS_IN_TEE_EXPLAINED.md) - detailed explainer of the TLS-in-TEE mechanism (TEE/PCRs, in-enclave TLS, attestation binding, verification, and how it differs from the archived notary design).
- [docs/flow-steps/](docs/flow-steps/) - step-by-step walkthrough of one verified request (step0 index → step9), with real URLs/payloads, plus the embedded PoC and production topology diagrams.
- [docs/DOWSURE_BUSINESS_BRIEF.md](docs/DOWSURE_BUSINESS_BRIEF.md) - partner-facing business brief for Dowsure (plain-language flow + the Olea Oracle API integration surface).
- [opinions.md](opinions.md) - the decision and reasoning log: what was done, what was deliberately not done, and why.

**Design (canonical baseline)**

- [olea-dowsure-executive-proposal.md](olea-dowsure-executive-proposal.md) - the leadership-facing proposal, responsibility model, and two-week plan.
- [olea-dowsure-technical-design.md](olea-dowsure-technical-design.md) - the full technical architecture, threat model, and interface design.

**Specs (implemented)**

- [.kiro/specs/tls-tee-oracle/](.kiro/specs/tls-tee-oracle) — requirements/design/tasks for the TLS-in-TEE conversion: the oracle now uses enclave-terminated TLS across all 7 source calls. **IMPLEMENTED** — this spec is the basis of the live TLS-in-TEE path described in the status matrix.

**Handoffs**

- [IMPLEMENTATION_AGENT_HANDOFF.md](IMPLEMENTATION_AGENT_HANDOFF.md) - the single internal handoff for the next implementer (mission, verified state, next steps, acceptance / Definition of Done).
- [dowsure-implementation-handoff.md](dowsure-implementation-handoff.md) - the Dowsure-facing handoff with the defined interfaces and diagrams.

**Component and SAM docs**

- [sam/README.md](sam/README.md) - overview of the SAM deployment units.
- [nitro-enclave/README.md](nitro-enclave/README.md) - the enclave runtime notes.
- [sam/docs/DEPLOYMENT_SUMMARY.md](sam/docs/DEPLOYMENT_SUMMARY.md) - deployment summary (status facts link to the matrix).
- [sam/docs/POC_LIMITATIONS.md](sam/docs/POC_LIMITATIONS.md) - the honest limitations list (status facts link to the matrix).
- [sam/docs/ARCHITECTURE_SUMMARY.md](sam/docs/ARCHITECTURE_SUMMARY.md) - a pointer to the single internal handoff.

## What is done and what is open

The oracle uses **TLS-in-TEE**: the Nitro enclave terminates TLS itself, so source
authenticity and execution trust are collapsed into a single boundary. **All 7 source
calls are proven live with 202 ACCEPTED**: 3 Amazon SP-API (getOrderMetrics,
listFinancialEventGroups, listTransactions) and 4 KYC vendors (alicloudTelThree,
qichachaEnterpriseVerify, qichachaShixinCheck, gutuPanoramaChecks). The previous
MPC-TLS/TLSNotary approach is historical reference.

The remaining planned work is: real business transforms (currently pass-through),
production config delivery (baked → attested KMS/Secrets Manager), verifier Lambda
sync with CloudFormation, and Super PO / Repayment flows (not yet built).

The exact facts (stacks, enclave fingerprints, evidence IDs) live only in
[docs/PROJECT_STATUS_MATRIX.md](docs/PROJECT_STATUS_MATRIX.md).

## Recommended next step

Per the status matrix: the TLS-in-TEE path is proven end-to-end (7×202 ACCEPTED). The
next slices are production hardening (attested KMS/Secrets Manager config delivery
instead of baked config), syncing the verifier Lambda with CloudFormation
(`sam deploy`), real business transforms, and building the Super PO / Repayment flows.

> Note: some older design/handoff docs (`olea-dowsure-technical-design.md`,
> `olea-dowsure-executive-proposal.md`, dated handoff blocks) are kept as historical
> records and may carry the earlier MPC-TLS/notary framing. The status matrix is
> authoritative where they differ.
