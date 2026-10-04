# Windows wrapper for the Step 0 gate proof. Requires Docker Desktop running.
$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path

docker volume create tlsn-cargo-cache | Out-Null
docker volume create tlsn-target-cache | Out-Null

$script = Join-Path $here 'run-gate-proof.sh'
docker run --rm `
  -v tlsn-cargo-cache:/usr/local/cargo/registry `
  -v tlsn-target-cache:/work/tlsn/target `
  -v "${script}:/run-gate-proof.sh:ro" `
  -e CARGO_NET_GIT_FETCH_WITH_CLI=true `
  rust:1.90-bookworm `
  bash -c "apt-get update -qq && apt-get install -y -qq git pkg-config libssl-dev cmake clang >/dev/null 2>&1; bash /run-gate-proof.sh"
