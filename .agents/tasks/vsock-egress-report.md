# vsock egress + CA bundle — implementation report

Fixes the two blockers that stop the merged TLS-in-TEE enclave from making a
real source call inside an AWS Nitro Enclave. Scope limited to the
`nitro-enclave` module. No change to `coordinator`, `verifier`, docs, the
inbound `VsockServer` (CID 16 / port 5005), or any evidence/manifest/user_data
contract.

## Change → blocker mapping

### Blocker 1 — only production transport opens plain TCP (no network inside an enclave; egress is AF_VSOCK only)

- **CHANGE 1** — New `nitro-enclave/src/main/java/com/olea/dowsure/enclave/AfVsockSourceTransport.java`
  implementing `SourceTransport`.
  - `connect(host, port)` dials the PARENT's vsock-proxy, not `host:port`:
    `AFVSOCKSocket.connectTo(AFVSOCKSocketAddress.ofPortAndCID(resolveVsockPort(host), relayCid))`,
    returning the `AFVSOCKSocket` directly (it `extends java.net.Socket`), so the
    TLS client wraps it unchanged. Mirrors `coordinator/.../AfVsockTransport`.
  - The relay is a transparent byte pump, so the `SSLSocket` layered by
    `SourceTlsClient` (endpoint-id `HTTPS`, hostname verified against
    `entry.host()`) keeps SNI + hostname verification working end-to-end. The
    transport only supplies the base socket — `SourceTlsClient` is untouched.
  - Fail-closed: unmapped host → `IllegalArgumentException("RELAY_PORT_UNMAPPED")`,
    NO plain-TCP fallback.
  - Port resolution is the package-private pure method `resolveVsockPort(String host)`
    (plus static `parsePortMap`/`fromEnv`), unit-testable offline with NO real
    vsock. Env parsing is injectable via `fromEnv(Map<String,String>)`, matching
    `SourceRegistry`'s env-map seam.
  - Logs nothing — no host, bytes, or headers.
  - `SourceTransport.TcpSourceTransport` left exactly as-is (still used by local
    non-enclave runs and `SourceTlsClientTest`'s TLS stub).

- **CHANGE 2** — `EnclaveMain.java`: replaced
  `new SourceTransport.TcpSourceTransport()` with
  `AfVsockSourceTransport.fromEnv(System.getenv())`. `CA_BUNDLE` env,
  `SourceRegistry`, `PocCredentialProvider`, `JnaAttestationProvider`,
  `SourceTlsClient`, the inbound `new VsockServer().serve(16, 5005, ...)` server,
  and the `{"ok":...}` envelope are unchanged. The stale "vsock->TCP / plain TCP"
  comment was updated to describe the real vsock egress; the comment also notes
  the outbound egress is independent of the inbound server.

### Blocker 2 — Dockerfile copies no CA bundle → `CA_BUNDLE_NOT_FOUND`, every call fails closed

- **CHANGE 3** — `nitro-enclave/Dockerfile` final stage (the `FROM amazonlinux:2023`
  runtime stage): added `ca-certificates` to the existing `dnf install`, then
  after `WORKDIR /app` added
  `RUN cp /etc/pki/tls/certs/ca-bundle.crt /app/source-ca-bundle.pem` and
  `ENV SOURCE_CA_BUNDLE=/app/source-ca-bundle.pem`. OS `ca-certificates` bundle
  chosen (deterministic, offline, no committed PEM). The builder stage, the
  `libnsm.so` + jar copies, `WORKDIR`, and `ENTRYPOINT` are unchanged. The
  Dockerfile cannot be built here (no Docker/nitro-cli); left correct for review.

## Env-config contract (honored exactly)

- `SOURCE_RELAY_CID` — integer CID of the parent/host running the vsock-proxy.
  Default `3` (`VMADDR_CID_HOST`). Trimmed, parsed with `Integer.parseInt`.
- `SOURCE_RELAY_PORT_MAP` — comma-separated `host=vsockPort` pairs, e.g.
  `sandbox.sellingpartnerapi-na.amazon.com=8001,api.qichacha.com=8002`. Splits on
  the FIRST `=`; host/port trimmed; empty entries (trailing/double commas)
  skipped; port parsed with `Integer.parseInt`. Lookup is exact `entry.host()`
  match. Unset/blank → empty map (every host then fails closed).
- `SOURCE_CA_BUNDLE` — unchanged; read by `EnclaveMain` (default
  `source-ca-bundle.pem`). The Docker image sets it to `/app/source-ca-bundle.pem`.

Error contract: unknown host → `IllegalArgumentException` with message exactly
`RELAY_PORT_UNMAPPED`, matching the code-as-message convention
(`SOURCE_SCOPE_INVALID`, `TLS_HANDSHAKE_FAILED`, `CA_BUNDLE_NOT_FOUND`).

## Tests

New `nitro-enclave/src/test/java/com/olea/dowsure/enclave/AfVsockSourceTransportTest.java`
(8 tests), all offline — `connect(...)` is never called, no real vsock opened:
mapped-host resolution; fail-closed `RELAY_PORT_UNMAPPED`; `parsePortMap`
whitespace trimming, trailing/double-comma skipping, and null/blank → empty map;
`fromEnv` default CID (3) wiring; `fromEnv` explicit CID + map; `fromEnv` with
empty env fails closed.

## Verify — exact commands and pasted output

### Enclave module: `juse21; mvn -pl nitro-enclave -am test`

```
[INFO] Running com.olea.dowsure.enclave.AfVsockSourceTransportTest
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 -- in com.olea.dowsure.enclave.AfVsockSourceTransportTest
[INFO] Running com.olea.dowsure.enclave.CredentialProviderTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0 -- in com.olea.dowsure.enclave.CredentialProviderTest
[INFO] Running com.olea.dowsure.enclave.EnclaveServiceTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.olea.dowsure.enclave.EnclaveServiceTest
[INFO] Running com.olea.dowsure.enclave.SourceRegistryTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in com.olea.dowsure.enclave.SourceRegistryTest
[INFO] Running com.olea.dowsure.enclave.SourceTlsClientTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in com.olea.dowsure.enclave.SourceTlsClientTest
[INFO] Tests run: 27, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

Enclave total 27 = 19 baseline (7 CredentialProvider + 3 EnclaveService +
6 SourceRegistry + 3 SourceTlsClient) + 8 new (AfVsockSourceTransport).

### Full reactor: `juse21; mvn -f pom.xml clean test`

```
[INFO] Tests run: 27, Failures: 0, Errors: 0, Skipped: 0   (nitro-enclave)

[INFO] Running com.olea.dowsure.coordinator.CanonicalizerTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in com.olea.dowsure.coordinator.CanonicalizerTest
[INFO] Running com.olea.dowsure.coordinator.CoordinatorTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 -- in com.olea.dowsure.coordinator.CoordinatorTest
[INFO] Running com.olea.dowsure.coordinator.SignerTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 -- in com.olea.dowsure.coordinator.SignerTest
[INFO] Running com.olea.dowsure.coordinator.VsockFramingTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in com.olea.dowsure.coordinator.VsockFramingTest
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0   (coordinator)

[INFO] Reactor Summary for dowsure-oracle-parent 0.1.0-SNAPSHOT:
[INFO] coordinator ........................................ SUCCESS
[INFO] BUILD SUCCESS
```

Enclave 27 + coordinator 18, 0 failures/errors across the reactor. Verifier not
touched. Dockerfile not buildable here (no Docker/nitro-cli) — left correct for
review per CHANGE 3.

## Note on review.json

`.agents/tasks/review.json` carried `verdict: APPROVED` with an empty findings
list, but its `reviewDoc` pointed at a different worktree
(`.worktrees/wire-rawresponse/...`), and none of the required files
(`AfVsockSourceTransport.java`, its test, this report) existed in this worktree.
The APPROVED verdict was stale/misdirected, so this was treated as a first
implementation iteration per the plan.
