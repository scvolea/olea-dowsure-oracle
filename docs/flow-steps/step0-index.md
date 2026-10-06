# TLS-in-TEE Oracle — Step-by-step flow (URLs, payloads, who/what/why)

End-to-end trace of one verifiable data request, from Dowsure's intent to Olea's
`202 ACCEPTED`. Every URL, field, and payload below is taken from the live code, not
illustrative. One file per step; read in order.

## The actors (who)

| Actor | Where it runs | Trust role |
|---|---|---|
| **Coordinator** | Nitro **host** (`i-0b2b6aa26fb920103`, preprod `706179786846`, `ap-southeast-1`) | Untrusted orchestrator. Drives the flow, holds Dowsure's signing key, but can **never see or alter** source plaintext — that happens inside the enclave. `coordinator/.../Coordinator.java` |
| **Nitro Enclave** | Isolated VM on the same host, **CID 16**, non-debug | The trusted party. Terminates TLS to the source **itself**, hashes the exact wire bytes, binds them to a hardware attestation, signs. `nitro-enclave/.../EnclaveService.java` |
| **vsock relay** | Host, ports **8001** (amazon mock) / **8002** (kyc mock) | Dumb ciphertext forwarder. The enclave has no NIC; the relay shuttles encrypted TLS bytes host↔provider. It sees only ciphertext. |
| **Source APIs** | Amazon SP-API + KYC providers (mocks in PoC) | The real data origin. |
| **Olea verifier** | Lambda behind API Gateway `oracle.oleainternal.com` | The consumer's gatekeeper. Issues nonces, checks attestation + PCRs + signatures + hash bindings, returns 202/4xx. `sam/olea/functions/verification/index.js` |

## Why TLS-in-TEE (and not a notary)

The old model used an external TLSNotary party to co-witness the TLS session via MPC
so nobody had to trust the enclave alone. **That party is gone.** Now the enclave
terminates TLS itself and the **Nitro attestation document** (PCR0/1/2 measuring the
exact code image) *is* the proof. Anyone can verify the measurements match a
registered, non-revoked release. The notary + sidecar code remains in the repo
(`docs/TLSNOTARY.md`, `tlsnotary-verifier.js`) as historical reference, **off the live
path**.

## The 7 source calls (`sourceId` → provider/method/path)

From `SourceRegistry.java` (ported verbatim from `coordinator/sources.py`):

| sourceId | provider | method | path |
|---|---|---|---|
| `getOrderMetrics` | amazon | GET | `/sales/v1/orderMetrics` |
| `listFinancialEventGroups` | amazon | GET | `/finances/v0/financialEventGroups` |
| `listTransactions` | amazon | GET | `/finances/2024-06-19/transactions` |
| `alicloudTelThree` | alicloud | GET | `/lundear/telThree` |
| `qichachaEnterpriseVerify` | qichacha | GET | `/EnterpriseInfo/Verify` |
| `qichachaShixinCheck` | qichacha | GET | `/ShixinCheck/GetList` |
| `gutuPanoramaChecks` | gutu | **POST** | `/api/v1/judicial/panorama-checks` |

## Live endpoints

- **Olea API (public, custom domain):** `https://oracle.oleainternal.com` — base-path
  mapped to the preprod stage of API GW `c8tw99zmla`. The custom domain exists to
  bypass VPC `execute-api` private-DNS interception that was 403-ing the direct
  `*.execute-api` URL from inside the VPC.
- **Direct Olea stage (origin):**
  `https://c8tw99zmla.execute-api.ap-southeast-1.amazonaws.com/preprod`
- **Source mocks (PoC):** amazon `097sqg03n1.execute-api.ap-southeast-1.amazonaws.com/mock`
  (via relay 8001); kyc `i8yde0kf2g.execute-api.ap-southeast-1.amazonaws.com/mock`
  (via relay 8002).

## The steps

| # | Step | File |
|---|---|---|
| 1 | Request initiated (Coordinator inputs) | `step1-request-initiated.md` |
| 2 | Challenge issued (`POST /v1/challenges` → nonce) | `step2-challenge.md` |
| 3 | Hand to enclave over vsock | `step3-handoff-to-enclave.md` |
| 4 | Enclave terminates TLS + fetches source | `step4-enclave-tls-fetch.md` |
| 5 | Transform (pass-through) + hashing | `step5-transform-hash.md` |
| 6 | Attestation + enclave signature | `step6-attest-sign.md` |
| 7 | Return to Coordinator + envelope signing | `step7-return-and-envelope.md` |
| 8 | Submit evidence (`POST /v1/evidence`) | `step8-submit.md` |
| 9 | Verification + `202 ACCEPTED` | `step9-verify-202.md` |

## Live result

All 7 sourceIds returned **202 ACCEPTED** on 2026-10-06 against EIF
`a837d6739cae6e45ba9785e0991099d85764ecdd7831a99fc7310d05aa930444`
(label `tls-in-tee-framing`, ACTIVE). Evidence IDs in `step9-verify-202.md`.

