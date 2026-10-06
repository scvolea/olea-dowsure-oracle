# Tasks — Convert the oracle to TLS-in-TEE (7 calls)

Ordered, incremental plan. Each task names the exact files, cites the requirements it
satisfies, and states how to verify it. The order keeps the tree buildable and leaves
the live notary path working until the enclave can fetch on its own (so nothing is
broken mid-way). Code modules first, EIF rebuild + live E2E last.

Build/test env (from the repo): JDK 21 via `juse21`; system `mvn` (no wrapper); Node
24 for the verifier (`node --test`); PowerShell (`;` not `&&`). No `cargo`/Rust needed
(notary sidecar is being removed from the live path, not rebuilt).

---

- [ ] 1. Add the in-enclave source registry (ported from `sources.py`).
      Create `nitro-enclave/src/main/java/com/olea/dowsure/enclave/SourceRegistry.java`
      and a `SourceEntry` record with `{id, provider, host, port, method, pathTemplate,
      requiredParams, authType, expectedFormat}`. Populate exactly the 7 entries from
      design §"7-entry source registry". Port host/path/param selection and
      `qichacha_token = md5(appKey+Timespan+secretKey)` verbatim from
      `coordinator/sources.py`. Expose `resolve(sourceId) -> SourceEntry` that throws
      `SOURCE_SCOPE_INVALID` for unknown ids. Host base URL comes from registry config
      (mock host for PoC, real host for sandbox).
      _Requirements: 2.1, 2.2, 2.4; design §registry._
      Verify: `SourceRegistryTest` — 7 known ids resolve to the right host/method/path;
      an unknown id throws `SOURCE_SCOPE_INVALID`; `qichacha_token` matches a known
      Python-computed vector.

- [ ] 2. Add the in-enclave TLS source client over the vsock-backed transport.
      Create `nitro-enclave/.../SourceTlsClient.java`: given a resolved `SourceEntry`,
      optional request body (POST), and auth headers, open an `SSLSocket` over the
      vsock→TCP transport, send HTTP/1.1 with `Connection: close` +
      `Accept-Encoding: identity`, read the FULL response bytes `R`, return `R`.
      Validate the server cert against a CA bundle loaded from the EIF (classpath/well-
      known path); chain/hostname failure throws `TLS_HANDSHAKE_FAILED`. No logging of
      request/response bytes.
      _Requirements: 1.1, 1.2, 1.3, 1.4; design §SourceTlsClient, §networking._
      Verify: `SourceTlsClientTest` against a local in-process TLS stub server (self-
      signed, custom trust store) — asserts request line/headers shaping and that the
      returned bytes equal the stub's full response; a hostname/chain mismatch throws
      `TLS_HANDSHAKE_FAILED`. (vsock itself is mocked by a plain socket transport in the
      test, mirroring `AfVsockTransport` seam.)

- [ ] 3. Add credential provisioning under attestation (interface + PoC impl).
      Create `nitro-enclave/.../CredentialProvider.java` with
      `headersFor(SourceEntry, requestContext) -> Map<String,String>`. PoC impl returns
      the mock `x-api-key` if present and `{}` otherwise (mock mode = zero provider
      secrets, mirroring `sources.py`). Add a `KmsAttestedCredentialProvider` seam
      (sandbox/prod) that decrypts ciphertext via KMS with the attestation document and
      builds the provider auth header (Amazon `x-amz-access-token` incl. LWA exchange;
      AliCloud `APPCODE`; Qichacha `key`/`Timespan`/`Token`; Gutu `Bearer`) — port the
      header logic from `sources.py build_auth_headers`. Missing/rejected credential →
      `SOURCE_AUTH_FAILED`. Never log secrets.
      _Requirements: 3.1, 3.2, 3.3, 3.4; design §credentials._
      Verify: `CredentialProviderTest` — PoC impl returns `{}`/`x-api-key` correctly;
      header-builder unit tests (fed fake env) produce the right header names per
      provider; a grep test asserts no credential value is passed to any logger.

