# Bind TLSNotary proof and Nitro attestation to the same raw HTTP response bytes

This change closes a trust gap in the three-part evidence contract: previously the TLSNotary sidecar committed `response_hash` over the exact revealed recv wire bytes, but the Nitro enclave and the Olea verifier re-derived their "raw" hash from a *re-canonicalized parsed JSON object* (`sha256(canonical(rawPayload))`). Those two values could diverge whenever JSON serialization differed from the wire bytes (key ordering, whitespace, numeric formatting, duplicate keys), which would let a parsed-but-not-byte-equal payload pass as attested. The fix makes a single source of truth: the sidecar now emits the exact revealed recv bytes as STANDARD base64 (`revealed_recv_b64`), and both the enclave and the verifier now hash the *decoded* `rawResponseB64` wire bytes for the raw-payload digest. The parsed `rawPayload` object is retained only for the transform step and for storage, never for the raw hash.

Watch for: the producers that populate `rawResponseB64` (coordinator request builder, verifier evidence assembler) are explicitly out of scope here (confirmed in the evidence note) — the enclave and verifier now *require and consume* the field, but wiring the producers to send `base64(revealed recv bytes)` is a separate follow-up. Until that follow-up lands, a producer that still sends canonicalized JSON would fail `TLS_PROOF_HASH_MISMATCH` / `RAW_PAYLOAD_HASH_MISMATCH` (fail-closed, which is the safe direction). (confirmed)

**Verdict**: APPROVED

## High-level view

The sidecar change is minimal and exact: one struct field plus one assignment, both reusing the identical `recv_bytes_revealed` slice (`&partial.received_unsafe()[..recv_end]`) that already feeds `response_hash`. Because both derive from the same slice, `sha256(base64decode(revealed_recv_b64)) === response_hash` holds by construction, with no change to the committed/revealed ranges or to how `response_hash` is computed.

The enclave now decodes `rawResponseB64` and hashes those bytes via a new `sha256Bytes(byte[])` helper, instead of hashing the canonicalized parsed object. The existing `sha256(String)` is refactored to delegate to `sha256Bytes` so no other hash site changes behavior. The `TLS_PROOF_HASH_MISMATCH` gate against `tlsProof.responseHash` is retained, the attestation `user_data` binding map keeps the exact field order {requestId, nonce, policyVersion, rawHash, transformedHash, publicKey, tlsProofHash}, and `tlsProofResponseHash` / `rawPayloadDigest` both still equal `rawHash`. The parsed `rawPayload` still drives the transform and still appears in evidence output.

The verifier adds `rawResponseB64` to the required set and swaps its raw-payload digest check from `sha256(canonicalize(rawPayload))` to `sha256(Buffer.from(rawResponseB64,'base64'))`. The transformed-payload digest check and the `tlsProofResponseHash === rawPayloadDigest` check are untouched, so the end-to-end chain stays consistent.

The scope is surgical: three production files, their tests, and the `.agents/tasks` notes. No new Rust architecture, no unrelated reformatting, and the recorded build/test evidence (Maven BUILD SUCCESS 3/3; node 28/28 including the two new base64->sha256 tests) was reviewed rather than re-run, per instruction.

<details>
<summary>Issues (1)</summary>

1. **Producer wiring is a follow-up (non-blocking)** — the enclave and verifier now require `rawResponseB64`, but the components that populate it are out of scope for this change. Fail-closed until the producers are wired; track as a separate task before end-to-end runs. (confirmed, informational)

</details>

<details>
<summary>Details</summary>

## Sidecar: one slice, two consumers

The revealed recv bytes are computed once and consumed by both the hash and the new base64 field:

```rust
let recv_end = partial.received_authed().end();
let recv_bytes_revealed = &partial.received_unsafe()[..recv_end];
...
let response_hash = sha256_hex(recv_bytes_revealed);
...
revealed_recv_b64: base64::engine::general_purpose::STANDARD.encode(recv_bytes_revealed),
```

`response_hash` and `revealed_recv_b64` consume the identical `recv_bytes_revealed` slice, so `sha256(base64decode(revealed_recv_b64)) === response_hash` is true by construction. The committed/revealed byte range (`[..recv_end]`), the `response_hash` computation, and `revealed_recv_preview` are all unchanged. The STANDARD engine matches the one already used for `attestation_b64`/`presentation_b64`, and the `base64::Engine` trait was already in scope, so no new imports or dependencies. This is a single struct field plus a single assignment — no new Rust architecture.

## Enclave: hash the wire bytes, keep everything else

