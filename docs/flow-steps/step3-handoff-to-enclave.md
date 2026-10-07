# Step 3 — Hand off to enclave over vsock

## Who
**Coordinator** (`Coordinator.java:run()` + `EnclaveClient` → `AfVsockTransport`
→ `VsockChannel`) → **Nitro Enclave** (`VsockServer` → `EnclaveMain` → `EnclaveService`)

## What happens
The Coordinator builds a JSON request combining the challenge nonce, sourceId, and
metadata, then sends it to the enclave over an `AF_VSOCK` connection. The enclave
is an isolated VM — no network, no disk, no shell. The **only** way in or out is
vsock.

## Transport details

| Parameter | Value |
|---|---|
| Protocol | `AF_VSOCK` (Linux virtio) |
| Enclave CID | `16` |
| Enclave port | `5005` |
| Framing | **Length-prefixed**: 4-byte big-endian length header + JSON payload bytes. Required because `AF_VSOCK` doesn't support TCP half-close (`shutdownOutput()`). Without the length prefix, the reader can't tell when the message ends. |

The Coordinator writes:
```
[4 bytes: payload length, big-endian] [payload bytes (UTF-8 JSON)]
```

The enclave's `VsockServer` reads the 4-byte length, then reads exactly that many
bytes, then processes.

## Payload sent to enclave

Built by `Coordinator.enclaveRequest()`:

```jsonc
{
  "requestId":     "7e7e04ee-...",   // string (UUID) — from step 1
  "nonce":         "dGhpcyBpcyBh...", // string (base64url) — from the challenge; gets bound into the attestation
  "policyVersion": "v1.0",           // string — from the challenge
  "evidenceId":    "a1b2c3d4-...",   // string (UUID) — from step 1
  "eifDigest":     "a837d673...0444",// string (hex sha256) — the running EIF; echoed into the evidence
  "sourceId":      "getOrderMetrics" // string (enum) — selects the SourceRegistry entry to fetch
  // "requestBody": { ... }          // object (optional) — only for POST sources (gutuPanoramaChecks)
}
```

For POST sources (`gutuPanoramaChecks`), includes `"requestBody": {...}`.

```java
static Map<String, Object> enclaveRequest(...) {
    request.put("requestId", requestId);
    request.put("nonce", challenge.get("nonce"));
    request.put("policyVersion", challenge.get("policyVersion"));
    request.put("evidenceId", evidenceId);
    request.put("eifDigest", eifDigest);
    request.put("sourceId", sourceId);
    if (requestBody != null) request.put("requestBody", requestBody);
}
```

## What the enclave receives

`VsockServer` (port 5005) accepts the connection, reads the length-prefixed frame,
deserializes the JSON, calls `EnclaveService.acquire(request)`. The required fields
are validated first:

```java
require(request, "requestId", "nonce", "policyVersion", "evidenceId", "eifDigest", "sourceId");
```

If any is missing → throws `REQUEST_FIELD_MISSING:<field>`, enclave returns an error
frame. This is what triggered the early 403s during the live cutover when the
`sourceId` was `undefined` due to a stale release entry.

## Why vsock (and not HTTP, gRPC, etc.)

Nitro enclaves have **no NIC** — the hypervisor enforces this at the hardware level.
The only channel is `vsock`, a lightweight socket interface between the parent VM (host)
and the enclave VM. This is a Nitro security property: the enclave can't be reached from
the network, and the host can't inspect the enclave's memory. The only data that crosses
is what goes through the vsock channel, and the enclave controls what it sends back.

## The framing bug (historical context)

Initially we used TCP-style `shutdownOutput()` to signal end-of-message. `AF_VSOCK`
silently ignores `shutdown()` — the reader hung forever waiting for EOF. Fixed by
switching to length-prefix framing in commit `838a170`. Both coordinator and enclave
now use 4-byte big-endian length headers.

## What can go wrong

| Error | Cause |
|---|---|
| `ENCLAVE_TRANSPORT_FAILED` | Enclave not running, wrong CID/port, or enclave crashed |
| `REQUEST_FIELD_MISSING:<field>` | Missing required field in the JSON payload |
| Connection timeout | Enclave overloaded or stuck (dead but vsock doesn't know) |

## Outputs → Step 4
The enclave now has `{requestId, nonce, policyVersion, evidenceId, eifDigest, sourceId}`
in memory and proceeds to resolve the source endpoint and open a TLS connection.
