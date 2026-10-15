-- bre.studio.audit.v1 delivery, in compact mode (M3-10, FR-AUD-005).
--
-- **There is no outbox table here, and that is the design rather than an omission.** The usual
-- shape — a row copied into an outbox at the same time as the thing it describes — exists to make
-- a stream out of state that is otherwise overwritten in place. The audit log is not that: it is
-- already append-only, already gapless, already sequenced per tenant by a trigger, and already
-- forbidden to UPDATE or DELETE. Copying every entry into a second table would double the writes,
-- double the storage, and introduce the one failure mode neither table has on its own — an outbox
-- that disagrees with the log, where the published trail is not the recorded trail.
--
-- So `audit_log.seq` IS the cursor. It is a better one than the decision outbox's id: the trigger
-- assigns it under FOR UPDATE on the chain head, so it is gapless by construction, and a consumer
-- that has seen seq 400 knows it has seen everything — a gap means tampering rather than a
-- concurrent writer.
--
-- This table therefore holds one row per tenant: how far delivery has got, and a lease so two
-- registry replicas cannot deliver the same entries in two orders.

CREATE TABLE bre_ctl.studio_audit_delivery (
  tenant_id     text        NOT NULL PRIMARY KEY REFERENCES bre_ctl.tenants (tenant_id),
  delivered_seq bigint      NOT NULL DEFAULT 0 CHECK (delivered_seq >= 0),
  attempts      integer     NOT NULL DEFAULT 0,
  last_error    text,
  next_attempt_at timestamptz NOT NULL DEFAULT now(),
  leased_by     text,
  leased_until  timestamptz NOT NULL DEFAULT now(),
  updated_at    timestamptz NOT NULL DEFAULT now()
);

COMMENT ON COLUMN bre_ctl.studio_audit_delivery.delivered_seq IS
  'The highest audit_log.seq this tenant''s consumer has accepted. Never decreases.';
COMMENT ON COLUMN bre_ctl.studio_audit_delivery.next_attempt_at IS
  'Exponential backoff after a refusal. A consumer that is down must not be retried every two '
  'seconds for an hour.';
COMMENT ON COLUMN bre_ctl.studio_audit_delivery.leased_until IS
  'A lease, not a lock: delivery happens with NO transaction held, because holding one across an '
  'HTTP call is how a connection pool dies — observed, not theorised. See OutboxRelay.';

ALTER TABLE bre_ctl.studio_audit_delivery ENABLE ROW LEVEL SECURITY;
ALTER TABLE bre_ctl.studio_audit_delivery FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bre_ctl.studio_audit_delivery
  USING (tenant_id = current_setting('bre.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('bre.tenant_id', true));

GRANT SELECT, INSERT, UPDATE ON bre_ctl.studio_audit_delivery TO bre_ctl_app, bre_ctl_platform;

-- Undelivered entries have to be found before any tenant is in scope, because the relay is a
-- timer and has no principal. Same shape and same reasoning as V13's tenants_with_due_deployments:
-- owned by bre_ctl_authn for BYPASSRLS, returning tenant ids and nothing else, so the privileged
-- surface is one column of one query and every subsequent read happens under ordinary RLS.
--
-- The LEFT JOIN is what makes a tenant with no delivery row yet appear: its first audit entry is
-- undelivered, and treating "no row" as "nothing to do" would mean a tenant never starts.
GRANT SELECT ON bre_ctl.audit_log, bre_ctl.studio_audit_delivery TO bre_ctl_authn;

CREATE FUNCTION bre_ctl.tenants_with_undelivered_audit()
RETURNS TABLE (tenant_id text)
LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, bre_ctl STABLE AS $$
  SELECT h.tenant_id
    FROM bre_ctl.audit_chain_heads h
    LEFT JOIN bre_ctl.studio_audit_delivery d ON d.tenant_id = h.tenant_id
   WHERE h.last_seq > coalesce(d.delivered_seq, 0)
     AND coalesce(d.next_attempt_at, now()) <= now()
$$;
ALTER FUNCTION bre_ctl.tenants_with_undelivered_audit() OWNER TO bre_ctl_authn;
GRANT EXECUTE ON FUNCTION bre_ctl.tenants_with_undelivered_audit() TO bre_ctl_app;

GRANT SELECT ON bre_ctl.audit_chain_heads TO bre_ctl_authn;