```java
byte[] rawResponseBytes = Base64.getDecoder().decode((String) request.get("rawResponseB64"));
String rawHash = sha256Bytes(rawResponseBytes);
if (!rawHash.equals(tlsProof.get("responseHash"))) throw new IllegalArgumentException("TLS_PROOF_HASH_MISMATCH");
```

`rawHash` now derives from the decoded wire bytes rather than `sha256(canonical(rawPayload))`. The new `sha256Bytes(byte[])` carries the actual digest logic and the pre-existing `sha256(String)` delegates to it (UTF-8 bytes), so every other hash call site is behavior-preserving. The parsed `rawPayload` object is still used for `transform(rawPayload)` and still stored in evidence. The `TLS_PROOF_HASH_MISMATCH` check is retained verbatim, and the binding map field order {requestId, nonce, policyVersion, rawHash, transformedHash, publicKey, tlsProofHash} is confirmed unchanged (lines 51-57, outside the diff). `evidence.tlsProofResponseHash` and `evidence.rawPayloadDigest` both equal `rawHash`, so the attestation `user_data` binding changes only in the *source* of `rawHash`, not its structure.

## Verifier: decoded-bytes digest, unchanged chain

```js
const rawResponseBytes = Buffer.from(body.rawResponseB64, 'base64');
if (crypto.createHash('sha256').update(rawResponseBytes).digest('hex') !== body.rawPayloadDigest) throw new Error('RAW_PAYLOAD_HASH_MISMATCH');
```

`rawResponseB64` is added to the `required` list (so a missing field fails fast with `PAYLOAD_CORRUPTED`), and the raw-payload digest check hashes the decoded wire bytes instead of `sha256(canonicalize(rawPayload))`. The transformed-payload digest check (`sha256(canonicalize(transformedPayload))`) and the `tlsProofResponseHash === rawPayloadDigest` check are untouched. `crypto` was already imported at the top of the file. The result: `sha256(base64decode(rawResponseB64)) === rawPayloadDigest === tlsProofResponseHash` across all three components.

## Tests lock the invariant on both sides

The enclave test was restructured around a `request(...)` helper that supplies `rawResponseB64 = base64(rawBytes)` and derives `responseHash = service.sha256Bytes(rawBytes)`, so the proof's `responseHash` equals the enclave's `rawHash` by the new rule. The happy-path test additionally asserts `rawHash === evidence.tlsProofResponseHash`. The mismatch test deliberately sets `responseHash = "wrong"` with *valid* base64, confirming the code reaches `TLS_PROOF_HASH_MISMATCH` after `require()` passes. The new verifier test file asserts the exact `crypto.createHash('sha256').update(Buffer.from(b64,'base64'))` relation the handler uses, plus the tampered-bytes negative. These lock the behavioral contract without reaching into the AWS-backed handler path.

## Build/test evidence (reviewed, not re-run)

Per instruction, the recorded evidence was read rather than re-executed. The verification note reports: enclave `mvn -pl nitro-enclave -am clean test` -> BUILD SUCCESS, `Tests run: 3, Failures: 0`; verifier `node --test` -> tests 28, pass 28, fail 0 (including the two new tests). The sidecar had no `cargo` on PATH, so it was manually reviewed; the slice-reuse argument above independently confirms invariant 1 holds regardless of a compile. The evidence is present and specific, so no suite re-run and no rejection-for-missing-evidence applies.

## Scope and secrets

Changed files: the three production files, their test files, and `.agents/tasks/{plan.md, verification-note.md}` — within the stated scope. No unrelated reformatting appears in any diff hunk. No new logging statements were added, so no secrets/tokens/payloads/PII enter logs. No live-infra commands are present in the diff or evidence (the note explicitly records that no live notarization and no `cargo build` were run).

</details>

<details>
<summary>File map</summary>

- `tls-notary/prover-sidecar/src/main.rs` — add `revealed_recv_b64` struct field + assignment from the existing revealed recv slice.
- `nitro-enclave/.../EnclaveService.java` — require `rawResponseB64`; `rawHash` = sha256 of decoded bytes via new `sha256Bytes`; `sha256(String)` delegates to it.
- `nitro-enclave/.../EnclaveServiceTest.java` — `request(...)` helper supplies `rawResponseB64`; asserts `rawHash === tlsProofResponseHash`; mismatch test uses valid base64.
- `sam/olea/functions/verification/index.js` — require `rawResponseB64`; raw-payload digest hashes decoded wire bytes.
- `sam/olea/functions/verification/index.test.js` — new; locks base64->sha256 invariant + tampered negative.
- `.agents/tasks/plan.md`, `.agents/tasks/verification-note.md` — task notes/evidence.

Full diff: `git -C <worktree> diff main...bind-response-bytes`

</details>
