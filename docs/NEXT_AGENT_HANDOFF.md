# Next-agent handoff (2026-09-30)

## Current status

The Nitro attestation release gate is complete. Jira `DEVOPS-1816` and its three child tasks (`DEVOPS-1817`, `DEVOPS-1818`, `DEVOPS-1819`) currently read `Done`; the parent story covers the attestation and approved-PCR release gate, not the remaining source-proof integration.

Previously captured live evidence shows the Java enclave running in non-debug mode and producing a real Nitro attestation document. Verification passed against AWS Nitro Root-G1 for COSE signature, certificate chain, PCR0/PCR1/PCR2, attested public-key binding, and canonicalized `user_data`. The phase4 EIF digest is registered `ACTIVE` in preprod. The detailed evidence and measurements remain in [IMPLEMENTATION_AGENT_HANDOFF.md](../IMPLEMENTATION_AGENT_HANDOFF.md).

## Source integration direction

Use the available Amazon SP-API sandbox and its approved seller authorization/SSO flow for integration testing. Do not build a local Amazon API mock for the next slice.

The API reference in [api-mocks/#1 amazon-us-order-onboarding process.html](../api-mocks/%231%20amazon-us-order-onboarding%20process.html) documents:

- Operation: `getOrderMetrics`
- Request: `GET /sales/v1/orderMetrics`
- The full North America URL shown in the reference is the production host; use the sandbox host from the approved sandbox setup for testing.
- Seller authorization context supplies the seller ID; do not send it as an invented query parameter.
- Example parameters: marketplace ID, interval, daily granularity, UTC timezone, and buyer type.
- A sample response and transformation are included as reference data.

First confirm the sandbox accepts this operation and returns its static test response. If not, report that compatibility result and use another sandbox operation only after approval. Never silently replace the sandbox with a local fixture. Sandbox static responses are test data, not live seller activity and not proof that a real seller's data is authentic.

## Next steps

1. Confirm sandbox access details, approved SSO/seller authorization flow, app/scopes, and credential owner. Never place secrets in the repository, logs, handoff, or evidence report.
2. Run the documented order-metrics request against the sandbox host, not the production host shown in the reference, using the approved authorization flow. Record the exact environment, operation, non-sensitive parameters, response status/schema, and whether the payload is static. Do not record tokens or seller PII.
3. Make source selection policy-driven: caller selects a registered source ID; configuration controls the environment base URL, allowed operation, parameters, and disclosure. Reject arbitrary URLs and enforce host/path allowlists.
4. Test TLSNotary compatibility against the actual sandbox endpoint. Replace the current proof-contract/hash placeholder with real proof generation and verification: approved notary key pinning, source and endpoint binding, freshness/request binding, and exact response-hash binding.
5. Route the source response and proof through the coordinator, vsock, enclave, and Olea verifier. Keep fail-closed behavior for auth failures, unsupported operations, invalid proofs, policy violations, and attestation failures.
6. Add positive and negative tests: accepted signed proof; modified payload/proof; unknown notary/source; wrong endpoint; stale/expired proof; replay; and existing PCR, public-key, `user_data`, and submission-signature failures.
7. Capture a redacted end-to-end report and Olea acceptance receipt. Register Dowsure's signing public key if required by the current acceptance contract.
8. If the sandbox endpoint or TLSNotary protocol is incompatible, stop and document the evidence and smallest approved alternative. Do not fall back to unproved direct HTTPS and describe it as TLSNotary.

## Implementation prompt

> Continue the bounded Olea-Dowsure PoC. Use the available Amazon SP-API sandbox and its approved seller authorization/SSO flow; do not create a local Amazon API mock. Begin with `GET /sales/v1/orderMetrics` documented under `api-mocks/` and verify sandbox support and static-response behavior. Use the approved sandbox host; the API reference displays the production host. Protect credentials and seller data. Implement policy-controlled source IDs and strict endpoint allowlists, then replace the proof-contract placeholder with actual TLSNotary proof generation and verification, including approved notary-key pinning, request/source/endpoint/freshness checks, and exact response-hash binding. Integrate through coordinator, vsock, Nitro enclave, and Olea acceptance. Add acceptance and tamper/replay/expiry/unknown-trust negative tests. Preserve fail-closed behavior. Treat sandbox payloads as test data, never live seller evidence. If compatibility fails, document the result and stop for the required architecture/trust decision; do not use an unproved fallback. Finish with redacted validation evidence and an Olea acceptance receipt, or a precise blocker statement.

## Completion boundary

Do not state that Amazon source authenticity or the TLSNotary gate is complete until a real signed TLSNotary proof is verified against approved trust material, bound to the exact response processed, and accepted by Olea. The existing Nitro attestation verification remains a separately completed gate.
