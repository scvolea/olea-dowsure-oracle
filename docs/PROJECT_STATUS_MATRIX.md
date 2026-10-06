# Project status matrix

> **Read this first (plain English).** This project proves that Olea can accept
> "Amazon says this seller did X" from Dowsure WITHOUT trusting Dowsure. The
> oracle uses **TLS-in-TEE**: the Nitro enclave opens and terminates the TLS
> connection to each upstream source itself — the host is a transparent
> vsock→TCP byte relay that only sees ciphertext. There is no external notary
> on the live path. Trust is derived from the enclave's own attestation:
> challenge nonce + PCR0/1/2 (EIF measurement) + attestation document +
> rawHash + transformedHash + enclave signature. **7 source calls have been
> proven live, each returning 202 ACCEPTED** via the custom domain
> `oracle.oleainternal.com`. This page records the hard facts; other docs
> link here.

> **Freshness:** this page was refreshed after the TLS-in-TEE conversion was
> proven end-to-end. Code is at `main` HEAD `838a170`, merged across 5 workflow
> cycles. The previous approach (MPC-TLS / TLSNotary with an external notary)
> is superseded — the notary code is kept in the repo as historical reference
> only. For the full narrative see `SESSION_HANDOFF.md`.

This matrix is the **single source of truth** for status facts. If any other
document disagrees, this page is correct.

## Acronyms

- **TLS-in-TEE** — the enclave opens and terminates its own TLS connection to the
  upstream source; the host is a transparent vsock→TCP byte relay carrying only
  ciphertext. No external notary is involved.
- **Nitro Enclave** — isolated, tamper-proof VM inside an EC2 host; no persistent
  storage, no interactive access, no network except a virtual socket (vsock) to its host.
- **EIF (Enclave Image File)** — the single artifact that boots inside the enclave; its SHA-256 is its identity.
- **PCR (Platform Configuration Register)** — a measurement (hash) of what loaded into the enclave; PCR0/1/2 fingerprint the exact EIF.
- **attestation** — a signed document the enclave produces proving which EIF (PCRs) runs and binding a public key.
- **NSM (Nitro Security Module)** — the hardware that signs the attestation. **COSE/CBOR** — the attestation's signature format/encoding.
- **MPC-TLS / TLSNotary** — the previous approach (now historical reference): a protocol producing a proof that a specific HTTPS response really came from a specific server, using a separate prover + independent notary who jointly run the TLS client.
- **vsock (AF_VSOCK)** — the only channel between the enclave and its host.
- **SP-API (Selling Partner API)** — Amazon's seller data API. **LWA** — Login with Amazon (OAuth token).

## Status table

| Area | Status | Evidence | Notes |
| --- | --- | --- | --- |
| Nitro host / EC2 environment | Verified | Host `i-0b2b6aa26fb920103` live, SSM-managed | preprod, stack `olea-dowsure-nitro-preprod` |
| Java enclave runtime | Verified | `olea-orders-tlsintee-f` RUNNING non-debug (Flags: NONE) | AF_VSOCK CID 16 |
| EIF generation | Verified | Rebuilt no-cache from merged code; measured | SHA-256 + PCRs below |
| AWS Nitro attestation | Verified | Attestation produced by NSM, verified vs AWS Nitro Root-G1 | COSE sig + cert chain + PCR0/1/2 + public key + user_data |
| PCR validation | Verified | PCR0/1/2 match approved baseline | fail-closed |
| EIF release registration | Verified | EIF registered ACTIVE with dowsurePublicKeyPem | label `tls-in-tee-framing` |
| vsock communication | Verified | Java AF_VSOCK path live, 4-byte big-endian length-prefix framing | CID 16 |
| Coordinator (Java) | Verified | Driven by `--source-id`; no `--raw-payload-file`, `--raw-response-b64-file`, `--tls-proof-file` | runs on host for vsock |
| **TLS-in-TEE (enclave terminates TLS itself)** | **Verified (live, 7×202)** | Enclave opens its own TLS connection to each source via vsock→TCP relay; host sees only ciphertext | SUPERSEDES the MPC-TLS/notary approach |
| Source registry (7 entries) | Verified | getOrderMetrics, listFinancialEventGroups, listTransactions, alicloudTelThree, qichachaEnterpriseVerify, qichachaShixinCheck, gutuPanoramaChecks | all 7 proven live |
| **Full Olea acceptance (202) end-to-end** | **DONE — 7×202 ACCEPTED** | See evidence IDs below | via `oracle.oleainternal.com` custom domain |
| Custom domain DNS bypass | Verified | `oracle.oleainternal.com` → `d-qbnfey8xcc.execute-api.ap-southeast-1.amazonaws.com` | bypasses VPC execute-api VPCE private-DNS interception |
| MPC-TLS / TLSNotary (previous approach) | Historical reference | Code in repo (`tls-notary/`); no longer on the live path | superseded by TLS-in-TEE |
| Finances-endpoint reorientation | Verified (live) | listTransactions + listFinancialEventGroups proven as part of the 7 calls | |
| KYC / judicial checks | Verified (live) | alicloudTelThree, qichachaEnterpriseVerify, qichachaShixinCheck, gutuPanoramaChecks proven live | 4 calls, 202 ACCEPTED each |
| Super PO / Financing / Repayment | Planned | documented; not built | Repayment REQUIRES TLS-in-TEE |
| Production Amazon onboarding | Out of scope | deferred | |

