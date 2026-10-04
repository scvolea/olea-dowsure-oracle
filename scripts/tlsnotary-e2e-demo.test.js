'use strict';

// Agent-verifiable gate for FEAT-004 (node:test, no creds, no Rust compile).
// Imports runScenarios() from the demo and asserts the valid bundle is
// ACCEPTED and every negative scenario is REJECTED with its expected reasonCode.

const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const {runScenarios} = require(path.join(__dirname, 'tlsnotary-e2e-demo.js'));

// Freeze one run; each scenario carries its own captured result.
const {scenarios, binding, userDataCanonical, tlsProof} = runScenarios();

function scenario(name) {
  const found = scenarios.find((s) => s.name === name);
  assert.ok(found, `scenario '${name}' must exist`);
  return found;
}

test('valid bundle is ACCEPTED (SUCCESS)', () => {
  const s = scenario('valid bundle');
  assert.equal(s.result.accepted, true);
  assert.equal(s.result.reasonCode, 'SUCCESS');
});

const negatives = [
  ['tampered response', 'TLS_PROOF_HASH_MISMATCH'],
  ['tampered proof', 'TLS_PROOF_INVALID'],
  ['wrong nonce', 'NONCE_MISMATCH'],
  ['missing nonce', 'NONCE_MISMATCH'],
  ['expired nonce', 'CHALLENGE_EXPIRED'],
  ['replayed nonce', 'CHALLENGE_REPLAY'],
  ['wrong notary key', 'NOTARY_KEY_UNTRUSTED'],
  ['wrong domain', 'DOMAIN_MISMATCH'],
];

for (const [name, reasonCode] of negatives) {
  test(`${name} is REJECTED with ${reasonCode}`, () => {
    const s = scenario(name);
    assert.equal(s.result.accepted, false, `${name} must be rejected`);
    assert.equal(s.result.reasonCode, reasonCode);
  });
}

test('all eight negative cases are present', () => {
  assert.equal(scenarios.length, 1 + negatives.length);
});

test('enclave user_data binding carries nonce + tlsProofHash (FEAT-003 shape)', () => {
  assert.equal(binding.tlsProofHash, tlsProof.proofHash);
  const decoded = JSON.parse(userDataCanonical);
  assert.equal(decoded.nonce, binding.nonce);
  assert.equal(decoded.tlsProofHash, tlsProof.proofHash);
  // Canonical form sorts keys: confirm the FEAT-003 seven-field binding.
  assert.deepEqual(
    Object.keys(decoded).sort(),
    ['nonce', 'policyVersion', 'publicKey', 'rawHash', 'requestId', 'tlsProofHash', 'transformedHash'],
  );
});
