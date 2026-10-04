# Verification — coordinator Python-to-Java migration

Branch: `coordinator-python-to-java`
JDK: **21** (Temurin 21.0.11) — per the orchestrator override, the enclave was
NOT downgraded to 17 and no `maven.compiler.release=17` was set anywhere. The new
parent pom and the coordinator module both target release 21.

## Toolchain

- `JAVA_HOME = %USERPROFILE%\scoop\apps\temurin21-jdk\current` (openjdk 21.0.11 LTS)
- Maven: pinned scoop Maven 3.9.16 (`%USERPROFILE%\scoop\apps\maven\current\bin\mvn.cmd`)
- There is no Maven wrapper in the repo (none added — that is a separate task).

Each command was run with JAVA_HOME set to JDK 21 and its `bin` prepended to PATH:

```powershell
$env:JAVA_HOME="$env:USERPROFILE\scoop\apps\temurin21-jdk\current"
$env:Path="$env:JAVA_HOME\bin;"+$env:Path
$mvn="$env:USERPROFILE\scoop\apps\maven\current\bin\mvn.cmd"
```

## Commands run and results

### 1. Coordinator module tests

```
& $mvn -f "<ROOT>\coordinator\pom.xml" test
```

Result: **BUILD SUCCESS**. Per-class results:

| Test class            | Tests | Failures | Errors |
|-----------------------|-------|----------|--------|
| CanonicalizerTest     | 6     | 0        | 0      |
| CoordinatorTest       | 5     | 0        | 0      |
| SignerTest            | 1     | 0        | 0      |
| VsockFramingTest      | 5     | 0        | 0      |
| **Total**             | **17**| **0**    | **0**  |

### 2. Full reactor (both modules) on JDK 21

```
& $mvn -f "<ROOT>\pom.xml" clean test
```

Result: **BUILD SUCCESS**. Reactor summary:

- `dowsure-oracle-parent` .......... SUCCESS
- `enclave-service` ................ SUCCESS (EnclaveServiceTest: 2 tests, 0 failures — enclave still green at release 21)
- `coordinator` .................... SUCCESS (17 tests, 0 failures)

### 3. CLI shaded jar + usage behavior

```
& $mvn -f "<ROOT>\coordinator\pom.xml" package -DskipTests
java -jar "<ROOT>\coordinator\target\coordinator-0.1.0-SNAPSHOT.jar"
```

Result: shaded `coordinator-0.1.0-SNAPSHOT.jar` produced; running with no args
prints the usage block to **stderr** and exits with code **2** (missing required
`--olea-url`), matching the Python argparse behavior (required args enforced,
non-zero exit).

## Behavior-parity notes

- `Canonicalizer` reproduces the Python `canonicalize` byte-for-byte (sorted-key
  objects, compact `,`/`:` separators, UTF-8 strings left un-escaped). Covered by
  `CanonicalizerTest` with pinned expected strings.
- `VsockEnclaveClient` fails closed: `ok:false` propagates the enclave `error`,
  missing/absent `ok` yields `ENCLAVE_FAILED`. The write side is half-closed before
  reading (`AfVsockTransport.exchange` ordering), the one behavior the Python test
  asserted — covered by `VsockFramingTest`.
- `tlsProof` is passed through to the enclave untouched (same object reference,
  no inspection/validation in the coordinator) — asserted in `CoordinatorTest`.
- Submission signature is ECDSA-SHA256 over the canonical envelope, verified via a
  round-trip against the matching public key (`SignerTest`, `CoordinatorTest`).
- No real network or vsock I/O occurs in tests; the HTTP and vsock boundaries are
  behind the `OleaClient` / `EnclaveClient` / `VsockTransport` adapters and mocked.

## Secrets / logging

- The EC private key is read only inside `Signer.sign(...)` for the duration of the
  signing call; it is never logged or persisted. No tokens, raw payloads, PII, or
  URLs are logged anywhere in the coordinator.

## Python removal

- Deleted `coordinator/coordinator.py`, `tests/test_coordinator.py`, and the tracked
  `__pycache__` artifacts under `coordinator/`, `tests/`, and `nitro-enclave/`
  (the last was an orphaned `enclave_server.cpython-314.pyc` with no `.py` source).
- `tests/` is now empty and removed. Zero tracked `*.py`/`*.pyc` remain; no Python
  files remain on disk under the `coordinator/` or `nitro-enclave/` runtime dirs.
- Grep found NO non-doc references (scripts, infra, non-doc READMEs) to the Python
  coordinator entrypoint — only descriptive Javadoc comments in the new Java sources
  and `.agents/tasks/plan.md` mention it. `docs/*.md` and `sam/mocks/**` were left
  untouched per scope; any `docs/*.md` that still references `coordinator.py` is left
  for the docs track (see final report).

## Deviation from plan

- Plan decisions D2/D7 specified JDK 17. Per the orchestrator's explicit override,
  everything targets **JDK 21** instead: parent pom `maven.compiler.release=21`,
  `nitro-enclave/pom.xml` left at its Java 21 target (now inherited from the parent),
  and all verification was run under JDK 21.
