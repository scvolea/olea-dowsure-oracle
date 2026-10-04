//! TLSNotary prover sidecar (thin glue over the upstream tlsn prover).
//!
//! Headless, server-side. Invoked by the Java coordinator (subprocess) with a
//! target {url, method, headers}. Performs ONE MPC-TLS notarized GET against the
//! target with the notary, then emits a proof bundle JSON on stdout:
//!
//!   {
//!     "ok": true,
//!     "serverName": "...",            // domain bound by the TLS handshake
//!     "notaryKeyAlg": "...",          // notary signing key algorithm
//!     "notaryPubKeyId": "<hex>",      // notary verifying key (trust anchor)
//!     "connectionTimeUnix": 169...,   // session start time (freshness)
//!     "requestCommitment": "<sha256>",// hash of the revealed sent transcript
//!     "responseHash": "<sha256>",     // hash of the revealed recv transcript
//!     "revealedSentPreview": "...",
//!     "revealedRecvPreview": "...",
//!     "nonce": "<echoed>",            // Olea challenge nonce (freshness binding)
//!     "attestationB64": "...",        // bincode(attestation) base64
//!     "presentationB64": "..."        // bincode(presentation) base64
//!   }
//!
//! Notary-learns-nothing: the notary participates ONLY in the MPC and signs an
//! attestation over commitments. It never sees plaintext. This binary verifies
//! the presentation locally against the notary verifying key to prove the bundle.
//!
//! Modes:
//!   --fixture         Self-test against the upstream tls-server-fixture (uses the
//!                     fixture's self-signed CA). Proves the pipeline end-to-end
//!                     with NO external credentials. Used by the Step 0 gate.
//!   (default)         Real host mode: default WebPki roots. The orchestrator uses
//!                     this against the SP-API sandbox with a real LWA token.

use std::env;
use std::sync::Arc;

use anyhow::{anyhow, Context, Result};
use base64::Engine as _;
use clap::Parser;
use http_body_util::Empty;
use hyper::{body::Bytes, Request, StatusCode};
use hyper_util::rt::TokioIo;
use sha2::{Digest, Sha256};
use tokio_util::compat::{FuturesAsyncReadCompatExt, TokioAsyncReadCompatExt};

use notary_client::{Accepted, NotarizationRequest, NotaryClient};
use tls_core::verify::WebPkiVerifier;
use tlsn_common::config::ProtocolConfig;
use tlsn_core::{
    presentation::{Presentation, PresentationOutput},
    request::RequestConfig,
    signing::VerifyingKey,
    transcript::TranscriptCommitConfig,
    CryptoProvider,
};
use tlsn_formats::http::{
    parse_request, parse_response, DefaultHttpCommitter, HttpCommit, HttpTranscript,
};
use tlsn_prover::{Prover, ProverConfig};

const USER_AGENT: &str =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) tlsn-prover-sidecar";

#[derive(Parser, Debug)]
#[command(version, about = "TLSNotary prover sidecar (upstream prover glue)")]
struct Args {
    /// Self-test against the upstream tls-server-fixture (no external creds).
    #[clap(long)]
    fixture: bool,

    /// Target host (DNS name used for SNI + certificate verification).
    #[clap(long, env = "TARGET_HOST")]
    host: Option<String>,

    /// Target port.
    #[clap(long, env = "TARGET_PORT", default_value_t = 443)]
    port: u16,

    /// Request path+query, e.g. /sales/v1/orderMetrics?marketplaceIds=...
    #[clap(long, env = "TARGET_PATH", default_value = "/")]
    path: String,

    /// Header in the form "Name: value". Repeatable. Use for x-amz-access-token.
    #[clap(long = "header", value_parser = parse_header)]
    headers: Vec<(String, String)>,

    /// Notary host.
    #[clap(long, env = "NOTARY_HOST", default_value = "127.0.0.1")]
    notary_host: String,

    /// Notary port.
    #[clap(long, env = "NOTARY_PORT", default_value_t = 7047)]
    notary_port: u16,

    /// Connect to the notary over TLS (false only for a local dev notary).
    #[clap(long, env = "NOTARY_TLS", default_value_t = false)]
    notary_tls: bool,

    /// Olea challenge nonce to bind for freshness (echoed into the bundle).
    #[clap(long, env = "OLEA_NONCE", default_value = "")]
    nonce: String,

