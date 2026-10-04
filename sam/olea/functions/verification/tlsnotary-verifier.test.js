'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const {canonicalize, sha256} = require('./verification-contract');
const {verifyTlsNotaryProof, validateNonceBinding} = require('./tlsnotary-verifier');

const PINNED_NOTARY = 'a'.repeat(64);
const SP_API_HOST = 'sandbox.sellingpartnerapi-na.amazon.com';
const RAW_HASH = 'b'.repeat(64);
const MAX_AGE = 300;
const NONCE = 'nonce-xyz';
const NOW = 1_700_000_000_000; // fixed epoch millis for deterministic freshness
const NOW_SECONDS = Math.floor(NOW / 1000);

// Build a valid normalized tlsProof the SAME way FEAT-001's normalize-bundle.js
// does: proofHash = sha256(canonicalize(tlsProof-without-proofHash)).
function buildProof(overrides = {}) {
  const proof = {
    proofType: 'tlsnotary',
    serverName: SP_API_HOST,
    notaryPubKeyId: PINNED_NOTARY,
    notaryKeyAlg: 'secp256r1',
    connectionTimeUnix: NOW_SECONDS - 10,
    requestCommitment: 'c'.repeat(64),
    responseHash: RAW_HASH,
    revealedResponse: 'HTTP/1.1 200 OK',
    nonce: NONCE,
    presentationB64: Buffer.from('presentation').toString('base64'),
    ...overrides,
  };
  proof.proofHash = sha256(canonicalize(proof));
  // Allow a test to tamper the proofHash AFTER recompute.
  if (overrides.proofHash !== undefined) proof.proofHash = overrides.proofHash;
  return proof;
}

function expected(overrides = {}) {
  return {spApiHost: SP_API_HOST, rawPayloadDigest: RAW_HASH, maxAgeSeconds: MAX_AGE, nonce: NONCE, notaryPubKeyId: PINNED_NOTARY, now: NOW, ...overrides};
}

test('accepts a valid TLSNotary proof', () => {
  const result = verifyTlsNotaryProof(buildProof(), expected());
  assert.equal(result.reasonCode, 'SUCCESS');
  assert.equal(result.notaryPubKeyId, PINNED_NOTARY);
  assert.equal(result.serverName, SP_API_HOST);
});

test('rejects tampered responseHash (TLS_PROOF_HASH_MISMATCH)', () => {
  // Recompute proofHash so self-consistency passes but responseHash != rawHash.
  const proof = buildProof({responseHash: 'f'.repeat(64)});
  assert.throws(() => verifyTlsNotaryProof(proof, expected()), /TLS_PROOF_HASH_MISMATCH/);
});

test('rejects tampered proofHash (TLS_PROOF_INVALID)', () => {
  const proof = buildProof({proofHash: 'd'.repeat(64)});
  assert.throws(() => verifyTlsNotaryProof(proof, expected()), /TLS_PROOF_INVALID/);
});

test('rejects wrong notary key id (NOTARY_KEY_UNTRUSTED)', () => {
  const proof = buildProof({notaryPubKeyId: 'e'.repeat(64)});
  assert.throws(() => verifyTlsNotaryProof(proof, expected()), /NOTARY_KEY_UNTRUSTED/);
});

test('rejects an unpinned verifier (NOTARY_KEY_UNPINNED)', () => {
  const prev = process.env.OLEA_NOTARY_PUBKEY;
  delete process.env.OLEA_NOTARY_PUBKEY;
  try {
    assert.throws(() => verifyTlsNotaryProof(buildProof(), expected({notaryPubKeyId: undefined})), /NOTARY_KEY_UNPINNED/);
  } finally {
    if (prev !== undefined) process.env.OLEA_NOTARY_PUBKEY = prev;
  }
});

test('pins via OLEA_NOTARY_PUBKEY env var when expected omits it', () => {
  const prev = process.env.OLEA_NOTARY_PUBKEY;
  process.env.OLEA_NOTARY_PUBKEY = PINNED_NOTARY;
  try {
    const result = verifyTlsNotaryProof(buildProof(), expected({notaryPubKeyId: undefined}));
    assert.equal(result.reasonCode, 'SUCCESS');
  } finally {
    if (prev === undefined) delete process.env.OLEA_NOTARY_PUBKEY; else process.env.OLEA_NOTARY_PUBKEY = prev;
  }
});

test('rejects wrong domain (DOMAIN_MISMATCH)', () => {
  const proof = buildProof({serverName: 'evil.example.com'});
  assert.throws(() => verifyTlsNotaryProof(proof, expected()), /DOMAIN_MISMATCH/);
});

test('rejects stale timestamp (TLS_PROOF_STALE)', () => {
  const proof = buildProof({connectionTimeUnix: NOW_SECONDS - (MAX_AGE + 60)});
  assert.throws(() => verifyTlsNotaryProof(proof, expected()), /TLS_PROOF_STALE/);
});

test('rejects a future-dated connection time (TLS_PROOF_STALE)', () => {
  const proof = buildProof({connectionTimeUnix: NOW_SECONDS + 120});
  assert.throws(() => verifyTlsNotaryProof(proof, expected()), /TLS_PROOF_STALE/);
});

test('rejects wrong proof nonce against expected (NONCE_MISMATCH)', () => {
  const proof = buildProof({nonce: 'other-nonce'});
  assert.throws(() => verifyTlsNotaryProof(proof, expected()), /NONCE_MISMATCH/);
});

test('validateNonceBinding rejects missing/wrong nonce (NONCE_MISMATCH)', () => {
  const challenge = {nonce: NONCE, used: false, expiresAt: '2999-01-01T00:00:00.000Z'};
  const body = {nonce: NONCE, tlsProof: buildProof({nonce: 'mismatch'})};
  assert.throws(() => validateNonceBinding(challenge, body, NOW), /NONCE_MISMATCH/);
});

test('validateNonceBinding rejects a replayed (used) nonce (CHALLENGE_REPLAY)', () => {
  const challenge = {nonce: NONCE, used: true, expiresAt: '2999-01-01T00:00:00.000Z'};
  const body = {nonce: NONCE, tlsProof: buildProof()};
  assert.throws(() => validateNonceBinding(challenge, body, NOW), /CHALLENGE_REPLAY/);
});

test('validateNonceBinding rejects an expired nonce (CHALLENGE_EXPIRED)', () => {
  const challenge = {nonce: NONCE, used: false, expiresAt: '2020-01-01T00:00:00.000Z'};
  const body = {nonce: NONCE, tlsProof: buildProof()};
  assert.throws(() => validateNonceBinding(challenge, body, NOW), /CHALLENGE_EXPIRED/);
});

test('validateNonceBinding accepts a matching unexpired single-use nonce', () => {
  const challenge = {nonce: NONCE, used: false, expiresAt: '2999-01-01T00:00:00.000Z'};
  const body = {nonce: NONCE, tlsProof: buildProof()};
  assert.doesNotThrow(() => validateNonceBinding(challenge, body, NOW));
});
