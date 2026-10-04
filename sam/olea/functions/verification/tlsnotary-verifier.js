'use strict';

// Olea TLSNotary proof verifier.
//
// Added WITHOUT touching the existing Nitro attestation verifier
// (attestation-verifier.js) or the shared contract (verification-contract.js).
// This module checks a normalized `tlsProof` bundle (the contract FEAT-001's
// normalize-bundle.js emits) and fails closed on any discrepancy.
//
// Checks implemented by verifyTlsNotaryProof(tlsProof, expected):
//   (a) notary key pin   -> tlsProof.notaryPubKeyId === the pinned notary
//       verifying-key id (env OLEA_NOTARY_PUBKEY or expected.notaryPubKeyId),
//       rejecting ANY other signer with NOTARY_KEY_UNTRUSTED.
//   (b) domain binding    -> tlsProof.serverName === expected.spApiHost, else
//       DOMAIN_MISMATCH.
//   (c) response binding  -> tlsProof.responseHash === expected.rawPayloadDigest
//       AND proofHash self-consistency, delegated to validateTlsProof from
//       verification-contract.js so the integrity check stays byte-identical
//       (TLS_PROOF_HASH_MISMATCH / TLS_PROOF_INVALID).
//   (d) freshness         -> now - connectionTimeUnix within
//       expected.maxAgeSeconds and not in the future, else TLS_PROOF_STALE.
//   (e) nonce binding      -> validateNonceBinding: issued nonce matches, is
//       unexpired, and is single-use (NONCE_MISMATCH / CHALLENGE_EXPIRED /
//       CHALLENGE_REPLAY).
//
// Notary signature note: the alpha presentation format (bincode-encoded
// `presentation_b64`) is not natively verifiable in Node crypto. The
// cryptographically checkable portion in JS is the notary verifying-key id pin
// (a) plus the bundle self-consistency (proofHash recompute via
// validateTlsProof). The full presentation signature verification against the
// notary P-256 key is delegated to the sidecar/notary self-verify step
// (tls-notary/gate-proof prove->present->verify). If the pinned key id does not
// match, we reject here regardless, so an untrusted signer never reaches that
// delegated step.

const path = require('node:path');
const {validateTlsProof} = require(
  path.join(__dirname, 'verification-contract.js'),
);

// Resolve the pinned notary verifying-key id (trust anchor). Precedence:
// explicit expected.notaryPubKeyId (e.g. a pinned registry value supplied by
// the caller) wins, else the OLEA_NOTARY_PUBKEY env var. Fail closed if neither
// is configured so an unpinned deployment cannot silently trust any signer.
function pinnedNotaryKeyId(expected) {
  const pinned = (expected && expected.notaryPubKeyId) || process.env.OLEA_NOTARY_PUBKEY;
  if (typeof pinned !== 'string' || pinned.length === 0) throw new Error('NOTARY_KEY_UNPINNED');
  return pinned;
}

/**
 * Validate the nonce binding (check e) against the issued Olea challenge.
 *
 * Confirms the issued nonce matches, is unexpired, and has not already been
 * consumed (single-use). The single-use CONSUMPTION itself stays in index.js
 * (the conditional UpdateCommand -> CHALLENGE_REPLAY / SUBMISSION_REPLAY); this
 * is the pre-consumption gate that ties tlsProof.nonce === challenge.nonce ===
 * body.nonce for the SAME requestId.
 *
 * @param {object} challenge issued challenge item {nonce, used, expiresAt}
 * @param {object} body submitted evidence body (must carry nonce + tlsProof)
 * @param {number} now epoch millis
 */