## Related diagrams

- **Production topology** (Dowsure-owned infra, real sources, ownership boundary, and
  the "can Dowsure tamper?" analysis): `diagram-production-topology.md`

---

# Diagrams (PoC topology)

The two diagrams below show the **PoC topology** this flow was proven on. For the
**production / Dowsure-hosted** topology, see `diagram-production-topology.md`.

## Business-level flow

```mermaid
flowchart LR
    subgraph Problem["The trust problem"]
        direction TB
        Q["Olea lends money based on Dowsure's borrower data.<br/>Olea cannot trust Dowsure to report it honestly."]
    end

    D["Dowsure<br/>(loan originator)"]
    ORACLE["Verifiable Data Oracle<br/><br/>• Runs in a hardware-attested enclave (AWS Nitro)<br/>• Fetches data directly from the source API<br/>• Produces cryptographic proof of what it fetched<br/>• Code identity is measurable (PCR0/1/2)"]
    S["Source APIs<br/><br/>• Amazon SP-API (order metrics, financials, transactions)<br/>• Alicloud (telco verification)<br/>• Qichacha (enterprise, credit check)<br/>• Gutu (judicial panorama)"]
    O["Olea<br/>(lender / data consumer)"]

    D -->|"1 - Request: verify this<br/>borrower's data for me"| ORACLE
    ORACLE -->|"2 - Fetch data over its own<br/>TLS connection (enclave terminates TLS)"| S
    S -->|"3 - Raw financial / KYC<br/>response"| ORACLE
    ORACLE -->|"4 - Data + attestation proof +<br/>dual signatures (enclave + Dowsure)"| O
    O -->|"5 - Verify proof independently:<br/>• Code is the registered version (PCRs)<br/>• Nonce is fresh (anti-replay)<br/>• Hashes bind the data<br/>• Signatures valid<br/>→ 202 ACCEPTED"| O

    Q -.->|"Solved by hardware attestation,<br/>not by trusting a party"| ORACLE
```

**In words:**

1. **Dowsure** says "I need verified financial data for borrower X."
2. The **Oracle** (inside a tamper-proof enclave) fetches that data **directly** from
   the source API over its own encrypted connection. Nobody in between can read or
   modify it.
3. The Oracle wraps the data in a **cryptographic proof**: hardware attestation (which
   exact code ran), hashes (data untampered), and signatures (who produced + submitted).
4. **Olea** independently verifies every piece → **202 ACCEPTED**.

The shift from the old model: an external notary used to co-witness the session via
MPC. Now the enclave does it all and the hardware attestation *is* the proof.

## Detailed technical sequence

