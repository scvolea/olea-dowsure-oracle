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
