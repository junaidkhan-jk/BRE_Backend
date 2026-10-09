-- bre ctl schema roles and platform
--
-- Extension, roles, schema, generic triggers, and the platform tables (LLD §5.2).
--
-- Extracted verbatim from the LLD's §5.2 DDL rather than retyped, so the schema and the
-- specification cannot drift. Split on the specification's own section boundaries.

CREATE EXTENSION IF NOT EXISTS btree_gist;               -- equality on text/int inside GiST exclusion constraints

CREATE ROLE bre_ctl_owner    NOLOGIN;
CREATE ROLE bre_ctl_app      NOLOGIN;                    -- studio-service, policy-registry, simulation-service
CREATE ROLE bre_ctl_platform NOLOGIN BYPASSRLS;          -- tenant-admin-service (operator actions only)
CREATE ROLE bre_ctl_auditor  NOLOGIN;

CREATE SCHEMA bre_ctl AUTHORIZATION bre_ctl_owner;

-- ---------------------------------------------------------------- generic triggers
CREATE FUNCTION bre_ctl.tg_touch() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  NEW.row_version := OLD.row_version + 1;
  NEW.updated_at  := now();
  RETURN NEW;
END $$;

CREATE FUNCTION bre_ctl.tg_forbid() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% on %.% is not permitted (append-only)', TG_OP, TG_TABLE_SCHEMA, TG_TABLE_NAME
        USING ERRCODE = 'insufficient_privilege';
END $$;

-- ---------------------------------------------------------------- platform
CREATE TABLE bre_ctl.cells (
  cell_id           smallint    PRIMARY KEY CHECK (cell_id BETWEEN 0 AND 255),
  region_pair       text        NOT NULL,
  status            text        NOT NULL CHECK (status IN ('ACTIVE','DRAINING','RETIRED')),
  hot_store_shards  smallint    NOT NULL CHECK (hot_store_shards BETWEEN 1 AND 256),
  dedicated_tenant  text,
  created_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE bre_ctl.tenants (
  tenant_id                text        PRIMARY KEY CHECK (tenant_id ~ '^[a-z][a-z0-9_]{2,31}$'),
  display_name             text        NOT NULL,
  status                   text        NOT NULL DEFAULT 'ONBOARDING'
                                       CHECK (status IN ('ONBOARDING','ACTIVE','SUSPENDED','OFFBOARDING','CLOSED')),
  cell_id                  smallint    NOT NULL REFERENCES bre_ctl.cells (cell_id),
  time_zone                text        NOT NULL DEFAULT 'Asia/Kolkata',
  min_policy_reviewers     smallint    NOT NULL DEFAULT 1  CHECK (min_policy_reviewers BETWEEN 1 AND 5),
  routing_mode             text        NOT NULL DEFAULT 'HIGHEST_ONLY' CHECK (routing_mode IN ('HIGHEST_ONLY','EACH')),
  grandfather_window_days  smallint    NOT NULL DEFAULT 90 CHECK (grandfather_window_days BETWEEN 0 AND 730),
  languages                text[]      NOT NULL DEFAULT ARRAY['en'],
  hot_retention_days       smallint    NOT NULL DEFAULT 90 CHECK (hot_retention_days BETWEEN 7 AND 180),
  facts_retention_days     smallint    NOT NULL DEFAULT 14 CHECK (facts_retention_days BETWEEN 0 AND 180),
  archive_retention_years  smallint    NOT NULL DEFAULT 10 CHECK (archive_retention_years BETWEEN 1 AND 30),
  row_version              bigint      NOT NULL DEFAULT 1,
  created_at               timestamptz NOT NULL DEFAULT now(),
  updated_at               timestamptz NOT NULL DEFAULT now(),
  CHECK ('en' = ANY (languages)),
  CHECK (facts_retention_days <= hot_retention_days)
);

CREATE TABLE bre_ctl.role_assignments (
  tenant_id   text        NOT NULL REFERENCES bre_ctl.tenants (tenant_id),
  subject     text        NOT NULL,                                -- OIDC 'sub'
  role        text        NOT NULL CHECK (role IN ('POLICY_AUTHOR','POLICY_REVIEWER','DEPLOYMENT_APPROVER',
                                                   'RISK_ANALYST','AUDITOR','OPS_VIEWER','TENANT_ADMIN')),
  granted_by  text        NOT NULL,
  granted_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, subject, role)
);

CREATE TABLE bre_ctl.environments (
  tenant_id                text     NOT NULL REFERENCES bre_ctl.tenants (tenant_id),
  env_code                 text     NOT NULL CHECK (env_code ~ '^[A-Z][A-Z0-9_]{1,15}$'),
  is_production            boolean  NOT NULL DEFAULT false,
  promotion_order          smallint NOT NULL,
  min_deployment_approvers smallint NOT NULL CHECK (min_deployment_approvers BETWEEN 0 AND 5), -- DEV 0, UAT 1, PROD 2
  dry_run_allowed          boolean  NOT NULL DEFAULT true,
  PRIMARY KEY (tenant_id, env_code),
  UNIQUE (tenant_id, promotion_order),
  CHECK (NOT (is_production AND dry_run_allowed)),
  CHECK (NOT is_production OR min_deployment_approvers >= 1)
);
