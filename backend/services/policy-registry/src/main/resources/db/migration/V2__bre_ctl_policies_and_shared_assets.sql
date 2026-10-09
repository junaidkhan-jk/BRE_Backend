-- bre ctl policies and shared assets
--
-- Policies and the versioned shared assets: parameters, reference lists, reason codes,
-- authority matrix and fact schemas (LLD §5.2).
--
-- Extracted verbatim from the LLD's §5.2 DDL rather than retyped, so the schema and the
-- specification cannot drift. Split on the specification's own section boundaries.

-- ---------------------------------------------------------------- policies
CREATE TABLE bre_ctl.policies (
  tenant_id       text        NOT NULL REFERENCES bre_ctl.tenants (tenant_id),
  policy_code     text        NOT NULL CHECK (policy_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  name            text        NOT NULL,
  stage           text        NOT NULL CHECK (stage ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  segment         text        CHECK (segment ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  pinning_allowed boolean     NOT NULL DEFAULT true,
  status          text        NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','RETIRED')),
  created_by      text        NOT NULL,
  row_version     bigint      NOT NULL DEFAULT 1,
  created_at      timestamptz NOT NULL DEFAULT now(),
  updated_at      timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, policy_code)
);

CREATE TABLE bre_ctl.policy_resolution_keys (            -- one row per product line served (00 §3.7a)
  tenant_id    text NOT NULL,
  policy_code  text NOT NULL,
  product_line text NOT NULL CHECK (product_line ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  PRIMARY KEY (tenant_id, policy_code, product_line),
  FOREIGN KEY (tenant_id, policy_code) REFERENCES bre_ctl.policies (tenant_id, policy_code)
);

-- ---------------------------------------------------------------- shared assets (versioned)
CREATE TABLE bre_ctl.asset_versions (
  tenant_id        text        NOT NULL REFERENCES bre_ctl.tenants (tenant_id),
  asset_kind       text        NOT NULL CHECK (asset_kind IN ('PARAMETER_SET','REFERENCE_LIST',
                                                              'REASON_CODE_CATALOGUE','AUTHORITY_MATRIX')),
  asset_code       text        NOT NULL CHECK (asset_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  version          integer     NOT NULL CHECK (version >= 1),
  status           text        NOT NULL DEFAULT 'DRAFT'
                               CHECK (status IN ('DRAFT','IN_REVIEW','APPROVED','ARCHIVED','DISCARDED')),
  content_hash     text        CHECK (content_hash ~ '^sha256:[0-9a-f]{64}$'),
  author           text        NOT NULL,
  submission_round smallint    NOT NULL DEFAULT 0,
  approved_at      timestamptz,
  row_version      bigint      NOT NULL DEFAULT 1,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, asset_kind, asset_code, version),
  CHECK ((status IN ('APPROVED','ARCHIVED')) = (content_hash IS NOT NULL AND approved_at IS NOT NULL))
);

CREATE TABLE bre_ctl.asset_version_reviews (
  tenant_id        text        NOT NULL,
  asset_kind       text        NOT NULL,
  asset_code       text        NOT NULL,
  asset_version    integer     NOT NULL,
  submission_round smallint    NOT NULL,
  reviewer         text        NOT NULL,
  decision         text        NOT NULL CHECK (decision IN ('APPROVE','REQUEST_CHANGES')),
  comment          text,
  created_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, asset_kind, asset_code, asset_version, submission_round, reviewer),
  FOREIGN KEY (tenant_id, asset_kind, asset_code, asset_version)
    REFERENCES bre_ctl.asset_versions (tenant_id, asset_kind, asset_code, version)
);

CREATE TABLE bre_ctl.parameters (
  tenant_id      text    NOT NULL,
  asset_kind     text    NOT NULL DEFAULT 'PARAMETER_SET' CHECK (asset_kind = 'PARAMETER_SET'),
  asset_code     text    NOT NULL,
  asset_version  integer NOT NULL,
  name           text    NOT NULL CHECK (name ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  value_type     text    NOT NULL CHECK (value_type IN ('Decimal','Integer','Boolean','Text','Date')),
  regulatory     boolean NOT NULL DEFAULT false,                       -- PRD FR-GOV-005
  source_ref     text,
  next_review_on date,
  description    text,
  PRIMARY KEY (tenant_id, asset_code, asset_version, name),
  FOREIGN KEY (tenant_id, asset_kind, asset_code, asset_version)
    REFERENCES bre_ctl.asset_versions (tenant_id, asset_kind, asset_code, version),
  CHECK (NOT regulatory OR (source_ref IS NOT NULL AND next_review_on IS NOT NULL))
);

CREATE TABLE bre_ctl.parameter_values (
  tenant_id      text    NOT NULL,
  asset_kind     text    NOT NULL DEFAULT 'PARAMETER_SET' CHECK (asset_kind = 'PARAMETER_SET'),
  asset_code     text    NOT NULL,
  asset_version  integer NOT NULL,
  name           text    NOT NULL,
  effective_from date    NOT NULL,
  effective_to   date,                                                 -- exclusive; NULL = open-ended
  value          text    NOT NULL,                                     -- parsed by parameters.value_type
  PRIMARY KEY (tenant_id, asset_code, asset_version, name, effective_from),
  FOREIGN KEY (tenant_id, asset_code, asset_version, name)
    REFERENCES bre_ctl.parameters (tenant_id, asset_code, asset_version, name),
  CHECK (effective_to IS NULL OR effective_to > effective_from),
  EXCLUDE USING gist (tenant_id WITH =, asset_code WITH =, asset_version WITH =, name WITH =,
                      (daterange(effective_from, effective_to, '[)')) WITH &&)
);

CREATE TABLE bre_ctl.reference_list_meta (
  tenant_id     text    NOT NULL,
  asset_kind    text    NOT NULL DEFAULT 'REFERENCE_LIST' CHECK (asset_kind = 'REFERENCE_LIST'),
  asset_code    text    NOT NULL,
  asset_version integer NOT NULL,
  item_type     text    NOT NULL CHECK (item_type IN ('Text','Integer','Decimal','Date')),
  description   text,
  PRIMARY KEY (tenant_id, asset_code, asset_version),
  FOREIGN KEY (tenant_id, asset_kind, asset_code, asset_version)
    REFERENCES bre_ctl.asset_versions (tenant_id, asset_kind, asset_code, version)
);

CREATE TABLE bre_ctl.reference_list_items (
  tenant_id     text    NOT NULL,
  asset_kind    text    NOT NULL DEFAULT 'REFERENCE_LIST' CHECK (asset_kind = 'REFERENCE_LIST'),
  asset_code    text    NOT NULL,
  asset_version integer NOT NULL,
  item          text    NOT NULL,                                      -- normalised by item_type on insert
  PRIMARY KEY (tenant_id, asset_code, asset_version, item),
  FOREIGN KEY (tenant_id, asset_code, asset_version)
    REFERENCES bre_ctl.reference_list_meta (tenant_id, asset_code, asset_version)
);

CREATE TABLE bre_ctl.reason_codes (
  tenant_id     text    NOT NULL,
  asset_kind    text    NOT NULL DEFAULT 'REASON_CODE_CATALOGUE' CHECK (asset_kind = 'REASON_CODE_CATALOGUE'),
  asset_code    text    NOT NULL,
  asset_version integer NOT NULL,
  code          text    NOT NULL CHECK (code ~ '^[A-Z][A-Z0-9_]{1,63}$'
                                    AND code !~ '^(SYS_|DATA_MISSING(_|$)|DEV_NO_AUTHORITY$)'), -- reserved (CHK-016)
  category      text    NOT NULL CHECK (category IN ('KNOCKOUT','CREDIT','KYC','FRAUD','AFFORDABILITY',
                                                     'COLLATERAL','POLICY')),
  adverse       boolean NOT NULL,
  internal_text text    NOT NULL,
  customer_text jsonb   NOT NULL CHECK (jsonb_typeof(customer_text) = 'object' AND customer_text ? 'en'),
  PRIMARY KEY (tenant_id, asset_code, asset_version, code),
  FOREIGN KEY (tenant_id, asset_kind, asset_code, asset_version)
    REFERENCES bre_ctl.asset_versions (tenant_id, asset_kind, asset_code, version)
);

CREATE TABLE bre_ctl.authority_matrix_meta (
  tenant_id     text    NOT NULL,
  asset_kind    text    NOT NULL DEFAULT 'AUTHORITY_MATRIX' CHECK (asset_kind = 'AUTHORITY_MATRIX'),
  asset_code    text    NOT NULL,
  asset_version integer NOT NULL,
  routing_mode  text    NOT NULL CHECK (routing_mode IN ('HIGHEST_ONLY','EACH')),  -- copied from tenant at creation
  PRIMARY KEY (tenant_id, asset_code, asset_version),
  FOREIGN KEY (tenant_id, asset_kind, asset_code, asset_version)
    REFERENCES bre_ctl.asset_versions (tenant_id, asset_kind, asset_code, version)
);

CREATE TABLE bre_ctl.authority_levels (
  tenant_id     text     NOT NULL,
  asset_kind    text     NOT NULL DEFAULT 'AUTHORITY_MATRIX' CHECK (asset_kind = 'AUTHORITY_MATRIX'),
  asset_code    text     NOT NULL,
  asset_version integer  NOT NULL,
  level_code    text     NOT NULL CHECK (level_code ~ '^[A-Z][A-Z0-9_]{0,31}$'),
  rank          smallint NOT NULL CHECK (rank >= 1),                   -- higher = more senior
  PRIMARY KEY (tenant_id, asset_code, asset_version, level_code),
  UNIQUE (tenant_id, asset_code, asset_version, rank),
  FOREIGN KEY (tenant_id, asset_code, asset_version)
    REFERENCES bre_ctl.authority_matrix_meta (tenant_id, asset_code, asset_version)
);

CREATE TABLE bre_ctl.authority_entries (
  tenant_id      text          NOT NULL,
  asset_kind     text          NOT NULL DEFAULT 'AUTHORITY_MATRIX' CHECK (asset_kind = 'AUTHORITY_MATRIX'),
  asset_code     text          NOT NULL,
  asset_version  integer       NOT NULL,
  entry_no       integer       NOT NULL,
  deviation_type text          NOT NULL CHECK (deviation_type ~ '^[A-Z][A-Z0-9_]{1,63}$'),
  product_line   text          NOT NULL DEFAULT '*',
  segment        text          NOT NULL DEFAULT '*',
  amount_above   numeric(18,2),                                       -- exclusive lower bound; NULL = unbounded
  amount_up_to   numeric(18,2),                                       -- inclusive upper bound; NULL = unbounded
  level_code     text          NOT NULL,
  PRIMARY KEY (tenant_id, asset_code, asset_version, entry_no),
  FOREIGN KEY (tenant_id, asset_code, asset_version, level_code)
    REFERENCES bre_ctl.authority_levels (tenant_id, asset_code, asset_version, level_code),
  CHECK (amount_above IS NULL OR amount_above >= 0),
  CHECK (amount_above IS NULL OR amount_up_to IS NULL OR amount_up_to > amount_above),
  EXCLUDE USING gist (tenant_id WITH =, asset_code WITH =, asset_version WITH =, deviation_type WITH =,
                      product_line WITH =, segment WITH =,
                      (numrange(amount_above, amount_up_to, '(]')) WITH &&)
);

CREATE TABLE bre_ctl.fact_schemas (
  tenant_id    text        NOT NULL REFERENCES bre_ctl.tenants (tenant_id),
  schema_code  text        NOT NULL CHECK (schema_code ~ '^[a-z][a-z0-9-]{1,63}$'),
  version      text        NOT NULL CHECK (version ~ '^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$'),
  status       text        NOT NULL DEFAULT 'DRAFT'
                           CHECK (status IN ('DRAFT','IN_REVIEW','APPROVED','ARCHIVED','DISCARDED')),
  json_schema  jsonb       NOT NULL,
  content_hash text        CHECK (content_hash ~ '^sha256:[0-9a-f]{64}$'),
  base_version text,
  author       text        NOT NULL,
  approved_at  timestamptz,
  row_version  bigint      NOT NULL DEFAULT 1,
  created_at   timestamptz NOT NULL DEFAULT now(),
  updated_at   timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, schema_code, version),
  CHECK ((status IN ('APPROVED','ARCHIVED')) = (content_hash IS NOT NULL))
);

CREATE TABLE bre_ctl.governance_overrides (                          -- protected-attribute use (00 §3.9, BEL-017)
  tenant_id     text        NOT NULL REFERENCES bre_ctl.tenants (tenant_id),
  override_ref  text        NOT NULL CHECK (override_ref ~ '^GOV-[A-Z0-9-]{3,40}$'),
  fields        text[]      NOT NULL CHECK (cardinality(fields) >= 1),
  justification text        NOT NULL,
  status        text        NOT NULL DEFAULT 'REQUESTED'
                            CHECK (status IN ('REQUESTED','APPROVED','REJECTED','EXPIRED','REVOKED')),
  requested_by  text        NOT NULL,
  expires_on    date        NOT NULL,
  row_version   bigint      NOT NULL DEFAULT 1,
  created_at    timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, override_ref)
);

CREATE TABLE bre_ctl.governance_override_approvals (
  tenant_id     text        NOT NULL,
  override_ref  text        NOT NULL,
  approver      text        NOT NULL,
  approver_role text        NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, override_ref, approver),
  FOREIGN KEY (tenant_id, override_ref) REFERENCES bre_ctl.governance_overrides (tenant_id, override_ref)
);
