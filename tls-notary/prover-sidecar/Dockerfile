# Builds the TLSNotary prover sidecar as a Linux binary.
# This is the correct target: production proving runs server-side on Linux
# (alongside/within the Nitro enclave service model), never in a browser.
#
# Pinned toolchain: rust:1.90. The upstream tlsn v0.1.0-alpha.12 dependency tree
# (mpz rev ccc0057) uses Vec::extract_if, stabilized in Rust 1.87, so the
# toolchain must be >= 1.87; 1.90 is pinned for headroom. The upstream crates are
# pulled by git tag
# (see Cargo.toml); we compile them, we do not author protocol Rust.
FROM rust:1.90-bookworm AS build
WORKDIR /src

# System deps for the crypto crates (ring, openssl-sys style builds).
RUN apt-get update && apt-get install -y --no-install-recommends \
        pkg-config libssl-dev cmake clang \
    && rm -rf /var/lib/apt/lists/*

COPY Cargo.toml ./
COPY Cargo.lock ./
COPY src ./src

# Build the release binary. STRICT --locked: the committed Cargo.lock pins the
# whole crypto tree to the alpha.12-compatible RC versions (cipher 0.5.0-rc.0,
# aes 0.9.0-rc.0, crypto-common 0.2.0-rc.3), so cargo can never re-resolve up to
# the incompatible 0.5.2/0.9.3 line that caused error[E0308] inside mpz.
RUN cargo build --release --locked
RUN cp target/release/tlsn-prover-sidecar /tlsn-prover-sidecar

# ---- Minimal runtime image ----
FROM debian:bookworm-slim AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY --from=build /tlsn-prover-sidecar /usr/local/bin/tlsn-prover-sidecar
ENTRYPOINT ["/usr/local/bin/tlsn-prover-sidecar"]
