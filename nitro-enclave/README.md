# Nitro Enclave Proof of Concept (PoC)

This folder holds the Java program that runs inside an **AWS Nitro Enclave** (an
isolated, tamper-proof virtual machine inside an EC2 host, with no persistent
storage, no interactive login, and no network except a virtual socket to its
parent host). The goal is to prove that the trusted-execution and attestation
path is real.

> **In plain English.** This part proves "the code ran inside a real,
> locked-down box and we can cryptographically prove exactly which code ran." It
> does **not** yet prove "the data it fetched really came from Amazon" - that
> second proof (TLSNotary) is still a placeholder. See the limitation below.

## What it does

Inside the enclave, the Java service:

- accepts one bounded request over the **virtual socket (vsock / AF_VSOCK)** - the
  only channel between the enclave and its parent host,
- calculates the raw hash and the transformed hash of the payload,
- binds the request metadata into the attestation `user_data` field,
- generates a short-lived (ephemeral) keypair,
- asks the hardware for a real Nitro **attestation** document (a signed document
  that proves which code is running and binds the public key to this enclave),
- returns an evidence package that Olea can verify.

The request path is deliberately narrow. In the code (`EnclaveService.java`) the
source and endpoint are hardcoded to `source='mock-api'` and
`endpoint='GET_ORDERS'`; any other values are rejected with `SOURCE_SCOPE_INVALID`.
`EnclaveMain.java` serves this on **CID 16, port 5005** (the enclave only reaches
`RUNNING` on CID 16 because that binding is fixed in code).

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
- **TLSNotary** - a protocol that proves a specific HTTPS response really came from
  a specific server.

## Important limitation (honest)

The enclave is real and working, but the **upstream source proof is still a
placeholder.** In the code the enclave only checks that the proof contract is
present (`proofType == 'tlsnotary'`, a proof hash, and that the response hash
matches the raw hash). It does **not** yet verify a real signed TLSNotary proof
from an approved notary. So the enclave path is proven; the full source-authenticity
chain is still gated on that external notary dependency. This is a deliberate
fail-closed stance: no real proof material, no claim of real source authenticity.

## Native boundary

The Java code reaches the AWS NSM through `JnaAttestationProvider`, which calls the
pinned native library (`libnsm.so`) across the Java-to-native boundary. This is the
expected pattern for Nitro enclaves and is kept isolated from the general
application logic.
