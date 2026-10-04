'use strict';

// Normalizer: sidecar proof bundle (stdout JSON) -> canonical `tlsProof` object.
//
// The thin Rust prover-sidecar (tls-notary/prover-sidecar) emits a snake_case
// JSON bundle on stdout after one MPC-TLS notarized GET. This module turns that
// bundle into the SAME `tlsProof` contract the enclave (Java) and the Olea
// verifier (JS) already consume via verification-contract.js:
//
//   - proofType === 'tlsnotary'
//   - proofHash === sha256(canonicalize(tlsProof-without-proofHash))
//   - responseHash is the sha256 the enclave treats as rawResponseHash
//
// It reuses canonicalize/sha256 from the verifier contract directly so the
// recomputation on the verifier side is byte-identical. No new hashing is
// invented here.
//
// Redaction: the normalizer never carries header values. In particular, if any
// field (preview or header map) contains an `x-amz-access-token` value, it is
// stripped so no LWA token can enter the normalized bundle, the proofHash, or
// evidence. The sidecar reveal selection already excludes the token value; this
// is defence-in-depth at the JS seam.

const path = require('node:path');
const {canonicalize, sha256} = require(
  path.join(__dirname, '..', '..', 'sam', 'olea', 'functions', 'verification', 'verification-contract.js'),
);

// Case-insensitive sensitive header names whose VALUES must never survive
// normalization. Kept as a list so FEAT-002/003/004 can extend it if needed.
const REDACTED_HEADER_NAMES = ['x-amz-access-token'];

const HEX64 = /^[0-9a-f]{64}$/;

function requireString(bundle, snakeKey, label) {
  const value = bundle[snakeKey];
  if (typeof value !== 'string' || value.length === 0) {
    throw new Error(`TLS_BUNDLE_FIELD_MISSING:${label}`);
  }
  return value;
}

// Scrub any occurrence of a redacted header's value from a free-text preview.
// The sidecar previews are of the form "Header-Name: value\r\n...". We drop the
// whole header line for any redacted header so neither the name:value pair nor a
// trailing token fragment can leak into the normalized output.
function redactPreview(text) {
  if (typeof text !== 'string' || text.length === 0) return '';
  const lower = REDACTED_HEADER_NAMES.map((n) => n.toLowerCase());
  return text
    .split(/\r?\n/)
    .filter((line) => {
      const idx = line.indexOf(':');
      if (idx === -1) return true;
      const name = line.slice(0, idx).trim().toLowerCase();
      return !lower.includes(name);
    })
    .join('\n');
}

// Assert (recursively) that no redacted header value remains anywhere in the
// produced object. Throws if a token-shaped header name is still present as a
// key or inside a string, so a leak fails closed instead of shipping.
function assertNoRedactedHeader(value) {
  const lower = REDACTED_HEADER_NAMES.map((n) => n.toLowerCase());
  const walk = (node) => {
    if (typeof node === 'string') {
      const hay = node.toLowerCase();
      for (const name of lower) {
        // A bare mention of the header name followed by ':' means a value is
        // still attached. The name alone (e.g. in docs) is fine; name + ':' is
        // the leak signal.
        if (hay.includes(`${name}:`)) throw new Error('TLS_BUNDLE_TOKEN_LEAK');
      }
      return;
    }
    if (Array.isArray(node)) {
      node.forEach(walk);
      return;
    }
    if (node && typeof node === 'object') {
      for (const key of Object.keys(node)) {
        if (lower.includes(key.toLowerCase())) throw new Error('TLS_BUNDLE_TOKEN_LEAK');
        walk(node[key]);
      }
    }
  };
  walk(value);
}

/**
 * Normalize a sidecar proof bundle into the canonical tlsProof contract object.
 *
 * @param {object} bundle sidecar stdout JSON (snake_case):
 *   {ok, server_name, notary_pub_key_id, notary_key_alg, connection_time_unix,
 *    request_commitment, response_hash, revealed_recv_preview, nonce,
 *    attestation_b64, presentation_b64, [revealed_sent_preview]}
 * @returns {object} tlsProof:
 *   {proofType:'tlsnotary', serverName, notaryPubKeyId, notaryKeyAlg,
 *    connectionTimeUnix, requestCommitment, responseHash, revealedResponse,
 *    nonce, presentationB64, proofHash}
 */
function normalizeBundle(bundle) {
  if (!bundle || typeof bundle !== 'object') throw new Error('TLS_BUNDLE_INVALID');
  if (bundle.ok !== true) throw new Error('TLS_BUNDLE_NOT_OK');

  const serverName = requireString(bundle, 'server_name', 'serverName');
  const notaryPubKeyId = requireString(bundle, 'notary_pub_key_id', 'notaryPubKeyId');
  const notaryKeyAlg = requireString(bundle, 'notary_key_alg', 'notaryKeyAlg');
  const requestCommitment = requireString(bundle, 'request_commitment', 'requestCommitment');
  const responseHash = requireString(bundle, 'response_hash', 'responseHash');
  const presentationB64 = requireString(bundle, 'presentation_b64', 'presentationB64');

  if (!HEX64.test(responseHash)) throw new Error('TLS_BUNDLE_RESPONSE_HASH_INVALID');
  if (!HEX64.test(requestCommitment)) throw new Error('TLS_BUNDLE_REQUEST_COMMITMENT_INVALID');

  const connectionTimeUnix = bundle.connection_time_unix;
  if (!Number.isInteger(connectionTimeUnix) || connectionTimeUnix <= 0) {
    throw new Error('TLS_BUNDLE_CONNECTION_TIME_INVALID');
  }

  const nonce = typeof bundle.nonce === 'string' ? bundle.nonce : '';
  const revealedResponse = redactPreview(bundle.revealed_recv_preview);

  // Field order here is irrelevant: canonicalize sorts keys, so proofHash is
  // stable regardless of insertion order. proofHash is computed over the
  // tlsProof WITHOUT proofHash, exactly as validateTlsProof recomputes it.
  const tlsProof = {
    proofType: 'tlsnotary',
    serverName,
    notaryPubKeyId,
    notaryKeyAlg,
    connectionTimeUnix,
    requestCommitment,
    responseHash,
    revealedResponse,
    nonce,
    presentationB64,
  };

  // Defence-in-depth: fail closed if a redacted header value survived.
  assertNoRedactedHeader(tlsProof);

  tlsProof.proofHash = sha256(canonicalize(tlsProof));
  return tlsProof;
}

module.exports = {normalizeBundle, REDACTED_HEADER_NAMES};
