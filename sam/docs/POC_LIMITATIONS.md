# PoC Limitations

> **Where the hard facts live.** The verified enclave fingerprints (Enclave Image
> File SHA-256, PCR0/1/2), the host, and the verified request/evidence IDs are
> defined once in the single source of truth,
> [../../docs/PROJECT_STATUS_MATRIX.md](../../docs/PROJECT_STATUS_MATRIX.md). This
> page lists what the PoC does and does not prove; it does not restate those numbers.

- **[[Olea]]**
  - The verifier now requires Nitro CBOR/COSE documents, certificate-chain validation, AWS Nitro root configuration, PCR matching, attested public-key matching, and exact `user_data` binding.
  - The Java `JnaAttestationProvider` is wired to the pinned AWS `libnsm.so` ABI; the current Java EIF has been built, the approved AWS Nitro root has been used, and a non-debug document has passed live verification (exact EIF/PCR values in the status matrix).
  - Authentication and authorization for administrative policy/release endpoints must be added before production.
  - Synthetic JSON attestation is no longer accepted by the source verifier or enclave runtime.

- **[[Dowsure]]**
  - A real Nitro-capable EC2 host and running EIF exist in the preprod account.
  - The oracle uses TLS-in-TEE: the enclave terminates TLS itself to each of the 7 source endpoints. The host is a transparent vsock→TCP byte relay.
  - All 7 source calls are proven live with 202 ACCEPTED (3 Amazon SP-API + 4 KYC vendors). Evidence IDs are in the status matrix.
  - The coordinator is driven by `--source-id` (no `--raw-payload-file`, `--raw-response-b64-file`, `--tls-proof-file`).
  - vsock framing uses 4-byte big-endian length-prefix (no half-close).
  - The previous MPC-TLS/TLSNotary approach (external notary + prover sidecar) is historical reference, off the live path.
  - The EventBridge connection uses one PoC API-key credential for both endpoint families.
  - Production should use approved endpoint-specific authentication and private connectivity where required.
  - Config is baked into the EIF for the PoC; production should use attested KMS → Secrets Manager.

- **[[Mock Source API]]**
  - Responses are controlled fixtures and are not Amazon source proof.
  - The live path uses TLS-in-TEE (the enclave terminates TLS itself to reach the
    real upstream sources). The mock remains only a controlled fixture for local
    testing, not a source of Amazon authenticity.

- **Assurance Boundary**
  - No mock output can be described as verified Amazon evidence.
  - The Nitro host and the current Java EIF are real and running, and that EIF's
    SHA-256 is registered `ACTIVE` in preprod (exact values in the status matrix).
    The oracle uses TLS-in-TEE: the enclave terminates TLS itself. All 7 source
    calls are proven live with 202 ACCEPTED. The previous MPC-TLS/TLSNotary approach
    is historical reference.
  - Remaining production-hardening items: config delivery (baked → attested
    KMS/Secrets Manager), verifier Lambda sync with CloudFormation (`sam deploy`),
    real business transforms, encrypted evidence transfer.
  - Full production Amazon onboarding (LWA / Login with Amazon, real seller
    accounts, reports, presigned downloads) remains Phase 2.
  - Production acceptance requires real EIF measurements, Nitro attestation, signatures, deterministic canonicalization, and independently reproducible hashes.
