# Olea–Dowsure Verifiable Data Oracle — Presentation Deck (source)

> Maintainable source for the presentation. The rendered slide deck is generated
> from this file. Keep live URLs, IDs, and ownership here; this is the single
> place to edit for the deck. Status reflects the LIVE state as of this session
> (TLSNotary is now REAL, not the placeholder that older docs describe).

---

## Slide 1 — Title

**Olea–Dowsure Verifiable Data Oracle**
Proving where seller data came from — cryptographically.

- Two independent proofs on every piece of data: **TLS** (it really came from Amazon) + **TEE** (a tamper-proof enclave processed it).
- Status: live end-to-end proof-of-concept in Olea preprod + dev.

---

## Slide 2 — Explain Like I'm 5

Imagine Dowsure says to Olea: *"This seller sold $25,000 on Amazon, trust me."*

Olea's problem: **why should it trust Dowsure's copy of the data?** Dowsure could mistype, cache something stale, or (in theory) fudge a number.

So we add two "tamper-evident seals":

1. **TLS seal (TLSNotary)** — like a notary who watches the HTTPS call to Amazon and stamps *"these exact response bytes really came from Amazon's server."* Dowsure cannot change a single byte without breaking the seal.
2. **TEE seal (Nitro Enclave attestation)** — the data is processed inside a sealed box (a Nitro Enclave) that no human can open or peek into. The box signs a receipt saying *"I am the approved program, and I processed exactly this data."*

Olea checks both seals. If either is broken, Olea rejects. If both hold, Olea accepts — **without ever having to trust Dowsure's word.**

The magic word binding them: a **nonce** (a one-time random number Olea issues). Both seals must carry the same nonce, so an old proof can't be replayed.

---

## Slide 3 — The cast (who is who)

| Party | Role in the story |
|---|---|
| **Seller / Merchant** | Owns the Amazon shop; authorizes access (Login with Amazon). |
| **Amazon SP-API** | The real source of truth: sales, finances, transactions, repayments. |
| **Dowsure** | The lender. Fetches seller data, requests financing, runs the coordinator. |
| **Olea** | The verifier. Issues challenges (nonces), checks both seals, keeps the receipt. |
| **Notary** | Independent witness to the HTTPS call. Signs the TLS proof. |
| **Nitro Enclave** | The sealed box that processes data and produces the attestation. |
| **KYC providers** | AliCloud (identity), Qichacha (enterprise/judicial), Gutu (judicial panorama). |

---

## Slide 4 — End-to-end flow (happy path)

```
Seller authorizes  ─▶  Amazon SP-API (source data)
                               │
Olea issues a nonce ◀──────────┤  (1) Dowsure asks Olea for a challenge
                               ▼
                 (2) Notary watches the HTTPS call to Amazon
                     and signs:  "these response bytes are real"  ── TLS SEAL
                               │
                               ▼
                 (3) Dowsure coordinator sends the data + TLS proof
                     into the Nitro Enclave over a virtual socket (vsock)
                               │
                               ▼
                 (4) Enclave checks the TLS proof, hashes the SAME bytes,
                     signs a Nitro attestation binding
                     {nonce, data hash, TLS proof hash, its own key} ── TEE SEAL
                               │
                               ▼
                 (5) Dowsure submits evidence (both seals) to Olea
                               │
                               ▼
                 (6) Olea verifies: notary key pinned, domain = Amazon,
                     nonce matches, enclave PCRs match the approved release,
                     attestation chains to AWS Nitro root  ──▶  ACCEPT + receipt
```

The key invariant (what makes it airtight): **the TLS seal and the TEE seal both measure the exact same Amazon response bytes.** `tlsProof.responseHash == rawPayloadDigest`.

---

## Slide 5 — Who hosts what (NOW — PoC)

| Component | Hosted on (NOW) | AWS account | Owner (NOW) |
|---|---|---|---|
| **Olea verifier API** | API Gateway + Lambda (`olea-oracle-preprod`) | preprod `706179786846` | Olea (us) |
| **Olea evidence vault + registries** | S3 (Object Lock) + DynamoDB | preprod `706179786846` | Olea (us) |
| **Notary** | ECS Fargate on `olea-dev-cluster` | dev `855703743734` | Olea-hosted (us), on Dowsure's behalf for PoC |
| **Prover sidecar** | ECS Fargate (same cluster) | dev `855703743734` | Olea (us) — stands in for Dowsure |
| **Nitro Enclave + host** | EC2 Nitro host `i-0b2b6aa26fb920103` | preprod `706179786846` | Olea (us) |
| **Coordinator** | CLI (Java) run operator-side | — | Olea (us) — stands in for Dowsure |
| **Amazon SP-API** | Amazon sandbox (`sandbox.sellingpartnerapi-na.amazon.com`) | Amazon | Amazon |
| **Source + KYC mocks** | API Gateway MOCK integrations | preprod `706179786846` | Olea (us) |

