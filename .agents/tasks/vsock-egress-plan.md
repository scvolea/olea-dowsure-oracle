# Implementation Plan — vsock egress + CA bundle for the Nitro enclave

Fix the two blockers that stop the merged TLS-in-TEE enclave from making a real
source call inside an AWS Nitro Enclave:

1. The only production `SourceTransport` opens a plain TCP socket, which cannot
   work inside an enclave (no network interface; egress is AF_VSOCK only).
2. The Dockerfile copies no CA bundle, so `SourceTlsClient.loadFromPath` throws
   `CA_BUNDLE_NOT_FOUND` and every call fails closed.

All paths are absolute. Step agents run with cwd = the parent session workspace
(`c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle`), NOT the
worktree, so every file path below is written in full against the worktree root
`c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle\.worktrees\vsock-egress`.

## Scope / guardrails (verified against the code — do not violate)

- Touch ONLY the `nitro-enclave` module. Do NOT touch `coordinator`, `verifier`,
  any docs, the inbound `VsockServer` (CID 16 / port 5005), or any
  evidence/manifest/user_data contract.
- No secrets, tokens, PII, hosts, request/response bytes, or headers may be
  logged. The new transport must log nothing of that kind.
- Fail closed: an unmapped host must throw, with NO TCP fallback.
- `SourceTlsClient` already does the SSLSocket wrap with endpoint-id `"HTTPS"`
  and validates the cert hostname against `entry.host()` (confirmed in
  `SourceTlsClient.fetch`). The vsock proxy is a transparent byte relay, so SNI
  and hostname verification keep working end-to-end — do NOT change
  `SourceTlsClient`.
- `pom.xml` already declares `com.kohlschutter.junixsocket:junixsocket-core`
  (type `pom`) and `:junixsocket-vsock` at `2.11.1` — no dependency change needed.
- Keep `SourceTransport.TcpSourceTransport` exactly as it is: it is the default
  used by `SourceTlsClientTest`'s local TLS stub and by local non-enclave runs.

## Environment-config contract (final — implementer must honor exactly)

- `SOURCE_RELAY_CID` — integer CID of the parent/host that runs the vsock-proxy.
  Default `3` (`VMADDR_CID_HOST`). Parsed via `Integer.parseInt` on a trimmed value.
- `SOURCE_RELAY_PORT_MAP` — comma-separated `host=vsockPort` pairs, e.g.
  `sandbox.sellingpartnerapi-na.amazon.com=8001,api.qichacha.com=8002`. Each pair
  splits on the FIRST `=`; whitespace around host and port is trimmed; empty
  entries (from trailing/double commas) are skipped; the port is parsed with
  `Integer.parseInt`. Lookup is by exact `entry.host()` match (the host the
  `SourceRegistry` resolved). Default when the env var is unset/blank: empty map.
- `SOURCE_CA_BUNDLE` — unchanged; still read by `EnclaveMain` (default
  `source-ca-bundle.pem`). After CHANGE 3 the Docker image sets it to
  `/app/source-ca-bundle.pem`.

Error contract: unknown host (no mapping) → `IllegalArgumentException` whose
message is exactly `RELAY_PORT_UNMAPPED`. This mirrors the existing code-as-message
convention (`SOURCE_SCOPE_INVALID`, `TLS_HANDSHAKE_FAILED`, `CA_BUNDLE_NOT_FOUND`).

## Design decisions (recorded, 1–2 sentences each)

- Vsock connect mirrors `coordinator/.../AfVsockTransport`: use
  `AFVSOCKSocketAddress.ofPortAndCID(relayVsockPort, relayCid)` +
  `AFVSOCKSocket.connectTo(address)`, returning the `AFVSOCKSocket` directly —
  it `extends java.net.Socket`, so it satisfies `SourceTransport.connect`'s return
  type with no adapter. (Confirmed from the coordinator reference and the
  junixsocket API.)
- Port resolution is a package-private pure method taking the host and returning
  the mapped vsock port (or throwing `RELAY_PORT_UNMAPPED`), so it is unit-testable
  offline without opening a real vsock — matching how `SourceRegistry` keeps its
  resolution logic pure and env-injectable for `SourceRegistryTest`.
