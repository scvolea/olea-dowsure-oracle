# SAM Proof of Concept (PoC) overview

This folder holds the serverless deployment model for the PoC, built with the
**AWS Serverless Application Model (SAM)** - a framework for defining Lambda
functions and related AWS resources in templates. It is intentionally narrow and
controlled.

> **In plain English.** These templates stand up a small, controlled test harness
> that exercises the trust architecture end to end: real Olea verification logic,
> plus mock stand-ins for the parts that are not wired up yet.

## What this package is for

The SAM templates model a single bounded source-validation path:

- real Olea verification logic,
- a mock Dowsure orchestration layer,
- a mock upstream API,
- a trust checker for the final evidence bundle.

This is not a full production Amazon integration. It is a controlled test harness
for validating the trust architecture.

## Current status

The architecture is valid for a proof-of-trust prototype:

- attestation verification is implemented and tested,
- the verifier checks **PCR (Platform Configuration Register)** values, public keys,
  and canonicalized `user_data`,
- the mock endpoint behaves as a controlled source for the trust chain,
- the **TLSNotary** proof gate (a real signed proof that an HTTPS response came
  from a specific server) is still blocked by missing external notary/prover
  infrastructure. The code checks the proof contract and a response hash only;
  it is a placeholder, not a real signed proof.

For the exact verified status facts (enclave fingerprint, host, verified IDs), see
the single source of truth: [../docs/PROJECT_STATUS_MATRIX.md](../docs/PROJECT_STATUS_MATRIX.md).

## Deployment units

- [olea/template.yaml](olea/template.yaml)
  - Olea verification and challenge handling.
- [dowsure/template.yaml](dowsure/template.yaml)
  - orchestration and proof submission flow.
- [mocks/amazon/template.yaml](mocks/amazon/template.yaml)
  - mock source API used for bounded validation.

## Read these first

- [../IMPLEMENTATION_AGENT_HANDOFF.md](../IMPLEMENTATION_AGENT_HANDOFF.md) - the single
  internal handoff (architecture, acceptance, and Definition of Done; the former
  `docs/ARCHITECTURE_SUMMARY.md` now points here).
- [../docs/PROJECT_STATUS_MATRIX.md](../docs/PROJECT_STATUS_MATRIX.md) - the single
  source of truth for status facts.
- [docs/DEPLOYMENT_SUMMARY.md](docs/DEPLOYMENT_SUMMARY.md) - the deployment commands and sequence.
- [docs/POC_LIMITATIONS.md](docs/POC_LIMITATIONS.md) - what the PoC does and does not prove.

## Validation commands

Run these to lint the SAM templates locally:

- `sam validate --lint --template-file olea/template.yaml`
- `sam validate --lint --template-file dowsure/template.yaml`
- `sam validate --lint --template-file mocks/amazon/template.yaml`

## Important caveat

The code enforces a TLS proof contract and a response-hash binding, but it does
not prove a real TLSNotary result from a real notary service. That is a deliberate
security stance: fail closed until the real proof material exists.
