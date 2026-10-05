# Implementation Plan — bind TLSNotary proof and Nitro attestation to the SAME response bytes

## Goal

Make the TLSNotary proof and the Nitro enclave attestation bind to the SAME bytes — the exact
HTTP response bytes Amazon's SP-API sandbox sent on the wire. Path A1: both the enclave and the
Olea verifier hash the actual notarized response bytes (base64 `rawResponseB64`), and the Rust
sidecar additionally emits those exact bytes as base64 so a consumer can reproduce the hash.

## Scope (surgical — these files only)

Three production files + their tests, all under
`c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle\.worktrees\bind-response-bytes`:

1. `tls-notary/prover-sidecar/src/main.rs` (Rust — ONE struct field + ONE assignment)
2. `nitro-enclave/src/main/java/com/olea/dowsure/enclave/EnclaveService.java` + its test
   `nitro-enclave/src/test/java/com/olea/dowsure/enclave/EnclaveServiceTest.java`
3. `sam/olea/functions/verification/index.js` (`submitEvidence`) — no JS test currently exercises
   `submitEvidence`, so a focused test is ADDED (see item 6)

Do NOT reformat unrelated code. Java + JS for our code; Rust is limited to the existing sidecar.

## Verified facts from exploration (ground truth)

- Sidecar `main.rs`: `ProofBundle` struct is defined around lines 115-130; it serializes with
  `#[derive(serde::Serialize)]` so field names map to snake_case JSON. The revealed recv slice is
  already computed as `let recv_bytes_revealed = &partial.received_unsafe()[..recv_end];` immediately
  before `let response_hash = sha256_hex(recv_bytes_revealed);`. The bundle literal (the
  `let bundle = ProofBundle { ... }` block) uses
  `base64::engine::general_purpose::STANDARD.encode(&attestation_bytes)` for `attestation_b64` /
  `presentation_b64`. `base64::Engine as _` is already imported at the top.
- Enclave `EnclaveService.java`: `acquire` currently calls
  `require(request, "requestId", "nonce", "policyVersion", "evidenceId", "eifDigest", "rawPayload", "tlsProof");`
  then `String rawHash = sha256(canonical(rawPayload));` and
  `if (!rawHash.equals(tlsProof.get("responseHash"))) throw new IllegalArgumentException("TLS_PROOF_HASH_MISMATCH");`.
  Helpers present: `String sha256(String value)` (UTF-8 -> SHA-256 hex via `HexFormat`/`MessageDigest`),
  `String canonical(Object value)`. `java.util.Base64` and `java.security.MessageDigest`,
  `java.util.HexFormat`, `java.nio.charset.StandardCharsets` are already imported. `rawPayload` (parsed
  JSON map) is used by `transform(rawPayload)` and stored via `evidence.put("rawPayload", rawPayload)`.
  `evidence.put("rawPayloadDigest", rawHash)` and `evidence.put("tlsProofResponseHash", rawHash)` both
  use `rawHash`. The attestation user_data binding map keys are
  `{requestId, nonce, policyVersion, rawHash, transformedHash, publicKey, tlsProofHash}`.
- Enclave test `EnclaveServiceTest.java`: three tests. Each builds `raw` (a parsed JSON map),
  derives `rawHash = service.sha256(service.canonical(raw))`, sets `proof.responseHash = rawHash`, and
  builds `request` with `rawPayload=raw` and `tlsProof=proof`. Because today's enclave hashes
  `canonical(rawPayload)`, these pass; after the change the enclave hashes decoded `rawResponseB64`, so
  each request must carry a `rawResponseB64` whose decoded bytes hash to the proof's `responseHash`.
- Verifier `index.js` `submitEvidence`: `required` array lists all current fields (no `rawResponseB64`).
  Current digest check: `if (sha256(canonicalize(body.rawPayload)) !== body.rawPayloadDigest) throw new Error('RAW_PAYLOAD_HASH_MISMATCH');`
  The transformed check (`sha256(canonicalize(body.transformedPayload)) !== body.transformedPayloadDigest`)
  and `body.tlsProofResponseHash !== body.rawPayloadDigest` stay. `crypto` is already required
  (`const crypto = require('node:crypto');`). `sha256`/`canonicalize` come from `verification-contract.js`.
