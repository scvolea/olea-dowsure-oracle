# Project status matrix

> **Read this first (plain English).** This project proves that a tamper-proof
> computer-in-a-box (an AWS Nitro Enclave) can fetch data, transform it, and sign
> a cryptographic receipt that Olea can verify. That trusted-execution and
> attestation layer is built and verified against live AWS evidence. What is NOT
> yet done is the *source-authenticity* layer: a real TLSNotary proof that the
> fetched data truly came from Amazon. This page is the single place that records
> the hard facts (the enclave fingerprints, host, and verified IDs). Every other
> document links here instead of repeating these numbers, so there is exactly one
> source of truth.

This matrix is the **single source of truth** for status facts about the
Olea-Dowsure verifiable data oracle Proof of Concept (PoC). If any other document
disagrees with this page, this page is correct.

## Acronyms used on this page (expanded on first use)

- **Nitro Enclave** - an isolated, tamper-proof virtual machine inside an AWS EC2
  host with no persistent storage, no interactive access, and no external network
  except a virtual socket to its parent host.
- **EIF (Enclave Image File)** - the single built artifact that boots inside the
  enclave. Its SHA-256 hash is the enclave's identity.
- **PCR (Platform Configuration Register)** - a measurement (hash) of what was
  loaded into the enclave. PCR0/PCR1/PCR2 together fingerprint the exact EIF.
- **attestation** - a signed document the enclave produces that proves which EIF
  (via its PCRs) is running and binds a public key to that enclave.
- **NSM (Nitro Security Module)** - the hardware component that signs the
  attestation document.
- **COSE (CBOR Object Signing and Encryption)** - the signature format of the
  attestation document.
- **CBOR (Concise Binary Object Representation)** - the compact binary encoding
  that COSE uses.
- **TLSNotary** - a protocol that produces a proof that a specific HTTPS response
  really came from a specific server, without trusting the client.
- **vsock (virtual socket / AF_VSOCK)** - the only communication channel between
  the enclave and its parent host.
- **SP-API (Selling Partner API)** - Amazon's seller data API.

## Status table

| Area | Status | Evidence | Notes |
| --- | --- | --- | --- |
| Nitro host / EC2 environment | Verified | Host `i-0b2b6aa26fb920103` is live and SSM-managed | See verified facts below |
| Java enclave runtime | Verified | `olea-orders-java` running non-debug (Flags: NONE) | AF_VSOCK (virtual socket) port 5005 |
| EIF (Enclave Image File) generation | Verified | phase4 EIF built and measured | SHA-256 recorded below |
| AWS Nitro attestation | Verified | Attestation document produced by the NSM (Nitro Security Module) | Verified against AWS Nitro Root-G1 |
| COSE (CBOR Object Signing and Encryption) signature validation | Verified | Certificate and signature chain validated | CBOR (Concise Binary Object Representation) encoded |
| PCR (Platform Configuration Register) validation | Verified | PCR0, PCR1, PCR2 match the approved baseline | Fail-closed enforcement |
| Public key binding | Verified | Attested public key matches expected binding | Included in `user_data` checks |
| `user_data` canonicalization | Verified | Canonicalized binding accepted | JSON canonicalization + hash binding passed |
| EIF release registration | Verified | Exact EIF SHA-256 registered ACTIVE | Preprod Olea release registry |
| vsock (virtual socket) communication | Verified | Java AF_VSOCK path is live | Port 5005 |
| Implemented upstream source flow | Verified | Bounded mock `GET_ORDERS` path works | Enclave code hardcodes `source='mock-api'`, `endpoint='GET_ORDERS'` |
| Real TLSNotary proof integration | Open (blocked) | Enclave runs a TLSNotary hash-contract **placeholder** only | **Critical open gate** - no real signed proof from an approved notary yet |
| Amazon SP-API (Selling Partner API) sandbox | Available | Sandbox endpoints and credentials are now available for validation | Credentials provided out of band / stored as secrets; never written in any doc |
| Finances-endpoint reorientation | Planned (next) | Transactions + Financial Event Groups supersede order-metrics-only | `GET /finances/2024-06-19/transactions` and `GET /finances/v0/financialEventGroups` are the real next targets |
| KYC (Know Your Customer) / judicial checks | Planned (Phase 2) | New Phase-2 TLS+TEE consumer, confirmed by both teams | Schema/flow + decision model only; no code integration yet |
| Full Olea acceptance receipt (end-to-end) | Open | Acceptance flow is not complete end-to-end | Evidence package not yet accepted through the live coordinator path |
| Coordinator integration | Open | Transitional fixture path still in place | Coordinator must replace the API Gateway parent fixture |
| Production Amazon onboarding | Out of scope | Explicitly deferred | Phase 2 work only |