## Verified facts (authoritative — copy from here)

- **Nitro host:** `i-0b2b6aa26fb920103` (private subnet, SSM-managed, no SSH); stack `olea-dowsure-nitro-preprod`; artifact bucket `olea-dowsure-nitro-preprod-artifactbucket-vjb1iirzgmrc`.
- **Enclave (current):** `olea-orders-tlsintee-f`, CID 16, RUNNING non-debug (Flags: NONE).
- **EIF SHA-256:** `a837d6739cae6e45ba9785e0991099d85764ecdd7831a99fc7310d05aa930444`
- **PCR0:** `c5e703f0600254a7e677dde011d62eb48357179f811cb508bf4718c2ff73ed0daa1d1b245999d5cda096b8fb5e5da3de`
- **PCR1:** `4b4d5b3661b3efc12920900c80e126e4ce783c522de6c02a2a5bf7af3a2b9327b86776f188e4be1c1c404a129dbda493`
- **PCR2:** `9ab33673b0ca95c7e9c305e6d70d8ea2adbe5544fd4ef6695d5498af7059c40eb0d9ed5435c5e080729c60525d20de1a`
- **Release label:** `tls-in-tee-framing`, status ACTIVE, with dowsurePublicKeyPem.
- **Olea API (custom domain):** `https://oracle.oleainternal.com` — mapped to preprod stage of `olea-oracle-preprod` (`c8tw99zmla`). Cert: `*.oleainternal.com` (1c4f63f9). DNS alias in `oleainternal.com` private zone (Z04211121HBN9ZJWI8ZW1).
- **Olea API (regional):** `https://c8tw99zmla.execute-api.ap-southeast-1.amazonaws.com/preprod` (reachable from outside the VPC only).
- **Custom domain mapping:** `oracle.oleainternal.com` → `d-qbnfey8xcc.execute-api.ap-southeast-1.amazonaws.com`.
- **7 live 202 ACCEPTED evidence IDs:**
  - `getOrderMetrics` → `7e7e04ee`
  - `listFinancialEventGroups` → `3b785acc`
  - `listTransactions` → `b0105642`
  - `alicloudTelThree` → `d07f6de7`
  - `qichachaEnterpriseVerify` → `ac87b0ba`
  - `qichachaShixinCheck` → `e8c25235`
  - `gutuPanoramaChecks` → `71da25ed`
- **vsock framing:** 4-byte big-endian length-prefix (no half-close/shutdownOutput).
- **user_data binding:** `{requestId, nonce, policyVersion, sourceId, rawHash, transformedHash, publicKey}` — NO `tlsProofHash`.
- **Manifest fields:** no `tlsProofType`/`tlsProofHash`; uses `sourceId` instead of `source`/`endpoint`.
- **Transform:** pure pass-through (transformed = rawPayload, no logic).
- **Coordinator:** driven by `--source-id` (no `--raw-payload-file`, `--raw-response-b64-file`, `--tls-proof-file`).
- **Accounts:** preprod `706179786846`, dev `855703743734`. Region `ap-southeast-1`.
- **Code:** `main` at `838a170`, merged across 5 workflow cycles.

## Code defects found and fixed

1. Missing vsock egress transport + CA bundle (`96312ed`).
2. VsockServer crash on bad connection (`fc0cfc9`).
3. AF_VSOCK half-close framing → 4-byte big-endian length-prefix (`838a170`).

## Infrastructure findings

1. Nitro enclaves get no runtime env injection → config is baked. Production hardening: KMS → Secrets Manager.
2. Verifier Lambda drift from CloudFormation (`update-function-code`; `sam deploy` pending).
3. VPC `execute-api` VPCE private-DNS interception: the shared preprod VPC's VPCE intercepts all `*.execute-api.*` resolution, routing traffic through the VPCE path. Regional API Gateway rejects traffic arriving via the VPCE.
4. Custom domain `oracle.oleainternal.com` resolved it: Route 53 alias in the `oleainternal.com` private hosted zone bypasses the VPCE private-DNS interception.

## Plain-English summary

The TLS-in-TEE implementation has been deployed and validated end-to-end. The
Nitro enclave opens and terminates its own TLS connection to each upstream source;
the host is a transparent byte relay. All 7 source calls (3 Amazon SP-API +
4 KYC vendors) have been proven live with 202 ACCEPTED. The previous MPC-TLS /
TLSNotary approach is kept as historical reference; TLS-in-TEE supersedes it
by collapsing source authenticity and execution trust into a single boundary.
KYC is proven live (not mock-only). Super PO / Repayment are planned (Repayment
is the flow that genuinely requires TLS-in-TEE).

## Decision statement

Describe this project as: **a proven TLS-in-TEE verifiable oracle — the Nitro
enclave terminates TLS itself, producing attested evidence for 7 source calls
(3 Amazon SP-API + 4 KYC), all 7 returning 202 ACCEPTED. The MPC-TLS/notary
approach is historical reference; TLS-in-TEE is the live path.** Code is at
`main` `838a170`.
