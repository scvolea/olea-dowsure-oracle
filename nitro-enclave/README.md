# Nitro Enclave Proof of Concept (PoC)

This folder holds the Java program that runs inside an **AWS Nitro Enclave** (an
isolated, tamper-proof virtual machine inside an EC2 host, with no persistent
storage, no interactive login, and no network except a virtual socket to its
parent host). The goal is to prove that the trusted-execution and attestation
path is real.

> **In plain English.** This part proves "the code ran inside a real,
> locked-down box and we can cryptographically prove exactly which code ran." The
> oracle uses **TLS-in-TEE**: the enclave opens and terminates its own TLS
> connection to each upstream source — it is not a separate notary witnessing the
> call. The host is a transparent vsock→TCP byte relay carrying only ciphertext.
> Source authenticity and execution trust are collapsed into one enclave boundary.
> See the status section below and
> [docs/PROJECT_STATUS_MATRIX.md](../docs/PROJECT_STATUS_MATRIX.md) for the live facts
> (7 source calls proven live, 202 ACCEPTED each).

## What it does

Inside the enclave, the Java service:

- accepts one bounded request over the **virtual socket (vsock / AF_VSOCK)** - the
  only channel between the enclave and its parent host (4-byte big-endian
  length-prefix framing),
- resolves the `sourceId` via the SourceRegistry (7 entries),
- opens and terminates its own TLS connection to the upstream source via the
  vsock→TCP relay (the host only sees ciphertext),
- calculates the raw hash (`SHA256(response)`) and the transformed hash,
- binds the request metadata into the attestation `user_data` field
  (`{requestId, nonce, policyVersion, sourceId, rawHash, transformedHash, publicKey}`),
- generates a short-lived (ephemeral) keypair,
- asks the hardware for a real Nitro **attestation** document (a signed document
  that proves which code is running and binds the public key to this enclave),
- returns an evidence package that Olea can verify.

The request path is scoped by `sourceId`. In the code (`EnclaveService.java`) the
SourceRegistry holds the 7 approved source entries; a `sourceId` outside the
registry is rejected. `EnclaveMain.java` serves on **CID 16** (the enclave only
reaches `RUNNING` on CID 16 because that binding is fixed in code).

## Why it matters

This is the piece that proves the **Trusted Execution Environment (TEE)** is real.
The enclave is not a simulator. It is a real AWS Nitro-attested runtime that
produces a signed attestation document and measured **Platform Configuration
Register (PCR)** values that fingerprint the exact code image.

## Current status

The enclave is already running non-debug and has passed live verification against
real AWS trust material (AWS Nitro Root-G1 certificate, COSE signature,
certificate chain, PCR match, public-key binding, and `user_data` binding).

For the exact verified facts - the **Enclave Image File (EIF)** SHA-256 fingerprint,
the PCR0/PCR1/PCR2 values, the host, and the verified request/evidence IDs - see the
single source of truth: [docs/PROJECT_STATUS_MATRIX.md](../docs/PROJECT_STATUS_MATRIX.md).
Those numbers are defined there once and are not repeated here.

Acronyms used above, expanded on first use:

- **PoC** - Proof of Concept.
- **EIF (Enclave Image File)** - the single built artifact that boots inside the
  enclave; its SHA-256 hash is the enclave's identity.
- **PCR (Platform Configuration Register)** - a hash of what was loaded into the
  enclave; PCR0/1/2 together fingerprint the exact EIF.
- **attestation** - the signed document the enclave produces to prove which EIF is
  running and to bind a public key to it.
- **COSE (CBOR Object Signing and Encryption)** - the signature format of the
  attestation document.
- **CBOR (Concise Binary Object Representation)** - the compact binary encoding COSE uses.
- **NSM (Nitro Security Module)** - the hardware component that signs the attestation document.
- **vsock / AF_VSOCK (virtual socket)** - the only channel between the enclave and its host.
- **TLS-in-TEE** - the enclave opens and terminates its own TLS connection to the
  upstream source; the host relays only ciphertext.

## Source authenticity (TLS-in-TEE)

The enclave **terminates TLS itself**. The `SourceTlsClient` inside the enclave
opens a TLS connection to the upstream source and routes it through the host's
vsock→TCP relay, which forwards raw ciphertext bytes without inspecting them. The
enclave receives the plaintext response directly and computes
`rawHash = SHA256(response)` — that hash is the origin anchor, bound into the
attestation `user_data`. There is no external notary and no separate TLS proof to
cross-check: source authenticity and execution trust are collapsed into one enclave
boundary. Because TLS terminates inside the enclave, the host (and Dowsure) can
never see or alter the plaintext. This path is **proven live** — 7 source calls,
202 ACCEPTED each; see
[docs/PROJECT_STATUS_MATRIX.md](../docs/PROJECT_STATUS_MATRIX.md). (The earlier
MPC-TLS/TLSNotary approach, where a separate notary witnessed the call, is kept as
historical reference only.)

## Native boundary

The Java code reaches the AWS NSM through `JnaAttestationProvider`, which calls the
pinned native library (`libnsm.so`) across the Java-to-native boundary. This is the
expected pattern for Nitro enclaves and is kept isolated from the general
application logic.
