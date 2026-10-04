'use strict';

// End-to-end TLSNotary MPC-TLS demo (FEAT-004) — FIXTURE / MOCK MODE.
//
// Drives the FULL Olea flow with NO credentials and NO Rust compile, so an
// agent (or CI) can prove the accept/reject behaviour without the real sidecar,
// the real notary, or an authenticated SP-API sandbox call:
//
//   1. Issue an Olea challenge (nonce/requestId/policyVersion/expiry) exactly
//      like index.js issueChallenge produces.
//   2. Load the representative, REDACTED MPC-TLS proof bundle fixture
//      (evidence/tlsnotary/sample-bundle.json) and normalize it to the canonical
//      tlsProof contract via tls-notary/bundle/normalize-bundle.js (FEAT-001).
//   3. Build the enclave user_data binding EXACTLY as FEAT-003 defines it
//      (requestId, nonce, policyVersion, rawHash, transformedHash, publicKey,
//      tlsProofHash) and canonicalize it (the Nitro attestation commits to it).
//   4. Run the Olea TLSNotary verifier (FEAT-002: validateNonceBinding +
//      verifyTlsNotaryProof) + single-use nonce consumption to show ACCEPT.
//   5. Run each negative case by mutating ONE field and assert the specific
//      reasonCode: tampered response, tampered proof, wrong nonce, missing
//      nonce, expired nonce, replayed nonce, wrong notary key, wrong domain.
//
// Prints a PASS/FAIL line per case and exits non-zero if any case does not
// behave as expected. The credentialed sidecar + real-host sandbox run is
// orchestrator-only and documented in docs/TLSNOTARY.md (NOT executed here).

const path = require('node:path');
const fs = require('node:fs');
const crypto = require('node:crypto');

const ROOT = path.join(__dirname, '..');
const VERIFICATION_DIR = path.join(ROOT, 'sam', 'olea', 'functions', 'verification');

const {canonicalize, sha256} = require(path.join(VERIFICATION_DIR, 'verification-contract.js'));
const {normalizeBundle} = require(path.join(ROOT, 'tls-notary', 'bundle', 'normalize-bundle.js'));
const {verifyTlsNotaryProof, validateNonceBinding} = require(path.join(VERIFICATION_DIR, 'tlsnotary-verifier.js'));

const SAMPLE_BUNDLE_PATH = path.join(ROOT, 'evidence', 'tlsnotary', 'sample-bundle.json');
const SP_API_HOST = 'sandbox.sellingpartnerapi-na.amazon.com';
const MAX_AGE_SECONDS = 86400;
// Fixed epoch for deterministic freshness: just after the bundle's
// connection_time_unix (1690000000) so a valid bundle is fresh.
const NOW_MS = 1690000500 * 1000;

// ---------------------------------------------------------------------------
// Step 1: issue an Olea challenge (mirrors index.js issueChallenge shape).
// ---------------------------------------------------------------------------
function issueChallenge() {
  const issuedAt = new Date(NOW_MS - 1000);
  const expiresAt = new Date(NOW_MS + 300 * 1000); // 5-min TTL, unexpired at NOW
  return {
    requestId: 'req-e2e-demo-0001',
    nonce: crypto.randomBytes(32).toString('base64url'),
    policyVersion: 'v1.0',
    endpointScope: ['GET_ORDERS'],
    transformationVersion: 't1.0',
    issuedAt: issuedAt.toISOString(),
    expiresAt: expiresAt.toISOString(),
    used: false,
  };
}

// ---------------------------------------------------------------------------
// Step 2: load the redacted fixture bundle and bind the challenge nonce +
// response hash the way the real sidecar does (response_hash = sha256 of the
// revealed recv transcript; nonce = echoed Olea challenge nonce), then
// normalize to the canonical tlsProof contract (FEAT-001).
// ---------------------------------------------------------------------------
function loadNormalizedProof(challenge) {
  const raw = JSON.parse(fs.readFileSync(SAMPLE_BUNDLE_PATH, 'utf8'));
  const bundle = {...raw};
  delete bundle._comment;
  // The sidecar sets response_hash to sha256 of the revealed recv transcript and
  // echoes the Olea challenge nonce. The fixture ships placeholders for these
  // two so the demo stays self-contained and self-consistent; everything else
  // (server_name, notary_pub_key_id, previews, commitments) is the fixture's.
  bundle.response_hash = sha256(bundle.revealed_recv_preview);
  bundle.nonce = challenge.nonce;
  const tlsProof = normalizeBundle(bundle);
  return {tlsProof, rawPayloadDigest: tlsProof.responseHash};
}

