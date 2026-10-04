# TLSNotary end-to-end evidence (redacted)

This directory holds the **redacted** evidence for the TLSNotary MPC-TLS flow
(FEAT-004). Everything here is safe to commit: there is **no LWA token, no
secret, and no real PII**. The order figures are synthetic.

## Files

| File | What it is |
|---|---|
| `sample-bundle.json` | A representative MPC-TLS prover-sidecar proof bundle (the snake_case stdout JSON the thin upstream `tlsn-prover` sidecar emits after one notarized GET). **Redacted**: the `x-amz-access-token` header value is replaced with the literal `<redacted>` marker. `response_hash` and `nonce` are placeholders the demo recomputes/binds so the fixture stays self-consistent. |
| `e2e-transcript.json` | The ACCEPT + each REJECT `reasonCode` produced by the demo run. |
| `README.md` | This file. |

## How the evidence is produced (no creds, no Rust compile)

From the worktree root:

```powershell
node --test scripts/tlsnotary-e2e-demo.test.js   # agent-verifiable gate (11 cases)
node scripts/tlsnotary-e2e-demo.js               # prints ACCEPT + 8 REJECT reasonCodes, exits 0
```

The demo (`scripts/tlsnotary-e2e-demo.js`) issues an Olea challenge, loads
`sample-bundle.json`, normalizes it via `tls-notary/bundle/normalize-bundle.js`
(FEAT-001), builds the enclave `user_data` binding (FEAT-003 shape), then runs
the Olea TLSNotary verifier (FEAT-002) for one valid bundle and eight negative
cases.

## Negative cases and reasonCodes

| Case | reasonCode |
|---|---|
| valid bundle | `SUCCESS` (ACCEPT) |
| tampered response | `TLS_PROOF_HASH_MISMATCH` |
| tampered proof | `TLS_PROOF_INVALID` |
| wrong nonce | `NONCE_MISMATCH` |
| missing nonce | `NONCE_MISMATCH` |
| expired nonce | `CHALLENGE_EXPIRED` |
| replayed nonce | `CHALLENGE_REPLAY` |
| wrong notary key | `NOTARY_KEY_UNTRUSTED` |
| wrong domain | `DOMAIN_MISMATCH` |

## Redaction / secret-safety

- No `x-amz-access-token` **value** appears in any file (the normalizer strips
  the whole header line and fails closed on a surviving `name:` fragment).
- No real notary key: `notary_pub_key_id` is an example-shape P-256 key id.
- Raw/credentialed artifacts (a real sidecar bundle, a real transcript) are
  gitignored under `evidence/tlsnotary/` and never committed. See the
  orchestrator-only credentialed reproduce in `docs/TLSNOTARY.md`.
