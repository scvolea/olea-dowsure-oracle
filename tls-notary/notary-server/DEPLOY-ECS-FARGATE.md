# Notary-server deployment shape — ECS Fargate (infra-as-doc)

This is the production deployment target for the standalone Olea notary-server.
It is **design/doc only** here, not a live deploy. Region follows the Olea
platform standard (Singapore, `ap-southeast-1`).

## Why Fargate (a long-lived service), not Lambda

The notary-server is a **long-lived, stateful MPC party**:

- It holds an **open bidirectional MPC socket** with the prover for the whole
  notarization session (`:7047`), exchanging many rounds. This is not a single
  request/response.
- It keeps **per-session MPC state** in memory for the session's lifetime.
- It signs with a **stable, persistent signing keypair** that is the Olea
  verifier's pinned trust anchor; the key identity must not change per invocation.

Those three properties are exactly what a serverless function is wrong for:

| Need | Lambda | Fargate |
|---|---|---|
| Long-lived bidirectional socket | ✗ request/response, 15-min cap, no raw socket server | ✓ persistent task |
| In-memory session state across many MPC rounds | ✗ stateless, may cold-start/recycle | ✓ stable task |
| Stable signing-key identity (trust anchor) | ✗ ephemeral env | ✓ task reads one key from Secrets Manager |
| Inbound non-HTTP MPC socket behind an NLB | ✗ API Gateway is HTTP/WebSocket, not a raw MPC listener | ✓ NLB TCP :7047 |

**Decision:** API Gateway + Lambda is NOT suitable for the notary-server.

### Where API Gateway + Lambda IS correct

API Gateway + Lambda is the right fit for the **Olea-side** HTTP services, which
are short, stateless request/response:

- the **Olea verifier** (validates a submitted tlsProof + attestation), and
- the **Olea Challenge/nonce API** (issues single-use nonces, consumes them).

Those stay on API Gateway + Lambda. Only the notary-server (and the prover
sidecar it talks to) need the long-lived compute.

## Fargate target shape

```
                 (prover-sidecar on the Nitro/orchestrator host)
                                   |
                           TCP :7047 (MPC)
                                   v
            +-------------- private subnets --------------+
            |   NLB (internal, TCP :7047)                 |
            |            |                                |
            |   ECS Fargate service: olea-notary-server   |
            |   image ghcr.io/tlsnotary/tlsn/             |
            |         notary-server:v0.1.0-alpha.12       |
            |   task role reads signing key at startup    |
            +---------------------+-----------------------+
                                  |
                   Secrets Manager (notary signing key)
                   KMS CMK (encrypts the secret)
```

- **Networking:** private subnets only; no public IP. An **internal NLB**
  (TCP listener on `:7047`) fronts the Fargate service so the prover reaches it
  by stable DNS (e.g. `notary.olea.internal`). No API Gateway in this path.
- **Signing key:** stored in **AWS Secrets Manager**, encrypted with a **KMS
  CMK**. The task definition injects it at runtime (secret -> mounted file or
  env the entrypoint writes to the `private_key_pem_path` in `config.yaml`).
  The private key is never in the image, the task def, or git.
- **Public key / trust anchor:** `notary.pub` / the hex key id is published to
  the Olea verifier trust registry (see `notary-key-id.txt`). Rotating the
  Secrets Manager secret rotates the pin — update the verifier registry in the
  same change.
- **Config:** `config.yaml` baked as a read-only config (or SSM param); it only
  references key paths, never inlines a key.
- **Scaling:** one task is sufficient for the sandbox cadence; scale by session
  concurrency, not request rate. Health check on the TCP listener.
- **Isolation:** separate from the Nitro EC2 instance and from the Olea Lambda
  VPC config, so removing Nitro leaves the notary untouched.

## Not in scope here

No Terraform/CDK is authored in this feature — this document fixes the target
shape and the Lambda-vs-Fargate decision so later infra work (and FEAT-002/003/004)
build against a settled model.