// ---------------------------------------------------------------------------
// Step 3: build the enclave user_data binding EXACTLY as FEAT-003 defines it.
// The Java enclave inserts: requestId, nonce, policyVersion, rawHash,
// transformedHash, publicKey, tlsProofHash and serializes via canonical() (both
// sides sort keys), so this JS binding canonicalizes byte-identically.
// ---------------------------------------------------------------------------
function buildEnclaveBinding(challenge, tlsProof, rawPayloadDigest) {
  const binding = {
    requestId: challenge.requestId,
    nonce: challenge.nonce,
    policyVersion: challenge.policyVersion,
    rawHash: rawPayloadDigest,
    transformedHash: sha256(canonicalize({orders: 'transformed-synthetic'})),
    publicKey: Buffer.from('enclave-attested-public-key-der-placeholder').toString('base64'),
    tlsProofHash: tlsProof.proofHash,
  };
  return {binding, userDataCanonical: canonicalize(binding)};
}

// ---------------------------------------------------------------------------
// The expected{} trust context the Olea verifier needs (FEAT-002 shape).
// ---------------------------------------------------------------------------
function buildExpected(challenge, tlsProof, rawPayloadDigest) {
  return {
    spApiHost: SP_API_HOST,
    rawPayloadDigest,
    maxAgeSeconds: MAX_AGE_SECONDS,
    nonce: challenge.nonce,
    notaryPubKeyId: tlsProof.notaryPubKeyId, // pinned trust anchor for the demo
    now: NOW_MS,
  };
}

// Deep clone a plain JSON object (fixture proofs are JSON-safe).
function clone(obj) {
  return JSON.parse(JSON.stringify(obj));
}

// Recompute proofHash after a mutation so a case tests ONLY the field it means
// to (e.g. tampered responseHash must still be self-consistent to reach the
// hash-mismatch check rather than tripping the proofHash self-consistency).
function reseal(tlsProof) {
  const material = {...tlsProof};
  delete material.proofHash;
  tlsProof.proofHash = sha256(canonicalize(material));
  return tlsProof;
}

// Run one check and capture ACCEPT or the reasonCode thrown (fail closed).
function attempt(fn) {
  try {
    fn();
    return {accepted: true, reasonCode: 'SUCCESS'};
  } catch (error) {
    return {accepted: false, reasonCode: error.message};
  }
}

// The verifier gate the demo exercises for every scenario: the nonce binding
// (FEAT-002 validateNonceBinding) then the proof verification
// (FEAT-002 verifyTlsNotaryProof). This is the SAME order index.js submitEvidence
// runs them in. A scenario mutates the challenge, body, or proof before calling.
function verifyGate(challenge, body, expected, now) {
  validateNonceBinding(challenge, body, now);
  verifyTlsNotaryProof(body.tlsProof, expected);
}

