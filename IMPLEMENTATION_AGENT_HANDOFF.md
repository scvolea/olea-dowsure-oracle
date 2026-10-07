# Implementation Agent Handoff

> **Plain-English summary.** This is the one internal handoff for the next implementer.
> The oracle has been converted to **TLS-in-TEE**: the Nitro enclave opens and
> terminates its own TLS connection to each upstream source, hashes and (today)
> passes through the data, and produces an attested, signed evidence package. The host
> is a transparent vsock→TCP byte relay carrying only ciphertext. **All 7 source calls
> are proven live, each returning 202 ACCEPTED** (3 Amazon SP-API + 4 KYC vendors).
> The previous external MPC-TLS/TLSNotary design is retired to historical reference
> (`archive/TLSNOTARY.md`). For the authoritative status facts, see
> [docs/PROJECT_STATUS_MATRIX.md](docs/PROJECT_STATUS_MATRIX.md); for the mechanism in
> detail, [docs/TLS_IN_TEE_EXPLAINED.md](docs/TLS_IN_TEE_EXPLAINED.md); for the
> step-by-step flow, [docs/flow-steps/](docs/flow-steps/).
>
> Acronyms on first use: PoC (Proof of Concept), SP-API (Selling Partner API, Amazon's
> seller data API), Nitro Enclave (an isolated, tamper-proof virtual machine with no
> storage and no normal network), EIF (Enclave Image File, the single built artifact
> that boots inside the enclave), PCR (Platform Configuration Register, a hash that
> fingerprints the EIF), COSE (CBOR Object Signing and Encryption), CBOR (Concise
> Binary Object Representation), vsock (virtual socket, the only channel between the
> enclave and its host), LWA (Login with Amazon), NSM (Nitro Security Module),
> TLS-in-TEE (the enclave terminates TLS itself; no external notary on the live path).

## Mission

Harden and extend the Olea-Dowsure Verifiable Data Oracle PoC. The core architecture
is proven: a Nitro enclave that **terminates TLS to each source itself**, binds the
exact response bytes into a Nitro attestation, and submits evidence Olea verifies
fail-closed. The next work is production hardening and the business flows, not
re-proving the mechanism.

## Current Verified State

The authoritative, living status lives in
[docs/PROJECT_STATUS_MATRIX.md](docs/PROJECT_STATUS_MATRIX.md); copy hard facts from
there. Summary:

- **Architecture:** TLS-in-TEE — the enclave opens its own TLS connection to each
  source over a host `vsock-proxy` (vsock→TCP) relay; the host sees only ciphertext.
  No external notary on the live path.
- **Proven live:** all **7 source calls return 202 ACCEPTED** via the custom domain
  `oracle.oleainternal.com` — `getOrderMetrics`, `listFinancialEventGroups`,
  `listTransactions` (Amazon SP-API) and `alicloudTelThree`, `qichachaEnterpriseVerify`,
  `qichachaShixinCheck`, `gutuPanoramaChecks` (KYC vendors). Evidence IDs in the matrix.
- **Enclave:** `olea-orders-tlsintee-f`, CID 16, RUNNING non-debug (Flags: NONE).
- **EIF/PCRs:** registered ACTIVE under label `tls-in-tee-framing` with
  `dowsurePublicKeyPem`. Exact SHA-256 + PCR0/1/2 are in the matrix (do not hardcode
  them here — they change on every EIF rebuild).
- **vsock framing:** 4-byte big-endian length-prefix (no half-close/`shutdownOutput`).
- **Transform:** pure pass-through today (`transformed = rawPayload`, no logic).
- **Coordinator:** driven by `--source-id` (the old `--raw-payload-file`,
  `--raw-response-b64-file`, `--tls-proof-file` inputs are gone — the enclave now
  fetches the bytes itself).
- **Verifier:** `sam/olea/functions/verification/` — challenge/evidence/release APIs,
  fail-closed verification, S3 Object-Lock evidence vault.
- **Attestation:** NSM-produced, verified against AWS Nitro Root-G1 (COSE sig + cert
  chain + PCR0/1/2 + attested public key + `user_data` binding).

## Next Steps (production hardening + flows)

The mechanism is done. The remaining slices:

1. **Attested config/secret delivery.** Today non-secret config and a throwaway mock
   key are **baked** into the EIF (Nitro has no runtime env injection). Replace with
   KMS→Secrets Manager delivery gated on `kms:RecipientAttestation:PCR0` so real
   provider credentials are released only to the approved enclave. (See infra finding 1
   in the matrix.)
2. **Sync the verifier Lambda with CloudFormation.** The live verifier was updated via
   `update-function-code` and has drifted from the SAM template; run `sam deploy` to
   reconcile. (Infra finding 2.)
