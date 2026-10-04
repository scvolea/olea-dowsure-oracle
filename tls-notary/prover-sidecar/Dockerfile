# Builds the TLSNotary prover sidecar as a Linux binary.
# This is the correct target: production proving runs server-side on Linux
# (alongside/within the Nitro enclave service model), never in a browser.
#
# Pinned toolchain: rust:1.86 matches the sidecar crate's rust-version and the
# upstream tlsn v0.1.0-alpha.12 MSRV. The upstream crates are pulled by git tag
# (see Cargo.toml); we compile them, we do not author protocol Rust.
FROM rust:1.86-bookworm AS build
WORKDIR /src

# System deps for the crypto crates (ring, openssl-sys style builds).
RUN apt-get update && apt-get install -y --no-install-recommends \
        pkg-config libssl-dev cmake clang \
    && rm -rf /var/lib/apt/lists/*

COPY Cargo.toml ./
COPY src ./src

# Build the release binary. The git-pinned upstream workspace resolves mpz /
# tlsn-utils to the exact revs the alpha.12 workspace uses.
RUN cargo build --release --locked || cargo build --release
RUN cp target/release/tlsn-prover-sidecar /tlsn-prover-sidecar

# ---- Minimal runtime image ----
FROM debian:bookworm-slim AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /tlsn-prover-sidecar /usr/local/bin/tlsn-prover-sidecar
ENTRYPOINT ["/usr/local/bin/tlsn-prover-sidecar"]
