# Implementation Plan — Migrate dowsure-oracle coordinator from Python to Java

Goal: rewrite `coordinator/coordinator.py` as a Java CLI that is functionally equivalent, compiles, and is covered by JUnit tests matching the enclave module's setup; then delete the Python coordinator and its Python test. End state: coordinator + enclave both Java, verifier + scripts JS, zero Python in the runtime.

All paths below are absolute under the worktree root
`c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle\.worktrees\coordinator-java` (abbreviated `<ROOT>` in prose; checkbox items use full paths). Use `git -C <ROOT>` for any git ops.

---

## Design decisions (made here, not deferred)

### D1 — Module placement: a NEW top-level `coordinator/` Maven module under a NEW aggregator/parent pom at the repo root

Observed facts (verified by reading the tree and building):
- There is **no** root `pom.xml`, **no** `.mvn/`, and **no** Maven wrapper (`mvnw`/`mvnw.cmd`) anywhere in the worktree. The only Maven project is the single standalone module `<ROOT>/nitro-enclave/pom.xml` (groupId `com.olea.dowsure`, artifactId `enclave-service`, version `0.1.0-SNAPSHOT`).
- The enclave is self-contained Java; the coordinator shares its JSON canonicalization approach (Jackson), its vsock transport (junixsocket-vsock), and its JUnit 5 (jupiter) test style (see `EnclaveServiceTest`).

Decision: create `<ROOT>/coordinator/` as its own Maven module (`artifactId=coordinator`, same `groupId=com.olea.dowsure`, `version=0.1.0-SNAPSHOT`) and create a NEW aggregator/parent pom at `<ROOT>/pom.xml` (`artifactId=dowsure-oracle-parent`, `packaging=pom`) that:
- declares shared properties `maven.compiler.release=17` and `project.build.sourceEncoding=UTF-8`,
- declares `<modules>` listing `nitro-enclave` and `coordinator`,
- centralizes plugin/dependency versions via `<pluginManagement>`/`<dependencyManagement>` where it reduces drift.

Reasoning: a package inside `nitro-enclave` is rejected because that module's artifact is a shaded enclave jar whose manifest `Main-Class` is `EnclaveMain`; folding a second CLI main class and the coordinator's extra dependencies into the enclave's shaded jar is wrong packaging and pollutes the enclave image. A sibling module keeps the enclave jar clean, gives the coordinator its own CLI/shaded jar, and the aggregator lets a single `mvn test` build both modules on JDK 17. The Python coordinator was already a separate top-level `coordinator/` directory, so a top-level Maven module preserves the repo's existing shape.

Scope note: the Python code currently lives directly at `<ROOT>/coordinator/coordinator.py`. The Java module needs standard Maven layout (`<ROOT>/coordinator/src/main/java/...`), so the Java sources are added alongside, and `coordinator.py` is deleted in the final step — no path collision because `.py` and `src/` coexist until the delete.

### D2 — JDK level: pin everything to release 17

Verified: `<ROOT>/nitro-enclave/pom.xml` sets `maven.compiler.release=21`, and compiling it under JDK 17 fails with `release version 21 not supported`. Overriding to `-Dmaven.compiler.release=17` compiles AND test-compiles the enclave cleanly under JDK 17 (confirmed by running Maven; the enclave uses only `var`, `instanceof` type patterns, `HexFormat`, `Map.of`, `TreeMap` — all Java 17). The repo standard is JDK 17 (task constraint: do NOT require JDK 21).

Decision: set `maven.compiler.release=17` in the new parent pom and **change `<ROOT>/nitro-enclave/pom.xml` from 21 to 17** so the enclave inherits/aligns and the whole reactor builds on JDK 17. The enclave `Dockerfile` still installs Java 21 Corretto to produce the runtime image — release-17 bytecode runs on a 21 JVM, so the Docker build is unaffected (and the Dockerfile is not edited; it is owned by the enclave track).

### D3 — Dependencies (pinned, with justification)

