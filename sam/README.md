# SAM PoC overview

This folder contains the serverless deployment model for the PoC. It is intentionally narrow and controlled.

## What this package is for

The SAM templates model a single bounded source-validation path:

- real Olea verification logic,
- a mock Dowsure orchestration layer,
- a mock upstream API,
- a trust checker for the final evidence bundle.

This is not a full production Amazon integration. It is a controlled test harness for validating the trust architecture.

## Current status

The architecture is valid for a proof-of-trust prototype:

- attestation verification is implemented and tested,
- the verifier checks PCR values, public keys, and canonicalized user_data,
- the mock endpoint behaves as a controlled source for the trust chain,
- the TLSNotary proof gate remains blocked by missing external notary/prover infrastructure.

## Deployment units

- [olea/template.yaml](olea/template.yaml)
  - Olea verification and challenge handling.
- [dowsure/template.yaml](dowsure/template.yaml)
  - orchestration and proof submission flow.
- [mocks/amazon/template.yaml](mocks/amazon/template.yaml)
  - mock source API used for bounded validation.

## Read these summaries first

- [IMPLEMENTATION_AGENT_HANDOFF.md](../IMPLEMENTATION_AGENT_HANDOFF.md) - the single internal handoff (architecture, acceptance, and Definition of Done; the former ARCHITECTURE_SUMMARY.md now points here).
- [docs/DEPLOYMENT_SUMMARY.md](docs/DEPLOYMENT_SUMMARY.md)
- [docs/POC_LIMITATIONS.md](docs/POC_LIMITATIONS.md)

## Validation commands

Run these if you want to lint the SAM templates locally:

- `sam validate --lint --template-file olea/template.yaml`
- `sam validate --lint --template-file dowsure/template.yaml`
- `sam validate --lint --template-file mocks/amazon/template.yaml`

## Important caveat

The code currently enforces a TLS proof contract and response-hash binding, but it does not prove a real TLSNotary result from a real notary service. That is a deliberate security stance: fail closed until the real proof material exists.