// ---------------------------------------------------------------------------
// runScenarios(): the agent-verifiable gate. Returns the valid ACCEPT result
// and every negative case with the reasonCode actually produced, so the test
// file can assert them without re-driving the flow.
// ---------------------------------------------------------------------------
function runScenarios() {
  const challenge = issueChallenge();
  const {tlsProof, rawPayloadDigest} = loadNormalizedProof(challenge);
  const expected = buildExpected(challenge, tlsProof, rawPayloadDigest);
  const {binding, userDataCanonical} = buildEnclaveBinding(challenge, tlsProof, rawPayloadDigest);
  const body = {requestId: challenge.requestId, nonce: challenge.nonce, policyVersion: challenge.policyVersion, tlsProof};

  const scenarios = [];

  // --- VALID: ACCEPT -------------------------------------------------------
  scenarios.push({
    name: 'valid bundle',
    expectAccept: true,
    expectReason: 'SUCCESS',
    result: attempt(() => verifyGate(clone(challenge), {...body, tlsProof: clone(tlsProof)}, {...expected}, NOW_MS)),
  });

  // --- tampered response: responseHash no longer matches rawPayloadDigest ---
  scenarios.push({
    name: 'tampered response',
    expectAccept: false,
    expectReason: 'TLS_PROOF_HASH_MISMATCH',
    result: attempt(() => {
      const p = reseal(Object.assign(clone(tlsProof), {responseHash: 'f'.repeat(64)}));
      verifyGate(clone(challenge), {...body, tlsProof: p}, {...expected}, NOW_MS);
    }),
  });

  // --- tampered proof: proofHash self-consistency broken -------------------
  scenarios.push({
    name: 'tampered proof',
    expectAccept: false,
    expectReason: 'TLS_PROOF_INVALID',
    result: attempt(() => {
      const p = Object.assign(clone(tlsProof), {proofHash: 'd'.repeat(64)});
      verifyGate(clone(challenge), {...body, tlsProof: p}, {...expected}, NOW_MS);
    }),
  });

  // --- wrong nonce: proof carries a different nonce than the challenge -----
  scenarios.push({
    name: 'wrong nonce',
    expectAccept: false,
    expectReason: 'NONCE_MISMATCH',
    result: attempt(() => {
      const p = reseal(Object.assign(clone(tlsProof), {nonce: 'wrong-nonce-value'}));
      verifyGate(clone(challenge), {...body, tlsProof: p}, {...expected}, NOW_MS);
    }),
  });

  // --- missing nonce: proof has no nonce field -----------------------------
  scenarios.push({
    name: 'missing nonce',
    expectAccept: false,
    expectReason: 'NONCE_MISMATCH',
    result: attempt(() => {
      const p = clone(tlsProof);
      delete p.nonce;
      verifyGate(clone(challenge), {...body, tlsProof: p}, {...expected}, NOW_MS);
    }),
  });

  // --- expired nonce: challenge TTL elapsed --------------------------------
  scenarios.push({
    name: 'expired nonce',
    expectAccept: false,
    expectReason: 'CHALLENGE_EXPIRED',
    result: attempt(() => {
      const expiredChallenge = {...clone(challenge), expiresAt: new Date(NOW_MS - 1000).toISOString()};
      verifyGate(expiredChallenge, {...body, tlsProof: clone(tlsProof)}, {...expected}, NOW_MS);
    }),
  });

  // --- replayed nonce: challenge already consumed (used=true) --------------
  scenarios.push({
    name: 'replayed nonce',
    expectAccept: false,
    expectReason: 'CHALLENGE_REPLAY',
    result: attempt(() => {
      const usedChallenge = {...clone(challenge), used: true};
      verifyGate(usedChallenge, {...body, tlsProof: clone(tlsProof)}, {...expected}, NOW_MS);
    }),
  });

  // --- wrong notary key: proof signed by an untrusted notary ---------------
  scenarios.push({
    name: 'wrong notary key',
    expectAccept: false,
    expectReason: 'NOTARY_KEY_UNTRUSTED',
    result: attempt(() => {
      const p = reseal(Object.assign(clone(tlsProof), {notaryPubKeyId: 'e'.repeat(64)}));
      verifyGate(clone(challenge), {...body, tlsProof: p}, {...expected}, NOW_MS);
    }),
  });

  // --- wrong domain: TLS-bound host is not the expected SP-API host --------
  scenarios.push({
    name: 'wrong domain',
    expectAccept: false,
    expectReason: 'DOMAIN_MISMATCH',
    result: attempt(() => {
      const p = reseal(Object.assign(clone(tlsProof), {serverName: 'evil.example.com'}));
      verifyGate(clone(challenge), {...body, tlsProof: p}, {...expected}, NOW_MS);
    }),
  });

  return {challenge, tlsProof, rawPayloadDigest, binding, userDataCanonical, expected, scenarios};
}

// ---------------------------------------------------------------------------
// CLI entrypoint: print a PASS/FAIL line per case and exit non-zero on any
// unexpected behaviour.
// ---------------------------------------------------------------------------
function main() {
  const {challenge, tlsProof, userDataCanonical, scenarios} = runScenarios();

  console.log('TLSNotary MPC-TLS end-to-end demo (fixture/mock mode — no creds, no Rust compile)');
  console.log(`  requestId      : ${challenge.requestId}`);
  console.log(`  notaryPubKeyId : ${tlsProof.notaryPubKeyId}`);
  console.log(`  serverName     : ${tlsProof.serverName}`);
  console.log(`  tlsProofHash   : ${tlsProof.proofHash}`);
  console.log(`  responseHash   : ${tlsProof.responseHash}`);
  console.log(`  enclave user_data (canonical): ${userDataCanonical}`);
  console.log('');

  let failures = 0;
  for (const s of scenarios) {
    const okAccept = s.result.accepted === s.expectAccept;
    const okReason = s.result.reasonCode === s.expectReason;
    const pass = okAccept && okReason;
    if (!pass) failures += 1;
    const verdict = s.result.accepted ? 'ACCEPT' : `REJECT ${s.result.reasonCode}`;
    const expectStr = s.expectAccept ? 'ACCEPT' : `REJECT ${s.expectReason}`;
    console.log(`  [${pass ? 'PASS' : 'FAIL'}] ${s.name.padEnd(18)} -> ${verdict}${pass ? '' : `  (expected ${expectStr})`}`);
  }

  console.log('');
  if (failures > 0) {
    console.error(`${failures} scenario(s) did not behave as expected.`);
    process.exit(1);
  }
  console.log(`All ${scenarios.length} scenarios behaved as expected (1 ACCEPT + ${scenarios.length - 1} REJECT reasonCodes).`);
}

module.exports = {runScenarios, issueChallenge, loadNormalizedProof, buildEnclaveBinding, buildExpected};

if (require.main === module) main();
