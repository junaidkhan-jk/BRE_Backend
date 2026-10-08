-- The decision hot store, compact mode (LLD §5.3, D6).
--
-- Compact mode writes to PostgreSQL FIRST, with a transactional outbox, and the response does not
-- return until that transaction commits. That is what gives RPO 0 for an acknowledged decision: a
-- decision the caller has acted on but the lender cannot produce on audit is worse than a slow
-- decision.
--
-- SCOPE OF THIS MIGRATION. It creates the tables the sync Decision API needs: decisions, the
-- idempotency keys, and the outbox. It deliberately omits, with the reason:
--
--   * bre_dec.decision_facts  -- facts are PII and §9.2's envelope encryption (TDK/DPK) does not
--                                exist yet. See V2 for the plaintext-only development table, which
--                                is created but written to only when explicitly enabled.
--   * bre_dec.shadow_results  -- shadow-evaluator, M4.
--   * bre_dec.decision_jobs   -- bulk decision jobs, M4.
--   * bre_dec.principal_keys  -- the §9.2 key hierarchy, M4.
--   * bre_dec.sink_offsets    -- Kafka-first mode only; compact mode has no sink.
--
-- The roles in §5.3 (bre_dec_owner/app/sink/erasure) and row-level security are a deployment
-- concern, not a schema one, and are left to the DBA scripts for the target environment.

CREATE SCHEMA IF NOT EXISTS bre_dec;

-- Partitioned RANGE (decision_date), one partition per UTC day (§5.3). decision_date is the UTC
-- date of the decision_id's UUIDv7 timestamp, so a lookup by id prunes to a single day.
--
-- Retention is applied by DROPPING whole day partitions, never by DELETE: dropping a partition is
-- instant and leaves no dead tuples, where deleting a day's worth of rows would rewrite the table
-- and keep the data recoverable from the heap for a while afterwards.
CREATE TABLE bre_dec.decisions (
  tenant_id             text        NOT NULL,
  decision_date         date        NOT NULL,
  decision_id           uuid        NOT NULL,
  request_id            text        NOT NULL CHECK (length(request_id) BETWEEN 1 AND 128),
  application_ref       text        NOT NULL CHECK (length(application_ref) BETWEEN 1 AND 128),
  env_code              text        NOT NULL,
  policy_code           text        NOT NULL,
  policy_version        integer     NOT NULL,
  bundle_hash           text        NOT NULL,
  engine_version        text        NOT NULL,
  as_of                 timestamptz NOT NULL,
  as_of_date            date        NOT NULL,
  time_zone             text        NOT NULL,
  received_at           timestamptz NOT NULL,
  completed_at          timestamptz NOT NULL,
  latency_us            integer     NOT NULL CHECK (latency_us >= 0),
  decision              text        NOT NULL
      CHECK (decision IN ('DECLINE','REFER','APPROVE_WITH_DEVIATION','APPROVE')),
  stp_eligible          boolean     NOT NULL,
  reason_codes          text[]      NOT NULL DEFAULT '{}',
  deviation_types       text[]      NOT NULL DEFAULT '{}',
  required_levels       text[]      NOT NULL DEFAULT '{}',
  error_count           smallint    NOT NULL DEFAULT 0,
  fuel_used             bigint      NOT NULL DEFAULT 0,
  results_json          jsonb       NOT NULL,
  record_schema         smallint    NOT NULL DEFAULT 1,
  PRIMARY KEY (tenant_id, decision_date, decision_id),
  -- Straight-through processing and any decision other than APPROVE are mutually exclusive by
  -- definition. Enforced here as well as in the engine: a bug that produced the combination would
  -- otherwise be persisted and later believed.
  CONSTRAINT decisions_stp_only_on_approve CHECK (NOT stp_eligible OR decision = 'APPROVE')
) PARTITION BY RANGE (decision_date);

-- §5.3 names `results_envelope bytea` -- zstd(protobuf) under the tenant data key. This column is
-- `results_json jsonb` instead, and the difference is deliberate rather than an oversight: the
-- protobuf schema lives in bre-contracts (M2) and the key hierarchy in §9.2 (M4), and naming a
-- plaintext column "envelope" would read as encrypted to everyone who came after. A decision
-- result holds statuses, reason codes and measures -- not identifying facts -- so plaintext here
-- is a smaller exposure than the facts table, which is why that one is off by default.
COMMENT ON COLUMN bre_dec.decisions.results_json IS
  'Per-step and per-entity results. NOT ENCRYPTED: replaces the §9.2 results_envelope until the TDK key hierarchy exists (M4).';

CREATE INDEX decisions_by_application ON bre_dec.decisions (tenant_id, application_ref, received_at DESC);
CREATE INDEX decisions_by_request     ON bre_dec.decisions (tenant_id, request_id);

-- Authoritative idempotency for compact mode (§5.3). Evaluation is deterministic, so a duplicate
-- is harmless in itself -- but a retrying caller must see ONE decision, not two with different ids.
CREATE TABLE bre_dec.idempotency_keys (
  tenant_id           text        NOT NULL,
  request_id          text        NOT NULL,
  decision_id         uuid        NOT NULL,
  decision_date       date        NOT NULL,
  request_fingerprint bytea       NOT NULL,
  created_at          timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, request_id)
) PARTITION BY HASH (tenant_id, request_id);

CREATE TABLE bre_dec.idempotency_keys_p0 PARTITION OF bre_dec.idempotency_keys
  FOR VALUES WITH (MODULUS 4, REMAINDER 0);
CREATE TABLE bre_dec.idempotency_keys_p1 PARTITION OF bre_dec.idempotency_keys
  FOR VALUES WITH (MODULUS 4, REMAINDER 1);
CREATE TABLE bre_dec.idempotency_keys_p2 PARTITION OF bre_dec.idempotency_keys
  FOR VALUES WITH (MODULUS 4, REMAINDER 2);
CREATE TABLE bre_dec.idempotency_keys_p3 PARTITION OF bre_dec.idempotency_keys
  FOR VALUES WITH (MODULUS 4, REMAINDER 3);

CREATE INDEX idempotency_keys_expiry ON bre_dec.idempotency_keys (created_at);

-- The transactional outbox (§5.3, §7.6). Written in the SAME transaction as the decision, so the
-- event and the decision cannot disagree: either both are committed or neither is. A relay
-- publishes the rows afterwards, in order.
CREATE TABLE bre_dec.outbox (
  outbox_id    bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id    text        NOT NULL,
  topic        text        NOT NULL,
  record_key   text        NOT NULL,
  headers      jsonb       NOT NULL,
  payload      jsonb       NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz
);

-- Partial index: the relay only ever asks for unpublished rows, and this keeps that query O(pending)
-- rather than O(all decisions ever made).
CREATE INDEX outbox_unpublished ON bre_dec.outbox (outbox_id) WHERE published_at IS NULL;
