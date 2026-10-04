# Java coordinator migration (Python → Java)

The change rewrites `coordinator/coordinator.py` as a new top-level `coordinator/` Maven module under a new `dowsure-oracle-parent` aggregator pom, deletes the Python coordinator and its test (plus stale `__pycache__` artifacts), and leaves the enclave + JS scripts as the rest of the runtime. The Java coordinator reproduces the Python flow: generate `requestId`/`evidenceId`, POST a challenge to Olea, dispatch the enclave request over vsock with a half-close before read, build and ECDSA-sign the submission envelope over a byte-exact canonicalization, and print the `{requestId, evidenceId, challenge, evidence}` result that `scripts/phase1-evidence-report.js` consumes. Network and vsock are behind typed adapters; the `tlsProof` field is an opaque pass-through. 17 JUnit tests cover canonicalization, signing, envelope, and the fail-closed vsock cases.

Watch for: the reactor targets **JDK 21, not 17** (confirmed) — this directly contradicts review-focus item 7, but the verification doc and commit both attribute it to an explicit orchestrator override, and the plan's own D2 (lower enclave to 17) was deliberately reversed. This is the one item that needs an adjudication call rather than a code fix. Everything else in the review focus is satisfied.

**Verdict**: APPROVED

## High-level view

Functional equivalence to `coordinator.py` is faithful. The challenge body, enclave request field set, envelope key order, UUID generation, ISO-8601 UTC timestamp, 30s HTTP timeout, and the stdout result shape all match the Python original line for line. The fail-closed point is preserved exactly: the enclave `ok` contract is enforced in `VsockEnclaveClient` (false/missing → throw, defaulting to `ENCLAVE_FAILED`), mirroring Python's `invoke_enclave`. Deeper challenge-field validation (expiry/scope/operation) lives in the enclave and Olea, not the coordinator — the Python original did not validate those in the coordinator either, so "fail closed on out-of-scope/expired" is correctly delegated downstream, and the enclave's `SOURCE_SCOPE_INVALID` / `TLS_PROOF_*` rejections propagate back through the same `ok:false` path.

The `tlsProof` pass-through is exact and wire-compatible: `enclaveRequest` puts the same object reference into the `tlsProof` field with no inspection or reshaping, and a test asserts reference identity. No TLSNotary logic is introduced here.

The canonicalizer is a correct recursive port of the Python algorithm (sorted keys at every level, compact `,`/`:` separators, non-ASCII left unescaped), and it is the right one to match — the submission signature is a coordinator-owned domain that must match the *Python coordinator*, not the enclave's shallower `canonical()`. The signature round-trips (sign → verify with the matching public key) in two tests.

Security posture holds: the EC private key is read only inside `Signer.sign` for the duration of the call, never logged or persisted; the HTTP client never logs body or response; no tokens, PII, raw payloads, or presigned URLs are logged anywhere. Network and vsock are the only I/O, both behind interfaces that tests replace with fakes — no real sockets in the suite.

CLI arg surface matches the Python argparse exactly (`--enclave-cid` default 16, `--enclave-port` default 5005, the five required args), with required-arg enforcement printing usage to stderr and exiting non-zero. Vsock framing matches the enclave: both use junixsocket 2.11.1 `AFVSOCKSocketAddress.ofPortAndCID(port, cid)`, write-all then half-close then read-all, against the enclave's single `readNBytes` / write / close server loop.

The one real divergence is the JDK level. Review-focus item 7 asks for JDK 17; the reactor is pinned to release 21 and the enclave was intentionally kept at 21 (the plan had called for lowering it). The verification doc and commit both state this was an explicit orchestrator override of plan decisions D2/D7. It is internally consistent and the build is green at 21, so it is a traceable, approved deviation rather than a defect — flagged here for adjudication, not as a blocking code fault.

<details>
<summary>Issues (3)</summary>

1. **JDK 21 instead of 17 (likely-intended deviation)** — the parent pom and enclave both target `maven.compiler.release=21`, contradicting review-focus item 7 ("JDK 17, not 21"). Verification + commit attribute it to an explicit orchestrator override of plan D2/D7. Not a code fault; needs an owner sign-off that 21 is the accepted target. Non-blocking given the documented override.
2. **Enclave canonicalizer is shallower than the coordinator's (informational)** — `EnclaveService.canonical()` sorts only the top-level map via `TreeMap` + Jackson default, while the coordinator sorts recursively. These are independent signature domains (enclave manifest vs Dowsure envelope), so this is not an inconsistency to fix here; noted so a future reader does not assume the two canonicalizers are interchangeable.
3. **Dangling doc/task references to `coordinator.py` (informational)** — `.agents/tasks/dowsure-docs-overhaul/features/*.json` still mention `coordinator.py`. These belong to the docs track, which owns the update. No non-doc (script/infra/pom) reference remains.

