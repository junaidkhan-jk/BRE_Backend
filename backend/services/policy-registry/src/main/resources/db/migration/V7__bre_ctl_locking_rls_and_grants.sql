-- bre ctl locking rls and grants
--
-- Optimistic-locking triggers, row-level security and grants (LLD §5.1, §5.2).
--
-- RLS is ENABLED and FORCED on every tenant-owned table, and the policy compares tenant_id
-- with current_setting('bre.tenant_id', true). The second argument matters: with it, an unset
-- setting returns NULL rather than raising, and `tenant_id = NULL` is NULL, which RLS treats as
-- false — so a session that forgot to set the tenant sees no rows instead of seeing everything.
-- That is the fail-closed half of D1, and it is why TenantContext.require() throws.
--
-- Extracted verbatim from the LLD's §5.2 DDL rather than retyped, so the schema and the
-- specification cannot drift. Split on the specification's own section boundaries.

-- ---------------------------------------------------------------- optimistic-locking triggers
CREATE TRIGGER tenants_touch        BEFORE UPDATE ON bre_ctl.tenants        FOR EACH ROW EXECUTE FUNCTION bre_ctl.tg_touch();
CREATE TRIGGER policies_touch       BEFORE UPDATE ON bre_ctl.policies       FOR EACH ROW EXECUTE FUNCTION bre_ctl.tg_touch();
CREATE TRIGGER asset_versions_touch BEFORE UPDATE ON bre_ctl.asset_versions FOR EACH ROW EXECUTE FUNCTION bre_ctl.tg_touch();
CREATE TRIGGER fact_schemas_touch   BEFORE UPDATE ON bre_ctl.fact_schemas   FOR EACH ROW EXECUTE FUNCTION bre_ctl.tg_touch();
CREATE TRIGGER overrides_touch      BEFORE UPDATE ON bre_ctl.governance_overrides FOR EACH ROW EXECUTE FUNCTION bre_ctl.tg_touch();
CREATE TRIGGER sim_runs_touch       BEFORE UPDATE ON bre_ctl.simulation_runs FOR EACH ROW EXECUTE FUNCTION bre_ctl.tg_touch();

-- ---------------------------------------------------------------- row-level security
DO $$
DECLARE t text;
BEGIN
  FOREACH t IN ARRAY ARRAY[
    'tenants','role_assignments','environments','policies','policy_resolution_keys','asset_versions',
    'asset_version_reviews','parameters','parameter_values','reference_list_meta','reference_list_items',
    'reason_codes','authority_matrix_meta','authority_levels','authority_entries','fact_schemas',
    'governance_overrides','governance_override_approvals','policy_versions','policy_version_editors',
    'policy_version_pins','test_cases','compile_reports','policy_version_reviews','bundles','deployments',
    'deployment_approvals','resolution_index','simulation_runs','audit_log',
    -- DEVIATION from §5.2, and a necessary one. audit_chain_heads is keyed BY tenant_id and the
    -- specification's list omits it, so as written the table has no row-level security: one
    -- tenant's session can read another tenant's audit-chain head (last_seq, last_hash). The
    -- grants do revoke INSERT and UPDATE from bre_ctl_app, so the chain cannot be corrupted
    -- across tenants, but SELECT is granted on every table in the schema — so the state of one
    -- lender's audit chain is readable by another. Found by executing the DDL; see
    -- docs/OPEN-QUESTIONS.md Q7.
    'audit_chain_heads']
  LOOP
    EXECUTE format('ALTER TABLE bre_ctl.%I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE bre_ctl.%I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format($f$CREATE POLICY tenant_isolation ON bre_ctl.%I
                        USING (tenant_id = current_setting('bre.tenant_id', true))
                        WITH CHECK (tenant_id = current_setting('bre.tenant_id', true))$f$, t);
  END LOOP;
END $$;

-- ---------------------------------------------------------------- grants
GRANT USAGE ON SCHEMA bre_ctl TO bre_ctl_app, bre_ctl_platform, bre_ctl_auditor;
GRANT SELECT, INSERT, UPDATE ON ALL TABLES IN SCHEMA bre_ctl TO bre_ctl_app, bre_ctl_platform;
REVOKE UPDATE ON bre_ctl.audit_log, bre_ctl.policy_version_reviews, bre_ctl.asset_version_reviews,
                 bre_ctl.deployment_approvals, bre_ctl.governance_override_approvals, bre_ctl.audit_chain_heads,
                 bre_ctl.cells, bre_ctl.pointer_manifests
  FROM bre_ctl_app;
REVOKE INSERT ON bre_ctl.audit_chain_heads, bre_ctl.cells FROM bre_ctl_app;
GRANT DELETE ON bre_ctl.test_cases, bre_ctl.policy_version_pins, bre_ctl.parameters, bre_ctl.parameter_values,
                bre_ctl.reference_list_items, bre_ctl.reason_codes, bre_ctl.authority_levels,
                bre_ctl.authority_entries, bre_ctl.resolution_index
  TO bre_ctl_app;                                                     -- DRAFT-only, guarded by triggers
GRANT SELECT ON ALL TABLES IN SCHEMA bre_ctl TO bre_ctl_auditor;
