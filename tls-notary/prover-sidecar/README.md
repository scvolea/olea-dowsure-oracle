# TLSNotary Prover Sidecar

A thin, headless, server-side glue binary around the **upstream** TLSNotary
prover. The Java coordinator invokes it to perform one MPC-TLS **notarized GET**
against a target host and get back a proof bundle. It is the production path for
the "source authenticity" layer of the Olea/Dowsure oracle.

## Why this exists (and why not a browser / `tlsn-js`)

The production prover runs server-side (alongside / within the Nitro enclave
service model), not in a browser. `tlsn-js` is **browser-only and deprecated**,
so it is the wrong tool here. This sidecar drives the native upstream prover
crates (`tlsn-prover`, `notary-client`) directly, pinned to the **same release**
as the notary-server image.

The notary stays a **separate, prebuilt** upstream `notary-server` on its own
standalone host (Olea-hosted in production, decoupled from Nitro). If Nitro is
later removed, the notary + this sidecar still work.

## What is and isn't authored here

- We do **not** author bespoke protocol or business Rust. `src/main.rs` is thin
  glue: configure prover -> MPC-TLS GET -> notarize -> build presentation ->
  self-verify -> emit JSON bundle. This mirrors the upstream `attestation`
  example (`prove.rs` / `present.rs` / `verify.rs`) 1:1 in API usage.
- All Olea application logic stays in **Java + JS**. This Rust binary is run like
  a database/service dependency.

## Version pinning

| Component | Pin |
|---|---|
| Upstream tlsn crates | git `tag = v0.1.0-alpha.12` (see `Cargo.toml`) |
| notary-server image | `ghcr.io/tlsnotary/tlsn/notary-server:v0.1.0-alpha.12` |
| Rust toolchain | `1.86` (see `Dockerfile`, `rust-version` in `Cargo.toml`) |

Prover and notary **must** share the same protocol release; alpha.12 is chosen
to match the published notary-server image.

## Confirmed target TLS profile (Step 0 gate)

The SP-API sandbox host `sandbox.sellingpartnerapi-na.amazon.com` negotiates:

- TLS **1.2** (also offers 1.3; the prover will use 1.2)
- cipher **ECDHE-RSA-AES128-GCM-SHA256** (AES-128-GCM / SHA-256)
- **HTTP/1.1**, honoring `Connection: close` and `Accept-Encoding: identity`
- small bounded responses

tlsn MPC-TLS supports exactly TLS 1.2 + `TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256`,
so the host is compatible. The sidecar always sends `Accept-Encoding: identity`
and `Connection: close`.

## Build

```bash
# Linux binary (correct target: Linux/enclave), reproducible, host stays clean.
docker build -t tlsn-prover-sidecar:alpha.12 .
```

The multi-stage build compiles the upstream crates (pulled by git tag) and emits
a small `debian:bookworm-slim` runtime image with the binary at
`/usr/local/bin/tlsn-prover-sidecar`.

## Run

### Self-test (Step 0 gate, no credentials)

Proves the full MPC-TLS pipeline against the upstream `tls-server-fixture`. See
`../gate-proof/` for the one-command harness that also starts the notary and the
fixture.

```bash
tlsn-prover-sidecar --fixture --notary-host 127.0.0.1 --notary-port 7047
```

### Real host (orchestrator, with a real LWA token)

The orchestrator runs the SAME binary against the SP-API sandbox. The access
token is passed as a header and never committed.

```bash
tlsn-prover-sidecar \
  --host sandbox.sellingpartnerapi-na.amazon.com \
  --port 443 \
  --path '/sales/v1/orderMetrics?marketplaceIds=ATVPDKIKX0DER&interval=2022-08-01T00:00:00-07:00--2022-08-02T00:00:00-07:00&granularity=Total' \
  --header "x-amz-access-token: $LWA_ACCESS_TOKEN" \
  --nonce "$OLEA_CHALLENGE_NONCE" \
  --notary-host notary.olea.internal --notary-port 7047 --notary-tls true
```

The three Step 0 sandbox endpoints (all expect HTTP 200, host
`sandbox.sellingpartnerapi-na.amazon.com`, LWA bearer on `x-amz-access-token`):

- `getOrderMetrics`: `GET /sales/v1/orderMetrics?marketplaceIds=ATVPDKIKX0DER&interval=2022-08-01T00:00:00-07:00--2022-08-02T00:00:00-07:00&granularity=Total`
- `listFinancialEventGroups`: `GET /finances/v0/financialEventGroups?MaxResultsPerPage=1&FinancialEventGroupStartedAfter=2019-10-13&FinancialEventGroupStartedBefore=2019-10-31`
- `listTransactions`: `GET /finances/2024-06-19/transactions?postedAfter=2023-03-07&nextToken=jehgri34yo7jr9e8f984tr9i4o`

## Output: the proof bundle (stdout JSON)

```json
{
  "ok": true,
  "server_name": "sandbox.sellingpartnerapi-na.amazon.com",
  "notary_key_alg": "...",
  "notary_pub_key_id": "<hex notary verifying key>",
  "connection_time_unix": 1690000000,
  "request_commitment": "<sha256 of revealed sent transcript>",
  "response_hash": "<sha256 of revealed recv transcript>",
  "revealed_sent_preview": "...",
  "revealed_recv_preview": "...",
  "nonce": "<echoed Olea challenge nonce>",
  "attestation_b64": "<bincode(attestation)>",
  "presentation_b64": "<bincode(presentation)>"
}
```

`notary_pub_key_id` is the independent trust anchor: the Olea verifier must pin
it against the trust registry and reject proofs signed by any other notary.

## How the Java coordinator invokes it

The coordinator shells out to the binary (subprocess), passes the target +
headers + Olea nonce, and reads the JSON bundle from stdout. Loopback/subprocess
keeps the LWA token out of logs and off the network beyond the TLS session.

```java
// Illustrative: the real wiring lives behind the coordinator's tlsProof seam.
List<String> cmd = List.of(
    "/usr/local/bin/tlsn-prover-sidecar",
    "--host", host, "--port", "443",
    "--path", pathAndQuery,
    "--header", "x-amz-access-token: " + lwaAccessToken,
    "--nonce", oleaChallengeNonce,
    "--notary-host", notaryHost, "--notary-port", "7047", "--notary-tls", "true");
Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
String bundleJson = new String(p.getInputStream().readAllBytes(), UTF_8);
int code = p.waitFor();
// code == 0 && bundle.ok  -> hand bundle to the Olea JS verifier (notary key,
// domain, request commitment, response hash, nonce, freshness).
```

The sidecar fails closed: a non-zero exit or `ok=false` means no proof. The
coordinator must not fall back to an unnotarized fetch.

## Security notes

- No secrets or keys are committed. The LWA token is passed at call time only.
- `target/` and build artifacts are git-ignored.
- The notary signing key is generated and held by the notary host (Olea), never
  by this sidecar.
