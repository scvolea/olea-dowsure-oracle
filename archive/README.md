# Archive — superseded designs

These documents describe **earlier designs that are no longer on the live path**. They
are kept for historical context and traceability of decisions. For the current,
authoritative architecture see:

- **Live architecture:** `docs/ARCHITECTURE_DIAGRAM.md`, `docs/SEQUENCE_AND_FIELDS.md`
- **Detailed TLS-in-TEE explainer:** `docs/TLS_IN_TEE_EXPLAINED.md`
- **Step-by-step flow:** `docs/flow-steps/`
- **Current status/facts:** `docs/PROJECT_STATUS_MATRIX.md`

## What's here and why it was superseded

| File | What it describes | Superseded by |
|---|---|---|
| `TLSNOTARY.md` | The MPC-TLS / **TLSNotary** source-authenticity layer: an external, Olea-hosted notary co-witnessed the TLS session via multi-party computation, and a Rust prover sidecar produced a `tlsProof` the enclave bound into its attestation. | **TLS-in-TEE** — the Nitro enclave terminates TLS to each source itself; the hardware attestation (PCR0/1/2) is the origin + execution anchor. No notary, no sidecar, no `tlsProof`. |
| `dowsure-data-integrity-design.md` | Early Dowsure-side data-integrity design framing. | Current oracle design in `.kiro/specs/tls-tee-oracle/` + `docs/`. |
| `olea-data-integrity-design.md` | Early Olea-side proof-type / policy-registry design (TLSNotary-based source proofs). | Current verifier contract (`sam/olea/functions/verification/`) + the live TLS-in-TEE path. |
| `IMPLEMENTATION_AGENT_HANDOFF.pre-tls-in-tee.md` | The internal handoff as it stood before the TLS-in-TEE conversion (notary-placeholder era, bounded `GET_ORDERS`, EIF `246e2143…`). | Current `IMPLEMENTATION_AGENT_HANDOFF.md`. |
| `dowsure-implementation-handoff.pre-tls-in-tee.md` | The Dowsure-facing handoff with the old MPC-TLS/notary interfaces + sequence diagram. | Current `dowsure-implementation-handoff.md`. |

## Why the change (one line)

The notary model required trusting an external party to witness a session the enclave
did not control. TLS-in-TEE collapses source authenticity and execution integrity into
a **single hardware-attested boundary** — simpler trust story, no third-party
dependency on the request path.
