'use strict';

const crypto = require('node:crypto');

function canonicalize(value) {
  if (value === null || typeof value === 'boolean' || typeof value === 'string') return JSON.stringify(value);
  if (typeof value === 'number') {
    if (!Number.isFinite(value)) throw new Error('NON_CANONICAL_NUMBER');
    return JSON.stringify(value);
  }
  if (Array.isArray(value)) return `[${value.map(canonicalize).join(',')}]`;
  if (typeof value === 'object') return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${canonicalize(value[key])}`).join(',')}}`;
  throw new Error('NON_CANONICAL_VALUE');
}

function sha256(value) {
  return crypto.createHash('sha256').update(value).digest('hex');
}

function validateChallengeState(challenge, body, now = Date.now()) {
  if (!challenge) throw new Error('CHALLENGE_NOT_FOUND');
  if (challenge.used) throw new Error('CHALLENGE_REPLAY');
  if (Date.parse(challenge.expiresAt) <= now) throw new Error('CHALLENGE_EXPIRED');
  if (body.nonce !== challenge.nonce) throw new Error('NONCE_MISMATCH');
  if (body.policyVersion !== challenge.policyVersion) throw new Error('POLICY_VERSION_REVOKED');
  if (!challenge.endpointScope.includes(body.endpoint)) throw new Error('CHALLENGE_SCOPE_MISMATCH');
}

function validateTlsProof(proof, rawHash) {
  if (!proof || proof.proofType !== 'tlsnotary') throw new Error('TLS_PROOF_INVALID');
  const proofMaterial = {...proof};
  delete proofMaterial.proofHash;
  if (proof.proofHash !== sha256(canonicalize(proofMaterial))) throw new Error('TLS_PROOF_INVALID');
  if (proof.responseHash !== rawHash) throw new Error('TLS_PROOF_HASH_MISMATCH');
}

// tlsProof-free nonce binding: ties the challenge-issued nonce to the submission
// body nonce and enforces single-use + expiry. Replaces the notary-coupled check
// that previously lived in tlsnotary-verifier.js on the live path.
function validateNonceBinding(challenge, body, now = Date.now()) {
  if (!challenge) throw new Error('CHALLENGE_NOT_FOUND');
  if (challenge.used) throw new Error('CHALLENGE_REPLAY');
  if (Date.parse(challenge.expiresAt) <= now) throw new Error('CHALLENGE_EXPIRED');
  if (challenge.nonce !== body.nonce) throw new Error('NONCE_MISMATCH');
}

function buildManifest(body) {
  return {
    requestId: body.requestId,
    evidenceId: body.evidenceId,
    sourceId: body.sourceId,
    nonce: body.nonce,
    policyVersion: body.policyVersion,
    rawSourceHash: body.rawPayloadDigest,
    transformedHash: body.transformedPayloadDigest,
    canonicalizationVersion: body.canonicalizationVersion,
    attestedPublicKeyBase64: body.attestedPublicKeyBase64,
  };
}

function validateEnvelope(body) {
  const expected = {
    requestId: body.requestId,
    nonce: body.nonce,
    policyVersion: body.policyVersion,
    evidenceId: body.evidenceId,
    manifestDigest: body.manifestDigest,
    encryptedEvidenceReference: body.encryptedEvidenceReference,
    submittedAt: body.submissionEnvelope && body.submissionEnvelope.submittedAt,
  };
  if (canonicalize(body.submissionEnvelope) !== canonicalize(expected)) throw new Error('SUBMISSION_ENVELOPE_INVALID');
}

function verifyEnclaveSignature(body, manifest) {
  const key = crypto.createPublicKey({key: Buffer.from(body.attestedPublicKeyBase64, 'base64'), format: 'der', type: 'spki'});
  if (!crypto.verify('sha256', Buffer.from(canonicalize(manifest)), key, Buffer.from(body.enclaveSignature, 'base64'))) throw new Error('ENCLAVE_SIGNATURE_INVALID');
}

module.exports = {canonicalize, sha256, validateChallengeState, validateTlsProof, validateNonceBinding, buildManifest, validateEnvelope, verifyEnclaveSignature};
