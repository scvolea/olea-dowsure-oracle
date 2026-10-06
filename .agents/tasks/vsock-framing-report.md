# vsock-framing — AF_VSOCK half-close bug fix

## Problem (confirmed, not re-litigated)

On the live preprod Nitro host, the junixsocket `AFVSOCKSocket` client connected (CID 16,
port 5005), wrote the 118-byte request successfully, then `shutdownOutput()` threw
`java.net.SocketException: Socket is not connected`. The enclave did NOT crash. AF_VSOCK does
not reliably support `SHUT_WR` half-close via junixsocket here.

Because `AfVsockTransport.exchange` did `sendAll -> shutdownWrite -> readAll`, the
`shutdownWrite()` step threw, surfacing as `IOException` -> `VsockEnclaveClient` wrapped it as
`ENCLAVE_TRANSPORT_FAILED`. That is why all 7 live calls failed.

The enclave `VsockServer.handleOne` read with `readNBytes(1MB)`, which depends on the client's
EOF/half-close to know the request ended — the same half-close-dependent scheme.

## Fix — explicit LENGTH-PREFIX framing on both sides (no half-close anywhere)

### Wire protocol (identical on both sides)
- Request frame: 4-byte **big-endian unsigned** length `N`, then `N` bytes of UTF-8 JSON.
- Response frame: 4-byte **big-endian unsigned** length `M`, then `M` bytes of UTF-8 JSON.
- No socket half-close anywhere. The reader loops until it has the full 4-byte header, then
  loops until it has exactly `N` (or `M`) bytes, throwing `EOFException` on premature end.
- Max frame guard: a declared length `> 16 MiB` is rejected before allocation
  (`ENCLAVE_FRAME_TOO_LARGE` on the coordinator, `VSOCK_FRAME_TOO_LARGE` on the enclave).

### Coordinator — `AfVsockTransport` + `VsockChannel`

Before:
```java
// VsockChannel: sendAll(byte[]) / shutdownWrite() / readAll()
static byte[] exchange(VsockChannel channel, byte[] request) throws IOException {
    channel.sendAll(request);
    channel.shutdownWrite();        // <-- threw on AF_VSOCK
    return channel.readAll();       // inputStream.readAllBytes() (EOF-dependent)
}
// adapter.shutdownWrite() = socket.shutdownOutput();
```

After:
```java
// VsockChannel: write(byte[]) / readExactly(int)  — NO half-close method on the seam
static byte[] exchange(VsockChannel channel, byte[] request) throws IOException {
    channel.write(frame(request));  // [4-byte BE len][payload], then flush
    return readFrame(channel);      // read [4-byte BE len], then exactly len bytes
}
```
- `frame(payload)` prepends the big-endian length prefix.
- `readFrame` reads the 4-byte header, rejects `len > MAX_FRAME_BYTES`, then `readExactly(len)`.
- `readExactly(InputStream, n)` loops on `read` until `n` bytes or throws `EOFException`.
- `SocketChannelAdapter` keeps opening the junixsocket `AFVSOCKSocket` exactly as before; it
  **no longer calls `shutdownOutput`**. `shutdownWrite()`/`readAll()`/`sendAll()` are deleted
  from the interface and adapter, so no caller can depend on half-close.

### Enclave — `VsockServer.handleOne`

Before:
```java
byte[] request = connection.getInputStream().readNBytes(1024 * 1024); // EOF-dependent
if (request.length == 0) return;
byte[] response = handler.handle(new String(request, UTF_8)).getBytes(UTF_8);
out.write(response); out.flush();                                     // no length prefix
```

After:
```java
int first = in.read(header, 0, 1);
if (first < 0) return;                 // empty connection: no handler, no crash
readExactly(in, header, 1, 3);         // rest of the 4-byte BE length prefix
long len = ...;                        // big-endian decode
if (len > MAX_FRAME_BYTES) throw new IOException("VSOCK_FRAME_TOO_LARGE");
byte[] request = new byte[(int) len];
readExactly(in, request, 0, (int) len);
byte[] response = handler.handle(new String(request, UTF_8)).getBytes(UTF_8);
out.write(len-prefix of response); out.write(response); out.flush();   // framed response
```
- Empty connection (immediate EOF on the first header byte) is still a clean no-op.
- Truncated header or body -> `EOFException`, contained by `serve()`'s existing
  `catch (Throwable)` so the accept loop continues.
- The `Connection`/`SocketConnection` seam, `serve()` per-connection hardening, CID 16 /
  port 5005, the `RequestHandler` contract, `EnclaveMain` wiring, and the `{ok,...}` envelope
  are all UNCHANGED. Only the on-wire framing changed.

## Confirmation: half-close fully removed from the exchange path
- No `shutdownOutput`, `shutdownWrite`, `sendAll`, `readAll`, or `readAllBytes` remains anywhere
  in the worktree (grep returned no matches).
- `VsockFramingTest.noHalfCloseMethodOnChannelSeam` reflectively asserts the `VsockChannel`
  seam exposes no `shutdownWrite`/`shutdownOutput` method.

## Tests

New / updated:
- `coordinator/.../VsockLoopbackIntegrationTest` — **real connected-socket integration**: the
  REAL `AfVsockTransport.exchange` runs over a loopback TCP socket against an enclave stand-in
  that speaks the exact VsockServer wire protocol; asserts the request bytes on the wire and the
  exact response read back. No half-close.
- `nitro-enclave/.../VsockLoopbackIntegrationTest` — **real connected-socket integration**: the
  REAL `VsockServer.handleOne` runs over a loopback TCP socket; a coordinator stand-in writes
  `[len][request]` and reads `[len][response]`. Both loopback tests share the identical
  byte-level vectors `SHARED_REQUEST` / `SHARED_RESPONSE`, proving the two framings are
  byte-compatible.
- `coordinator/.../VsockFramingTest` (now 8 tests) — length-prefix write/read, no-half-close
  seam check, oversized-frame rejection, truncated-body `EOFException`, plus the 5 existing
  `ok`-contract tests.
- `nitro-enclave/.../VsockServerTest` (now 7 tests) — framed happy path, empty connection no-op,
  truncated header -> `EOFException`, truncated body -> `EOFException`, oversized length rejected,
  handler-throws propagates, write-failure propagates.

### Commands and results

`juse21; mvn -pl nitro-enclave -am test` (worktree pom):
```
Running com.olea.dowsure.enclave.VsockLoopbackIntegrationTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
Running com.olea.dowsure.enclave.VsockServerTest
Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
Results: Tests run: 35, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

`juse21; mvn -pl coordinator -am test` (worktree pom):
```
Running com.olea.dowsure.coordinator.VsockFramingTest
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
Running com.olea.dowsure.coordinator.VsockLoopbackIntegrationTest
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
Results: Tests run: 22, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

`juse21; mvn -f <worktree>/pom.xml clean test` (full reactor):
```
enclave-service ...... SUCCESS  (Tests run: 35, Failures: 0, Errors: 0, Skipped: 0)
coordinator .......... SUCCESS  (Tests run: 22, Failures: 0, Errors: 0, Skipped: 0)
BUILD SUCCESS  — 57 tests total, 0 failures
```

The verifier module was not touched. docker/nitro-cli/aws were not run (the orchestrator
rebuilds + redeploys the EIF and coordinator jar after merge and re-runs the live 7 calls).