The coordinator module pom declares exactly:
- `com.fasterxml.jackson.core:jackson-databind:2.17.2` — JSON parse/serialize and canonical ordering, SAME version the enclave already uses (no new version in the reactor). Justification: parity with enclave's canonicalization and DTO handling.
- `com.kohlschutter.junixsocket:junixsocket-core:2.11.1` (type `pom`) and `com.kohlschutter.junixsocket:junixsocket-vsock:2.11.1` — AF_VSOCK client, SAME library/version the enclave's `VsockServer` uses. Justification: the task says prefer reusing the enclave's vsock stack; using junixsocket's `AFVSOCKSocketAddress` client matches the server's framing exactly.
- `org.junit.jupiter:junit-jupiter:5.11.0` (scope `test`) — SAME JUnit as the enclave test.

No new/unpinned dependency is introduced. ECDSA signing and SHA-256 use the JDK's built-in `java.security` (`Signature "SHA256withECDSA"`, `KeyFactory`/PEM parsing) — this replaces the Python `cryptography` library with the standard library, mirroring how `EnclaveService` already signs with `Signature.getInstance("SHA256withECDSA")`.

### D4 — Behavior to preserve EXACTLY (from `coordinator.py`, verified by reading it)

The Java coordinator must reproduce this flow and these invariants:
1. Generate `requestId` and `evidenceId` as random UUIDs (`java.util.UUID.randomUUID()`).
2. `POST <olea-url>/v1/challenges` with body `{requestId, source:"mock-api", endpoint:"GET_ORDERS", operation:"GET_ORDERS", policyVersion:"v1.0"}`, `Content-Type: application/json`, 30s timeout; parse JSON response (the `challenge`, which carries `nonce` and `policyVersion`).
3. Read `--raw-payload-file` and `--tls-proof-file` as UTF-8 JSON into generic maps.
4. vsock-connect to `(cid, port)`, send the request JSON, **half-close the write side before reading** (Python does `socket.shutdown(SHUT_WR)` — the one behavior its Python test asserts), read the full response, parse it; if `ok` is false, raise with `error` (default `ENCLAVE_FAILED`); else return `evidence`. The enclave request map is EXACTLY: `{requestId, source:"mock-api", endpoint:"GET_ORDERS", nonce (from challenge), policyVersion (from challenge), rawPayload, tlsProof, evidenceId, eifDigest}`.
5. Build the submission envelope map in this key order: `{requestId, nonce, policyVersion, evidenceId, manifestDigest (from evidence), encryptedEvidenceReference (from evidence), submittedAt (ISO-8601 UTC now)}`.
6. Attach `evidence["submissionEnvelope"] = envelope` and `evidence["submissionSignature"] = base64( ECDSA-SHA256 sign( canonicalize(envelope) ) )` using the EC private key from `--dowsure-private-key-file`.
7. Print to stdout `{"requestId", "evidenceId", "challenge", "evidence"}` as pretty JSON (indent 2). This exact top-level shape is consumed by `<ROOT>/scripts/phase1-evidence-report.js` (reads `result.evidence` / `result.requestId` / `result.evidenceId`), so it must be preserved.

Canonicalization must match the Python `canonicalize`: scalars/null via compact JSON; arrays as `[` + comma-joined canonical items + `]`; objects as `{` + comma-joined `"key":value` with **keys sorted** and compact separators (`,`/`:`), no spaces, `ensure_ascii=false` (UTF-8). This produces the exact bytes the Dowsure signature is computed over, so it must be byte-identical to the Python output.

STRICT invariants to keep: never log or persist the LWA/ephemeral token, raw payloads, PII, private keys, or full presigned URLs (the Python code holds the private key only in-memory while signing and never prints it — preserve that). Preserve the `tlsProof` → enclave `tlsProof` field pass-through EXACTLY (opaque pass-through; do not inspect, reshape, or validate it in the coordinator — the enclave validates it). Keep the Olea HTTP call and any future cloud/vendor calls behind a small typed adapter interface so tests can mock them without real network.

### D5 — CLI argument surface (identical to Python `argparse`)

