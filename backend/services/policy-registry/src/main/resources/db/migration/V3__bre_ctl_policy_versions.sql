-- bre ctl policy versions
--
-- Policy versions, their editors, pins, test cases, compile reports and reviews (LLD §5.2).
--
-- Extracted verbatim from the LLD's §5.2 DDL rather than retyped, so the schema and the
-- specification cannot drift. Split on the specification's own section boundaries.

-- ---------------------------------------------------------------- policy versions
CREATE TABLE bre_ctl.policy_versions (
  tenant_id           text        NOT NULL,
  policy_code         text        NOT NULL,
  version             integer     NOT NULL CHECK (version >= 1),
  status              text        NOT NULL DEFAULT 'DRAFT'
                                  CHECK (status IN ('DRAFT','IN_REVIEW','APPROVED','ARCHIVED','DISCARDED')),
  ir                  jsonb       NOT NULL,                            -- §2.2; canonicalised at submit (DRAFT→IN_REVIEW)
  fact_schema_code    text        NOT NULL,
  fact_schema_version text        NOT NULL,
  based_on_version    integer,
  submission_round    smallint    NOT NULL DEFAULT 0,
  bundle_hash         text        CHECK (bundle_hash ~ '^sha256:[0-9a-f]{64}$'),
  imported            boolean     NOT NULL DEFAULT false,
  submitted_at        timestamptz,
  approved_at         timestamptz,
  row_version         bigint      NOT NULL DEFAULT 1,
  created_by          text        NOT NULL,
  created_at          timestamptz NOT NULL DEFAULT now(),
  updated_at          timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, policy_code, version),
  FOREIGN KEY (tenant_id, policy_code) REFERENCES bre_ctl.policies (tenant_id, policy_code),
  FOREIGN KEY (tenant_id, fact_schema_code, fact_schema_version)
    REFERENCES bre_ctl.fact_schemas (tenant_id, schema_code, version),
  CHECK ((status IN ('APPROVED','ARCHIVED')) = (bundle_hash IS NOT NULL AND approved_at IS NOT NULL)),
  CHECK (status <> 'IN_REVIEW' OR submitted_at IS NOT NULL)
);

CREATE FUNCTION bre_ctl.tg_policy_version_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF OLD.status <> 'DRAFT' AND (NEW.ir IS DISTINCT FROM OLD.ir
                                OR NEW.fact_schema_version IS DISTINCT FROM OLD.fact_schema_version) THEN
    RAISE EXCEPTION 'policy version %/% is % and immutable', OLD.policy_code, OLD.version, OLD.status
          USING ERRCODE = 'P0001';
  END IF;
  IF NEW.status <> OLD.status AND NOT ((OLD.status, NEW.status) IN
       (('DRAFT','IN_REVIEW'), ('IN_REVIEW','DRAFT'), ('IN_REVIEW','APPROVED'),
        ('DRAFT','DISCARDED'), ('APPROVED','ARCHIVED'))) THEN
    RAISE EXCEPTION 'illegal policy version transition % -> %', OLD.status, NEW.status USING ERRCODE = 'P0001';
  END IF;
  NEW.row_version := OLD.row_version + 1;
  NEW.updated_at  := now();
  RETURN NEW;
END $$;
CREATE TRIGGER policy_versions_guard BEFORE UPDATE ON bre_ctl.policy_versions
  FOR EACH ROW EXECUTE FUNCTION bre_ctl.tg_policy_version_guard();

CREATE TABLE bre_ctl.policy_version_editors (                        -- everyone who saved the DRAFT (SoD)
  tenant_id      text        NOT NULL,
  policy_code    text        NOT NULL,
  policy_version integer     NOT NULL,
  editor         text        NOT NULL,
  first_edit_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, policy_code, policy_version, editor),
  FOREIGN KEY (tenant_id, policy_code, policy_version)
    REFERENCES bre_ctl.policy_versions (tenant_id, policy_code, version)
);

