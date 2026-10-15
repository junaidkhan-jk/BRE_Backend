-- FR-AUD-005: "who, when, what (a diff), why (a note) and FROM WHERE (IP and client)".
--
-- Who, when, what and why were all recorded. From where was not, and it is the half that answers
-- a different question from the rest: not "what did this person do" but "was it this person".
-- A reviewer's approval arriving from an IP in a country the lender does not operate in is the
-- finding an audit trail exists to surface, and until now the trail could not show it.

ALTER TABLE bre_ctl.audit_log
  ADD COLUMN source_ip inet,
  ADD COLUMN client    text CHECK (client IS NULL OR length(client) <= 400);

COMMENT ON COLUMN bre_ctl.audit_log.source_ip IS
  'Where the request came from. NULL for a SYSTEM entry — a scheduled activation has no caller.';
COMMENT ON COLUMN bre_ctl.audit_log.client IS
  'The User-Agent, truncated. Evidence of WHICH client, not a trusted identity: a caller sets it.';

-- The chain covers the new columns, because a provenance field that is not hashed is one somebody
-- can rewrite — and the whole point of the chain is that nobody can.
--
-- APPENDED to the end of the hash input, and this is the load-bearing detail: concat_ws SKIPS null
-- arguments, so for an existing row (both columns null) the string it builds is byte-identical to
-- what the previous formula built. Every entry written before this migration therefore still
-- verifies, and every entry after it carries its provenance into the hash. Putting the columns
-- anywhere but last, or using concat instead of concat_ws, would invalidate the whole existing
-- chain — which reads exactly like tampering.
CREATE OR REPLACE FUNCTION bre_ctl.tg_audit_chain() RETURNS trigger
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
                        NEW.detail::text,
                        NEW.source_ip::text, NEW.client), 'UTF8'));
  UPDATE bre_ctl.audit_chain_heads SET last_seq = NEW.seq, last_hash = NEW.entry_hash
   WHERE tenant_id = NEW.tenant_id;
  RETURN NEW;
END $$;
ALTER FUNCTION bre_ctl.tg_audit_chain() OWNER TO bre_ctl_owner;
