'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const {normalizeBundle, REDACTED_HEADER_NAMES} = require(path.join(__dirname, 'normalize-bundle.js'));
const {canonicalize, sha256, validateTlsProof} = require(
  path.join(__dirname, '..', '..', 'sam', 'olea', 'functions', 'verification', 'verification-contract.js'),
);

const HEX64 = /^[0-9a-f]{64}$/;

// A representative sidecar bundle. response_hash/request_commitment are real
// sha256 hex of sample transcripts so the shape matches what the sidecar emits.
function sampleBundle(overrides = {}) {
  return {
    ok: true,
    server_name: 'sandbox.sellingpartnerapi-na.amazon.com',
    notary_key_alg: 'K256',
    notary_pub_key_id: '0399b01d8ba1c0e9f2a7b3c4d5e6f70819283a4b5c6d7e8f90a1b2c3d4e5f6a7b8',
    connection_time_unix: 1690000000,
    request_commitment: sha256('GET /sales/v1/orderMetrics HTTP/1.1\r\nHost: sandbox.sellingpartnerapi-na.amazon.com\r\n'),
    response_hash: sha256('HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{"payload":{"ok":true}}'),
    revealed_sent_preview: 'GET /sales/v1/orderMetrics HTTP/1.1\r\nHost: sandbox.sellingpartnerapi-na.amazon.com\r\nAccept: */*',
    revealed_recv_preview: 'HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n\r\n{"payload":{"ok":true}}',
    nonce: 'olea-nonce-abc123',
    attestation_b64: 'YXR0ZXN0YXRpb24=',
    presentation_b64: 'cHJlc2VudGF0aW9u',
    ...overrides,
  };
}

test('normalizes a sidecar bundle into a tlsnotary proof', () => {
  const proof = normalizeBundle(sampleBundle());
  assert.equal(proof.proofType, 'tlsnotary');
  assert.equal(proof.serverName, 'sandbox.sellingpartnerapi-na.amazon.com');
  assert.equal(proof.notaryPubKeyId, '0399b01d8ba1c0e9f2a7b3c4d5e6f70819283a4b5c6d7e8f90a1b2c3d4e5f6a7b8');
  assert.equal(proof.notaryKeyAlg, 'K256');
  assert.equal(proof.connectionTimeUnix, 1690000000);
  assert.equal(proof.nonce, 'olea-nonce-abc123');
  assert.equal(proof.presentationB64, 'cHJlc2VudGF0aW9u');
});

test('proofHash is 64-hex and recomputes from the proof material', () => {
  const proof = normalizeBundle(sampleBundle());
  assert.match(proof.proofHash, HEX64);
  const material = {...proof};
  delete material.proofHash;
  assert.equal(proof.proofHash, sha256(canonicalize(material)));
});

test('responseHash is a 64-hex sha256 and is preserved from the bundle', () => {
  const bundle = sampleBundle();
  const proof = normalizeBundle(bundle);
  assert.match(proof.responseHash, HEX64);
  assert.equal(proof.responseHash, bundle.response_hash);
});

test('the normalized proof passes the existing validateTlsProof contract', () => {
  const proof = normalizeBundle(sampleBundle());
  // rawHash the enclave sees == the bundle responseHash; must not throw.
  assert.doesNotThrow(() => validateTlsProof(proof, proof.responseHash));
});

test('redacts x-amz-access-token: no token value appears anywhere in the output', () => {
  const token = 'Atza|SECRET-LWA-TOKEN-VALUE-DO-NOT-LEAK';
  const bundle = sampleBundle({
    revealed_sent_preview:
      `GET /sales/v1/orderMetrics HTTP/1.1\r\nHost: sandbox.sellingpartnerapi-na.amazon.com\r\nx-amz-access-token: ${token}\r\nAccept: */*`,
    revealed_recv_preview:
      `HTTP/1.1 200 OK\r\nx-amz-access-token: ${token}\r\nContent-Type: application/json\r\n\r\n{"payload":{"ok":true}}`,
  });
  const proof = normalizeBundle(bundle);
  const serialized = JSON.stringify(proof);
  assert.ok(!serialized.includes(token), 'token value must not appear in the normalized output');
  assert.ok(!serialized.toLowerCase().includes('x-amz-access-token:'), 'token header line must be stripped');
});

test('redacted header list contains x-amz-access-token', () => {
  assert.ok(REDACTED_HEADER_NAMES.map((n) => n.toLowerCase()).includes('x-amz-access-token'));
});

test('rejects a bundle with ok=false (fails closed)', () => {
  assert.throws(() => normalizeBundle(sampleBundle({ok: false})), /TLS_BUNDLE_NOT_OK/);
});

test('rejects a bundle missing the notary public key id', () => {
  const bundle = sampleBundle();
  delete bundle.notary_pub_key_id;
  assert.throws(() => normalizeBundle(bundle), /TLS_BUNDLE_FIELD_MISSING:notaryPubKeyId/);
});

test('rejects a non-hex response hash', () => {
  assert.throws(() => normalizeBundle(sampleBundle({response_hash: 'not-a-hash'})), /TLS_BUNDLE_RESPONSE_HASH_INVALID/);
});