- `verification-contract.js`: `sha256(value)` = `crypto.createHash('sha256').update(value).digest('hex')`
  — it accepts a Buffer directly, so `sha256(rawResponseBytes)` also works; the plan uses the explicit
  `crypto.createHash` form from the task for clarity. `buildManifest(body)` reads `rawPayloadDigest`
  (unchanged — no edit needed).

## Build / test environment (verified)

- There is NO Maven wrapper (`mvnw.cmd`) in the worktree or repo. The enclave build therefore uses the
  system `mvn` (present on PATH) and MUST run under JDK 21. Default `java` on PATH is 17; the parent pom
  sets `maven.compiler.release=21`. Switch to JDK 21 first with the steering alias `juse21` (JDK 21 is at
  `%USERPROFILE%\scoop\apps\temurin21-jdk\current`). The task text references `& .\mvnw.cmd`; since no
  wrapper exists, the equivalent command is `mvn` with JDK 21 active (see item 4 verify).
- No Rust toolchain (`cargo`) is on PATH, so the sidecar change is verified by MANUAL REVIEW against the
  stated invariant, not `cargo build`. If a Rust toolchain becomes available, run `cargo build` in
  `tls-notary/prover-sidecar/`.
- `node` (v24) is present. The verification dir has no `package.json`/`node_modules`; tests run via the
  built-in runner `node --test`, which auto-discovers `*.test.js`. The verifier module requires the AWS
  SDK lazily, so importing it for tests needs no `node_modules`.
- Windows PowerShell: use `;` not `&&`; no `head`/`tail`.

## Cross-component invariants (must hold after the change — document, do not weaken)

1. Sidecar: `sha256(base64_decode(revealed_recv_b64)) == response_hash`.
2. Enclave: `rawHash == sha256(base64_decode(rawResponseB64))` AND `rawHash == tlsProof.responseHash`.
3. Verifier: `sha256(base64_decode(rawResponseB64)) == rawPayloadDigest == tlsProofResponseHash`.
4. Attestation user_data binding `{requestId, nonce, policyVersion, rawHash, transformedHash, publicKey,
   tlsProofHash}` canonical-JSON-encoded is UNCHANGED (only the SOURCE of `rawHash` changes).
5. No secrets/tokens/payloads/PII added to any log.

## Boundary / assumption (out of scope, note only)

The producers that POPULATE `rawResponseB64` (the coordinator that builds the enclave request, and
whatever assembles the verifier evidence body — e.g. `scripts/tlsnotary-e2e-demo.js`,
`tls-notary/bundle/normalize-bundle.js`) are OUT OF SCOPE for this change per the explicit constraint
"only the three files + their tests." This change makes the enclave and verifier REQUIRE and CONSUME
`rawResponseB64`; wiring the producers to send `base64(revealed recv bytes)` is a separate follow-up.
The tests added/updated here supply `rawResponseB64` directly so the three components are verifiable in
isolation. Flag this boundary in the implementation summary.

---

## Steps

- [ ] 1. Sidecar: add `revealed_recv_b64` to the `ProofBundle` struct.
      In `tls-notary/prover-sidecar/src/main.rs`, add one field to the `#[derive(serde::Serialize)]
      struct ProofBundle` (around lines 115-130). Place it next to `revealed_recv_preview`:
      `revealed_recv_b64: String,`. Make no other struct change; keep `revealed_recv_preview`.
      Files: `tls-notary/prover-sidecar/src/main.rs`
      Verify: visual — the struct now has `revealed_recv_b64: String` and still has
      `revealed_recv_preview: String`.