</details>

<details>
<summary>Details</summary>

### Functional equivalence to coordinator.py

The flow in `Coordinator.run` is a direct translation of Python `main()`. `requestId`/`evidenceId` come from `UUID.randomUUID()`. `challengeBody` builds `{requestId, source:"mock-api", endpoint:"GET_ORDERS", operation:"GET_ORDERS", policyVersion:"v1.0"}` and is POSTed to `oleaUrl + "/v1/challenges"` — the test asserts both the URL and the exact 5-key body. `enclaveRequest` reproduces the Python field set in order (`requestId, source, endpoint, nonce, policyVersion, rawPayload, tlsProof, evidenceId, eifDigest`), pulling `nonce`/`policyVersion` from the challenge. `submissionEnvelope` matches the Python key order (`requestId, nonce, policyVersion, evidenceId, manifestDigest, encryptedEvidenceReference, submittedAt`), asserted explicitly against a `List.of(...)` keyset. The result map is the exact `{requestId, evidenceId, challenge, evidence}` shape the JS report script reads, also asserted. `submittedAt` is ISO-8601 UTC via `OffsetDateTime.ofInstant(now, UTC)` with `ISO_OFFSET_DATE_TIME`, equivalent to Python's `datetime.now(timezone.utc).isoformat()`.

Fail-closed behavior is where the security contract lives, and it is faithful. `VsockEnclaveClient.invoke` treats anything other than `Boolean.TRUE` for `ok` as failure — `ok:false` with an `error` throws that error; `ok:false` without one, and a missing `ok` entirely, both throw `ENCLAVE_FAILED`. That is exactly Python's `if not result.get("ok"): raise RuntimeError(result.get("error", "ENCLAVE_FAILED"))`. The out-of-scope / expired / mismatched-nonce rejections named in the review focus are enforced in the enclave (`SOURCE_SCOPE_INVALID`, `TLS_PROOF_INVALID`, `REQUEST_FIELD_MISSING:*`) and surface back as `ok:false`, so the coordinator fails closed on all of them through the one `ok` gate — the same division of responsibility the Python coordinator had.

### tlsProof pass-through

`enclaveRequest` stores the `tlsProof` argument directly as the `tlsProof` field with no parsing, validation, or copy. `CoordinatorTest.enclaveRequestPassesTlsProofThroughUntouched` and the `run` test both assert `tlsProof == request.get("tlsProof")` (reference identity), which is the strongest possible check that nothing reshapes it. The enclave remains the sole validator (`proofType == "tlsnotary"`, `proofHash`, `responseHash`). No TLSNotary implementation is introduced.

### Canonicalizer correctness and the two-domain question

`Canonicalizer.canonicalize` recurses: scalars via Jackson compact serialization (no whitespace, non-ASCII unescaped), lists as `[` + comma-joined items + `]`, maps as `{` + sorted `"key":value` pairs + `}`. `CanonicalizerTest` pins byte-exact expected strings including a nested-object recursive-sort case and a UTF-8 (`café`) case, matching Python's `json.dumps(..., separators=(",", ":"), ensure_ascii=False)` plus the recursive sorted-key object rule.

Worth stating because it looks like a mismatch at first glance: the enclave's `EnclaveService.canonical()` sorts only the top-level map and defers to Jackson default serialization. It does not recurse. That is fine here — the coordinator canonicalizes the *submission envelope* (a flat map of scalars) for the Dowsure signature, a domain the Python coordinator owned with the recursive algorithm, and the signature is produced and verified entirely within the coordinator's own code path. The enclave canonicalizes its own manifest/binding for a separate enclave-owned signature. The two never cross-verify, so the coordinator correctly matches Python rather than the enclave.

### Signature

`Signer.sign` loads a PKCS#8 EC key (stripping PEM armor for both `PRIVATE KEY` and `EC PRIVATE KEY` headers), signs UTF-8 canonical bytes with `SHA256withECDSA`, and base64-encodes — the stdlib equivalent of the Python `cryptography` call. `SignerTest` and `CoordinatorTest.runWiresChallengeEnclaveAndSignsEnvelope` both verify the signature against the matching public key over `Canonicalizer.canonicalize(envelope)`, proving the signed bytes are the canonical envelope. Key material stays in locals; failures map to opaque `DOWSURE_*` codes with no key content.

### Vsock framing and the half-close ordering

