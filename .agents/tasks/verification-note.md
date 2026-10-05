# Verification note — bind TLSNotary proof and Nitro attestation to the SAME response bytes

Branch: `bind-response-bytes`

Three-part contract change: the Rust sidecar now emits the exact revealed recv bytes as
STANDARD base64 (`revealed_recv_b64`), and both the Nitro enclave and the Olea verifier now
derive the raw-response hash from the DECODED `rawResponseB64` wire bytes instead of from a
re-canonicalized parsed JSON object. The committed/revealed byte ranges, the attestation
`user_data` binding, and every other hash/signature are unchanged.

## Files changed (production + tests only)

1. `tls-notary/prover-sidecar/src/main.rs` — one struct field + one assignment.
2. `nitro-enclave/src/main/java/com/olea/dowsure/enclave/EnclaveService.java` — require
   `rawResponseB64`; `rawHash` now hashes decoded wire bytes via new `sha256Bytes(byte[])`.
3. `nitro-enclave/src/test/java/com/olea/dowsure/enclave/EnclaveServiceTest.java` — tests supply
   `rawResponseB64` whose decoded bytes hash to the proof's `responseHash`.
4. `sam/olea/functions/verification/index.js` (`submitEvidence`) — require `rawResponseB64`;
   raw-payload digest check hashes decoded wire bytes.
5. `sam/olea/functions/verification/index.test.js` (new) — locks the base64 -> sha256 invariant
   the verifier enforces, plus the negative (tampered bytes -> different digest).

## Commands run and results

### SIDECAR (Rust) — manual review (no `cargo` on PATH)

`Get-Command cargo` returned NOT FOUND, so no `cargo build` was run (and no live notarization
was run, per constraint). Manual review confirms:

- `ProofBundle` now has `revealed_recv_b64: String` placed next to `revealed_recv_preview`
  (which is kept). The struct still derives `serde::Serialize`.
- The bundle literal sets
  `revealed_recv_b64: base64::engine::general_purpose::STANDARD.encode(recv_bytes_revealed)`,
  reusing the EXACT `recv_bytes_revealed` slice (`&partial.received_unsafe()[..recv_end]`) that
  feeds `response_hash = sha256_hex(recv_bytes_revealed)`. Same STANDARD engine as
  `attestation_b64`/`presentation_b64`; the `base64::Engine as _` trait is already imported.
  `recv_bytes_revealed: &[u8]` satisfies `encode(AsRef<[u8]>)` — syntactically correct.
- No change to the committed/revealed ranges or to how `response_hash` is computed.
- Invariant 1 holds: `sha256(base64_decode(revealed_recv_b64)) == response_hash` because both
  derive from the identical slice.

### ENCLAVE (Java, JDK 21)

Toolchain: `JAVA_HOME = %USERPROFILE%\scoop\apps\temurin21-jdk\current` (openjdk 21.0.11 LTS),
`bin` prepended to PATH. There is no `mvnw.cmd` in the worktree/repo, so the system Maven
(`%USERPROFILE%\scoop\apps\maven\current\bin\mvn.cmd`, 3.9.16) was used with JDK 21 active.

```powershell
$env:JAVA_HOME="$env:USERPROFILE\scoop\apps\temurin21-jdk\current"
$env:Path="$env:JAVA_HOME\bin;"+$env:Path
mvn -pl nitro-enclave -am clean test
```

Result: **BUILD SUCCESS**. Fresh compile `javac [debug release 21]` (5 main source files, 1 test
source). `EnclaveServiceTest`: `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`.

### VERIFIER (Node v24)

```powershell
cd sam/olea/functions/verification
node --test
```

Result: **tests 28, pass 28, fail 0** (includes the 2 new tests in `index.test.js`:
`rawResponseB64 decoded bytes hash to rawPayloadDigest` and the tampered-bytes negative).

## Cross-component invariants (confirmed)

1. Sidecar: `sha256(base64_decode(revealed_recv_b64)) == response_hash` — same slice. ✔
2. Enclave: `rawHash == sha256(base64_decode(rawResponseB64))` and the
   `TLS_PROOF_HASH_MISMATCH` gate on `tlsProof.responseHash` is unchanged. ✔
   `evidence.rawPayloadDigest == rawHash == evidence.tlsProofResponseHash` (asserted in test). ✔
3. Verifier: `sha256(base64_decode(rawResponseB64)) == rawPayloadDigest == tlsProofResponseHash`
   (the `tlsProofResponseHash === rawPayloadDigest` check is unchanged). ✔
4. Attestation `user_data` binding `{requestId, nonce, policyVersion, rawHash, transformedHash,
   publicKey, tlsProofHash}` canonical-JSON encoding is UNCHANGED — only the SOURCE of `rawHash`
   changed. `rawPayload` (parsed JSON) is still used for `transform(rawPayload)` and stored under
   `evidence.rawPayload`. ✔
5. No secrets/tokens/payloads/PII added to any log line. ✔

## Boundary / out of scope (note only)

The producers that POPULATE `rawResponseB64` (coordinator building the enclave request, and the
assembler of the verifier evidence body) are out of scope per the "three files + their tests"
constraint. This change makes the enclave and verifier REQUIRE and CONSUME `rawResponseB64`;
wiring the producers to send `base64(revealed recv bytes)` is a separate follow-up. The tests
supply `rawResponseB64` directly so the three components verify in isolation.