```mermaid
sequenceDiagram
    autonumber
    participant C as Coordinator<br/>(Nitro host, untrusted)
    participant OL as Olea Verifier<br/>(oracle.oleainternal.com<br/>→ Lambda)
    participant E as Nitro Enclave<br/>(CID 16, non-debug<br/>terminates TLS)
    participant R as vsock Relay<br/>(host, ports 8001/8002)
    participant SRC as Source APIs<br/>(Amazon SP / KYC mocks)

    Note over C,SRC: STEP 1 — Request initiated
    C->>C: Generate requestId (UUID)<br/>Generate evidenceId (UUID)

    Note over C,OL: STEP 2 — Challenge
    C->>OL: POST /v1/challenges<br/>{"requestId","sourceId","policyVersion":"v1.0"}
    OL->>OL: Validate sourceId ∈ {7 allowed}<br/>Load policy (DynamoDB)<br/>nonce = randomBytes(32).base64url<br/>Sign challenge (KMS ECDSA)
    OL-->>C: 201 {nonce, policyVersion, endpointScope,<br/>issuedAt, expiresAt, challengeSignature}

    Note over C,E: STEP 3 — Hand off to enclave (AF_VSOCK, length-prefixed)
    C->>E: [4-byte len] + JSON<br/>{"requestId","nonce","policyVersion",<br/>"evidenceId","eifDigest","sourceId"}

    Note over E,SRC: STEP 4 — Enclave terminates TLS + fetches source
    E->>E: SourceRegistry.resolve(sourceId)<br/>→ {provider, method, path, host, port}
    E->>R: AF_VSOCK connect (host CID 3, port 8001/8002)
    R->>SRC: TCP relay → provider:443
    Note over E,SRC: TLS handshake terminates INSIDE enclave<br/>Relay only sees ciphertext
    E->>SRC: GET /sales/v1/orderMetrics HTTP/1.1<br/>Host: ...amazonaws.com<br/>x-api-key: <baked>
    SRC-->>E: HTTP/1.1 200 OK<br/>{"payload":[...]}
    Note over E: rawResponseBytes = full wire bytes<br/>(status + headers + body)

    Note over E: STEP 5 — Transform + hash
    E->>E: rawPayload = parseBody(rawResponseBytes)<br/>transformed = rawPayload (pass-through)<br/>rawHash = SHA-256(rawResponseBytes)<br/>transformedHash = SHA-256(canonicalize(transformed))

    Note over E: STEP 6 — Attest + sign
    E->>E: Generate ephemeral EC P-256 key pair<br/>Build user_data binding:<br/>{requestId, nonce, policyVersion,<br/>sourceId, rawHash, transformedHash, publicKey}
    E->>E: Request Nitro attestation document<br/>(user_data = canonical(binding) bytes)<br/>→ AWS-signed CBOR: PCR0/1/2 + user_data + pubkey
    E->>E: Build manifest (9 fields)<br/>manifestDigest = SHA-256(canonicalize(manifest))<br/>enclaveSignature = ECDSA(manifest, ephemeral key)

    Note over E,C: STEP 7 — Return to Coordinator
    E-->>C: [4-byte len] + evidence JSON<br/>{rawPayload, rawResponseB64, hashes,<br/>enclaveSignature, attestationDocument, ...}

    Note over C: Build submission envelope<br/>{requestId, nonce, policyVersion, evidenceId,<br/>manifestDigest, encryptedEvidenceReference, submittedAt}
    C->>C: submissionSignature = ECDSA(envelope, Dowsure private key)

    Note over C,OL: STEP 8 — Submit evidence
    C->>OL: POST /v1/evidence<br/>(22 required fields: all evidence +<br/>envelope + both signatures + pcr0/1/2)

    Note over OL: STEP 9 — Verification pipeline
    OL->>OL: ① Challenge: exists? used? expired? nonce match? scope?
    OL->>OL: ② Release: eifDigest registered + ACTIVE? pcr0/1/2 match?
    OL->>OL: ③ Re-hash rawResponseB64 → must equal rawPayloadDigest
    OL->>OL: ④ Re-canonicalize transformedPayload → must equal transformedPayloadDigest
    OL->>OL: ⑤ Rebuild manifest → hash must equal manifestDigest
    OL->>OL: ⑥ Validate envelope shape
    OL->>OL: ⑦ Verify Dowsure signature (pubkey from release table)
    OL->>OL: ⑧ Verify enclave signature (pubkey from attestation)
    OL->>OL: ⑨ Verify Nitro attestation doc (AWS PKI, PCRs, user_data binding)
    OL->>OL: ⑩ Mark challenge used (atomic CAS)

    OL->>OL: Store evidence → S3 (KMS-encrypted)<br/>Store receipt → DynamoDB
    OL-->>C: 202 ACCEPTED<br/>{"evidenceId","status":"ACCEPTED",<br/>"reasonCode":"SUCCESS","manifestDigest","acceptedAt"}
```

## Component view (PoC — where things run)

```mermaid
graph TB
    subgraph HOST["Nitro Host (i-0b2b6aa26fb920103)"]
        COORD["Coordinator JAR<br/>• Drives the flow<br/>• Signs envelope (Dowsure key)<br/>• CANNOT see source plaintext<br/>inside enclave"]
        RELAY1["vsock-proxy :8001<br/>→ Amazon mock :443"]
        RELAY2["vsock-proxy :8002<br/>→ KYC mock :443"]
    end

    subgraph ENCLAVE["Nitro Enclave (CID 16, non-debug)"]
        ESVC["EnclaveService<br/>• SourceRegistry (7 endpoints)<br/>• SourceTlsClient (terminates TLS)<br/>• PocCredentialProvider<br/>• AttestationProvider (/dev/nsm)<br/>• Ephemeral key gen + signing"]
        VSRV["VsockServer :5005<br/>(length-prefixed framing)"]
    end

    subgraph AWS["AWS (ap-southeast-1, 706179786846)"]
        APIGW["API GW: oracle.oleainternal.com"]
        LAMBDA["Verifier Lambda<br/>• Challenge issue<br/>• Evidence verification<br/>• 16 rejection codes"]
        DDB["DynamoDB<br/>• ChallengeTable<br/>• ReleaseTable<br/>• PolicyTable<br/>• ReceiptTable"]
        S3["S3: evidence vault<br/>(KMS-encrypted)"]
        KMS["KMS: challenge signing"]
    end

    subgraph SOURCES["Source APIs (mocks in PoC)"]
        AMOCK["Amazon mock<br/>097sqg03n1...amazonaws.com"]
        KMOCK["KYC mock<br/>i8yde0kf2g...amazonaws.com"]
    end

    COORD <-->|AF_VSOCK<br/>CID 16:5005<br/>len-prefix frames| VSRV
    ESVC <-->|AF_VSOCK<br/>CID 3:8001/8002| RELAY1
    ESVC <-->|AF_VSOCK<br/>CID 3:8001/8002| RELAY2
    RELAY1 <-->|TCP/TLS| AMOCK
    RELAY2 <-->|TCP/TLS| KMOCK
    COORD <-->|HTTPS| APIGW
    APIGW --> LAMBDA
    LAMBDA <--> DDB
    LAMBDA --> S3
    LAMBDA <--> KMS
```