- [ ] 4. Rewrite `EnclaveService.acquire` to fetch + drop the notary fields.
      In `nitro-enclave/.../EnclaveService.java`:
      (a) change `require(...)` to `{requestId, nonce, policyVersion, evidenceId,
      eifDigest, sourceId}` (+ optional `requestBody`); remove `rawPayload`,
      `rawResponseB64`, `tlsProof` from the required set.
      (b) delete the `tlsProof` validation block and the
      `rawHash == tlsProof.responseHash` gate.
      (c) resolve `SourceRegistry.resolve(sourceId)`, get headers from
      `CredentialProvider`, call `SourceTlsClient` → `R`; set
      `rawResponseB64 = base64(R)`, `rawHash = SHA256(R)`; parse `rawPayload` from `R`'s
      JSON body for the (pass-through) transform.
      (d) `user_data` binding → `{requestId, nonce, policyVersion, sourceId, rawHash,
      transformedHash, publicKey}` (remove `tlsProofHash`, add `sourceId`; fix order).
      (e) manifest → replace `source`/`endpoint`/`tlsProofType`/`tlsProofHash` with
      `sourceId`; remove `tlsProofResponseHash` from evidence. Keep everything else
      (digests, signature, attestation, `encryptedEvidenceReference`, `eifDigest`).
      Keep `transform()` pass-through/stub (Requirement 4.4).
      _Requirements: 1.4, 2.3, 2.4, 4.1, 4.2, 4.4; design §evidence contract._
      Verify: update `EnclaveServiceTest` — build request with `sourceId`, a stubbed
      `SourceTlsClient`/`CredentialProvider` (constructor-injected); assert evidence has
      no `tlsProof*`, `user_data` has `sourceId` and no `tlsProofHash`, `rawHash` equals
      SHA256 of the stub bytes. `juse21; mvn -q -pl nitro-enclave -am test`.

- [ ] 5. Wire the new dependencies through `EnclaveMain`.
      In `nitro-enclave/.../EnclaveMain.java`, construct `SourceRegistry`,
      `SourceTlsClient` (bound to the vsock→TCP transport), and the PoC
      `CredentialProvider`, and pass them into `EnclaveService`. Keep the vsock server
      on CID 16 / port 5005 and the `{ok, evidence}` / `{ok:false,error}` response shape
      (so coordinator error propagation is unchanged).
      _Requirements: 1.1, 1.2; design §architecture (control vs byte-relay)._
      Verify: enclave module compiles and `EnclaveServiceTest` passes under JDK 21;
      manual review that CID/port and the handler envelope are untouched.

- [ ] 6. Add the host-side vsock→TCP relay for enclave egress.
      Provide the parent-side relay the enclave dials for outbound TLS: either document
      the AWS `vsock-proxy` invocation (host:443 per provider host) in
      `infra/`/run scripts, or add a minimal parent relay. Record the exact command/
      config in `docs/` or the run script used for the E2E in task 12. Enclave egress
      must carry ciphertext only (no host plaintext).
      _Requirements: 1.1, 1.2, 9.1; design §networking._
      Verify: from the host, the enclave can open a TLS session to one mock host end to
      end (observed in task 12); host-side capture shows only ciphertext.