function validateNonceBinding(challenge, body, now = Date.now()) {
  if (!challenge) throw new Error('CHALLENGE_NOT_FOUND');
  if (challenge.used) throw new Error('CHALLENGE_REPLAY');
  if (Date.parse(challenge.expiresAt) <= now) throw new Error('CHALLENGE_EXPIRED');
  const tlsProof = body && body.tlsProof;
  if (!tlsProof || typeof tlsProof.nonce !== 'string') throw new Error('NONCE_MISMATCH');
  // Tie the three nonces together for the same request: the challenge-issued
  // nonce, the submission body nonce, and the TLSNotary-bound nonce must all be
  // identical, else the proof is for a different request (replay/stitch).
  if (challenge.nonce !== body.nonce || tlsProof.nonce !== challenge.nonce) throw new Error('NONCE_MISMATCH');
}

/**
 * Verify a normalized TLSNotary proof bundle. Fails closed: throws an Error
 * whose message is the distinct reasonCode on the first failing check.
 *
 * @param {object} tlsProof normalized tlsProof (FEAT-001 shape):
 *   {proofType:'tlsnotary', serverName, notaryPubKeyId, notaryKeyAlg,
 *    connectionTimeUnix, requestCommitment, responseHash, revealedResponse,
 *    nonce, presentationB64, proofHash}
 * @param {object} expected trust context:
 *   {spApiHost, rawPayloadDigest, maxAgeSeconds, nonce, [notaryPubKeyId], [now]}
 * @returns {{reasonCode:'SUCCESS', notaryPubKeyId:string, serverName:string}}
 */
function verifyTlsNotaryProof(tlsProof, expected) {
  if (!tlsProof || typeof tlsProof !== 'object' || tlsProof.proofType !== 'tlsnotary') {
    throw new Error('TLS_PROOF_INVALID');
  }
  if (!expected || typeof expected !== 'object') throw new Error('TLS_PROOF_INVALID');

  // (a) notary key pin: assert the bundle was issued by the pinned notary.
  const pinned = pinnedNotaryKeyId(expected);
  if (typeof tlsProof.notaryPubKeyId !== 'string' || tlsProof.notaryPubKeyId !== pinned) {
    throw new Error('NOTARY_KEY_UNTRUSTED');
  }

  // (b) domain binding: the TLS-bound server must be the expected SP-API host.
  if (typeof tlsProof.serverName !== 'string' || tlsProof.serverName !== expected.spApiHost) {
    throw new Error('DOMAIN_MISMATCH');
  }

  // (c) response binding + self-consistency: delegate to the shared contract so
  // the proofHash recompute and responseHash === rawResponseHash check are
  // byte-identical to normalize-bundle.js / the enclave. validateTlsProof
  // throws TLS_PROOF_INVALID (proofType/proofHash) or TLS_PROOF_HASH_MISMATCH.
  validateTlsProof(tlsProof, expected.rawPayloadDigest);

  // (d) freshness: connection must be recent and not in the future.
  const maxAgeSeconds = Number(expected.maxAgeSeconds);
  if (!Number.isFinite(maxAgeSeconds) || maxAgeSeconds <= 0) throw new Error('TLS_PROOF_STALE');
  const connectionTimeUnix = tlsProof.connectionTimeUnix;
  if (!Number.isInteger(connectionTimeUnix) || connectionTimeUnix <= 0) throw new Error('TLS_PROOF_STALE');
  const nowSeconds = Math.floor((Number.isFinite(expected.now) ? expected.now : Date.now()) / 1000);
  const ageSeconds = nowSeconds - connectionTimeUnix;
  if (ageSeconds < 0 || ageSeconds > maxAgeSeconds) throw new Error('TLS_PROOF_STALE');

  // (e-partial) nonce value binding to the trust context. The full single-use
  // and expiry checks live in validateNonceBinding (needs the challenge item);
  // here we assert the proof carries the expected request nonce.
  if (expected.nonce !== undefined && tlsProof.nonce !== expected.nonce) throw new Error('NONCE_MISMATCH');

  return {reasonCode: 'SUCCESS', notaryPubKeyId: tlsProof.notaryPubKeyId, serverName: tlsProof.serverName};
}

module.exports = {verifyTlsNotaryProof, validateNonceBinding, pinnedNotaryKeyId};