3. **Real business transforms.** Replace the pass-through with the approved
   per-source transformation(s); keep `rawHash` (wire bytes) and `transformedHash`
   (canonical output) as independent bindings so both the source bytes and the derived
   output are provable.
4. **Super PO / Financing / Repayment flows.** Documented, not built. Repayment is the
   flow that genuinely requires TLS-in-TEE. See `docs/FLOWS.md` and
   `docs/SOURCE_ENDPOINTS.md`.
5. **Keep fail-closed behavior.** Attestation/PCR/nonce/signature/hash failures must
   all reject (`422`), never silently accept.

### Dependency and ownership

- The joint TEE / TLS+TEE integration/test environment is **blocked on Dowsure**;
  Dowsure will share a schedule after their internal discussion on or around
  **10 October** and has not yet assigned a technical counterpart.
- **Owner:** Anudeep Sai Nunna is Olea's named owner for the TLS+TEE test-environment
  setup; the validated Olea-internal enclave is this repo's PoC.
- **Production hosting (open decision):** who hosts the enclave (Olea-hosted vs
  Dowsure-hosted) and who builds vs reviews the EIF are **not yet decided**. See
  `docs/flow-steps/diagram-production-topology.md` and `docs/ENGAGEMENT_CONTEXT.md`.

## Read First

1. `README.md`
2. `docs/PROJECT_STATUS_MATRIX.md` (authoritative status)
3. `docs/TLS_IN_TEE_EXPLAINED.md` (the mechanism)
4. `docs/flow-steps/` (step-by-step with real payloads)
5. `.kiro/specs/tls-tee-oracle/` (the implemented spec)

The files under `archive/` are historical and not authoritative.

## Architecture Decision (current)

- Dowsure is the operational custodian; Olea owns source policy, challenge issuance,
  PCR/EIF trust registration, verification, acceptance, and immutable retention.
- The **enclave terminates TLS itself** (TLS-in-TEE). The host `vsock-proxy` forwards
  only ciphertext and never terminates or rewrites source TLS.
- Dowsure calls the Olea Challenge API; Olea returns a scoped, single-use,
  time-limited challenge (request ID, nonce, policy version, endpoint scope, expiry).
- The coordinator dispatches the request to the enclave over vsock (length-prefixed
  frames); the enclave fetches, hashes, attests, signs; the coordinator wraps the
  evidence in a Dowsure-signed envelope and submits it; Olea verifies and returns
  accept/reject with a reason code.

## Evidence contract (current)

The submitted evidence binds (verified in `sam/olea/functions/verification/`):

- Request/evidence ID, `sourceId`, nonce, policy version.
- `rawPayload` + `rawResponseB64` + `rawPayloadDigest` (SHA-256 of the wire bytes).
- `transformedPayload` + `transformedPayloadDigest` (SHA-256 of canonical output).
- `manifestDigest`, `canonicalizationVersion` (`RFC8785-PoC`).
- `attestationDocument`, `attestedPublicKeyBase64`, `enclaveSignature`.
- `eifDigest`, `pcr0/1/2` (must match a registered ACTIVE release).
- `submissionEnvelope` + `submissionSignature` (Dowsure's key).

There are **no** `tlsProof*` fields and no `source`/`endpoint` pair — the enclave's own
TLS termination under attestation is the origin anchor, and `sourceId` selects the call.

## EIF Governance

- Every EIF change has a named owner and PR reviewer; CI runs tests, dependency scans,
  SBOM generation, reproducible build, EIF creation, and PCR measurement.
- The exact digest/PCR set is registered ACTIVE in the Olea release registry before
  deploy; a revoked release is terminal for new evidence.
- An EIF rebuild changes the PCRs, so the KMS attestation policy (once secret delivery
  lands, step 1) and the release registration must be updated together.

## Acceptance Tests (current)

PoC acceptance is met (and must stay met) when:

- Dowsure requests an Olea challenge for every policy-covered request; missing,
  expired, mismatched, or out-of-scope challenges fail closed.
- The 7 source calls work end-to-end through coordinator → vsock → enclave →
  verifier, each returning 202 ACCEPTED.
- No source side path bypasses the enclave; tokens/payloads never hit logs, disk, or
  the EIF.
- Raw and transformed hashes reproduce independently and match the attested binding.
- Nitro attestation and PCRs match Olea's registered release; envelope + enclave
  signatures validate.
- Invalid, stale, replayed, tampered, and revoked-release evidence is rejected.

## Out of Scope for This Agent

- Full production multi-region hardening.
- Broad endpoint/provider expansion beyond the 7.
- Funder CLI productization.
- Business-policy decisions owned by Olea Risk, Legal, or leadership.
- Changing the architecture without an explicit decision record.