- Env parsing is injectable via a `Map<String,String>` constructor (same pattern
  as `SourceRegistry(Map<String,String> env)`), with a no-arg constructor reading
  `System.getenv()`. This keeps the new unit test fully offline.
- Dockerfile uses the OS `ca-certificates` bundle (deterministic, offline, no
  network fetch of a specific PEM): install `ca-certificates`, copy
  `/etc/pki/tls/certs/ca-bundle.crt` to `/app/source-ca-bundle.pem`, and set
  `ENV SOURCE_CA_BUNDLE=/app/source-ca-bundle.pem`. Chosen over committing a PEM
  file so the trust set stays current with the base image and nothing extra needs
  to be copied into the build context.

---

## Steps

- [ ] 1. Add `AfVsockSourceTransport` implementing `SourceTransport` (CHANGE 1).
      Create the class with: a public no-arg ctor delegating to a package-private
      ctor `AfVsockSourceTransport(int relayCid, Map<String,String> hostToVsockPort)`;
      a package-private static parser `parsePortMap(String raw)` returning
      `Map<String,String>`→`Integer` (host → vsock port) per the
      `SOURCE_RELAY_PORT_MAP` contract above; a package-private static
      `fromEnv(Map<String,String> env)` factory that reads `SOURCE_RELAY_CID`
      (default `3`) and `SOURCE_RELAY_PORT_MAP`; a package-private
      `int resolveVsockPort(String host)` that returns the mapped port or throws
      `new IllegalArgumentException("RELAY_PORT_UNMAPPED")`; and
      `public Socket connect(String host, int port) throws IOException` that calls
      `resolveVsockPort(host)`, then
      `AFVSOCKSocket.connectTo(AFVSOCKSocketAddress.ofPortAndCID(vsockPort, relayCid))`
      and returns it. The `port` argument (443) is intentionally ignored for the
      vsock dial — the real `host:443` is reached by the parent's vsock-proxy; the
      TLS layer still validates against `host`. Log nothing (no host, no bytes, no
      headers). Keep `SourceTransport.TcpSourceTransport` untouched. Imports:
      `org.newsclub.net.unix.AFVSOCKSocketAddress`,
      `org.newsclub.net.unix.vsock.AFVSOCKSocket`.
      Files: `c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle\.worktrees\vsock-egress\nitro-enclave\src\main\java\com\olea\dowsure\enclave\AfVsockSourceTransport.java`
      Verify: `cd "c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle\.worktrees\vsock-egress"; juse21; mvn -q -pl nitro-enclave -am test-compile` — compiles clean (no new test yet; added in step 2).

- [ ] 2. Add `AfVsockSourceTransportTest` covering the mapping and the fail-closed
      path WITHOUT opening a real vsock. Mirror `SourceRegistryTest`: construct via
      the injected-env / injected-map seams only; assert
      `resolveVsockPort("sandbox.sellingpartnerapi-na.amazon.com")` returns the
      mapped port for a map built from a sample `SOURCE_RELAY_PORT_MAP`; assert an
      unmapped host throws `IllegalArgumentException` with message exactly
      `RELAY_PORT_UNMAPPED` (use `assertThrows` + `assertEquals` like
      `SourceRegistryTest.unknownIdThrowsSourceScopeInvalid`); assert `parsePortMap`
      handles whitespace, trailing/double commas, and blank input (empty map);
      assert `fromEnv` defaults `relayCid` to `3` when `SOURCE_RELAY_CID` is unset.
      Do NOT call `connect(...)` (that would require a real vsock).
      Files: `c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle\.worktrees\vsock-egress\nitro-enclave\src\test\java\com\olea\dowsure\enclave\AfVsockSourceTransportTest.java`
      Verify: `cd "c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle\.worktrees\vsock-egress"; juse21; mvn -q -pl nitro-enclave -am test` — all enclave tests pass; count rises from 19 to 19 + the new tests, 0 failures/errors.