> "We test as if Dowsure is testing on our infra" — for the PoC, Olea hosts the notary and runs the coordinator/sidecar so the whole chain can be exercised end to end.

---

## Slide 6 — Who hosts what (FUTURE — production)

| Component | Hosted on (FUTURE) | Owner (FUTURE) | Why |
|---|---|---|---|
| **Olea verifier API, vault, registries** | Olea production AWS | **Olea** | Olea is the trust anchor; must own verification + evidence. |
| **Notary** | Olea-operated, independent of Dowsure | **Olea** | A notary Dowsure controlled would defeat the point. Decoupled from the enclave host so removing Nitro never breaks it. |
| **Prover sidecar + coordinator** | Dowsure infrastructure | **Dowsure** | Dowsure is the party fetching its sellers' data; the prover runs where the data is pulled. |
| **Nitro Enclave + host** | Olea or a neutral operator | **Olea** | The enclave's measurement (PCRs) is the trust root; Olea approves + pins each release. |
| **Amazon SP-API** | Amazon production | **Amazon** | — |
| **KYC providers** | Real AliCloud / Qichacha / Gutu | **Dowsure (contracts)** | Dowsure holds the KYC vendor relationships. |

Trust boundary (future): **Dowsure proves, Olea verifies, the Notary + Enclave are the neutral witnesses.** No single party can forge evidence alone.

---

## Slide 7 — What each component DOES

- **Coordinator (Dowsure):** orchestrates one evidence run. Gets a nonce from Olea, drives the sidecar to notarize, feeds the data + TLS proof into the enclave over vsock, signs the submission envelope with the Dowsure key, submits to Olea.
- **Prover sidecar (Rust, upstream TLSNotary):** performs the MPC-TLS handshake with Amazon alongside the notary; reveals the response bytes; emits the signed proof bundle.
- **Notary (Rust, prebuilt image):** the independent co-signer of the TLS session. Its public key is **pinned** by Olea.
- **Nitro Enclave (Java):** validates the TLS proof, hashes the exact response bytes, runs a deterministic transform, generates an ephemeral key, and asks the Nitro Security Module for an attestation binding {nonce, hashes, TLS proof hash, public key}.
- **Olea verifier (Node/Lambda):** issues challenges; on evidence submit, checks notary-key pin, Amazon domain, nonce freshness + single-use, enclave PCRs vs the approved release, and the attestation's chain to the AWS Nitro root. Writes the receipt to the Object-Lock vault.

---

## Slide 8 — Example payload: source data (Amazon getOrderMetrics)

Live HTTP 200 from the Amazon SP-API sandbox (request id `46343f5c-…`):

```json
{
  "payload": [
    {
      "interval": "2019-08-01T00:00-07:00--2018-08-03T00:00-07:00",
      "unitCount": 2,
      "orderItemCount": 2,
      "orderCount": 2,
      "averageUnitPrice": { "amount": "12.5", "currencyCode": "USD" },
      "totalSales":       { "amount": "25",   "currencyCode": "USD" }
    }
  ]
}
```

The notary seals the **full HTTP response** (status line + headers + this body). Its SHA-256 is what both seals bind to.

---

## Slide 9 — Example payload: the TLS proof bundle (sealed)

What the sidecar + notary emit (abridged — the real bundle also carries the signed presentation + Amazon cert chain):

```json
{
  "ok": true,
  "server_name": "sandbox.sellingpartnerapi-na.amazon.com",
  "notary_pub_key_id": "02888ade3ae6a9c245315b8b20eefdc59709ffa671a258fafdc3c9db1cdf572327",
  "connection_time_unix": 1791159254,
  "response_hash": "b93bbf104aa328ec7d0db6523f4a817418b937bd8a9d6f5ba72226b338f99e27",
  "nonce": "olea-e2e-5edcfc7c63d147d4",
  "presentation_b64": "…(signed TLSNotary presentation)…"
}
```

Olea pins `notary_pub_key_id`. Any other signer → `NOTARY_KEY_UNTRUSTED`.

---

## Slide 10 — Example payload: the enclave attestation binding

The enclave signs a Nitro attestation whose `user_data` is this canonical object (one-time, tamper-proof):

```json
{
  "requestId":       "…uuid…",
  "nonce":           "olea-e2e-5edcfc7c63d147d4",
  "policyVersion":   "v1.0",
  "rawHash":         "b93bbf10…  (== the TLS response_hash)",
  "transformedHash": "…hash of the normalized orders…",
  "publicKey":       "…enclave ephemeral public key…",
  "tlsProofHash":    "0f9c55d3…  (hash of the whole TLS proof)"
}
```

