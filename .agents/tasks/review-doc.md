# AF_VSOCK egress transport and CA bundle for the Nitro enclave

The change gives the merged TLS-in-TEE enclave a working outbound path. Inside a Nitro Enclave there is no network interface, so the previous `TcpSourceTransport` could never reach a real source host. CHANGE 1 adds `AfVsockSourceTransport`, which dials the parent instance's vsock-proxy (relay) over AF_VSOCK and lets that relay forward bytes to `host:443`. CHANGE 2 wires it into `EnclaveMain` from the environment. CHANGE 3 makes the Docker runtime stage install OS `ca-certificates` and expose them at `SOURCE_CA_BUNDLE` so TLS trust-init no longer fails with `CA_BUNDLE_NOT_FOUND`. Scope is confined to the `nitro-enclave` module plus its Dockerfile; no docs, no coordinator/verifier, no contracts touched.

Watch for: nothing blocking. The one design nuance (parse-time `RELAY_PORT_MAP_INVALID` on a malformed map entry) is a reasonable fail-closed-at-startup behavior, not a regression. (confirmed)

**Verdict**: APPROVED

## High-level view

The egress design is a transparent-byte-relay: `AfVsockSourceTransport.connect(host, port)` ignores the IP route and instead opens `AFVSOCKSocket.connectTo(ofPortAndCID(resolveVsockPort(host), relayCid))` to the parent CID, returning the `AFVSOCKSocket` (which `extends Socket`) unwrapped. Because the relay just pumps bytes, the `SSLSocket` that `SourceTlsClient` layers on top still runs the handshake with endpoint-id `HTTPS` and hostname verification against `entry.host()` end-to-end — the trust boundary is unchanged. This matches the spec's requirement that vsock be a transparent relay, not a TLS terminator.

Host-to-vsock-port resolution is fail-closed: an unmapped host throws `IllegalArgumentException("RELAY_PORT_UNMAPPED")` with no TCP fallback, exactly as required. The resolution and env parsing are factored into package-private `resolveVsockPort`, `parsePortMap`, and `fromEnv` seams so they are unit-tested offline without opening a real vsock. `TcpSourceTransport` is retained untouched for local/test use.

The config surface is two env vars (`SOURCE_RELAY_CID` default 3, `SOURCE_RELAY_PORT_MAP` as `host=port,...`) plus the pre-existing `SOURCE_CA_BUNDLE`. The Docker runtime stage sources the CA bundle from the OS `ca-certificates` package (deterministic, no committed PEM) and sets `SOURCE_CA_BUNDLE` to its absolute path. The inbound vsock server (CID 16 / port 5005) and the `{ok,...}` envelope are left alone and are explicitly called out as independent of the outbound transport.

<details>
<summary>Issues (0)</summary>

No blocking or non-blocking actionable findings. One informational note is recorded in the details below.

</details>

<details>
<summary>Details</summary>

### Transport abstraction and the preserved TLS trust boundary

`AfVsockSourceTransport.connect(host, port)` (AfVsockSourceTransport.java:116-120) resolves the vsock port from `host`, then dials `AFVSOCKSocketAddress.ofPortAndCID(vsockPort, relayCid)` against the parent CID — never `host:port`. The returned `AFVSOCKSocket extends java.net.Socket`, so `SourceTlsClient.fetch` (SourceTlsClient.java:59-62) wraps it with `factory.createSocket(base, entry.host(), entry.port(), true)` and sets `setEndpointIdentificationAlgorithm("HTTPS")` exactly as before. The critical security property — the certificate hostname is verified against `entry.host()`, not against the relay CID — is preserved because the TLS layer sits above the byte relay and `SourceTlsClient` is untouched. This is the correct way to keep the relay from becoming a MITM point.

The connect pattern mirrors the coordinator's `AfVsockTransport` (`AFVSOCKSocket.connectTo(AFVSOCKSocketAddress.ofPortAndCID(port, cid))`), so the same junixsocket API/version is used and the framing/library expectations match.

### Fail-closed port resolution, factored for offline testing

`resolveVsockPort` (AfVsockSourceTransport.java:98-104) throws `IllegalArgumentException("RELAY_PORT_UNMAPPED")` for any host not in the map, with no TCP fallback — the required fail-closed behavior. The map is built from `SOURCE_RELAY_PORT_MAP` by `parsePortMap` (splitting each comma-separated entry on the first `=`, trimming host/port, skipping empty entries) and the CID from `SOURCE_RELAY_CID` (default `DEFAULT_RELAY_CID = 3`). All three — `fromEnv`, `parsePortMap`, `resolveVsockPort` — are package-private and driven through injected maps, so `AfVsockSourceTransportTest` exercises them with `connect(...)` never called and no real vsock opened.