- [ ] 7. Update the coordinator to drive sourceId (drop response/proof inputs).
      In `coordinator/.../CoordinatorMain.java` + `Coordinator.java` +
      `VsockEnclaveClient.java`: remove `--raw-response-b64-file` and `--tls-proof-file`;
      add `--source-id` (required) and `--request-body-file` (optional, for the POST
      call #7). The enclave request now carries `{requestId, nonce, policyVersion,
      evidenceId, eifDigest, sourceId, requestBody?}` — NOT rawResponseB64/tlsProof.
      Keep challenge request, submission-envelope signing, and the Signer path
      unchanged. Update `USAGE`/`KNOWN`.
      _Requirements: 2.3, 4; design §current-state (coordinator row)._
      Verify: update `CoordinatorTest` — the recorded enclave request carries `sourceId`
      (and `requestBody` for #7) and no `rawResponseB64`/`tlsProof`; `run(...)` arity
      tests updated. `juse21; mvn -q -pl coordinator -am test`.

- [ ] 8. Update the Olea verifier: registry-aware source check, drop notary fields.
      In `sam/olea/functions/verification/index.js`:
      (a) remove `tlsProofType`, `tlsProofHash`, `tlsProofResponseHash`, `tlsProof` from
      the `submitEvidence` `required[]`.
      (b) delete the `verifyTlsNotaryProof(...)` call + the `TLS_PROOF_INVALID` block;
      remove the `require('./tlsnotary-verifier')` import from the live path.
      (c) replace `source==='mock-api' && endpoint==='GET_ORDERS'` with a shared
      `SOURCE_IDS` set check on `body.sourceId` (else `SOURCE_SCOPE_INVALID`); apply the
      same change to the `issueChallenge` endpoint check.
      (d) in the `verifyNitroAttestation(...)` call, drop `tlsProofHash` and add
      `sourceId` in the `user_data` object (match the enclave order).
      Keep same-bytes `RAW_PAYLOAD_HASH_MISMATCH`, transformed-digest, PCR, nonce,
      envelope, signature, vault, 202.
      _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 6.1, 6.2; design §verifier._
      Verify: `node --test` in `sam/olea/functions/verification/` — add/extend a test
      asserting notary fields are no longer required, the 7 sourceIds pass the scope
      check and an unknown one is rejected, and the `user_data` object has `sourceId`
      not `tlsProofHash`.

- [ ] 9. Keep `verification-contract.js buildManifest` in lockstep with the enclave.
      In `sam/olea/functions/verification/verification-contract.js`, remove the
      `tlsProof*` fields from the rebuilt manifest and replace `source`/`endpoint` with
      `sourceId`, so `sha256(canonicalize(buildManifest(body)))` still equals the
      enclave `manifestDigest`.
      _Requirements: 4.1, 7.1; design §verifier (buildManifest)._
      Verify: a contract-parity test computes `manifestDigest` from a sample evidence
      body on both sides (enclave canonical vs JS `buildManifest`) and asserts equality;
      existing `node --test` suite stays green.

- [ ] 10. Bake the CA bundle into the EIF and rebuild.
      In `nitro-enclave/Dockerfile`, copy a pinned CA bundle into the image at the path
      `SourceTlsClient` loads, so the trust anchor is PCR-measured. Rebuild the EIF
      (existing build flow). No Rust/notary build.
      _Requirements: 1.3, 7.1; design §EIF._
      Verify: `docker build` succeeds; `nitro-cli build-enclave` emits new PCR0/1/2;
      record the new EIF SHA-256 + PCRs (do not reuse the old ones).

- [ ] 11. Re-register the converted EIF as ACTIVE.
      `POST /v1/releases` with the new `eifDigest`, `pcr0/1/2`, and the attested public
      key; confirm the old release is not relied on. For sandbox/prod, update the KMS
      key policy `kms:RecipientAttestation:PCRn` to the new PCR0 (task 3's
      `KmsAttestedCredentialProvider`).
      _Requirements: 7.1, 7.2, 3.2, 9.2; design §EIF, §credentials._
      Verify: `GET /v1/releases/{eifDigest}` returns ACTIVE with the new PCRs.

- [ ] 12. Prove all 7 calls end to end against the deployed mocks, with negatives.
      For each of the 7 sourceIds: `POST /v1/challenges` → run the coordinator with
      `--source-id` (and `--request-body-file` for `gutuPanoramaChecks`) → enclave
      fetches over its own TLS via the task-6 relay → `POST /v1/evidence` → expect
      **202**. Save each accepted record under `evidence/tls-in-tee/<sourceId>.json`.
      Then two negatives: (a) point a run at a wrong host → fail closed;
      (b) flip a byte in the host relay → acceptance fails
      (`RAW_PAYLOAD_HASH_MISMATCH`/attestation mismatch), proving the host cannot alter
      data.
      _Requirements: 8.1, 8.2, 8.3; design §all-7-E2E._
      Verify: 7 × 202 receipts captured; 2 negative runs rejected with the expected
      codes; a short `evidence/tls-in-tee/_summary.json` lists sourceId→status→
      evidenceId (mirroring `evidence/sandbox-calls/_summary.json`).

- [ ] 13. Decommission the notary from the live path (keep as reference) + doc sync.
      Confirm nothing on the live path imports the sidecar/notary or `tlsnotary-verifier`
      (left in-repo as reference, per the spec intro). Update `docs/FLOWS.md`,
      `docs/SEQUENCE_AND_FIELDS.md`, `docs/ARCHITECTURE_DIAGRAM.md`,
      `docs/PROJECT_STATUS_MATRIX.md`, and `README.md` to describe the TLS-in-TEE path
      (enclave terminates TLS; no notary on the live path) and the new `sourceId`/
      evidence contract. Note the relationship to the deferred `finances-transformation`
      spec. Decide whether to delete `coordinator/sources.py` now that its logic is
      ported, or keep it as a labeled reference.
      _Requirements: 4.3, 5.4; design §decisions._
      Verify: grep shows no live-path import of the notary modules; the docs no longer
      describe the notary as the current live source-proof; the status matrix reflects
      TLS-in-TEE.

---

## Traceability (requirement → task)

| Requirement | Tasks |
|---|---|
| 1 Enclave makes the TLS call | 2, 5, 6, 10 |
| 2 7-entry registry | 1, 4, 7 |
| 3 Credentials under attestation | 3, 11 |
| 4 Evidence contract unchanged (minus notary) | 4, 8, 9 |
| 5 Olea verification (minus notary) | 8, 9 |
| 6 Challenge / nonce / replay | 8 |
| 7 EIF rebuild + re-register | 10, 11 |
| 8 All 7 proven E2E | 12 |
| 9 Open items (network, creds, transform) | 6, 11, 13 |

## Notes

- **Order rationale:** modules (1–5) and verifier (8–9) are built and unit-tested while
  the old live path still works; only task 10+ rebuilds/registers the EIF and task 12
  cuts over the live run. This keeps every intermediate state green.
- **Transform stays stubbed** here on purpose; the richer finances transform is the
  separate `finances-transformation` spec, whose Requirement 7 pledges not to disturb
  these seals.
- **No secret ever logged or committed** — tasks 2, 3, 7 explicitly keep bytes/creds
  out of logs; sandbox creds come from env/KMS, never the repo.
