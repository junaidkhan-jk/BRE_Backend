-- bre ctl bundles and deployments
--
-- Bundles, deployments, approvals and the resolution index (LLD §5.2).
--
-- Extracted verbatim from the LLD's §5.2 DDL rather than retyped, so the schema and the
-- specification cannot drift. Split on the specification's own section boundaries.

-- ---------------------------------------------------------------- bundles and deployments
CREATE TABLE bre_ctl.bundles (
  tenant_id          text        NOT NULL,
  bundle_hash        text        NOT NULL CHECK (bundle_hash ~ '^sha256:[0-9a-f]{64}$'),
  policy_code        text        NOT NULL,
  policy_version     integer     NOT NULL,
  origin             text        NOT NULL CHECK (origin IN ('BUILT','IMPORTED')),
  object_key         text        NOT NULL,                             -- bundles/<tenant>/<sha256>
  size_bytes         bigint      NOT NULL CHECK (size_bytes > 0),
  ir_version         smallint    NOT NULL,
  engine_min_version text        NOT NULL,
  signatures         jsonb       NOT NULL,
  created_at         timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, bundle_hash),
  FOREIGN KEY (tenant_id, policy_code, policy_version)
    REFERENCES bre_ctl.policy_versions (tenant_id, policy_code, version)
);

CREATE TABLE bre_ctl.deployments (
  tenant_id                  text        NOT NULL,
  deployment_id              uuid        NOT NULL,
  env_code                   text        NOT NULL,
  policy_code                text        NOT NULL,
  bundle_hash                text        NOT NULL,
  mode                       text        NOT NULL CHECK (mode IN ('CHAMPION','CHALLENGER','SHADOW')),
  traffic_basis_points       integer,
  bucket_from                integer,
  bucket_to                  integer,
  experiment_id              text,
  shadow_sample_basis_points integer,
  effective_from             timestamptz NOT NULL,
  status                     text        NOT NULL DEFAULT 'PENDING_APPROVAL'
                                         CHECK (status IN ('PENDING_APPROVAL','SCHEDULED','ACTIVE',
                                                           'SUPERSEDED','ROLLED_BACK','CANCELLED')),
  expedited                  boolean     NOT NULL DEFAULT false,
  rollback_of                uuid,
  reason                     text        NOT NULL,
  requested_by               text        NOT NULL,
  activated_at               timestamptz,
  ended_at                   timestamptz,
  pir_task_ref               text,                                     -- post-implementation review (EXPEDITED)
  row_version                bigint      NOT NULL DEFAULT 1,
  created_at                 timestamptz NOT NULL DEFAULT now(),
  updated_at                 timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, deployment_id),
  FOREIGN KEY (tenant_id, env_code)    REFERENCES bre_ctl.environments (tenant_id, env_code),
  FOREIGN KEY (tenant_id, policy_code) REFERENCES bre_ctl.policies (tenant_id, policy_code),
  FOREIGN KEY (tenant_id, bundle_hash) REFERENCES bre_ctl.bundles (tenant_id, bundle_hash),
  CHECK ((mode = 'CHALLENGER') = (traffic_basis_points IS NOT NULL)),
  CHECK (mode <> 'CHALLENGER' OR (traffic_basis_points BETWEEN 1 AND 5000
                                  AND bucket_from BETWEEN 0 AND 9999
                                  AND bucket_to = bucket_from + traffic_basis_points
                                  AND bucket_to <= 10000
                                  AND experiment_id IS NOT NULL)),
  CHECK ((mode = 'SHADOW') = (shadow_sample_basis_points IS NOT NULL)),
  CHECK (shadow_sample_basis_points IS NULL OR shadow_sample_basis_points BETWEEN 1 AND 10000),
  CHECK (status <> 'ACTIVE' OR activated_at IS NOT NULL),
  CHECK (NOT expedited OR mode = 'CHAMPION'),
  -- at most one ACTIVE champion per (tenant, env, policy); checked at COMMIT so supersede+activate can run in any order
  CONSTRAINT one_active_champion EXCLUDE USING btree (tenant_id WITH =, env_code WITH =, policy_code WITH =)
    WHERE (status = 'ACTIVE' AND mode = 'CHAMPION') DEFERRABLE INITIALLY DEFERRED,
  -- concurrently active challengers of one policy never overlap in bucket space
  CONSTRAINT challenger_buckets_disjoint EXCLUDE USING gist (tenant_id WITH =, env_code WITH =,
    policy_code WITH =, (int4range(bucket_from, bucket_to)) WITH &&)
    WHERE (status = 'ACTIVE' AND mode = 'CHALLENGER') DEFERRABLE INITIALLY DEFERRED
);
CREATE INDEX deployments_by_policy ON bre_ctl.deployments (tenant_id, env_code, policy_code, activated_at DESC);
CREATE TRIGGER deployments_touch BEFORE UPDATE ON bre_ctl.deployments
  FOR EACH ROW EXECUTE FUNCTION bre_ctl.tg_touch();

