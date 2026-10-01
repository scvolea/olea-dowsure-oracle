# Olea-Dowsure Verifiable Data Oracle

This repository is a working Proof of Concept (PoC) for a **verifiable data oracle**:
a way to fetch data, process it inside a tamper-proof computer, and hand Olea a
cryptographic receipt it can check without trusting the party that fetched the data.

## Plain-English summary (read this first)

Imagine a sealed, tamper-proof box (an **AWS Nitro Enclave** - an isolated virtual
machine with no storage and no normal network) that fetches data, transforms it, and
signs a receipt proving exactly which code ran. Olea can verify that receipt against
Amazon's own trust material. This PoC proves that sealed-box-and-receipt part works
with real AWS evidence.

What it does **not** yet prove is that the fetched data genuinely came from Amazon.
That needs a **TLSNotary** proof (a protocol that proves a specific HTTPS response
really came from a specific server). The code today only runs a placeholder check for
that, so the project is deliberately "fail-closed": it refuses to claim more than it
can prove.

For the exact, up-to-date status (what is verified, what is open), there is **one**
source of truth: [docs/PROJECT_STATUS_MATRIX.md](docs/PROJECT_STATUS_MATRIX.md). This
README does not repeat the hard numbers (enclave fingerprints, host IDs); it links to
the matrix instead.

## What is in this repository (every folder)

| Folder | What lives there |
| --- | --- |
| [sam/](sam/README.md) | AWS Serverless Application Model (SAM) apps: the real Olea verifier, a mock Dowsure orchestration layer, and a mock upstream Application Programming Interface (API). |
| [nitro-enclave/](nitro-enclave/README.md) | The Java AWS Nitro Enclave application, its Dockerfile, Maven build, and Enclave Image File (EIF) / runtime notes. |
| [coordinator/](coordinator) | The Python coordinator that drives the challenge, the enclave over a virtual socket (vsock), and the evidence envelope. |
| [infra/](infra) | CloudFormation infrastructure, including the Nitro EC2 host template. |
| [scripts/](scripts) | Node.js and PowerShell helpers for evidence capture and validation. |
| [tests/](tests) | Test harnesses (Python coordinator tests, Node verifier tests). |
| [archive/](archive) | Historical design notes kept for traceability only - clearly banner-marked ARCHIVED. |
| [docs/](docs) | The living documentation set (status matrix, flows, source endpoints, quickstart, architecture diagram). |
| [api-mocks/](api-mocks) | Ground-truth HTML flow references (onboarding, Super Purchase Order, repayment). Present only on the sandbox-handoff branch. |

## Documentation index (every document)

Start with the status matrix, then the flows, then the design docs.

**Status and orientation**

- [docs/PROJECT_STATUS_MATRIX.md](docs/PROJECT_STATUS_MATRIX.md) - the single source of truth for status facts. Everything else links here.
- [docs/QUICKSTART.md](docs/QUICKSTART.md) - a short "start here" index.
- [docs/FLOWS.md](docs/FLOWS.md) - the five end-to-end flows traced against the actual code.
- [docs/SOURCE_ENDPOINTS.md](docs/SOURCE_ENDPOINTS.md) - which Amazon Selling Partner API (SP-API) endpoint maps to which data category, plus the Super Purchase Order eligibility formula.
- [docs/ARCHITECTURE_DIAGRAM.md](docs/ARCHITECTURE_DIAGRAM.md) - the architecture diagram with a plain-language walkthrough.
- [opinions.md](opinions.md) - the decision and reasoning log: what was done, what was deliberately not done, and why.

**Design (canonical baseline)**

- [olea-dowsure-executive-proposal.md](olea-dowsure-executive-proposal.md) - the leadership-facing proposal, responsibility model, and two-week plan.
- [olea-dowsure-technical-design.md](olea-dowsure-technical-design.md) - the full technical architecture, threat model, and interface design.

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

The trusted-execution and attestation layer is **verified** against live AWS
evidence; the real source-authenticity (TLSNotary) layer is still an **open** gate.
The Amazon SP-API sandbox and its credentials are now **available** for the next
integration slice, so any older wording that calls the sandbox "blocked" or
"unavailable" is out of date.

The exact facts (which stacks, which enclave fingerprints, which verified IDs) live
only in [docs/PROJECT_STATUS_MATRIX.md](docs/PROJECT_STATUS_MATRIX.md).

## Recommended next step

Per the status matrix and
[IMPLEMENTATION_AGENT_HANDOFF.md](IMPLEMENTATION_AGENT_HANDOFF.md), the next slice
exercises the available Amazon SP-API sandbox against the finances endpoints -
Transactions (`GET /finances/2024-06-19/transactions`) and Financial Event Groups
(`GET /finances/v0/financialEventGroups`) - and replaces the TLSNotary placeholder
with a real signed proof from an approved notary. The implemented path today is still
the bounded mock `GET_ORDERS` flow; the finances endpoints and sandbox are planned
next, not yet wired in.