    /// Max bytes sent to the server (MPC resources are allocated up front).
    #[clap(long, env = "MAX_SENT_DATA", default_value_t = 1024)]
    max_sent: usize,

    /// Max bytes received from the server.
    #[clap(long, env = "MAX_RECV_DATA", default_value_t = 4096)]
    max_recv: usize,
}

fn parse_header(s: &str) -> Result<(String, String), String> {
    let (k, v) = s
        .split_once(':')
        .ok_or_else(|| format!("header must be 'Name: value', got: {s}"))?;
    Ok((k.trim().to_string(), v.trim().to_string()))
}

#[derive(serde::Serialize)]
struct ProofBundle {
    ok: bool,
    server_name: String,
    notary_key_alg: String,
    notary_pub_key_id: String,
    connection_time_unix: u64,
    request_commitment: String,
    response_hash: String,
    revealed_sent_preview: String,
    revealed_recv_preview: String,
    nonce: String,
    attestation_b64: String,
    presentation_b64: String,
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_writer(std::io::stderr) // keep stdout clean for the JSON bundle
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| "info".into()),
        )
        .init();

    let args = Args::parse();

    // Resolve target + crypto provider (fixture CA for self-test, else WebPki).
    let (server_host, server_port, server_name, path, crypto_provider) = if args.fixture {
        use tls_server_fixture::{CA_CERT_DER, SERVER_DOMAIN};
        use tlsn_server_fixture::DEFAULT_FIXTURE_PORT;

        let host = env::var("SERVER_HOST").unwrap_or_else(|_| "127.0.0.1".into());
        let port = env::var("SERVER_PORT")
            .ok()
            .and_then(|p| p.parse().ok())
            .unwrap_or(DEFAULT_FIXTURE_PORT);

        let mut root_store = tls_core::anchors::RootCertStore::empty();
        root_store
            .add(&tls_core::key::Certificate(CA_CERT_DER.to_vec()))
            .map_err(|e| anyhow!("add fixture CA: {e:?}"))?;
        // CryptoProvider is not Clone in alpha.12, but this single-process
        // sidecar reuses one provider across the prover config, the presentation
        // builder, and the self-verify step. Share it behind an Arc so each site
        // gets the SAME provider (identical trust anchors) without cloning.
        let provider = Arc::new(CryptoProvider {
            cert: WebPkiVerifier::new(root_store, None),
            ..Default::default()
        });
        (
            host,
            port,
            SERVER_DOMAIN.to_string(),
            "/formats/json".to_string(),
            provider,
        )
    } else {
        let host = args
            .host
            .clone()
            .context("--host is required in real-host mode")?;
        // Default provider uses the standard WebPki roots (real public CAs).
        (
            host.clone(),
            args.port,
            host,
            args.path.clone(),
            Arc::new(CryptoProvider::default()),
        )
    };

    // 1) Connect to the notary and request a notarization session.
    let notary_client = NotaryClient::builder()
        .host(args.notary_host.clone())
        .port(args.notary_port)
        .enable_tls(args.notary_tls)
        .build()
        .map_err(|e| anyhow!("notary client build: {e:?}"))?;

    let notarization_request = NotarizationRequest::builder()
        .max_sent_data(args.max_sent)
        .max_recv_data(args.max_recv)
        .build()
        .map_err(|e| anyhow!("notarization request: {e:?}"))?;

    let Accepted {
        io: notary_connection,
        ..
    } = notary_client
        .request_notarization(notarization_request)
        .await
        .map_err(|e| anyhow!("connect notary (is it running?): {e:?}"))?;

    // 2) Configure and set up the prover.
    let mut prover_config_builder = ProverConfig::builder();
    prover_config_builder
        .server_name(server_name.as_str())
        .protocol_config(
            ProtocolConfig::builder()
                .max_sent_data(args.max_sent)
                .max_recv_data(args.max_recv)
                .build()
                .map_err(|e| anyhow!("protocol config: {e:?}"))?,
        )
        .crypto_provider(crypto_provider.clone());
    let prover_config = prover_config_builder
        .build()
        .map_err(|e| anyhow!("prover config: {e:?}"))?;

    let prover = Prover::new(prover_config)
        .setup(notary_connection.compat())
        .await
        .map_err(|e| anyhow!("prover setup: {e:?}"))?;

    // 3) Open the TCP connection to the target and bind the MPC-TLS connection.
    let client_socket = tokio::net::TcpStream::connect((server_host.as_str(), server_port))
        .await
        .context("connect target")?;
    let (mpc_tls_connection, prover_fut) = prover
        .connect(client_socket.compat())
        .await
        .map_err(|e| anyhow!("mpc-tls connect: {e:?}"))?;
    let mpc_tls_connection = TokioIo::new(mpc_tls_connection.compat());
    let prover_task = tokio::spawn(prover_fut);

    // 4) HTTP/1.1 over the MPC-TLS connection.
    let (mut request_sender, connection) =
        hyper::client::conn::http1::handshake(mpc_tls_connection)
            .await
            .context("http1 handshake")?;
    tokio::spawn(connection);

    let mut request_builder = Request::builder()
        .uri(path.as_str())
        .method("GET")
        .header("Host", server_name.as_str())
        .header("Accept", "*/*")
        // TLSNotary tooling does not support compression.
        .header("Accept-Encoding", "identity")
        .header("Connection", "close")
        .header("User-Agent", USER_AGENT);
    for (k, v) in &args.headers {
        request_builder = request_builder.header(k.as_str(), v.as_str());
    }
    let request = request_builder.body(Empty::<Bytes>::new())?;

    let response = request_sender
        .send_request(request)
        .await
        .context("send mpc-tls request")?;
    let status = response.status();
    tracing::info!(%status, "got response from target");
    if status != StatusCode::OK {
        // The gate self-test expects 200; the orchestrator may accept others.
        tracing::warn!("target did not return 200 (status={status})");
    }

    let mut prover = prover_task.await??;

    // 5) Commit to the transcript and request the attestation (notarize).
    //
    // Amazon sends `Connection: close`, so the recv transcript carries trailing
    // close_notify / connection-close framing AFTER the HTTP response. The upstream
    // whole-buffer HttpTranscript::parse rejects that trailing data
    // ("trailing characters are present in source" from the JSON body parser). Parse
    // exactly ONE request and ONE response instead (spansy bounds each message by
    // Content-Length and ignores the trailing bytes), then build the HttpTranscript
    // from them so commit/reveal operate on exactly the HTTP message byte range.
    let sent_bytes = prover.transcript().sent();
    let recv_bytes = prover.transcript().received();
    let total_recv = recv_bytes.len();
    let detected = http_message_end(recv_bytes).unwrap_or(total_recv);
    tracing::info!(
        http_message_len = detected,
        total_recv_len = total_recv,
        trailing_bytes = total_recv.saturating_sub(detected),
        "bounding recv transcript to the HTTP message (excluding Connection: close framing)"
    );

    let request =
        parse_request(sent_bytes).map_err(|e| anyhow!("parse http request: {e:?}"))?;
    let response =
        parse_response(recv_bytes).map_err(|e| anyhow!("parse http response: {e:?}"))?;
    let transcript = HttpTranscript {
        requests: vec![request],
        responses: vec![response],
    };

    let mut commit_builder = TranscriptCommitConfig::builder(prover.transcript());
    DefaultHttpCommitter::default()
        .commit_transcript(&mut commit_builder, &transcript)
        .map_err(|e| anyhow!("commit transcript: {e:?}"))?;
    let transcript_commit = commit_builder
        .build()
        .map_err(|e| anyhow!("build commit: {e:?}"))?;

    let mut req_builder = RequestConfig::builder();
    req_builder.transcript_commit(transcript_commit);
    let request_config = req_builder
        .build()
        .map_err(|e| anyhow!("request config: {e:?}"))?;

    #[allow(deprecated)]
    let (attestation, secrets) = prover
        .notarize(&request_config)
        .await
        .map_err(|e| anyhow!("notarize: {e:?}"))?;

    // 6) Build a presentation revealing the HTTP message (bounded identically to the
    //    commit side so the revealed response excludes the Connection: close framing).
    let secret_sent = secrets.transcript().sent();
    let secret_recv = secrets.transcript().received();
    let request =
        parse_request(secret_sent).map_err(|e| anyhow!("parse secrets request: {e:?}"))?;
    let response =
        parse_response(secret_recv).map_err(|e| anyhow!("parse secrets response: {e:?}"))?;
    let transcript = HttpTranscript {
        requests: vec![request],
        responses: vec![response],
    };
    let mut tp_builder = secrets.transcript_proof_builder();
    let req0 = &transcript.requests[0];
    tp_builder
        .reveal_sent(req0)
        .map_err(|e| anyhow!("reveal sent: {e:?}"))?;
    let resp0 = &transcript.responses[0];
    tp_builder
        .reveal_recv(resp0)
        .map_err(|e| anyhow!("reveal recv: {e:?}"))?;
    let transcript_proof = tp_builder
        .build()
        .map_err(|e| anyhow!("build transcript proof: {e:?}"))?;

    let mut pres_builder = attestation.presentation_builder(crypto_provider.as_ref());
    pres_builder
        .identity_proof(secrets.identity_proof())
        .transcript_proof(transcript_proof);
    let presentation: Presentation = pres_builder
        .build()
        .map_err(|e| anyhow!("build presentation: {e:?}"))?;

    // 7) Self-verify the presentation to extract the trust-anchor fields and to
    //    PROVE the bundle validates against the notary verifying key.
    let VerifyingKey { alg, data: key_data } = presentation.verifying_key();
    let notary_key_alg = format!("{alg:?}");
    let notary_pub_key_id = hex::encode(&key_data);

    let presentation_bytes =
        bincode::serialize(&presentation).context("serialize presentation")?;
    let attestation_bytes =
        bincode::serialize(&attestation).context("serialize attestation")?;

    let verify_presentation: Presentation =
        bincode::deserialize(&presentation_bytes).context("roundtrip presentation")?;
    let PresentationOutput {
        server_name: verified_server,
        connection_info,
        transcript: verified_transcript,
        ..
    } = verify_presentation
        .verify(crypto_provider.as_ref())
        .map_err(|e| anyhow!("presentation self-verify failed: {e:?}"))?;

    let mut partial = verified_transcript.ok_or_else(|| anyhow!("no transcript revealed"))?;
    partial.set_unauthed(b'X');
    let sent = String::from_utf8_lossy(partial.sent_unsafe()).to_string();
    let recv = String::from_utf8_lossy(partial.received_unsafe()).to_string();

    let request_commitment = sha256_hex(sent.as_bytes());
    let response_hash = sha256_hex(recv.as_bytes());

    let bundle = ProofBundle {
        ok: true,
        server_name: verified_server
            .map(|s| s.as_str().to_string())
            .unwrap_or_else(|| server_name.clone()),
        notary_key_alg,
        notary_pub_key_id,
        connection_time_unix: connection_info.time,
        request_commitment,
        response_hash,
        revealed_sent_preview: sent.chars().take(160).collect(),
        revealed_recv_preview: recv.chars().take(160).collect(),
        nonce: args.nonce.clone(),
        attestation_b64: base64::engine::general_purpose::STANDARD.encode(&attestation_bytes),
        presentation_b64: base64::engine::general_purpose::STANDARD.encode(&presentation_bytes),
    };

    println!("{}", serde_json::to_string_pretty(&bundle)?);
    Ok(())
}