CREATE TABLE bre_ctl.deployment_approvals (
  tenant_id     text        NOT NULL,
  deployment_id uuid        NOT NULL,
  approver      text        NOT NULL,
  comment       text,
  created_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, deployment_id, approver),
  FOREIGN KEY (tenant_id, deployment_id) REFERENCES bre_ctl.deployments (tenant_id, deployment_id)
);

CREATE FUNCTION bre_ctl.tg_deployment_approval_sod() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF EXISTS (
       SELECT 1
         FROM bre_ctl.deployments d
         JOIN bre_ctl.bundles b ON b.tenant_id = d.tenant_id AND b.bundle_hash = d.bundle_hash
        WHERE d.tenant_id = NEW.tenant_id AND d.deployment_id = NEW.deployment_id
          AND (EXISTS (SELECT 1 FROM bre_ctl.policy_version_editors e
                        WHERE e.tenant_id = b.tenant_id AND e.policy_code = b.policy_code
                          AND e.policy_version = b.policy_version AND e.editor = NEW.approver)
               OR EXISTS (SELECT 1 FROM bre_ctl.policy_version_reviews r
                           WHERE r.tenant_id = b.tenant_id AND r.policy_code = b.policy_code
                             AND r.policy_version = b.policy_version AND r.reviewer = NEW.approver
                             AND r.decision = 'APPROVE'))) THEN
    RAISE EXCEPTION 'segregation of duties: % authored or reviewed the bundle and cannot approve its deployment',
          NEW.approver USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER deployment_approvals_sod BEFORE INSERT ON bre_ctl.deployment_approvals
  FOR EACH ROW EXECUTE FUNCTION bre_ctl.tg_deployment_approval_sod();

-- resolution uniqueness (00 §3.7a): one ACTIVE CHAMPION per (tenant, env, productLine, stage, segment)
CREATE TABLE bre_ctl.resolution_index (
  tenant_id     text NOT NULL,
  env_code      text NOT NULL,
  product_line  text NOT NULL,
  stage         text NOT NULL,
  segment_key   text NOT NULL,                                         -- segment or '*'
  policy_code   text NOT NULL,
  deployment_id uuid NOT NULL,
  PRIMARY KEY (tenant_id, env_code, product_line, stage, segment_key),
  FOREIGN KEY (tenant_id, deployment_id) REFERENCES bre_ctl.deployments (tenant_id, deployment_id)
);

-- signed Active Pointer Manifests, one monotonically increasing sequence per cell (§8)
CREATE TABLE bre_ctl.pointer_manifests (
  cell_id          smallint    NOT NULL REFERENCES bre_ctl.cells (cell_id),
  manifest_version bigint      NOT NULL CHECK (manifest_version >= 1),
  body             jsonb       NOT NULL,
  body_sha256      text        NOT NULL CHECK (body_sha256 ~ '^sha256:[0-9a-f]{64}$'),
  signature        jsonb       NOT NULL,                               -- {kid, alg:"Ed25519", sig}
  created_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (cell_id, manifest_version)
);

CREATE TABLE bre_ctl.serving_reports (                               -- last report per pod (§8.5)
  cell_id          smallint    NOT NULL,
  pod_id           text        NOT NULL,
  manifest_version bigint      NOT NULL,
  status           text        NOT NULL CHECK (status IN ('SERVING','LOADING','LOAD_FAILED','STALE')),
  bundle_hashes    text[]      NOT NULL,
  failures         jsonb       NOT NULL DEFAULT '[]'::jsonb,
  reported_at      timestamptz NOT NULL,
  PRIMARY KEY (cell_id, pod_id)
);

CREATE VIEW bre_ctl.active_pointers WITH (security_invoker = true) AS
  SELECT tenant_id, env_code, policy_code, deployment_id, bundle_hash, mode,
         traffic_basis_points, bucket_from, bucket_to, experiment_id, shadow_sample_basis_points,
         effective_from, activated_at
    FROM bre_ctl.deployments
   WHERE status = 'ACTIVE';
