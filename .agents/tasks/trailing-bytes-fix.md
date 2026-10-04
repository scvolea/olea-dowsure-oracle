# TLS Transcript Trailing-Bytes Fix

## Symptom

A live MPC-TLS notarization against the real Amazon SP-API sandbox completed
(Amazon returned HTTP 200), then the sidecar failed on the next line:

```
INFO tlsn_prover_sidecar: got response from target status=200 OK
Error: parse http transcript: ParseError("trailing characters are present in source")
```

## Root cause

Amazon sends `Connection: close`, so the TLS layer records trailing
close_notify / connection-close framing bytes into the **recv** transcript
*after* the HTTP response body. The upstream whole-buffer parser
`tlsn_formats::http::HttpTranscript::parse` tries to parse the entire recv
buffer as a sequence of complete HTTP messages. The response is
`Content-Type: application/json`, so the body is routed through spansy's JSON
parser, whose "no trailing characters" invariant
(`value.as_str().len() != src.len()`) fires on the extra framing bytes and
raises `ParseError("trailing characters are present in source")`.

`max_recv` is NOT the cause — the response is small and well under the notary
`max_recv_data` ceiling (16384). The gate `--fixture` path passed because
`tls-server-fixture` returns a clean response with no trailing bytes.

## Fix (src/main.rs only — no Cargo / Cargo.lock / Dockerfile changes)

Stop parsing the whole buffer. At BOTH parse sites, parse exactly ONE request
and ONE response with the single-message parsers that `tlsn_formats::http`
re-exports from spansy (`parse_request` / `parse_response`), then build the
`HttpTranscript` value manually from those (its `requests` / `responses` fields
are public):

- Site 1 (before commit): `HttpTranscript::parse(prover.transcript())` ->
  `parse_request(prover.transcript().sent())` +
  `parse_response(prover.transcript().received())`.
- Site 2 (before building the presentation):
  `HttpTranscript::parse(secrets.transcript())` ->
  `parse_request(secrets.transcript().sent())` +
  `parse_response(secrets.transcript().received())`.

`parse_response` bounds the message by `Content-Length`
(`response_body_len` reads `Content-Length`; the sandbox returns
`Content-Length`, not chunked). The returned `Response.span` is
`offset .. head_end + body_len`, i.e. it stops exactly at the end of the HTTP
message and ignores every trailing byte.

A small helper `http_message_end(buf)` computes the same boundary independently
(first `\r\n\r\n` + a case-insensitive `Content-Length:` scan) and feeds a log
line so the orchestrator can confirm the fix on the real response:

```
bounding recv transcript to the HTTP message (excluding Connection: close framing)
  http_message_len=<N> total_recv_len=<M> trailing_bytes=<M-N>
```

The helper is used ONLY for the log line / sanity check; the authoritative
commit/reveal ranges come from spansy's `parse_response`.

## Why the responseHash is still the EXACT HTTP response (no weakening)

The commit and reveal APIs operate on byte **ranges**, not the raw buffer, and
they derive those ranges from the parsed message span:

- `DefaultHttpCommitter::commit_transcript` iterates `transcript.responses` and
  calls `builder.commit(response, direction)`. A spansy `Response` implements
  `ToRangeSet<usize>` returning `self.span.indices`, i.e. exactly
  `[start, head_end + content_length)`.
- `secrets.transcript_proof_builder().reveal_recv(resp0)` likewise reveals
  `response.span.indices`.

Because `response.span` now ends at the HTTP message boundary, both the
committed range and the revealed range are exactly `[start, http_msg_end)` of
the real HTTP response — the trailing close_notify is never committed, never
revealed, and never truncates the real response. Both parse sites parse the same
bytes with the same parser, so the commit range and the reveal range agree by
construction.

`responseHash` in step 7 is `sha256_hex(recv)` where `recv` comes from the
verified `PartialTranscript.received_unsafe()` with unauthed bytes set to `b'X'`.
Since only the HTTP-message range was revealed/authed, the hash is over exactly
the real HTTP response bytes — no trailing alert, no truncation.

The sent/request side is unchanged in substance (`Connection: close` affects
recv only); using `parse_request` for the single request is equivalent to the
old whole-buffer path and keeps both sites symmetric.

## Verification performed

- `docker build -t tlsn-prover-sidecar:alpha.12 .` from
  `tls-notary/prover-sidecar` — exit 0, image built under strict `--locked`
  with the frozen alpha.12 pins (no dependency changes).
- `docker run --rm tlsn-prover-sidecar:alpha.12 --help` — exit 0, clap usage
  prints (binary links and starts).
- `tls-notary/gate-proof/run-gate-proof.ps1` — the upstream MPC-TLS
  prove -> present -> verify pipeline (same alpha.12 crates the sidecar links)
  ran to `== [5/5] gate proof complete ==` under `set -euo pipefail`, verifying
  the clean fixture response (HTTP/1.1 200, `Content-Length: 722`). The clean
  path has no trailing bytes, so `parse_response` yields the same span the old
  whole-buffer parse did — no regression.

The live Amazon notarization cannot be run here (no creds; notary in a private
VPC). On the real run, expect the new `bounding recv transcript to the HTTP
message ...` log with `http_message_len < total_recv_len` and
`trailing_bytes > 0`, then a bundle with `ok: true`.
