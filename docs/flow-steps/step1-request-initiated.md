# Step 1 — Request initiated

## Who
**Coordinator** (`coordinator/.../Coordinator.java` + `CoordinatorMain.java`)
Runs on the Nitro host. Untrusted — it orchestrates but never sees source plaintext.

## What happens
The Coordinator is invoked with CLI arguments that define which data to fetch and how
to prove it. It generates a `requestId` (UUID) and an `evidenceId` (UUID), then
proceeds to Step 2.

## CLI invocation (real command)

```bash
java -jar coordinator/target/coordinator-*.jar \
  --olea-url  https://oracle.oleainternal.com \
  --source-id getOrderMetrics \
  --dowsure-private-key-file tmp/dowsure-key/dowsure-private.pem \
  --eif-digest a837d6739cae6e45ba9785e0991099d85764ecdd7831a99fc7310d05aa930444 \
  --enclave-cid 16 \
  --enclave-port 5005
```

For `gutuPanoramaChecks` (the only POST source), add:
```bash
  --request-body-file tmp/gutu-request.json
```

## Inputs (what the Coordinator needs)

| Input | Value (preprod live) | Purpose |
|---|---|---|
| `olea-url` | `https://oracle.oleainternal.com` | Olea verifier base URL (custom domain bypasses VPC execute-api VPCE interception) |
| `source-id` | one of 7 (see index) | Which source endpoint to call |
| `dowsure-private-key-file` | `tmp/dowsure-key/dowsure-private.pem` | Dowsure's EC key. Used to sign the submission envelope (Step 7). Never leaves the host. |
| `eif-digest` | `a837d673...0444` | SHA-256 of the running EIF. Must match a registered ACTIVE release in Olea's release table. |
| `enclave-cid` | `16` | The Nitro enclave's vsock context ID |
| `enclave-port` | `5005` | The enclave's VsockServer listen port |
| `request-body-file` | (optional) JSON body for POST sources | Only `gutuPanoramaChecks` uses this |

## Generated IDs

```java
// Coordinator.java:run()
String requestId  = UUID.randomUUID().toString();  // e.g. "7e7e04ee-..."
String evidenceId = UUID.randomUUID().toString();  // e.g. "a1b2c3d4-..."
```

Both are random UUIDs. `requestId` ties the challenge→evidence flow together.
`evidenceId` becomes the key under which Olea stores the accepted evidence in S3.

## Why
Dowsure needs to prove to Olea that financial/KYC data about a borrower came from the
real source API, untampered. The Coordinator starts that proof by naming the data call
(`sourceId`) and providing credentials that let both sides (enclave + verifier) bind
results cryptographically.

## What can go wrong here
- Wrong `eif-digest` → verifier rejects at Step 9 (`PCR_MISMATCH`).
- Expired AWS session → SSM commands fail before this step even starts.
- Wrong `source-id` string → verifier rejects challenge at Step 2 (`CHALLENGE_ENDPOINT_MISMATCH`).

## Outputs → Step 2
`requestId`, `evidenceId`, `sourceId`, and the Olea base URL. The Coordinator
immediately calls `POST /v1/challenges`.
