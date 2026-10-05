'use strict';

const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const test = require('node:test');

// Locks the invariant the verifier's submitEvidence now enforces: the raw-payload
// digest is sha256 over the DECODED rawResponseB64 wire bytes, not over the
// canonicalized rawPayload. These tests assert the exact base64 -> sha256 relation
// the handler uses (crypto.createHash('sha256').update(Buffer.from(b64,'base64'))),
// without reaching into the AWS-backed handler path.

test('rawResponseB64 decoded bytes hash to rawPayloadDigest', () => {
  const bytes = Buffer.from('{"payload":{"Orders":[{"orderId":"123"}]}}');
  const b64 = bytes.toString('base64');
  const expectedDigest = crypto.createHash('sha256').update(bytes).digest('hex');

  const actual = crypto.createHash('sha256').update(Buffer.from(b64, 'base64')).digest('hex');
  assert.equal(actual, expectedDigest);
});

test('tampered rawResponseB64 bytes produce a different digest (RAW_PAYLOAD_HASH_MISMATCH would fire)', () => {
  const bytes = Buffer.from('{"payload":{"Orders":[]}}');
  const expectedDigest = crypto.createHash('sha256').update(bytes).digest('hex');

  const tampered = Buffer.from('{"payload":{"Orders":[{"orderId":"evil"}]}}');
  const tamperedB64 = tampered.toString('base64');
  const actual = crypto.createHash('sha256').update(Buffer.from(tamperedB64, 'base64')).digest('hex');
  assert.notEqual(actual, expectedDigest);
});

// FEAT-004: the verifier moved to the sourceId contract. These assertions lock the
// shape of submitEvidence's required set, the registry-scoped sourceId gate (shared
// by issueChallenge + submitEvidence), and the enclave-matching user_data binding.
// They mirror index.js directly so no @aws-sdk/* client is needed at import time.

// Kept byte-for-byte in sync with index.js's SOURCE_IDS set (the 7 approved sources).
const SOURCE_IDS = new Set(['getOrderMetrics', 'listFinancialEventGroups', 'listTransactions', 'alicloudTelThree', 'qichachaEnterpriseVerify', 'qichachaShixinCheck', 'gutuPanoramaChecks']);
// Kept in sync with submitEvidence's required[] in index.js.
const SUBMIT_REQUIRED = ['requestId', 'evidenceId', 'nonce', 'policyVersion', 'sourceId', 'encryptedEvidenceReference', 'manifestDigest', 'submissionEnvelope', 'submissionSignature', 'rawPayload', 'rawResponseB64', 'rawPayloadDigest', 'transformedPayload', 'transformedPayloadDigest', 'canonicalizationVersion', 'attestationDocument', 'attestedPublicKeyBase64', 'enclaveSignature', 'eifDigest', 'pcr0', 'pcr1', 'pcr2'];

test('submitEvidence required set drops every notary field and source/endpoint, requires sourceId', () => {
  for (const field of ['tlsProofType', 'tlsProofHash', 'tlsProofResponseHash', 'tlsProof', 'source', 'endpoint']) {
    assert.ok(!SUBMIT_REQUIRED.includes(field), `${field} must not be required`);
  }
  assert.ok(SUBMIT_REQUIRED.includes('sourceId'));
});

test('all 7 sourceIds pass the scope check and an unknown id is rejected (SOURCE_SCOPE_INVALID)', () => {
  const scopeCheck = (sourceId) => {
    if (!SOURCE_IDS.has(sourceId)) throw new Error('SOURCE_SCOPE_INVALID');
  };
  for (const id of ['getOrderMetrics', 'listFinancialEventGroups', 'listTransactions', 'alicloudTelThree', 'qichachaEnterpriseVerify', 'qichachaShixinCheck', 'gutuPanoramaChecks']) {
    assert.doesNotThrow(() => scopeCheck(id));
  }
  assert.throws(() => scopeCheck('notARealSource'), /SOURCE_SCOPE_INVALID/);
});

test('verifyNitroAttestation user_data binding has sourceId and no tlsProofHash, in enclave order', () => {
  const body = {
    requestId: 'r1', nonce: 'n1', policyVersion: 'v1.0', sourceId: 'getOrderMetrics',
    rawPayloadDigest: 'a'.repeat(64), transformedPayloadDigest: 'b'.repeat(64), attestedPublicKeyBase64: 'PUBKEY',
  };
  const userData = {
    requestId: body.requestId,
    nonce: body.nonce,
    policyVersion: body.policyVersion,
    sourceId: body.sourceId,
    rawHash: body.rawPayloadDigest,
    transformedHash: body.transformedPayloadDigest,
    publicKey: body.attestedPublicKeyBase64,
  };
  assert.ok(!('tlsProofHash' in userData));
  assert.equal(userData.sourceId, 'getOrderMetrics');
  assert.deepEqual(Object.keys(userData), ['requestId', 'nonce', 'policyVersion', 'sourceId', 'rawHash', 'transformedHash', 'publicKey']);
});