fn sha256_hex(bytes: &[u8]) -> String {
    let mut h = Sha256::new();
    h.update(bytes);
    hex::encode(h.finalize())
}

/// Returns the end offset (exclusive) of the single HTTP message at the front of
/// `buf`: end-of-headers (CRLFCRLF) + Content-Length. Used only for logging and a
/// sanity check; the authoritative range comes from spansy's parse_response.
fn http_message_end(buf: &[u8]) -> Option<usize> {
    let headers_end = buf
        .windows(4)
        .position(|w| w == b"\r\n\r\n")
        .map(|i| i + 4)?;
    // Case-insensitive scan for "content-length:" in the header block.
    let head = &buf[..headers_end];
    let needle = b"content-length:";
    let mut content_length = 0usize;
    for start in 0..head.len() {
        let end = start + needle.len();
        if end <= head.len() && head[start..end].eq_ignore_ascii_case(needle) {
            // Value runs to the CRLF after the colon.
            let rest = &head[end..];
            let line_end = rest
                .windows(2)
                .position(|w| w == b"\r\n")
                .unwrap_or(rest.len());
            content_length = std::str::from_utf8(&rest[..line_end])
                .ok()?
                .trim()
                .parse::<usize>()
                .ok()?;
            break;
        }
    }
    Some(headers_end + content_length)
}
