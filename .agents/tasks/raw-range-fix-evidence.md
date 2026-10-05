# Author note — raw byte-range commit/reveal for the prover-sidecar response

## Symptom (observed live)
A live run against the real Amazon SP-API sandbox through the notary reached:

```
INFO tlsn_prover: finished MPC-TLS
INFO tlsn_prover_sidecar: got response from target status=200 OK
INFO tlsn_prover_sidecar: bounding recv transcript to the HTTP message ... http_message_len=960 total_recv_len=960 trailing_bytes=0
Error: parse http response: ParseError("trailing characters are present in source")
```

`trailing_bytes=0` and `http_message_len == total_recv_len` (960==960): there is NO TLS
close_notify framing to strip. The entire recv buffer IS the HTTP message. The error came from
spansy's JSON **body** parser inside `tlsn_formats::http::parse_response` rejecting Amazon's real
`{"payload":[ ... ]}` JSON body. The prior Content-Length-bounding fix did not help because the code
LOGGED the computed boundary but still called `parse_response` on the whole buffer via
`DefaultHttpCommitter` / `HttpTranscript` — so the structural JSON parse still ran and still failed.

## Root cause
Committing/revealing the response through the HTTP+JSON-structured path (`parse_request` /
`parse_response` → `HttpTranscript` → `DefaultHttpCommitter`) forces spansy to parse the body as
JSON. Amazon's real body shape trips spansy's "no trailing characters" invariant. The response must
be treated as **opaque bytes**, not parsed as HTTP/JSON.

## Fix — commit + reveal the transcript as a raw byte range (no HTTP/JSON parse)
Edited `tls-notary/prover-sidecar/src/main.rs` ONLY. Replaced both the commit site (step 5) and the
reveal site (step 6) with the upstream `tlsn-core` raw-range transcript API, and removed the
`use tlsn_formats::http::{ parse_request, parse_response, DefaultHttpCommitter, HttpCommit,
HttpTranscript };` import (all now unused). No Cargo.toml / Cargo.lock / Dockerfile change.

### Exact alpha.12 API used (verified against upstream tag `v0.1.0-alpha.12` source)
Commit side — `tlsn_core::transcript::TranscriptCommitConfigBuilder`
(`crates/core/src/transcript/commit.rs`):

```rust
pub fn commit_sent(&mut self, ranges: &dyn ToRangeSet<usize>)
    -> Result<&mut Self, TranscriptCommitConfigBuilderError>;
pub fn commit_recv(&mut self, ranges: &dyn ToRangeSet<usize>)
    -> Result<&mut Self, TranscriptCommitConfigBuilderError>;
```

Reveal side — `tlsn_core::transcript::TranscriptProofBuilder`
(`crates/core/src/transcript/proof.rs`), from `secrets.transcript_proof_builder()`:

```rust
pub fn reveal_sent(&mut self, ranges: &dyn ToRangeSet<usize>)
    -> Result<&mut Self, TranscriptProofBuilderError>;
pub fn reveal_recv(&mut self, ranges: &dyn ToRangeSet<usize>)
    -> Result<&mut Self, TranscriptProofBuilderError>;
```

`&(0..n)` is a `&Range<usize>`; it coerces to `&dyn ToRangeSet<usize>` without importing the trait
(unsized coercion needs the impl, not the trait in scope). This mirrors the upstream
`crates/examples/interactive/interactive.rs` idiom `builder.reveal_recv(&(0..pos))`, which also does
NOT import `ToRangeSet`. Confirmed by the green `--locked` Docker build — no `use rangeset::...` was
needed.

### How N (recv) and S (sent) are computed and actually applied
- `S = sent_bytes.len()` — commit/reveal `(0..S)` (the full request; no trailing framing on sent).
- `N = http_message_end(recv_bytes).unwrap_or(total_recv).min(total_recv)` — end-of-headers
  (CRLFCRLF) + Content-Length, with a safe fallback to the whole buffer when there is no
  Content-Length / it does not parse / it would exceed the buffer (e.g. chunked). Clamped into
  `[0, total_recv]`. **Never fails.**
- Unlike the prior code, `N` is now **actually used** as the committed and revealed recv bound
  `(0..N)` — not merely logged. The reveal site recomputes `S`/`N` from `secrets.transcript()`,
  which is the same session transcript as `prover.transcript()`, so the revealed range is the exact
  committed range → `reveal` is an exact subset of the commit → succeeds.
- Log line now tells the truth (the logged `http_message_len` is the `N` that is committed):
  `committing/revealing recv transcript as raw byte range [0, n)` with
  `http_message_len=N total_recv_len=... trailing_bytes=total_recv-N`.

### responseHash is byte-exact over the revealed range (also clears review finding #1)
Step 7 now slices the verified `PartialTranscript` to the authed end before hashing:
`recv_end = partial.received_authed().end()` (== N), `sent_end = partial.sent_authed().end()` (== S).
`response_hash = sha256(received_unsafe()[..N])`, `request_commitment = sha256(sent_unsafe()[..S])` —
the raw revealed bytes, NO `X` padding, NO truncation, NO trailing alert. (The prior code hashed the
full `X`-padded buffer; the APPROVED review flagged this as non-blocking finding #1 — now fixed.)
Hashing raw bytes (not the lossy-UTF-8 re-encoding) keeps the hash exact even for a non-UTF-8 body;
for a well-formed UTF-8 JSON body it is identical. Previews remain lossy-UTF-8 over the revealed
bytes (human-readable only). All `ProofBundle` fields are unchanged.

## Why this fixes the live failure by construction
`parse_response` / `parse_request` are no longer called on the recv/sent buffers. Commit and reveal
operate purely on byte ranges, so Amazon's `{"payload":[...]}` body shape can no longer trigger
`ParseError("trailing characters are present in source")`.

## Verification (run here)
- `docker build -t tlsn-prover-sidecar:alpha.12 .` from `tls-notary/prover-sidecar/` → **exit 0**
  (release build under strict `cargo build --release --locked`, frozen alpha.12 pins, no Cargo
  change; no `ToRangeSet` import required).
- `docker run --rm tlsn-prover-sidecar:alpha.12 --help` → **exit 0** (clap usage printed).
- Gate-proof (`tls-notary/gate-proof`, upstream attestation example prove→present→verify on the clean
  `tls-server-fixture`) → container exit 0, reached `== [5/5] gate proof complete ==` and printed:
  `Successfully verified that the data below came from a session with test-server.io at 2026-10-04 23:59:45 UTC.`
- Live Amazon notarization NOT runnable here (no SP-API creds; notary in a private VPC) — the
  raw-range fix is what makes that live run stop crashing on the JSON body, by construction.