CREATE TABLE bre_ctl.policy_version_pins (
  tenant_id      text    NOT NULL,
  policy_code    text    NOT NULL,
  policy_version integer NOT NULL,
  asset_kind     text    NOT NULL,
  asset_code     text    NOT NULL,
  asset_version  integer NOT NULL,
  PRIMARY KEY (tenant_id, policy_code, policy_version, asset_kind, asset_code),
  FOREIGN KEY (tenant_id, policy_code, policy_version)
    REFERENCES bre_ctl.policy_versions (tenant_id, policy_code, version),
  FOREIGN KEY (tenant_id, asset_kind, asset_code, asset_version)
    REFERENCES bre_ctl.asset_versions (tenant_id, asset_kind, asset_code, version)
);
CREATE UNIQUE INDEX policy_version_pins_one_per_kind
  ON bre_ctl.policy_version_pins (tenant_id, policy_code, policy_version, asset_kind)
  WHERE asset_kind <> 'REFERENCE_LIST';

CREATE TABLE bre_ctl.test_cases (
  tenant_id      text        NOT NULL,
  policy_code    text        NOT NULL,
  policy_version integer     NOT NULL,
  test_name      text        NOT NULL CHECK (length(test_name) BETWEEN 1 AND 200),
  as_of          date        NOT NULL,
  facts          jsonb       NOT NULL,                                 -- synthetic only (§9.7)
  expect         jsonb       NOT NULL,
  smoke          boolean     NOT NULL DEFAULT false,
  fixture_sha256 text        CHECK (fixture_sha256 ~ '^[0-9a-f]{64}$'),
  created_at     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, policy_code, policy_version, test_name),
  FOREIGN KEY (tenant_id, policy_code, policy_version)
    REFERENCES bre_ctl.policy_versions (tenant_id, policy_code, version)
);

CREATE TABLE bre_ctl.compile_reports (                               -- latest static-check + test result per save
  tenant_id      text        NOT NULL,
  policy_code    text        NOT NULL,
  policy_version integer     NOT NULL,
  report_no      integer     NOT NULL,
  engine_version text        NOT NULL,
  ok             boolean     NOT NULL,
  findings       jsonb       NOT NULL,                                 -- [{code, severity, path, message, witness?}]
  tests          jsonb       NOT NULL,                                 -- {total, passed, failures:[...]}
  created_at     timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, policy_code, policy_version, report_no),
  FOREIGN KEY (tenant_id, policy_code, policy_version)
    REFERENCES bre_ctl.policy_versions (tenant_id, policy_code, version)
);

CREATE TABLE bre_ctl.policy_version_reviews (
  tenant_id        text        NOT NULL,
  policy_code      text        NOT NULL,
  policy_version   integer     NOT NULL,
  submission_round smallint    NOT NULL,
  reviewer         text        NOT NULL,
  decision         text        NOT NULL CHECK (decision IN ('APPROVE','REQUEST_CHANGES')),
  comment          text,
  created_at       timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, policy_code, policy_version, submission_round, reviewer),
  FOREIGN KEY (tenant_id, policy_code, policy_version)
    REFERENCES bre_ctl.policy_versions (tenant_id, policy_code, version)
);

-- children of a version are editable only while it is DRAFT
CREATE FUNCTION bre_ctl.tg_policy_child_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r record; s text;
BEGIN
  IF TG_OP = 'DELETE' THEN r := OLD; ELSE r := NEW; END IF;
  SELECT status INTO s FROM bre_ctl.policy_versions
   WHERE tenant_id = r.tenant_id AND policy_code = r.policy_code AND version = r.policy_version;
  IF s IS DISTINCT FROM 'DRAFT' THEN
    RAISE EXCEPTION 'policy version %/% is % and immutable', r.policy_code, r.policy_version, s
          USING ERRCODE = 'P0001';
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER test_cases_guard   BEFORE INSERT OR UPDATE OR DELETE ON bre_ctl.test_cases
  FOR EACH ROW EXECUTE FUNCTION bre_ctl.tg_policy_child_guard();
CREATE TRIGGER pins_guard         BEFORE INSERT OR UPDATE OR DELETE ON bre_ctl.policy_version_pins
  FOR EACH ROW EXECUTE FUNCTION bre_ctl.tg_policy_child_guard();
CREATE TRIGGER editors_guard      BEFORE INSERT OR UPDATE OR DELETE ON bre_ctl.policy_version_editors
  FOR EACH ROW EXECUTE FUNCTION bre_ctl.tg_policy_child_guard();