Informational (not a finding): `parsePortMap` throws `RELAY_PORT_MAP_INVALID` on a malformed entry (no `=`, or empty host/port). Since `fromEnv` runs at `EnclaveMain` startup, a misconfigured map fails the process at boot rather than silently dropping an entry. The task spec only mandated the `RELAY_PORT_UNMAPPED` runtime path; this stricter startup validation is consistent with the code-as-message convention (`SOURCE_SCOPE_INVALID`, `TLS_HANDSHAKE_FAILED`, `CA_BUNDLE_NOT_FOUND`) and is a safe, intentional choice. No action needed.

### No logging of host, bytes, or headers

`AfVsockSourceTransport` has no logging calls at all; it holds only the CID and the host→port map and returns a socket. `SourceTlsClient` (unchanged) already documents that request/response bytes and header values are never logged. The no-secrets/no-PII/no-bytes constraint holds for the added code. (confirmed)

### EnclaveMain wiring is minimal and non-conflating

The only functional change in `EnclaveMain.java` (lines 18-20) swaps `new SourceTransport.TcpSourceTransport()` for `AfVsockSourceTransport.fromEnv(System.getenv())` and updates the comment. `CA_BUNDLE`, `SourceRegistry`, `PocCredentialProvider`, `JnaAttestationProvider`, `SourceTlsClient`, the inbound `new VsockServer().serve(16, 5005, ...)`, and the `{"ok":true,"evidence":...}` / `{"ok":false,"error":...}` envelope are all unchanged. The comment explicitly notes the outbound egress is independent of the inbound server, matching the requirement not to conflate the two. (confirmed)

### Dockerfile CA bundle — review passed

The final runtime stage adds `ca-certificates` to the existing `dnf install`, then after `WORKDIR /app` runs `cp /etc/pki/tls/certs/ca-bundle.crt /app/source-ca-bundle.pem` and `ENV SOURCE_CA_BUNDLE=/app/source-ca-bundle.pem`. This prefers the OS trust store (deterministic, offline, no committed PEM) and sets the env to an absolute path, exactly as CHANGE 3 specifies. The builder stage, the `libnsm.so` and jar `COPY --from=builder`, `WORKDIR`, and `ENTRYPOINT` are untouched. The `/etc/pki/tls/certs/ca-bundle.crt` path is the correct amazonlinux:2023 location populated by the `ca-certificates` package. Dockerfile review: PASSED. (confirmed — not built here, no Docker available, but statically correct.)

### Test coverage

`AfVsockSourceTransportTest` (8 tests, all offline) covers mapped-host resolution, the `RELAY_PORT_UNMAPPED` fail-closed path, `parsePortMap` whitespace trimming and trailing/double-comma skipping, null/blank → empty map, and `fromEnv` default-CID / explicit-CID / empty-env wiring. The required mapping + fail-closed tests are present and pass per the report (enclave module 27 tests, full reactor enclave 27 + coordinator 18, all green).

Not tested: the actual `connect(...)` vsock dial (correctly out of scope — can't open a real vsock offline, and the junixsocket call is thin); the Dockerfile build (no Docker in the environment). Both omissions are expected and acceptable for this change.

The pasted test evidence in `vsock-egress-report.md` is complete and consistent with the diff (8 new tests named match the test file), so no re-run was required.

</details>

<details>
<summary>File map</summary>

- `nitro-enclave/src/main/java/com/olea/dowsure/enclave/AfVsockSourceTransport.java` — new production transport: vsock dial to parent relay, fail-closed port resolution.
- `nitro-enclave/src/main/java/com/olea/dowsure/enclave/EnclaveMain.java` — swap TCP transport for `AfVsockSourceTransport.fromEnv(...)`; comment updated.
- `nitro-enclave/Dockerfile` — install `ca-certificates`, copy to `/app/source-ca-bundle.pem`, set `SOURCE_CA_BUNDLE`.
- `nitro-enclave/src/test/java/com/olea/dowsure/enclave/AfVsockSourceTransportTest.java` — new offline tests for mapping + fail-closed path.
- `.agents/tasks/vsock-egress-plan.md`, `.agents/tasks/vsock-egress-report.md` — task artifacts (not production).

Full diff: `git -C <worktree> diff main...vsock-egress`.

</details>
