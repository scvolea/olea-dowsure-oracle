# Deployment Summary

> **Status facts live in one place.** This page keeps the deployment commands and
> sequence. The verified hard facts (the Enclave Image File SHA-256, the
> PCR0/PCR1/PCR2 values, the host, and the verified request/evidence IDs) are
> defined once in the single source of truth,
> [../../docs/PROJECT_STATUS_MATRIX.md](../../docs/PROJECT_STATUS_MATRIX.md), and
> are not restated here.

- **Deployment Model: Real [[Olea]], Real [[AWS Nitro Enclave]], Mock [[Dowsure]] orchestration, and Mock Source API**
  - Validate and build Olea:
    - `sam validate --lint --template-file olea/template.yaml`
    - `sam build --template-file olea/template.yaml --build-dir olea/.aws-sam/build`
  - Deploy Olea with an explicit approved profile:
    - `sam deploy --guided --template-file olea/.aws-sam/build/template.yaml --profile preprod --region ap-southeast-1`
  - Record the Olea `ApiUrl` output.
  - Supply `NitroRootCertPem` with the AWS Nitro root certificate PEM when deploying Olea; an empty value intentionally causes attestation verification to fail closed.
  - Register policy `v1.0` through `POST /v1/policies`.
  - Register the approved EIF digest and live PCR0/PCR1/PCR2 values through `POST /v1/releases` for the `GET_ORDERS` source-proof flow.
  - Deploy Dowsure, passing the Olea output:
    - `sam deploy --guided --template-file dowsure/template.yaml --parameter-overrides OleaApiBaseUrl=<OLEA_API_URL> --profile preprod --region ap-southeast-1`
  - Configure the approved mock source `GET /orders` endpoint and TLSNotary proof in the enclave runtime for Phase 1.
  - Do not deploy or configure Amazon SP-API, LWA, seller accounts, reports, or financial-report fixtures in Phase 1.

- **Stack Independence**
  - Each directory is an independent SAM application.
  - No stack imports or exports are required.
  - Endpoint URLs are explicit deployment parameters.
  - The mock source can be replaced independently without changing the Olea control plane.

- **Nitro PoC Runtime**
  - Template: [../../infra/nitro-ec2.yaml](../../infra/nitro-ec2.yaml)
  - Stack: `olea-dowsure-nitro-preprod`
  - Verified running enclave name: `olea-orders-java`, CID `16`, `2048 MiB`, `2` vCPUs,
    Enclave Image File (EIF) state `RUNNING`, non-debug (`Flags: NONE`).
  - For the host, EIF SHA-256, and PCR values, see the status matrix
    ([../../docs/PROJECT_STATUS_MATRIX.md](../../docs/PROJECT_STATUS_MATRIX.md)); they are not repeated here.
  - **Infra template caveat:** the committed `infra/nitro-ec2.yaml` launch snippet
    still shows `nitro-cli run-enclave ... --eif-path /opt/olea-nitro/olea-orders.eif
    --enclave-cid 16 --debug-mode` with the pre-phase4 image name `olea-nitro-orders:latest`
    and `memory_mib: 4096`. That template pre-dates the verified run. The VERIFIED
    runtime is the non-debug phase4 Java EIF (`Flags: NONE`). Do not read the committed
    template as launching the verified non-debug enclave.
  - The stack uses a two-phase deployment: create support resources with `LaunchHost=false`, upload the context, then update with `LaunchHost=true`.
  - Publish context from the repository root: `& .\scripts\publish-nitro-artifact.ps1 -Profile preprod -Region ap-southeast-1 -StackName olea-dowsure-nitro-preprod`
  - The host builds the EIF with `NITRO_CLI_ARTIFACTS=/var/lib/nitro_enclaves` and starts it through `nitro-cli`.

- **Verified PoC Evidence**
  - Olea stack: `UPDATE_COMPLETE`.
  - Direct signed evidence test: policy `201`, release `201`, challenge `201`, evidence `202 ACCEPTED`.
  - Evidence vault object was written with KMS (Key Management Service) encryption.
  - The PoC release uses the live EIF PCR values and generated ECDSA keys.
  - The verified enclave fingerprints (phase4 EIF SHA-256, PCR0/1/2), the verified
    request/evidence IDs, and the Jira release-gate items are recorded once in the
    status matrix: [../../docs/PROJECT_STATUS_MATRIX.md](../../docs/PROJECT_STATUS_MATRIX.md).

- **Java Enclave Phase 4 — Runtime Verified**
  - The Docker builder compiles the shaded Java 21 JAR, compiles the pinned NSM
    (Nitro Security Module) library, and runs `java -jar /app/enclave-service.jar`.
  - The enclave runs as `olea-orders-java`, CID `16`, `2` vCPUs, `2048 MiB`,
    non-debug (`Flags: NONE`). CID `16` is required because `EnclaveMain` binds the
    Java AF_VSOCK (virtual socket) port `5005` to CID `16`; a CID `17` launch
    surfaced the mismatch as `VSOCK_SERVER_FAILED` (not an EIF failure), and the
    corrected CID `16` launch reaches `RUNNING`.
  - The old Python EIF/PCR set must not be used as Java evidence.
  - Verified fingerprints (EIF SHA-256, PCR0/1/2), the live request/evidence IDs,
    and the AWS Nitro Root-G1 / COSE / certificate-chain / PCR / public-key /
    canonicalized `user_data` verification results are recorded once in the status
    matrix: [../../docs/PROJECT_STATUS_MATRIX.md](../../docs/PROJECT_STATUS_MATRIX.md).
  - Remaining gates: run the live Olea acceptance receipt and replace the TLSNotary
    proof contract with an approved prover/notary service and real signed proof verification.

- **Suggested Stack Names**
  - `olea-oracle-preprod`
  - `dowsure-oracle-preprod`
  - `olea-source-proof-mock`

- **Teardown Warning**
  - The real Olea stack retains KMS keys, DynamoDB tables, and the Object Lock bucket.
  - Object Lock COMPLIANCE objects cannot be deleted before retention expires.
  - Mock stacks contain no retained data stores.