The ordering that the sole Python test asserted — half-close the write side before reading — is preserved and tested. `AfVsockTransport.exchange(channel, request)` does `sendAll` → `shutdownWrite` → `readAll`, split behind the package-private `VsockChannel` seam so `VsockFramingTest.halfClosesWriteSideBeforeRead` can assert `shutdownWrite` precedes `readAll` and `sendAll` precedes `shutdownWrite` with a fake channel (the direct analogue of the Python `FakeSocket` call-order assertion). The real transport uses junixsocket `AFVSOCKSocket.connectTo(AFVSOCKSocketAddress.ofPortAndCID(port, cid))` → write+flush → `shutdownOutput()` → `readAllBytes()`, matching the enclave `VsockServer` (same library/version, same `ofPortAndCID(port, cid)` arg order, read-all/write/close loop). DTO conventions (Jackson `Map<String,Object>`, `LinkedHashMap` for ordered maps) match the enclave module.

### Test coverage and no-network guarantee

17 tests: canonicalization (6, byte-exact + reject-non-canonical), signer round-trip (1), vsock framing/fail-closed (5, including the ported ordering test and three fail-closed branches), and coordinator orchestration (5, including challenge body, tlsProof pass-through, envelope key order, full `run` wiring with signature verification, and fail-closed on enclave rejection). All boundaries are interfaces (`OleaClient`, `EnclaveClient`, `VsockTransport`) replaced with fakes/lambdas; no real HTTP or socket is opened. Per the instructions I did not re-run the Maven suite — the verification doc records BUILD SUCCESS with 17/17 coordinator + 2/2 enclave on the reactor, and the no-arg jar printing usage to stderr with exit 2.

### Secrets and logging

No `System.out`/logging of tokens, keys, payloads, PII, or URLs anywhere. `HttpOleaClient` never logs the body or response. `Signer` holds the key only in locals. The only stdout write is the final result JSON in `CoordinatorMain`, which contains evidence/challenge data by design (the documented contract the JS report consumes), not credentials. This matches the Python STRICT invariants.

### Python removal and pom hygiene

`coordinator/coordinator.py`, `tests/test_coordinator.py`, and three `__pycache__` artifacts (including the orphaned `nitro-enclave/.../enclave_server.cpython-314.pyc`) are deleted in the diff; `tests/` is gone. Grep finds no non-doc reference to the Python entrypoint — only `.agents/tasks/*.json` in the docs-overhaul track, which that track owns (informational). New dependencies are all pinned and justified in pom comments: jackson-databind 2.17.2 and junixsocket 2.11.1 match the enclave exactly (version parity, no reactor drift); junit-jupiter 5.11.0 matches the enclave test. No unpinned or novel dependency. Plugin versions are centralized in the parent `pluginManagement`.

### JDK level — the deviation

Review-focus item 7 and plan D2/D7 call for JDK 17 (parent `release=17`, enclave lowered 21→17). The actual diff sets parent `maven.compiler.release=21` and the enclave pom keeps 21 (now inherited, with a comment "kept at 21 (not lowered)"). The verification doc's "Deviation from plan" section and the commit body both state this is an explicit orchestrator override. It is internally consistent (parent + both modules at 21, verified green at 21) and traceable, so it reads as an accepted decision rather than an accident. I am flagging it as the single item needing an owner's confirmation that 21 is the intended target; it is not a code correctness fault and does not block functional acceptance.

</details>

<details>
<summary>File map</summary>

- `pom.xml` — new `dowsure-oracle-parent` aggregator; modules `nitro-enclave` + `coordinator`; `release=21`; plugin pins.
- `nitro-enclave/pom.xml` — now inherits the parent; `release` kept at 21 (not lowered to 17).
- `coordinator/pom.xml` — new module; pinned jackson/junixsocket/junit; shade plugin → `CoordinatorMain`.
- `coordinator/src/main/java/.../Coordinator.java` — orchestration (challenge, enclave request, envelope, sign, result).
- `.../CoordinatorMain.java` — CLI arg parsing (matches Python argparse), file reads, stdout.
- `.../Canonicalizer.java` — recursive byte-exact port of Python `canonicalize`.
- `.../Signer.java` — PEM EC key load + ECDSA-SHA256 base64 signature.
- `.../OleaClient.java` + `HttpOleaClient.java` — HTTP adapter (30s timeout, JSON).
- `.../EnclaveClient.java` + `VsockEnclaveClient.java` — enclave adapter; `ok` fail-closed gate.
- `.../VsockTransport.java` + `VsockChannel.java` + `AfVsockTransport.java` — vsock byte exchange + half-close seam.
- `coordinator/src/test/java/.../*Test.java` — Canonicalizer, Signer, Coordinator, VsockFraming (17 tests).
- Deletions: `coordinator/coordinator.py`, `tests/test_coordinator.py`, three `__pycache__` artifacts.
- `.agents/tasks/plan.md`, `.agents/tasks/verification.md` — plan + verification evidence.

Full diff: `git -C <root> diff main...coordinator-python-to-java`.

</details>