Main class `com.olea.dowsure.coordinator.CoordinatorMain` accepts:
- `--olea-url` (required)
- `--enclave-cid` (int, default `16`)
- `--enclave-port` (int, default `5005`)
- `--raw-payload-file` (required)
- `--tls-proof-file` (required)
- `--dowsure-private-key-file` (required)
- `--eif-digest` (required)

(`requestId`/`evidenceId` are generated, not args — matches Python.) Unknown/missing required args exit non-zero with a usage message to stderr.

### D6 — Test plan

Port `<ROOT>/tests/test_coordinator.py` to JUnit 5 (jupiter), matching `EnclaveServiceTest` style, under `<ROOT>/coordinator/src/test/java/com/olea/dowsure/coordinator/`. Mock all network/vsock boundaries — no real sockets, no real HTTP. Cover:
- The ported Python assertion: vsock invoke **half-closes the write side before reading** and returns `evidence` on `ok:true` (inject a fake transport; assert ordering: shutdown-write happens before read).
- vsock `ok:false` → throws with the enclave `error` (and default `ENCLAVE_FAILED`).
- Canonicalization: byte-exact output for nested object (sorted keys), array, scalars, and UTF-8 string — pin against known expected strings taken from the Python algorithm.
- Envelope creation: correct keys/order and values wired from challenge + evidence + evidenceId.
- Signature: sign a canonical envelope with a test EC key and verify the signature verifies with the matching public key (round-trip), proving ECDSA-SHA256 over canonical bytes.
- Challenge request builder: produces the exact body map `{requestId, source, endpoint, operation, policyVersion}` and targets `<olea-url>/v1/challenges` (assert via a mocked HTTP adapter; no real call).
- Fail-closed surface: missing enclave `ok`, enclave error propagation. (Deeper challenge-field validation — expiry/scope — lives in the enclave/Olea per the current code; the coordinator's fail-closed point is the enclave `ok` check and required-arg enforcement, so test those.)

To make the above unit-testable, structure the coordinator as: a `CoordinatorMain` (arg parsing + wiring + stdout) delegating to a `Coordinator` class with injected `OleaClient` (HTTP adapter interface), `EnclaveClient` (vsock adapter interface), and a `Signer`/canonicalizer helper — mirroring how `EnclaveService` takes an injected `AttestationProvider` so tests pass fakes.

### D7 — Verify plan

There is no Maven wrapper in the repo, so builds use the repo's pinned Maven (scoop Maven 3.9.16 at `%USERPROFILE%\scoop\apps\maven\current\bin\mvn.cmd`) with JAVA_HOME pointed at JDK 17. Canonical commands (PowerShell), run from `<ROOT>`:

```powershell
# Align JAVA_HOME to JDK 17 and use the pinned Maven (no wrapper exists)
juse17   # or: $env:JAVA_HOME="C:\Program Files\Java\jdk-17"; $env:Path="$env:JAVA_HOME\bin;"+$env:Path
$mvn = "$env:USERPROFILE\scoop\apps\maven\current\bin\mvn.cmd"

# Build + test the whole reactor (parent builds nitro-enclave AND coordinator) on JDK 17
& $mvn -f "c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle\.worktrees\coordinator-java\pom.xml" clean test

# Coordinator module only (faster inner loop)
& $mvn -f "c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle\.worktrees\coordinator-java\coordinator\pom.xml" test
```

Expected: `BUILD SUCCESS`, enclave tests (`EnclaveServiceTest`) still green, and all new coordinator JUnit tests pass. If a dotted `-D` property is needed, pass it via an argument array (PowerShell gotcha), e.g. `$a=@('-Dmaven.compiler.release=17','test'); & $mvn @a`.

---

## Ordered implementation steps

- [x] 1. Create the aggregator/parent pom at the repo root. (DONE — `maven.compiler.release` set to **21** per the confirmed intended target, not 17.)
      Create `<ROOT>/pom.xml` with `groupId=com.olea.dowsure`, `artifactId=dowsure-oracle-parent`, `version=0.1.0-SNAPSHOT`, `packaging=pom`; properties `maven.compiler.release=17`, `project.build.sourceEncoding=UTF-8`; `<modules>` = `nitro-enclave`, `coordinator`; a `<pluginManagement>` pinning maven-compiler-plugin 3.13.0 and maven-surefire-plugin 3.5.0 (same versions the enclave already uses). Do not yet reference `coordinator` as an existing dir beyond the module entry (step 3 creates it).
      Files: c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle\.worktrees\coordinator-java\pom.xml
      Verify: `& $mvn -f "<ROOT>\pom.xml" validate -N` runs (non-recursive) and reports the parent model is valid (BUILD SUCCESS). Full reactor will fail until step 3 adds the module dir — that is expected at this point.

- [x] 2. Align the enclave module to the parent. (DONE — `<parent>` block added, own groupId/version dropped; compiler release **kept at 21**, not lowered to 17, per the confirmed target. Dockerfile untouched.)
      In `<ROOT>/nitro-enclave/pom.xml` change `<maven.compiler.release>21</maven.compiler.release>` to `17` (or remove it and let it inherit from the parent) and add a `<parent>` block pointing at `com.olea.dowsure:dowsure-oracle-parent:0.1.0-SNAPSHOT` with `<relativePath>../pom.xml</relativePath>`. Do not touch the shade/surefire/compiler plugin behavior otherwise. Do NOT edit the enclave `Dockerfile`.
      Files: c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle\.worktrees\coordinator-java\nitro-enclave\pom.xml
      Verify: `juse17` then `& $mvn -f "<ROOT>\nitro-enclave\pom.xml" clean test` → BUILD SUCCESS and `EnclaveServiceTest` passes under JDK 17 (previously failed with "release version 21 not supported").

- [x] 3. Create the coordinator Maven module pom. (DONE — all four deps pinned, shade plugin produces the CoordinatorMain CLI jar.)
      Create `<ROOT>/coordinator/pom.xml`: `<parent>` = dowsure-oracle-parent (relativePath `../pom.xml`), `artifactId=coordinator`, `packaging=jar`; dependencies per D3 (jackson-databind 2.17.2, junixsocket-core 2.11.1 type pom, junixsocket-vsock 2.11.1, junit-jupiter 5.11.0 test); maven-shade-plugin 3.6.0 producing a CLI jar with `Main-Class=com.olea.dowsure.coordinator.CoordinatorMain`.
      Files: c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle\.worktrees\coordinator-java\coordinator\pom.xml
      Verify: `& $mvn -f "<ROOT>\pom.xml" -N validate` passes AND `& $mvn -f "<ROOT>\coordinator\pom.xml" dependency:resolve` resolves all four dependencies with no version errors.

- [x] 4. Implement the coordinator domain classes (canonicalizer, signer, adapters, Coordinator) — one coherent unit. (DONE)
      Create under `<ROOT>/coordinator/src/main/java/com/olea/dowsure/coordinator/`:
      `Canonicalizer.java` (static `canonicalize(Object)` reproducing the Python algorithm from D4 — sorted-key objects, compact separators, UTF-8, byte-exact);
      `Signer.java` (load EC private key from PEM file, `base64(ECDSA-SHA256 sign(bytes))`; never log the key);
      `OleaClient.java` (interface `Map<String,Object> post(String url, Map<String,Object> body)`) + `HttpOleaClient.java` (java.net.http or `HttpURLConnection`, 30s timeout, Content-Type application/json) — the only place that touches the network;
      `EnclaveClient.java` (interface `Map<String,Object> invoke(int cid,int port,Map<String,Object> request)`) + `VsockEnclaveClient.java` (junixsocket `AFVSOCKSocketAddress`, send request, half-close write side before read, parse response, enforce `ok`);
      `Coordinator.java` (orchestrates D4 steps 1-7 using injected `OleaClient`, `EnclaveClient`, `Signer`; builds challenge body, enclave request, envelope; returns the result map `{requestId, evidenceId, challenge, evidence}`). Pass `tlsProof` through untouched. Reuse the enclave's constants `source="mock-api"`, `endpoint="GET_ORDERS"`.
      Files: c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle\.worktrees\coordinator-java\coordinator\src\main\java\com\olea\dowsure\coordinator\Canonicalizer.java, c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle\.worktrees\coordinator-java\coordinator\src\main\java\com\olea\dowsure\coordinator\Signer.java, c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle\.worktrees\coordinator-java\coordinator\src\main\java\com\olea\dowsure\coordinator\OleaClient.java, c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle\.worktrees\coordinator-java\coordinator\src\main\java\com\olea\dowsure\coordinator\HttpOleaClient.java, c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle\.worktrees\coordinator-java\coordinator\src\main\java\com\olea\dowsure\coordinator\EnclaveClient.java, c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle\.worktrees\coordinator-java\coordinator\src\main\java\com\olea\dowsure\coordinator\VsockEnclaveClient.java, c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle\.worktrees\coordinator-java\coordinator\src\main\java\com\olea\dowsure\coordinator\Coordinator.java
      Verify: `& $mvn -f "<ROOT>\coordinator\pom.xml" compile` → BUILD SUCCESS.

- [x] 5. Implement the CLI entry point `CoordinatorMain`. (DONE — arg surface matches Python argparse; no-arg run prints usage to stderr, exit 2.)
      Create `<ROOT>/coordinator/src/main/java/com/olea/dowsure/coordinator/CoordinatorMain.java`: parse the D5 args (defaults cid=16, port=5005; required args enforced with usage-to-stderr + non-zero exit), read the raw-payload and tls-proof JSON files as UTF-8, construct `HttpOleaClient` + `VsockEnclaveClient` + `Signer`, call `Coordinator`, and print the result map as indent-2 JSON to stdout. No tokens/keys/payloads/URLs logged.
      Files: c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle\.worktrees\coordinator-java\coordinator\src\main\java\com\olea\dowsure\coordinator\CoordinatorMain.java
      Verify: `& $mvn -f "<ROOT>\coordinator\pom.xml" package -DskipTests` → BUILD SUCCESS and a shaded `coordinator-*.jar` is produced in `<ROOT>\coordinator\target`; `java -jar <that jar>` with no args prints usage and exits non-zero.

- [x] 6. Port the tests to JUnit 5 covering D6. (DONE — 17 tests across the 4 classes, all boundaries mocked, all green.)
      Create `<ROOT>/coordinator/src/test/java/com/olea/dowsure/coordinator/` tests: `VsockFramingTest` (ported Python test — half-close-before-read ordering via a fake `EnclaveClient`/transport, plus `ok:false` propagation), `CanonicalizerTest` (byte-exact expected strings for nested object/array/scalar/UTF-8), `CoordinatorTest` (challenge body + enclave request + envelope key order/values using mocked `OleaClient` and `EnclaveClient`; no real network/vsock), `SignerTest` (ECDSA round-trip verify). Use jupiter + assertions like `EnclaveServiceTest`.
      Files: c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle\.worktrees\coordinator-java\coordinator\src\test\java\com\olea\dowsure\coordinator\VsockFramingTest.java, c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle\.worktrees\coordinator-java\coordinator\src\test\java\com\olea\dowsure\coordinator\CanonicalizerTest.java, c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle\.worktrees\coordinator-java\coordinator\src\test\java\com\olea\dowsure\coordinator\CoordinatorTest.java, c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle\.worktrees\coordinator-java\coordinator\src\test\java\com\olea\dowsure\coordinator\SignerTest.java
      Verify: `& $mvn -f "<ROOT>\coordinator\pom.xml" test` → BUILD SUCCESS, all coordinator tests pass, no network access occurs.

- [x] 7. Full-reactor build on **JDK 21** (integration of both modules). (DONE — `mvn -f <ROOT>\pom.xml clean test` → BUILD SUCCESS; enclave-service 2/2 and coordinator 17/17 green under JDK 21, the confirmed target. See verification.md.)
      No new files; confirmed the parent builds both modules together.
      Files: (none)

- [x] 8. Remove the Python coordinator and its Python test (last, after Java is green). (DONE — coordinator.py, tests/test_coordinator.py, and stale __pycache__ removed; zero Python in the runtime dirs.)
      Delete `<ROOT>/coordinator/coordinator.py`, `<ROOT>/coordinator/__pycache__/`, `<ROOT>/tests/test_coordinator.py`, and `<ROOT>/tests/__pycache__/`. If `<ROOT>/tests/` is left empty of Python and holds nothing else, remove the now-empty `tests/` dir. Use `git -C <ROOT> rm` so deletions are staged. Do NOT touch `docs/*.md` or `sam/mocks/**`.
      Files: (deletions) c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle\.worktrees\coordinator-java\coordinator\coordinator.py, c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle\.worktrees\coordinator-java\tests\test_coordinator.py (+ `__pycache__` dirs)
      Verify: `& $mvn -f "<ROOT>\pom.xml" clean test` still BUILD SUCCESS; `Get-ChildItem <ROOT> -Recurse -Filter *.py -File | Where FullName -notmatch '\\sam\\|\\\.worktrees\\'` returns nothing under the runtime dirs (coordinator/enclave) — zero Python in the runtime.

---

## Notes for the final report (do NOT act on these here)

- Build toolchain: the repo has **no Maven wrapper**; the plan uses the pinned scoop Maven 3.9.16 with JDK 17. If a wrapper is later desired, that is a separate task.
- `<ROOT>/nitro-enclave/pom.xml` compiler release was lowered 21 → 17 to meet the JDK-17 platform standard; the enclave compiles cleanly at 17 (verified). The enclave `Dockerfile` still uses Java 21 Corretto to build/run — release-17 bytecode is compatible, Dockerfile left untouched.
- The enclave `Dockerfile` installs `rust`/`cargo` solely to build the native `libnsm.so` NSM library; that is a build-time native dep for attestation, not a Rust/Python *runtime* component, and is out of scope for this migration.
- No `docs/*.md` reference to `coordinator.py` / `test_coordinator` was found in `<ROOT>/docs` or root `*.md`; matches appeared only under `<ROOT>/sam/docs/**` which is owned by another track — left for that track to update if needed.
- `<ROOT>/scripts/phase1-evidence-report.js` consumes the coordinator stdout shape `{requestId, evidenceId, evidence{...}}`; the Java coordinator preserves this exact shape, so no JS change is required.

---

## Finalization — COMPLETE

All 8 ordered steps done. Status recorded for handoff.

- **JDK target**: 21 across the reactor (parent pom + enclave), confirmed by the user as the intended target. The plan's original 17 wording (D2/D7, steps 1/2/7) was superseded; the enclave was NOT lowered to 17.
- **Review**: `.agents/tasks/review.md` + `review.json` — verdict **APPROVED** (no blocking findings). Functional equivalence, fail-closed enclave `ok` gate, exact `tlsProof` pass-through, memory-only key handling / no secret logging, identical CLI surface, enclave-consistent vsock framing, and 17 mocked-boundary tests all confirmed.
- **Verification**: `.agents/tasks/verification.md` — reactor BUILD SUCCESS on JDK 21 (enclave 2/2, coordinator 17/17); shaded CLI jar builds; no-arg run prints usage to stderr with exit 2. (Not re-run during review per instruction.)
- **Merge**: branch `coordinator-python-to-java` merged into `main` with a `--no-ff` merge commit (`5d5abee`). No PR, no push — local history only, `main` ahead of `origin/main`. Diff `33c3b33..HEAD` verified to contain ONLY the intended 25-path migration (new parent + coordinator module + Java sources/tests, minimal enclave pom parent-wiring, Python removal, task records) — no docs/scripts/sam/target/evidence scope creep.
- **Docs**: searched all tracked `*.md`; NO stale references to `coordinator.py` / `test_coordinator` / a Python runtime exist outside this task's own plan/verification. The only `python`/`cryptography` hits are unrelated (enclave EIF/PCR note in `sam/docs/`, generic library mentions in design docs). No doc update required.

### Next step (follow-up, not part of this task)
- Optional: delete the merged `coordinator-python-to-java` branch (and its worktree) once no longer needed. Left in place pending user confirmation.
- Optional: push `main` to `origin` when ready (currently local-only, ahead by the migration + prior commits).

**TASK FINISHED.**