- [ ] 2. Sidecar: populate `revealed_recv_b64` in the bundle literal.
      In the `let bundle = ProofBundle { ... }` initializer, add
      `revealed_recv_b64: base64::engine::general_purpose::STANDARD.encode(recv_bytes_revealed),`
      (reuse the exact `recv_bytes_revealed` slice already used for `response_hash`; same STANDARD
      engine as `attestation_b64`/`presentation_b64`). Do NOT change the committed/revealed ranges or
      how `response_hash` is computed. Keep `revealed_recv_preview` assignment unchanged.
      Files: `tls-notary/prover-sidecar/src/main.rs`
      Verify: with no `cargo` on PATH, MANUAL REVIEW confirming the invariant
      `sha256(base64_decode(revealed_recv_b64)) == response_hash` holds because both derive from the
      same `recv_bytes_revealed` slice. If a Rust toolchain is available: `cargo build` in
      `tls-notary/prover-sidecar/` succeeds.

- [ ] 3. Enclave: require `rawResponseB64` and hash the DECODED wire bytes for `rawHash`.
      In `EnclaveService.java` `acquire`: (a) add `"rawResponseB64"` to the `require(request, ...)` call
      alongside the existing field names. (b) Replace `String rawHash = sha256(canonical(rawPayload));`
      with hashing of the decoded bytes:
      `byte[] rawResponseBytes = java.util.Base64.getDecoder().decode((String) request.get("rawResponseB64"));`
      `String rawHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawResponseBytes));`
      (wrap in try/catch or add a private `String sha256Bytes(byte[])` helper mirroring the existing
      `sha256(String)` so no checked exception leaks — prefer the small helper for cleanliness). KEEP
      `rawPayload` (parsed map) and its uses: `transform(rawPayload)` and
      `evidence.put("rawPayload", rawPayload)` stay identical. The check
      `if (!rawHash.equals(tlsProof.get("responseHash"))) throw ... TLS_PROOF_HASH_MISMATCH;` stays.
      `binding`, `manifest`, keypair/signature, `evidence.put("rawPayloadDigest", rawHash)`,
      `evidence.put("tlsProofResponseHash", rawHash)` all stay — they already read the new `rawHash`.
      Files: `nitro-enclave/src/main/java/com/olea/dowsure/enclave/EnclaveService.java`
      Verify: compiles under JDK 21 (covered by item 4's test run).

- [ ] 4. Enclave test: supply `rawResponseB64` whose decoded bytes hash to `tlsProof.responseHash`.
      In `EnclaveServiceTest.java`, update all three tests that build a passing/`request` map so the
      enclave's new hashing matches. Pattern per test: choose raw bytes (e.g.
      `byte[] rawBytes = "{\"payload\":{\"Orders\":[{\"orderId\":\"123\"}]}}".getBytes(StandardCharsets.UTF_8);`),
      set `String rawResponseB64 = Base64.getEncoder().encodeToString(rawBytes);`,
      `String responseHash = service.sha256(new String(rawBytes, StandardCharsets.UTF_8));` — NOTE the
      enclave hashes the bytes, so compute the expected hash from the SAME bytes; the simplest match is to
      compute `responseHash` via a byte-based SHA-256 of `rawBytes` (e.g. a small test helper
      `HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawBytes))`) so it equals the
      enclave's `rawHash`. Build `proofMaterial` with that `responseHash`, derive
      `proofHash = service.sha256(service.canonical(proofMaterial))`, build `proof` with
      `responseHash` + `proofHash`, and build `request` adding `"rawResponseB64", rawResponseB64` while
      KEEPING `"rawPayload", raw` (still needed for `transform`). For `rejectsTlsHashMismatch`, keep the
      mismatch by giving `rawResponseB64` bytes that do NOT hash to the proof's `responseHash` (or keep
      `responseHash="wrong"`), and still include a valid-base64 `rawResponseB64` so the `require` passes
      and the code reaches the hash check. For `bindsNonceAndTlsProofHashIntoAttestationUserData`, update
      the same way; its assertions on `userData.nonce`/`tlsProofHash` are unaffected. `Map.of(...)` has a
      10-entry (20-arg) limit — the request map gains one key (now `rawResponseB64`); if any `Map.of`
      call exceeds 10 entries, switch that literal to a `LinkedHashMap` built with `put`.
      Files: `nitro-enclave/src/test/java/com/olea/dowsure/enclave/EnclaveServiceTest.java`
      Verify (JDK 21 active via `juse21`), from the worktree root
      `c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle\.worktrees\bind-response-bytes`:
      `juse21; mvn -q -pl nitro-enclave -am test` — all `EnclaveServiceTest` tests pass. (If a repo
      `mvnw.cmd` is later added, `& .\mvnw.cmd -q -pl nitro-enclave -am test` is the equivalent.)

