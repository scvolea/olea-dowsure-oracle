# Quick start

> **Plain-English summary.** This page is just a signpost. It tells you which document
> to open for what. It does not repeat status facts - those live in one place, the
> status matrix.

## What this repo is

A Proof of Concept (PoC) for a verifiable data oracle: data is processed inside a
sealed, tamper-proof AWS Nitro Enclave that signs a receipt Olea can verify. It proves
the architecture; it does not yet claim production-grade upstream authenticity.

## Start here (in order)

1. [README.md](../README.md) - the front door and full index of folders and docs.
2. [docs/PROJECT_STATUS_MATRIX.md](PROJECT_STATUS_MATRIX.md) - the single source of truth for status facts (what is verified, what is open). Read this instead of any restated status elsewhere.
3. [docs/FLOWS.md](FLOWS.md) - the five end-to-end flows traced against the code.
4. [docs/SOURCE_ENDPOINTS.md](SOURCE_ENDPOINTS.md) - which Amazon endpoint maps to which data category, plus the eligibility formula.
5. [opinions.md](../opinions.md) - what was done, what was deliberately not done, and why.

## Going deeper

- [IMPLEMENTATION_AGENT_HANDOFF.md](../IMPLEMENTATION_AGENT_HANDOFF.md) - the single internal handoff for the next implementer.
- [docs/ARCHITECTURE_DIAGRAM.md](ARCHITECTURE_DIAGRAM.md) - the architecture diagram with a plain-language walkthrough.
- [sam/docs/POC_LIMITATIONS.md](../sam/docs/POC_LIMITATIONS.md) - the honest limitations list.

For the current status, do not rely on this page - see
[docs/PROJECT_STATUS_MATRIX.md](PROJECT_STATUS_MATRIX.md).