## Verified facts (defined here, linked everywhere else)

These are the authoritative hard facts. Copy them from here; do not restate them
in other documents.

- **Nitro host:** `i-0b2b6aa26fb920103` (c5.xlarge, private subnet, SSM-managed,
  no SSH); stack `olea-dowsure-nitro-preprod`.
- **Enclave:** `olea-orders-java`, CID 16, 2 vCPU, 2048 MiB, phase4 EIF
  (Enclave Image File) RUNNING, non-debug (Flags: NONE), Java AF_VSOCK
  (virtual socket) port 5005.
- **phase4 EIF SHA-256:** `246e2143aeecb6c2e4f5e551536b2dfc75e8313fd521dd4da91da8f5d907da94`
  - registered ACTIVE in the preprod Olea release registry.
- **PCR0:** `5fba63399c819c8658452d9d48f35ecd491f9d65d5d842eb7ada4ae23a63cb666796a643102c9cc9e71c8a4bc60baba9`
- **PCR1:** `4b4d5b3661b3efc12920900c80e126e4ce783c522de6c02a2a5bf7af3a2b9327b86776f188e4be1c1c404a129dbda493`
- **PCR2:** `6560ff543ea448942b3f50ebadf606223a7aed1120856b503468a127671b22de6cbf195e0beb423b255100349e22c68f`
- **Live non-debug attestation** verified against AWS Nitro Root-G1: COSE
  (CBOR Object Signing and Encryption) signature, certificate chain, PCR0/1/2,
  attested public key, and canonicalized `user_data`.
- **Verified request:** `293548c9-9975-4cc0-9039-b19a5ce6b421` /
  **evidence:** `e826e531-cbc6-41ed-93d5-1da3ae68fe09`.
- **Stacks:** `olea-oracle-preprod` and `dowsure-oracle-preprod` are
  UPDATE_COMPLETE (plus `olea-dowsure-nitro-preprod` above).
- **Jira:** DEVOPS-1816 plus 1817 / 1818 / 1819 are Done for the attestation and
  approved-PCR release-gate scope.

## Plain-English summary

The project is successfully proving the trusted-execution and attestation
architecture: a real Nitro Enclave boots a measured EIF, produces a genuine
attestation document, and Olea verifies it against AWS trust material. It is
**not** yet proving real source authenticity, because the TLSNotary proof is still
a placeholder (the code only checks a proof contract and a response hash, not a
real signed proof from an approved notary).

The Amazon SP-API sandbox and its credentials are now available, so the next slice
can exercise the real sandbox (Transactions, Financial Event Groups, Order
Metrics) through the enclave instead of the local mock.

## Decision statement

The current honest position is:

- the enclave and attestation path is real and validated,
- the trust model is working for the controlled PoC,
- the real TLSNotary source-proof layer is still an open gate (placeholder only),
- the Amazon SP-API sandbox is available (not blocked) for the next integration slice.

Describe this project as a real attestation PoC with an open source-proof gate and
an available sandbox for the next slice - not as a completed Amazon-origin
verification deployment.
