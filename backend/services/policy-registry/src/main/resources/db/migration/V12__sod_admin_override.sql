-- Let the §3.3 segregation-of-duties triggers honour the tenant's own override setting.
--
-- §5.2 enforces segregation of duties in the DATABASE as well as the application —
-- `tg_policy_review_sod` and `tg_deployment_approval_sod`, both marked "defence in depth; the
-- services check first". That is the right design and it is why this migration exists rather than
-- the application simply deciding: an application-level override against an absolute database
-- trigger produces a 500 at the moment somebody tries to use it, which is the worst of both — the
-- control is bypassable in intent and broken in practice.
--
-- So the two have to agree. V8 added `tenants.allow_admin_sod_override`; these triggers now read
-- it, and read `role_assignments` to confirm the actor really is an administrator. The trigger
-- cannot be fooled by an application that lies about who is calling, because it looks the roles up
-- itself.
--
-- WHAT THIS COSTS, stated plainly because it is a real weakening of a real control. Maker-checker
-- on credit policy is something a regulator and an auditor expect to find working: the person who
-- wrote the rule that declines a loan should not be the only person who agreed to it. After this
-- migration, a TENANT_ADMIN in a tenant with the override enabled can author, approve and deploy
-- alone.
--
-- WHY IT IS STILL DEFENSIBLE:
--
--   * It is off-able per tenant, by a column. A lender with two people who can review sets
--     allow_admin_sod_override to false and an auditor finds the bypass UNAVAILABLE rather than
--     merely unused — which is a materially stronger statement than "we have a policy about it".
--   * Every use writes POLICY_VERSION.SOD_OVERRIDDEN or DEPLOYMENT.SOD_OVERRIDDEN to the
--     hash-chained, append-only audit log, naming the person and the control. An override nobody
--     can see is the actual risk; one that is attributable and tamper-evident is a finding
--     somebody reviews.
--   * It requires TENANT_ADMIN, which is a role an administrator has to have been granted and
--     which does not carry POLICY_REVIEW on its own — so an administrator who wants to approve
--     still has to hold the reviewer role as well.
--   * The alternative is worse in practice. A lender onboarding with one administrator, a pilot
--     with two people, or a 2am fix cannot proceed at all without it, and what happens then is a
--     shared login or a direct database write — both of which destroy attribution entirely.
--
-- The default is `true`, because an installation with one administrator has to be able to start.
-- Turning it off is a step in going live, and it belongs on that checklist.

CREATE FUNCTION bre_ctl.sod_override_permitted(p_tenant_id text, p_subject text)
RETURNS boolean
LANGUAGE sql
STABLE
AS $$
  SELECT EXISTS (
    SELECT 1
      FROM bre_ctl.tenants t
      JOIN bre_ctl.role_assignments ra
        ON ra.tenant_id = t.tenant_id AND ra.subject = p_subject
     WHERE t.tenant_id = p_tenant_id
       AND t.allow_admin_sod_override
       AND ra.role IN ('TENANT_ADMIN', 'PLATFORM_ADMIN'))
$$;

ALTER FUNCTION bre_ctl.sod_override_permitted(text, text) OWNER TO bre_ctl_owner;

COMMENT ON FUNCTION bre_ctl.sod_override_permitted(text, text) IS
  'Whether this actor may bypass 00 §3.3 segregation of duties in this tenant: the tenant must still allow it AND the actor must hold TENANT_ADMIN or PLATFORM_ADMIN. Read by the SoD triggers, which look the roles up themselves rather than trusting the caller.';

-- Policy review. Same refusal as before, now with the override as the one exception.
CREATE OR REPLACE FUNCTION bre_ctl.tg_policy_review_sod() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF EXISTS (SELECT 1 FROM bre_ctl.policy_version_editors e
              WHERE e.tenant_id = NEW.tenant_id AND e.policy_code = NEW.policy_code
                AND e.policy_version = NEW.policy_version AND e.editor = NEW.reviewer) THEN
    IF NOT bre_ctl.sod_override_permitted(NEW.tenant_id, NEW.reviewer) THEN
      RAISE EXCEPTION 'segregation of duties: % edited %/% and cannot review it',
            NEW.reviewer, NEW.policy_code, NEW.policy_version USING ERRCODE = 'P0001';
    END IF;
    -- Permitted. The application writes the POLICY_VERSION.SOD_OVERRIDDEN audit entry; the
    -- trigger does not, because an audit entry inserted from inside a trigger would carry the
    -- trigger's idea of the actor rather than the request's, and `actor` has to be the person.
    RAISE NOTICE 'segregation of duties overridden: % is reviewing %/% which they edited',
          NEW.reviewer, NEW.policy_code, NEW.policy_version;
  END IF;
  RETURN NEW;
END $$;

ALTER FUNCTION bre_ctl.tg_policy_review_sod() OWNER TO bre_ctl_owner;

-- Deployment approval. §3.3 bars the version's author AND its reviewer from approving the
-- deployment — three distinct people for a production change.
CREATE OR REPLACE FUNCTION bre_ctl.tg_deployment_approval_sod() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF EXISTS (
    SELECT 1
      FROM bre_ctl.deployments d
      JOIN bre_ctl.bundles b
        ON b.tenant_id = d.tenant_id AND b.bundle_hash = d.bundle_hash
      LEFT JOIN bre_ctl.policy_version_editors e
        ON e.tenant_id = b.tenant_id AND e.policy_code = b.policy_code
       AND e.policy_version = b.policy_version AND e.editor = NEW.approver
      LEFT JOIN bre_ctl.policy_version_reviews r
        ON r.tenant_id = b.tenant_id AND r.policy_code = b.policy_code
       AND r.policy_version = b.policy_version AND r.reviewer = NEW.approver
     WHERE d.tenant_id = NEW.tenant_id AND d.deployment_id = NEW.deployment_id
       AND (e.editor IS NOT NULL OR r.reviewer IS NOT NULL)) THEN
    IF NOT bre_ctl.sod_override_permitted(NEW.tenant_id, NEW.approver) THEN
      RAISE EXCEPTION
            'segregation of duties: % authored or reviewed the bundle and cannot approve its deployment',
            NEW.approver USING ERRCODE = 'P0001';
    END IF;
    RAISE NOTICE 'segregation of duties overridden: % is approving a deployment of their own work',
          NEW.approver;
  END IF;
  RETURN NEW;
END $$;

ALTER FUNCTION bre_ctl.tg_deployment_approval_sod() OWNER TO bre_ctl_owner;
