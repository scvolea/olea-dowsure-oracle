# Step 4 — Enclave terminates TLS + fetches source data

## Who
**Nitro Enclave** (`EnclaveService.acquire()` → `SourceRegistry.resolve()` →
`SourceTlsClient.fetch()` → `AfVsockSourceTransport.connect()`)

The relay (`vsock-proxy` on host ports 8001/8002) just shuttles encrypted bytes.

## What happens
The enclave resolves which external API to call based on `sourceId`, opens its **own
TLS connection** to that API (through the vsock relay), sends a raw HTTP/1.1 request,
and reads the **complete response bytes** (status line + headers + body). This is the
heart of TLS-in-TEE: the TLS session terminates **inside** the enclave, not on the
host.

## Source resolution (`SourceRegistry.resolve()`)

The enclave looks up `sourceId` in a static registry:

```java
// SourceRegistry.java — the 7 definitions
defs.put("getOrderMetrics",           new Definition("amazon",   "GET",  "/sales/v1/orderMetrics"));
defs.put("listFinancialEventGroups",  new Definition("amazon",   "GET",  "/finances/v0/financialEventGroups"));
defs.put("listTransactions",          new Definition("amazon",   "GET",  "/finances/2024-06-19/transactions"));
defs.put("alicloudTelThree",          new Definition("alicloud", "GET",  "/lundear/telThree"));
defs.put("qichachaEnterpriseVerify",  new Definition("qichacha", "GET",  "/EnterpriseInfo/Verify"));
defs.put("qichachaShixinCheck",       new Definition("qichacha", "GET",  "/ShixinCheck/GetList"));
defs.put("gutuPanoramaChecks",        new Definition("gutu",     "POST", "/api/v1/judicial/panorama-checks"));
```

Unknown `sourceId` → throws `SOURCE_SCOPE_INVALID`.

Mode resolution (env `SOURCE_MODE`):
- `mock` (PoC): base URL comes from per-provider env vars (e.g. `AMAZON_SP_BASE_URL`),
  or falls back to `http://localhost:4010`.
- `sandbox`/production: uses the real provider URLs:
  - amazon: `https://sandbox.sellingpartnerapi-na.amazon.com`
  - alicloud: `https://qrymobile.market.alicloudapi.com`
  - qichacha: `https://api.qichacha.com`
  - gutu: `https://turningapi.valuemap.cn`

**PoC live**: mocks baked as env vars → amazon calls go to
`097sqg03n1.execute-api.ap-southeast-1.amazonaws.com`, KYC calls to
`i8yde0kf2g.execute-api.ap-southeast-1.amazonaws.com`.

## The TLS connection path

```
Enclave Java code
  → AfVsockSourceTransport.connect(host, port)    // opens AF_VSOCK to host CID 3 (parent)
  → host vsock-proxy (port 8001 or 8002)           // forwards to provider:443 over TCP
  → provider TLS endpoint                          // the REAL source API

SourceTlsClient wraps the vsock socket in SSLSocket:
  SSLSocket socket = factory.createSocket(baseVsockSocket, entry.host(), entry.port(), true);
  params.setEndpointIdentificationAlgorithm("HTTPS");  // verify cert hostname
  socket.startHandshake();                              // TLS negotiation INSIDE enclave
```

The relay sees **only encrypted TLS records**. The plaintext HTTP request and response
exist only inside the enclave's memory, which Nitro's hypervisor isolates.

## HTTP request built by enclave (`SourceTlsClient.buildRequest()`)

**GET example** (getOrderMetrics → amazon mock):
```http
GET /mock/sales/v1/orderMetrics HTTP/1.1
Host: 097sqg03n1.execute-api.ap-southeast-1.amazonaws.com
Connection: close
Accept-Encoding: identity
x-api-key: <MOCK_API_KEY baked in EIF>
Content-Type: application/json

```

**POST example** (gutuPanoramaChecks):
```http
POST /mock/api/v1/judicial/panorama-checks HTTP/1.1
Host: i8yde0kf2g.execute-api.ap-southeast-1.amazonaws.com
Connection: close
Accept-Encoding: identity
x-api-key: <MOCK_API_KEY>
Content-Type: application/json
Content-Length: 142

{"companyName":"...","idNumber":"..."}
```

Auth headers come from `CredentialProvider.headersFor()` — in PoC, that's
`PocCredentialProvider` which returns `{"x-api-key": env.MOCK_API_KEY}`.
In production, `KmsAttestedCredentialProvider` fetches real provider credentials
from Secrets Manager via KMS-attested decryption.

## What comes back

The enclave reads the **full HTTP response** until connection close:

```http
HTTP/1.1 200 OK
Content-Type: application/json
...

{"payload":[{"date":"2026-01-01","amount":"15000"},...]}
```

`SourceTlsClient.readAll()` captures every byte — status line, headers, body — into
`rawResponseBytes`. This is the exact wire content that gets hashed.

## Why TLS inside the enclave matters

If TLS terminated on the host (or via a proxy), the host could read and alter the
plaintext response before the enclave sees it. By terminating TLS inside the enclave:
- The host's relay only sees ciphertext.
- The enclave verifies the server certificate against a CA bundle baked into the EIF.
- Hostname verification (`HTTPS` endpoint identification) prevents man-in-the-middle
  even if the host tried to reroute DNS.
- The exact bytes the enclave receives are what it hashes — no intermediate can tamper.

## CA trust

The enclave's `SourceTlsClient` loads a CA bundle from a path baked into the Docker
image. The `SSLContext` is initialized from this bundle — not the host's trust store.
If the provider's cert doesn't chain to a CA in the bundle → `TLS_HANDSHAKE_FAILED`.

## What can go wrong

| Error | Cause |
|---|---|
| `SOURCE_SCOPE_INVALID` | `sourceId` not in the 7-entry registry |
| `TLS_HANDSHAKE_FAILED` | Cert verification failure, relay down, host not forwarding, provider unreachable, CA bundle incomplete |
| `SOURCE_REQUEST_INVALID` | POST body serialization failure |
| `SOURCE_RESPONSE_INVALID` | Response body is not parseable JSON |
| `ENCLAVE_TRANSPORT_FAILED` | vsock relay not running on the expected port |

## Outputs → Step 5
- `rawResponseBytes` — the exact wire bytes (status line + headers + body)
- `rawResponseB64` — base64 of `rawResponseBytes`
- `rawPayload` — parsed JSON body extracted from the response