- [ ] 5. Verifier: require `rawResponseB64` and hash DECODED wire bytes for the raw-payload digest check.
      In `sam/olea/functions/verification/index.js` `submitEvidence`: (a) add `'rawResponseB64'` to the
      `required` array. (b) Replace
      `if (sha256(canonicalize(body.rawPayload)) !== body.rawPayloadDigest) throw new Error('RAW_PAYLOAD_HASH_MISMATCH');`
      with
      `const rawResponseBytes = Buffer.from(body.rawResponseB64, 'base64');`
      `if (crypto.createHash('sha256').update(rawResponseBytes).digest('hex') !== body.rawPayloadDigest) throw new Error('RAW_PAYLOAD_HASH_MISMATCH');`
      Leave the transformedPayload digest check, `tlsProofResponseHash === rawPayloadDigest`, manifest /
      envelope / attestation / notary / nonce checks UNCHANGED. `buildManifest` needs no change.
      Files: `sam/olea/functions/verification/index.js`
      Verify: covered by item 6's `node --test` run.

- [ ] 6. Verifier test: add a focused test for the new raw-bytes digest check in `submitEvidence`.
      No existing JS test exercises `submitEvidence`. Add `index.test.js` in the verification dir using
      the built-in runner (`const {test} = require('node:test'); const assert = require('node:assert');`).
      Because `submitEvidence` reaches the digest check only after DynamoDB lookups (challenge/release),
      prefer a UNIT-level assertion that does not require AWS: either (a) extract no code (keep scope to
      three files) and instead assert the invariant directly against the shared contract — e.g. compute
      `const bytes = Buffer.from('{"payload":{"Orders":[]}}'); const b64 = bytes.toString('base64');
      const digest = require('node:crypto').createHash('sha256').update(bytes).digest('hex');` and assert
      `require('node:crypto').createHash('sha256').update(Buffer.from(b64,'base64')).digest('hex') === digest`
      to lock the base64->sha256 invariant the verifier now enforces; OR (b) if driving `submitEvidence`
      end-to-end, stub the AWS SDK via `process.env` table names plus a mock — this is heavier and risks
      scope creep, so prefer (a). The test MUST also assert the NEGATIVE: tampered base64 bytes produce a
      different digest (so `RAW_PAYLOAD_HASH_MISMATCH` would fire). Do not log secrets.
      Files: `sam/olea/functions/verification/index.test.js` (new)
      Verify, from `c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle\.worktrees\bind-response-bytes/sam/olea/functions/verification/`:
      `node --test` — the new test and the existing `*.test.js` all pass.

- [ ] 7. Final cross-component invariant review (no code change).
      Re-read the three edited files and confirm invariants 1-5 above hold: sidecar b64 derives from the
      same slice as `response_hash`; enclave `rawHash` now equals `sha256(base64decode(rawResponseB64))`
      and still gates on `tlsProof.responseHash`; verifier hashes decoded bytes into the same digest it
      compares to `rawPayloadDigest`/`tlsProofResponseHash`; the user_data binding map is unchanged; no
      token/payload/PII was added to any log line. Confirm no unrelated code was reformatted.
      Files: none (review)
      Verify: re-run item 4 (`juse21; mvn -q -pl nitro-enclave -am test`) and item 6 (`node --test`);
      both green. Sidecar remains manual-review (no `cargo`).