- [ ] 3. Wire the vsock transport into `EnclaveMain` (CHANGE 2). Replace the line
      `SourceTransport transport = new SourceTransport.TcpSourceTransport();` with a
      construction of `AfVsockSourceTransport` from the process environment (via the
      `fromEnv(System.getenv())` factory added in step 1). Leave everything else
      exactly as-is: the `CA_BUNDLE` env read, `SourceRegistry`,
      `PocCredentialProvider`, `JnaAttestationProvider`, the `SourceTlsClient`
      construction, the inbound `new VsockServer().serve(16, 5005, ...)` server, and
      the `{"ok":true,"evidence":...}` / `{"ok":false,"error":...}` envelope. Inbound
      server (CID 16/5005) and outbound egress transport are independent — do not
      conflate them. Update the existing comment that says "vsock->TCP binding ...
      plain TCP" to reflect the real vsock egress.
      Files: `c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle\.worktrees\vsock-egress\nitro-enclave\src\main\java\com\olea\dowsure\enclave\EnclaveMain.java`
      Verify: `cd "c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle\.worktrees\vsock-egress"; juse21; mvn -q -pl nitro-enclave -am test` — all enclave tests still pass (19 + new), 0 failures/errors. `EnclaveMain` has no unit test, so passing compilation + green suite is the gate.

- [ ] 4. Add the CA bundle to the enclave Docker image (CHANGE 3). In the FINAL
      stage of the Dockerfile (the `FROM amazonlinux:2023` stage, NOT the builder),
      extend the existing `dnf install` to also install `ca-certificates`
      (`dnf install -y java-21-amazon-corretto-headless ca-certificates`), then after
      the headless-JRE install add
      `RUN cp /etc/pki/tls/certs/ca-bundle.crt /app/source-ca-bundle.pem` (ensure
      `/app` exists — it is created by `WORKDIR /app`; place the copy after `WORKDIR`
      or use an explicit `mkdir -p /app`), and add
      `ENV SOURCE_CA_BUNDLE=/app/source-ca-bundle.pem`. Keep the existing
      `COPY --from=builder /tmp/libnsm.so /usr/lib64/libnsm.so`, the jar copy, the
      `WORKDIR /app`, and the `ENTRYPOINT ["java","-jar","/app/enclave-service.jar"]`
      unchanged. Do NOT commit a separate PEM file.
      Files: `c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle\.worktrees\vsock-egress\nitro-enclave\Dockerfile`
      Verify: by review (no Docker/nitro-cli available here). Confirm: final stage
      installs `ca-certificates`; `source-ca-bundle.pem` lands at `/app`;
      `ENV SOURCE_CA_BUNDLE=/app/source-ca-bundle.pem` matches the path
      `EnclaveMain`'s `CA_BUNDLE` default resolves against; jar + libnsm.so copies
      and ENTRYPOINT are intact.

- [ ] 5. Full-reactor regression — confirm nothing outside the enclave moved.
      No code change in this step; it is the final gate.
      Files: none.
      Verify: `cd "c:\Users\AnudeepSaiNunna\workspace\Kiro\logging\dowsure-oracle\.worktrees\vsock-egress"; juse21; mvn -f pom.xml clean test` — BUILD SUCCESS with enclave at 19 + the new tests and coordinator unchanged at 18, 0 failures/errors across the reactor. (Baseline confirmed during exploration: enclave 19 green, split 7/3/6/3 across CredentialProviderTest/EnclaveServiceTest/SourceRegistryTest/SourceTlsClientTest.)

## Notes / assumptions

- Windows PowerShell: commands use `;` as the separator (not `&&`), and `juse21`
  is run before `mvn` to pin JDK 21. Both `juse21` and `mvn` were confirmed
  working during exploration.
- No `AGENTS.md`, `CONTRIBUTING`, or `.kiro/steering/*` exist in the worktree, so
  the project's real build/test commands (above) are the only contribution gate.
- The `coordinator` module stays untouched; `AfVsockTransport.java` there is a
  read-only reference pattern, not an edit target.
- `relayCid` default `3` assumes the standard Nitro layout where the parent
  instance is the vsock host (`VMADDR_CID_HOST`); it is overridable via
  `SOURCE_RELAY_CID` for non-default deployments.