`rawHash == response_hash` is the bridge: the TEE attests the exact bytes the TLS notary sealed.

---

## Slide 11 — Example payload: KYC mocks (Phase 2)

Identity (AliCloud three-element), live mock 200 — test data only:

```json
{ "code": "0", "msg": "成功",
  "data": { "result": 1, "desc": "一致", "orderNo": "ALI2023…",
            "name": "张伟", "idcard": "1101011990030785xx", "mobile": "138001380xx" } }
```

Judicial panorama (Gutu), live mock 200 — note `riskLevel` and one enforcement case:

```json
{ "code": 200, "message": "success",
  "data": { "riskLevel": "MEDIUM",
            "judicialSummary": { "dishonestCount": 0, "enforcementCount": 1,
              "caseList": [ { "caseType": "执行", "amount": "50000", "filingDate": "2021-06-18" } ] } } }
```

KYC feeds a ~16-rule engine → risk level NONE/MEDIUM/HIGH → Accept / Review / Reject.

---

## Slide 12 — Live staging URLs + endpoints

| What | URL / value |
|---|---|
| **Olea verifier API** (preprod) | `https://c8tw99zmla.execute-api.ap-southeast-1.amazonaws.com/preprod` |
| &nbsp;&nbsp;Challenge | `POST /v1/challenges` |
| &nbsp;&nbsp;Submit evidence | `POST /v1/evidence` |
| &nbsp;&nbsp;Register / revoke release | `POST /v1/releases`, `POST /v1/releases/{eifDigest}/revoke` |
| **Amazon SP-API mock** (preprod) | `https://097sqg03n1.execute-api.ap-southeast-1.amazonaws.com/mock` (API key id `o3edrh1qi4`) |
| **KYC mock** (preprod) | `https://i8yde0kf2g.execute-api.ap-southeast-1.amazonaws.com/mock` (API key id `4oyo8bv75g`) |
| **Amazon SP-API sandbox** (real) | `https://sandbox.sellingpartnerapi-na.amazon.com` |
| **Notary** (dev, in-VPC) | `10.2.x.x:7047` (plaintext, VPC-internal; private per run) |

KYC mock paths: `/lundear/telThree` (AliCloud), `/EnterpriseInfo/Verify`, `/ShixinCheck/GetList`, `/ZhixingCheck/GetList`, `/SumptuaryCheck/GetList`, `/BankruptcyCheck/GetList` (Qichacha), `/api/v1/judicial/panorama-checks` (Gutu).

> API key **values** are never stored in the repo; fetch with `aws apigateway get-api-key --api-key <id> --include-value`.

---

## Slide 13 — What's proven today vs. what's next

**Proven live (this PoC):**
- 3/3 Amazon SP-API sandbox calls → HTTP 200 (getOrderMetrics, listFinancialEventGroups, listTransactions).
- 7/7 preprod mocks (Amazon + KYC) → 200 with key, 403 without.
- Real MPC-TLS notarization of the Amazon sandbox via the Olea-hosted notary → signed proof bundle, nonce-bound.
- Olea JS verifier accepts the live TLS proof (notary-pinned); wrong key rejected.
- Nitro Enclave live, non-debug, PCRs registered ACTIVE; attestation chains to AWS Nitro root.

**In flight / next:**
- Bind both seals to the identical response bytes (`responseHash == rawPayloadDigest`) and run the full `POST /v1/evidence` → **202 ACCEPTED** receipt.
- Wire Financial Event Groups + Super PO + Repayment flows (Repayment is TEE-only by Amazon contract).
- Move the prover/coordinator to Dowsure infra; keep notary + enclave Olea-operated.

---

## Slide 14 — The trust summary (one line)

**Dowsure proves. Olea verifies. The Notary and the Enclave are the neutral witnesses. No single party can forge the evidence — and every receipt is bound to a one-time nonce.**

---

## Slide 15 — The flow in 10 plain lines (for the dev team)

1. Dowsure asks Olea to start a check. Olea hands back a one-time ticket number.
2. Dowsure calls Amazon for the seller's data over a secure connection.
3. An independent witness (the notary) watches that call and stamps "these exact bytes came from Amazon."
4. The stamp carries the one-time ticket, so it can't be reused later.
5. Dowsure hands the data plus the stamp into a sealed, tamper-proof box (the enclave).
6. The box re-checks that the data matches what the witness stamped — down to the byte.
7. The box signs a receipt: "I'm the approved program and I processed exactly this."
8. Dowsure signs the whole package with its own key and sends it to Olea.
9. Olea verifies every seal: witness stamp, box receipt, matching bytes, ticket, Dowsure signature.
10. All seals hold → Olea accepts and files an unchangeable copy; any seal off → reject.
