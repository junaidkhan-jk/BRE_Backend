-- The fact snapshot, for replay (D7).
--
-- READ THIS BEFORE ENABLING IT.
--
-- D7 regenerates a decision's full explanation by re-evaluating the STORED FACTS against the
-- stored bundle, which is roughly ten times less storage than keeping a trace per rule and is
-- still exact. So replay needs the facts.
--
-- But facts are the customer's personal data -- income, dependants, bureau tradelines, KYC
-- references -- and §9.2 requires them stored as two envelopes under a tenant data key and a
-- per-principal key, so that erasure can be performed by destroying a key rather than by finding
-- and deleting rows (§9.5, crypto-shredding). That key hierarchy does not exist yet; it is M4.
--
-- This table therefore stores facts as PLAINTEXT jsonb, and the column says so in its name. It is
-- created by the migration but WRITTEN ONLY when `bre.decision-log.store-facts` is explicitly set
-- to true, which defaults to false. The flag exists so that local development and replay testing
-- are possible, not so that it can be switched on in an environment holding real applicants.
--
-- Consequences of leaving it off, which is the default:
--   * a decision is still durable, auditable and idempotent;
--   * GET /v1/decisions/{id} still returns the decision and its per-step results;
--   * what is NOT possible is regenerating the full evaluation trace, because the inputs are gone.
--
-- Replace this table with the §9.2 envelopes before any environment holds real data.

CREATE TABLE bre_dec.decision_facts (
  tenant_id            text    NOT NULL,
  decision_date        date    NOT NULL,
  decision_id          uuid    NOT NULL,
  facts_json_plaintext jsonb   NOT NULL,
  facts_sha256         bytea   NOT NULL,
  fact_schema          text    NOT NULL,
  PRIMARY KEY (tenant_id, decision_date, decision_id)
) PARTITION BY RANGE (decision_date);

COMMENT ON TABLE bre_dec.decision_facts IS
  'Fact snapshots for replay (D7). DEVELOPMENT ONLY: plaintext PII, written only when bre.decision-log.store-facts=true. Superseded by the §9.2 envelopes in M4.';

COMMENT ON COLUMN bre_dec.decision_facts.facts_json_plaintext IS
  'UNENCRYPTED personal data. §9.2 requires facts_envelope + principal_envelope under TDK/DPK so that erasure is key destruction (§9.5). Not implemented yet.';

COMMENT ON COLUMN bre_dec.decision_facts.facts_sha256 IS
  'SHA-256 over the canonical facts, for integrity. §9.2 specifies an HMAC under a tenant key; a plain digest detects corruption but not tampering by someone who can also rewrite the digest.';