CREATE FUNCTION bre_ctl.tg_asset_child_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE r record; s text;
BEGIN
  IF TG_OP = 'DELETE' THEN r := OLD; ELSE r := NEW; END IF;
  SELECT status INTO s FROM bre_ctl.asset_versions
   WHERE tenant_id = r.tenant_id AND asset_kind = r.asset_kind
     AND asset_code = r.asset_code AND version = r.asset_version;
  IF s IS DISTINCT FROM 'DRAFT' THEN
    RAISE EXCEPTION 'asset %/%@% is % and immutable', r.asset_kind, r.asset_code, r.asset_version, s
          USING ERRCODE = 'P0001';
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER parameters_guard       BEFORE INSERT OR UPDATE OR DELETE ON bre_ctl.parameters
  FOR EACH ROW EXECUTE FUNCTION bre_ctl.tg_asset_child_guard();
CREATE TRIGGER parameter_values_guard BEFORE INSERT OR UPDATE OR DELETE ON bre_ctl.parameter_values
  FOR EACH ROW EXECUTE FUNCTION bre_ctl.tg_asset_child_guard();
CREATE TRIGGER list_meta_guard        BEFORE INSERT OR UPDATE OR DELETE ON bre_ctl.reference_list_meta
  FOR EACH ROW EXECUTE FUNCTION bre_ctl.tg_asset_child_guard();
CREATE TRIGGER list_items_guard       BEFORE INSERT OR UPDATE OR DELETE ON bre_ctl.reference_list_items
  FOR EACH ROW EXECUTE FUNCTION bre_ctl.tg_asset_child_guard();
CREATE TRIGGER reason_codes_guard     BEFORE INSERT OR UPDATE OR DELETE ON bre_ctl.reason_codes
  FOR EACH ROW EXECUTE FUNCTION bre_ctl.tg_asset_child_guard();
CREATE TRIGGER matrix_meta_guard      BEFORE INSERT OR UPDATE OR DELETE ON bre_ctl.authority_matrix_meta
  FOR EACH ROW EXECUTE FUNCTION bre_ctl.tg_asset_child_guard();
CREATE TRIGGER levels_guard           BEFORE INSERT OR UPDATE OR DELETE ON bre_ctl.authority_levels
  FOR EACH ROW EXECUTE FUNCTION bre_ctl.tg_asset_child_guard();
CREATE TRIGGER entries_guard          BEFORE INSERT OR UPDATE OR DELETE ON bre_ctl.authority_entries
  FOR EACH ROW EXECUTE FUNCTION bre_ctl.tg_asset_child_guard();

-- segregation of duties (defence in depth; the services check first)
CREATE FUNCTION bre_ctl.tg_policy_review_sod() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF EXISTS (SELECT 1 FROM bre_ctl.policy_version_editors e
              WHERE e.tenant_id = NEW.tenant_id AND e.policy_code = NEW.policy_code
                AND e.policy_version = NEW.policy_version AND e.editor = NEW.reviewer) THEN
    RAISE EXCEPTION 'segregation of duties: % edited %/% and cannot review it',
          NEW.reviewer, NEW.policy_code, NEW.policy_version USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER policy_reviews_sod BEFORE INSERT ON bre_ctl.policy_version_reviews
  FOR EACH ROW EXECUTE FUNCTION bre_ctl.tg_policy_review_sod();

CREATE FUNCTION bre_ctl.tg_asset_review_sod() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF EXISTS (SELECT 1 FROM bre_ctl.asset_versions a
              WHERE a.tenant_id = NEW.tenant_id AND a.asset_kind = NEW.asset_kind
                AND a.asset_code = NEW.asset_code AND a.version = NEW.asset_version
                AND a.author = NEW.reviewer) THEN
    RAISE EXCEPTION 'segregation of duties: % authored the asset and cannot review it', NEW.reviewer
          USING ERRCODE = 'P0001';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER asset_reviews_sod BEFORE INSERT ON bre_ctl.asset_version_reviews
  FOR EACH ROW EXECUTE FUNCTION bre_ctl.tg_asset_review_sod();
