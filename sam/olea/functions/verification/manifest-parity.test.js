'use strict';

const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const test = require('node:test');
const {buildManifest, canonicalize, sha256} = require('./verification-contract');

// FEAT-004 enclave<->verifier manifest parity lock.
//
// The enclave (nitro-enclave EnclaveService) builds its manifest as a 9-entry
// LinkedHashMap and signs sha256(canonical(manifest)), where canonical() =
// TreeMap key-sort + Jackson compact JSON. The verifier's buildManifest() must
// produce the SAME field set and the verifier's canonicalize() (Object.keys().sort()
// + compact JSON) must produce the byte-identical canonical string, so that
// sha256(canonicalize(buildManifest(body))) reproduces the enclave manifestDigest.
//
// Enclave manifest field set/order (FEAT-002 settled shape), 9 entries:
//   requestId, evidenceId, sourceId, nonce, policyVersion,
//   rawSourceHash, transformedHash, canonicalizationVersion, attestedPublicKeyBase64
// (source+endpoint collapsed to a single sourceId; tlsProofType/tlsProofHash removed.)
//
// FIXTURE REPRODUCTION:
//   For the SAMPLE_BODY below, both canonical rules sort keys alphabetically and
//   emit compact JSON, yielding EXPECTED_CANONICAL. Its sha256 hex is
//   EXPECTED_DIGEST. Reproduce with:
//     node -e "const {buildManifest,canonicalize,sha256}=require('./verification-contract'); \
//       console.log(sha256(canonicalize(buildManifest(SAMPLE_BODY))))"
//   -> 7e5b672dc1c80c4f3122e34fa6faaaa0f9ebe6e59974e285487447c9babe4793
const SAMPLE_BODY = {
  requestId: 'req-parity-1',
  evidenceId: 'ev-parity-1',
  sourceId: 'getOrderMetrics',
  nonce: 'nonce-parity-1',
  policyVersion: 'v1.0',
  rawPayloadDigest: 'a'.repeat(64),
  transformedPayloadDigest: 'b'.repeat(64),
  canonicalizationVersion: 'RFC8785-PoC',
  attestedPublicKeyBase64: 'TESTPUBKEYBASE64==',
};

// Independently derived from the enclave field set using key-sort + compact JSON
// (the enclave's TreeMap+compact rule applied by hand to SAMPLE_BODY).
const EXPECTED_CANONICAL = '{' + [
  '"attestedPublicKeyBase64":"TESTPUBKEYBASE64=="',
  '"canonicalizationVersion":"RFC8785-PoC"',
  '"evidenceId":"ev-parity-1"',
  '"nonce":"nonce-parity-1"',
  '"policyVersion":"v1.0"',
  '"rawSourceHash":"' + 'a'.repeat(64) + '"',
  '"requestId":"req-parity-1"',
  '"sourceId":"getOrderMetrics"',
  '"transformedHash":"' + 'b'.repeat(64) + '"',
].join(',') + '}';
const EXPECTED_DIGEST = '7e5b672dc1c80c4f3122e34fa6faaaa0f9ebe6e59974e285487447c9babe4793';

test('buildManifest has exactly the 9 enclave fields and no notary/source/endpoint fields', () => {
  const manifest = buildManifest(SAMPLE_BODY);
  assert.deepEqual(Object.keys(manifest).sort(), [
    'attestedPublicKeyBase64',
    'canonicalizationVersion',
    'evidenceId',
    'nonce',
    'policyVersion',
    'rawSourceHash',
    'requestId',
    'sourceId',
    'transformedHash',
  ]);
  for (const absent of ['source', 'endpoint', 'tlsProofType', 'tlsProofHash']) {
    assert.ok(!(absent in manifest), `${absent} must be absent from the manifest`);
  }
  assert.equal(manifest.sourceId, 'getOrderMetrics');
});

test('canonicalize(buildManifest(body)) equals the enclave-rule canonical string', () => {
  assert.equal(canonicalize(buildManifest(SAMPLE_BODY)), EXPECTED_CANONICAL);
});

test('sha256(canonicalize(buildManifest(body))) equals the enclave-rule fixture digest', () => {
  const digest = sha256(canonicalize(buildManifest(SAMPLE_BODY)));
  assert.equal(digest, EXPECTED_DIGEST);
  // Cross-check against a direct crypto hash of the independently derived string.
  assert.equal(digest, crypto.createHash('sha256').update(EXPECTED_CANONICAL).digest('hex'));
});
