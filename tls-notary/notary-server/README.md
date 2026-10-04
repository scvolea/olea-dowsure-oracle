# Standalone Olea notary-server

The notary half of the TLSNotary MPC-TLS flow, run as a **separate, prebuilt**
upstream service on a host **decoupled from the Nitro EC2 instance**. The prover
(`../prover-sidecar`) dials this notary over an MPC socket; the notary signs an
attestation over commitments and **never sees plaintext** (notary-learns-nothing).
Keeping it standalone means the notary + prover still work if Nitro is removed.

## Version pin

| Component | Pin |
|---|---|
| notary-server image | `ghcr.io/tlsnotary/tlsn/notary-server:v0.1.0-alpha.12` |
| prover-sidecar crates | git tag `v0.1.0-alpha.12` (same protocol release) |
| Rust toolchain (sidecar/gate build only) | `1.90` (floor 1.87) |

Prover and notary **must** share the same protocol release (`alpha.12`).

## Files

- `docker-compose.yml` — runs the pinned prebuilt image, binds the MPC socket
  (`:7047`), mounts `config.yaml` and the gitignored signing key.
- `config.yaml` — config template; references the signing keypair **by path**,
  never inlines a private key.
- `notary-key-id.txt` — documents WHERE the pinned notary **public** key (the
  Olea verifier trust anchor) lives, and holds the hex key id once generated.
- `.gitignore` — blocks every private key form (`*.key`, `*_private.pem`,
  `secrets/`, `keys/` private material) while allowing `notary.pub` /
  `notary-key-id.txt`.
- `DEPLOY-ECS-FARGATE.md` — the production deployment shape (Fargate + NLB +
  Secrets Manager/KMS), and why API Gateway + Lambda is **not** suitable here.

## One-time key generation (kept out of git)

Run on the notary host; the private key never leaves it (never committed):

```bash
mkdir -p secrets keys
openssl ecparam -name prime256v1 -genkey -noout -out secrets/notary-signing.key
openssl ec -in secrets/notary-signing.key -pubout -out keys/notary.pub
```

Then publish `keys/notary.pub` (and record its hex key id in
`notary-key-id.txt`) to the Olea verifier trust registry as the pinned anchor.

## Run (local / single host)

```bash
docker compose up -d
docker compose logs -f notary-server   # expect it to bind :7047
```

The prover-sidecar then connects with
`--notary-host <this-host> --notary-port 7047`.

## Trust anchor

The proof bundle's `notary_pub_key_id` (normalized to `notaryPubKeyId`) must
match the pinned public key in `notary-key-id.txt`. The Olea verifier rejects
any proof signed by a different notary. See `notary-key-id.txt` for rotation.
