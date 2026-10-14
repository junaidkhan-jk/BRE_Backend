-- coverage, the submit evidence trail, and scheduled activation
--
-- Three things the control plane was missing, each of which the specification states plainly and
-- the first twelve migrations did not carry.
--
-- 1. FR-TEST-003 measures coverage and lets a tenant set a minimum for submit. Nothing stored a
--    coverage figure, so the submit gate could only check that tests PASSED — not that they
--    touched anything. A suite of one happy-path case passed that gate for a 300-rule policy.
--
-- 2. FR-LIFE-002 requires a change note and a committee reference at submit. These are the
--    evidence a reviewer and, later, a regulator read to answer "why was this changed, and who
--    agreed to it". They are enforced by a CHECK rather than by the application for the same
--    reason the segregation-of-duties triggers are: a direct UPDATE must not be able to move a
--    version into review without them.
--
-- 3. 00 §3.4's SCHEDULED state existed in the status CHECK and nothing ever set it: approving a
--    deployment activated it immediately, ignoring effective_from. A lender scheduling a policy
--    change for the start of the next business day got it live during the current one. Activation
--    now needs a claim, so two registry instances cannot both activate the same deployment.

-- ---------------------------------------------------------------- coverage
ALTER TABLE bre_ctl.compile_reports
  ADD COLUMN coverage jsonb NOT NULL DEFAULT '{}'::jsonb;              -- {ruleStatuses{}, rows{}, bins{}, overallPercent}

ALTER TABLE bre_ctl.tenants
  ADD COLUMN min_coverage_percent numeric(4,1) NOT NULL DEFAULT 80.0
    CHECK (min_coverage_percent >= 0 AND min_coverage_percent <= 100);

COMMENT ON COLUMN bre_ctl.tenants.min_coverage_percent IS
  'FR-TEST-003 submit threshold over rule statuses, table rows and scorecard bins pooled. 0 '
  'switches the gate off, which is a decision a tenant administrator makes and the audit log '
  'records — not a default.';

-- ---------------------------------------------------------------- submit evidence
ALTER TABLE bre_ctl.policy_versions
  ADD COLUMN change_note         text CHECK (change_note IS NULL OR length(change_note) BETWEEN 10 AND 4000),
  ADD COLUMN committee_reference text CHECK (committee_reference IS NULL OR length(committee_reference) BETWEEN 1 AND 200),
  ADD CONSTRAINT policy_versions_submit_evidence
    CHECK (status NOT IN ('IN_REVIEW','APPROVED','ARCHIVED')
           OR (change_note IS NOT NULL AND committee_reference IS NOT NULL));

COMMENT ON COLUMN bre_ctl.policy_versions.change_note IS
  'FR-LIFE-002. What changed and why, in the author''s words. Ten characters is a low bar '
  'deliberately: the point is to make "no note" impossible, not to judge the prose.';

-- ---------------------------------------------------------------- scheduled activation
ALTER TABLE bre_ctl.deployments
  ADD COLUMN scheduled_at timestamptz,                                 -- when approvals were met
  ADD COLUMN activation_claimed_by text;                               -- which instance is activating

COMMENT ON COLUMN bre_ctl.deployments.activation_claimed_by IS
  'Set under SELECT ... FOR UPDATE SKIP LOCKED by the instance that will activate this '
  'deployment. Two registry pods polling the same scheduled deployment is the normal case, not '
  'an edge one, and activating twice would publish two manifests and audit two activations.';

CREATE INDEX deployments_due ON bre_ctl.deployments (tenant_id, effective_from)
  WHERE status = 'SCHEDULED';

-- A scheduled deployment has to be found before any tenant is in scope: the activator is a timer,
-- not a request, so there is no principal and no bre.tenant_id. Same shape as V11's login lookup
-- and for the same reason — owned by bre_ctl_authn, which holds BYPASSRLS, because a SECURITY
-- DEFINER function owned by the TABLE's owner does not bypass FORCE ROW LEVEL SECURITY.
--
-- It returns tenant ids and nothing else. The activator then binds each tenant's scope and does
-- every read and write under ordinary RLS, so the privileged surface is one column of one query.
-- BYPASSRLS is not a table privilege. bre_ctl_authn can see past every row-level policy and still
-- cannot read a table it has no SELECT on — which surfaces as "permission denied for table
-- deployments" from inside the function, reported by Spring as "bad SQL grammar" on the outer
-- statement. V11 grants this role exactly the four tables its login functions read; this is the
-- fifth, and it is SELECT only.
GRANT SELECT ON bre_ctl.deployments TO bre_ctl_authn;

CREATE FUNCTION bre_ctl.tenants_with_due_deployments()
RETURNS TABLE (tenant_id text)
LANGUAGE sql SECURITY DEFINER SET search_path = pg_catalog, bre_ctl STABLE AS $$
  SELECT DISTINCT d.tenant_id
    FROM bre_ctl.deployments d
   WHERE d.status = 'SCHEDULED'
     AND d.effective_from <= now()
$$;
ALTER FUNCTION bre_ctl.tenants_with_due_deployments() OWNER TO bre_ctl_authn;
GRANT EXECUTE ON FUNCTION bre_ctl.tenants_with_due_deployments() TO bre_ctl_app;
