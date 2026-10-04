# Step 0 Gate Proof — MPC-TLS pipeline is real

One command that proves the TLSNotary MPC-TLS pipeline is **real** and
**notary-learns-nothing**, end-to-end, with **no external credentials**.

It runs inside a pinned `rust:1.90` Linux container (production target is
Linux/enclave, not a browser) and:

1. clones upstream `tlsn` at `v0.1.0-alpha.12` (same release as the notary image),
2. builds `notary-server` + `tls-server-fixture` + the `attestation` example,
3. starts the notary (`:7047`) and the fixture on container localhost,
4. runs the upstream `attestation` example: **prove -> present -> verify**.

A successful run prints `Successfully verified that the data below came from a
session with <domain>`, which means a real MPC-TLS session was notarized and the
presentation validated against the notary verifying key.

This is the TLSNotary project's **own** end-to-end flow. The production
`../prover-sidecar` reuses the identical prover crates/API pinned to the same
tag, so this proof de-risks the sidecar build path.

## Run (Linux/macOS/WSL or Docker on Windows)

```bash
docker volume create tlsn-cargo-cache
docker volume create tlsn-target-cache
docker run --rm \
  -v tlsn-cargo-cache:/usr/local/cargo/registry \
  -v tlsn-target-cache:/work/tlsn/target \
  -v "$PWD/run-gate-proof.sh:/run-gate-proof.sh:ro" \
  -e CARGO_NET_GIT_FETCH_WITH_CLI=true \
  rust:1.90-bookworm \
  bash -c "apt-get update -qq && apt-get install -y -qq git pkg-config libssl-dev cmake clang >/dev/null && bash /run-gate-proof.sh"
```

## Run (Windows PowerShell)

```powershell
./run-gate-proof.ps1
```

## Notes

- First run compiles the full tlsn + mpz crypto tree (tens of minutes). The
  cargo/target volumes make re-runs fast.
- No Rust is authored here. Upstream sources are compiled only.
- The orchestrator (with real creds) runs the SAME binaries against the SP-API
  sandbox via `../prover-sidecar` real-host mode. This gate does not fake a
  sandbox call.
