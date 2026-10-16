-- Per-tenant quotas (M3-08, FR-ADM-001, §4.9, §6.4).
--
-- The tenants table already carries a tenant's SETTINGS — time zone, retention, reviewer counts.
-- A quota is a different kind of thing and belongs apart from them: a setting is what a lender
-- chose, a quota is what the platform will give them. Mixing the two in one table makes the
-- TENANT_SETTINGS capability, which a lender's own administrator holds, into the capability that
-- raises their own rate limit.
CREATE TABLE bre_ctl.tenant_quotas (
  tenant_id            text     NOT NULL PRIMARY KEY REFERENCES bre_ctl.tenants (tenant_id),
  -- §6.4 control 1: the gateway's token bucket, in decisions per second plus a burst.
  decisions_per_second integer  NOT NULL DEFAULT 100 CHECK (decisions_per_second BETWEEN 1 AND 100000),
  burst_decisions      integer  NOT NULL DEFAULT 200 CHECK (burst_decisions BETWEEN 1 AND 200000),
  -- How many bulk jobs this tenant may run at once. Bulk is priority class P2 and shares a cell;
  -- without a cap one tenant's overnight batch is every other tenant's morning.
  bulk_concurrency     smallint NOT NULL DEFAULT 2 CHECK (bulk_concurrency BETWEEN 0 AND 64),
  -- §6.1 bundle memory. A tenant whose policies exceed their share would otherwise evict another
  -- tenant's CHAMPION bundle from a shared pod's cache, which is a latency problem for somebody
  -- who did nothing.
  bundle_memory_mb     integer  NOT NULL DEFAULT 256 CHECK (bundle_memory_mb BETWEEN 16 AND 16384),
  updated_by           text     NOT NULL,
  updated_at           timestamptz NOT NULL DEFAULT now(),
  row_version          bigint   NOT NULL DEFAULT 1,
  -- A burst below the sustained rate is a configuration that can only ever reject traffic the
  -- tenant is entitled to. Enforced here rather than in the application because a direct UPDATE
  -- must not be able to create it either.
  CHECK (burst_decisions >= decisions_per_second)
);

COMMENT ON TABLE bre_ctl.tenant_quotas IS
  'What the PLATFORM grants a tenant, as opposed to what the tenant chose. Separate from the '
  'tenants table so TENANT_SETTINGS — which a lender administers — is not the capability that '
  'raises their own rate limit.';

ALTER TABLE bre_ctl.tenant_quotas ENABLE ROW LEVEL SECURITY;
ALTER TABLE bre_ctl.tenant_quotas FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bre_ctl.tenant_quotas
  USING (tenant_id = current_setting('bre.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('bre.tenant_id', true));

GRANT SELECT, INSERT, UPDATE ON bre_ctl.tenant_quotas TO bre_ctl_app, bre_ctl_platform;

-- Provisioning a tenant has to happen BEFORE that tenant exists, so there is no bre.tenant_id to
-- bind and row-level security has nothing to compare against. Same shape and same reasoning as
-- V11's login lookup and V13's activation scan: owned by bre_ctl_authn for BYPASSRLS, doing one
-- narrow thing, and every subsequent read and write happens under ordinary RLS.
--
-- It is a FUNCTION rather than a grant on the tables because the alternative is giving the
-- application role the ability to write any tenant's row at any time, to solve a problem that
-- exists only for the few milliseconds before a tenant has an identity.
CREATE FUNCTION bre_ctl.provision_tenant(
    p_tenant_id text,
    p_display_name text,
    p_cell_id smallint,
    p_time_zone text,
    p_created_by text)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = pg_catalog, bre_ctl AS $$
BEGIN
  INSERT INTO bre_ctl.tenants (tenant_id, display_name, cell_id, time_zone, status)
  VALUES (p_tenant_id, p_display_name, p_cell_id, p_time_zone, 'ONBOARDING');

  -- DEV 0, UAT 1, PROD 2 approvers (00 §3.4). Created with the tenant rather than left to a
  -- second call, because a tenant with no environments cannot deploy anything and a half-created
  -- tenant is the state an operator discovers at the worst moment.
  INSERT INTO bre_ctl.environments
      (tenant_id, env_code, is_production, promotion_order, min_deployment_approvers,
       dry_run_allowed)
  VALUES
      (p_tenant_id, 'DEV',  false, 1, 0, true),
      (p_tenant_id, 'UAT',  false, 2, 1, true),
      (p_tenant_id, 'PROD', true,  3, 2, false);

  INSERT INTO bre_ctl.tenant_quotas (tenant_id, updated_by)
  VALUES (p_tenant_id, p_created_by);
END $$;
ALTER FUNCTION bre_ctl.provision_tenant(text, text, smallint, text, text) OWNER TO bre_ctl_authn;
GRANT EXECUTE ON FUNCTION bre_ctl.provision_tenant(text, text, smallint, text, text)
  TO bre_ctl_app;

GRANT INSERT ON bre_ctl.tenants, bre_ctl.environments, bre_ctl.tenant_quotas TO bre_ctl_authn;
