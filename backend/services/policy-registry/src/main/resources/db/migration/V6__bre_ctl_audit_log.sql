-- bre ctl audit log
--
-- The hash-chained audit log (LLD §5.2, §10.4).
--
-- Extracted verbatim from the LLD's §5.2 DDL rather than retyped, so the schema and the
-- specification cannot drift. Split on the specification's own section boundaries.

-- ---------------------------------------------------------------- hash-chained audit log
CREATE TABLE bre_ctl.audit_chain_heads (
  tenant_id text   PRIMARY KEY REFERENCES bre_ctl.tenants (tenant_id),
  last_seq  bigint NOT NULL DEFAULT 0,
  last_hash bytea  NOT NULL DEFAULT decode(repeat('00', 32), 'hex')
);

CREATE TABLE bre_ctl.audit_log (
  tenant_id    text        NOT NULL REFERENCES bre_ctl.tenants (tenant_id),
  seq          bigint      NOT NULL,
  occurred_at  timestamptz NOT NULL,
  actor        text        NOT NULL,
  actor_type   text        NOT NULL CHECK (actor_type IN ('USER','SERVICE','SYSTEM')),
  action       text        NOT NULL,                                   -- e.g. POLICY_VERSION.APPROVED
  subject_kind text        NOT NULL,
  subject_key  text        NOT NULL,
  detail       jsonb       NOT NULL,                                   -- never contains fact values
  prev_hash    bytea       NOT NULL,
  entry_hash   bytea       NOT NULL,
  PRIMARY KEY (tenant_id, seq)
);

CREATE FUNCTION bre_ctl.tg_audit_chain() RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, bre_ctl AS $$
DECLARE h bre_ctl.audit_chain_heads%ROWTYPE;
BEGIN
  INSERT INTO bre_ctl.audit_chain_heads (tenant_id) VALUES (NEW.tenant_id) ON CONFLICT (tenant_id) DO NOTHING;
  SELECT * INTO h FROM bre_ctl.audit_chain_heads WHERE tenant_id = NEW.tenant_id FOR UPDATE;
  NEW.seq         := h.last_seq + 1;
  NEW.occurred_at := clock_timestamp();
  NEW.prev_hash   := h.last_hash;
  NEW.entry_hash  := sha256(h.last_hash || convert_to(concat_ws(E'\x1f',
                        NEW.tenant_id, NEW.seq::text,
                        to_char(NEW.occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'),
                        NEW.actor, NEW.actor_type, NEW.action, NEW.subject_kind, NEW.subject_key,
                        NEW.detail::text), 'UTF8'));
  UPDATE bre_ctl.audit_chain_heads SET last_seq = NEW.seq, last_hash = NEW.entry_hash
   WHERE tenant_id = NEW.tenant_id;
  RETURN NEW;
END $$;
ALTER FUNCTION bre_ctl.tg_audit_chain() OWNER TO bre_ctl_owner;
CREATE TRIGGER audit_log_chain       BEFORE INSERT ON bre_ctl.audit_log
  FOR EACH ROW EXECUTE FUNCTION bre_ctl.tg_audit_chain();
CREATE TRIGGER audit_log_no_mutation BEFORE UPDATE OR DELETE ON bre_ctl.audit_log
  FOR EACH ROW EXECUTE FUNCTION bre_ctl.tg_forbid();
CREATE TRIGGER audit_log_no_truncate BEFORE TRUNCATE ON bre_ctl.audit_log
  FOR EACH STATEMENT EXECUTE FUNCTION bre_ctl.tg_forbid();
