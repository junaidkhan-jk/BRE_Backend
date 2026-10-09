-- bre ctl simulations
--
-- Simulation runs, back-tests and impact analyses (LLD §5.2).
--
-- Extracted verbatim from the LLD's §5.2 DDL rather than retyped, so the schema and the
-- specification cannot drift. Split on the specification's own section boundaries.

-- ---------------------------------------------------------------- simulations / back-tests / impact analyses
CREATE TABLE bre_ctl.simulation_runs (
  tenant_id               text        NOT NULL REFERENCES bre_ctl.tenants (tenant_id),
  run_id                  uuid        NOT NULL,
  kind                    text        NOT NULL CHECK (kind IN ('SIMULATION','BACKTEST','IMPACT_ANALYSIS')),
  policy_code             text        NOT NULL,
  candidate_version       integer     NOT NULL,
  candidate_build_hash    text        NOT NULL CHECK (candidate_build_hash ~ '^sha256:[0-9a-f]{64}$'),
  baseline_bundle_hash    text        CHECK (baseline_bundle_hash ~ '^sha256:[0-9a-f]{64}$'),
  source                  jsonb       NOT NULL,                        -- §6.5
  status                  text        NOT NULL DEFAULT 'QUEUED'
                                      CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED','CANCELLED')),
  total                   bigint,
  processed               bigint      NOT NULL DEFAULT 0,
  summary                 jsonb,
  result_object_key       text,
  requested_by            text        NOT NULL,
  requested_at            timestamptz NOT NULL DEFAULT now(),
  started_at              timestamptz,
  finished_at             timestamptz,
  row_version             bigint      NOT NULL DEFAULT 1,
  updated_at              timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, run_id),
  FOREIGN KEY (tenant_id, policy_code, candidate_version)
    REFERENCES bre_ctl.policy_versions (tenant_id, policy_code, version),
  CHECK (kind = 'SIMULATION' OR baseline_bundle_hash IS NOT NULL)
);
