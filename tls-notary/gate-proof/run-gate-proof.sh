#!/usr/bin/env bash
# =============================================================================
# Step 0 gate proof: demonstrate the TLSNotary MPC-TLS pipeline is REAL and
# notary-learns-nothing, end-to-end, with NO external credentials.
#
# Runs entirely inside a pinned rust:1.86 Linux container (the production target
# is Linux/enclave, not a browser). It:
#   1. clones the upstream tlsn repo at the SAME tag as the notary-server image
#      (v0.1.0-alpha.12),
#   2. builds notary-server + tls-server-fixture + the attestation example,
#   3. starts notary-server (:7047) and the fixture, all on container localhost,
#   4. runs the upstream attestation example: prove -> present -> verify,
#      proving a real MPC-TLS session was notarized and the presentation
#      verifies against the notary verifying key.
#
# This is the TLSNotary project's OWN end-to-end flow. The prover-sidecar in
# ../prover-sidecar reuses the identical prover crates/API (tlsn-prover,
# notary-client) pinned to the same tag, so this proof de-risks that build path.
#
# No Rust is authored here. We compile upstream sources only.
# =============================================================================
set -euo pipefail

TAG="${TLSN_TAG:-v0.1.0-alpha.12}"
cd /work

echo "== [1/5] clone upstream tlsn @ ${TAG} =="
if [ ! -d tlsn/.git ]; then
  rm -rf tlsn
  git clone --depth 1 --branch "$TAG" https://github.com/tlsnotary/tlsn.git
fi
cd tlsn

echo "== [2/5] build notary-server + server-fixture + attestation example =="
cargo build --release -p notary-server -p tlsn-server-fixture \
  --example attestation_prove --example attestation_present --example attestation_verify

echo "== [3/5] start notary-server (:7047) and server-fixture =="
# Run the notary from its crate dir so it finds its default config + fixture key.
pushd crates/notary/server >/dev/null
RUST_LOG=info ../../../target/release/notary-server > /work/notary.log 2>&1 &
NOTARY_PID=$!
popd >/dev/null

RUST_LOG=info ./target/release/tlsn-server-fixture > /work/fixture.log 2>&1 &
FIXTURE_PID=$!

cleanup() { kill "$NOTARY_PID" "$FIXTURE_PID" 2>/dev/null || true; }
trap cleanup EXIT

echo "   waiting for notary (:7047) + fixture (:3000)..."
up=0
for i in $(seq 1 40); do
  if (exec 3<>/dev/tcp/127.0.0.1/7047) 2>/dev/null && (exec 4<>/dev/tcp/127.0.0.1/3000) 2>/dev/null; then
    up=1; echo "   both up."; break
  fi
  sleep 1
done
if [ "$up" != "1" ]; then
  echo "!! notary/fixture did not come up; logs:"; echo "--- notary ---"; cat /work/notary.log || true
  echo "--- fixture ---"; cat /work/fixture.log || true
  exit 2
fi

echo "== [4/5] run attestation example: prove -> present -> verify =="
export NOTARY_HOST=127.0.0.1 NOTARY_PORT=7047 SERVER_HOST=127.0.0.1 SERVER_PORT=3000
export RUST_LOG="info"
./target/release/examples/attestation_prove json
./target/release/examples/attestation_present json
./target/release/examples/attestation_verify json | tee /work/verify-output.txt

echo "== [5/5] gate proof complete =="
echo "A 'Successfully verified that the data below came from a session with"
echo "<domain>' line above means the MPC-TLS pipeline is REAL and the notary"
echo "signature validated (notary-learns-nothing MPC)."
