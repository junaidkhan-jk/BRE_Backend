-- Three things the first real run of these migrations through an application exposed.
--
-- V1 to V7 had only ever been executed by a test that migrated and then queried as a superuser.
-- Starting the service against them found all three of these within a minute of each other, and
-- every one of them is invisible to a superuser.

-- ---------------------------------------------------------------- 1. table ownership
--
-- §5.2's DDL is written to be run AS bre_ctl_owner: it creates the schema with
-- `AUTHORIZATION bre_ctl_owner`, and `bre_ctl.tg_audit_chain()` is SECURITY DEFINER owned by that
-- role so the audit chain can be maintained by a function nobody else can reproduce.
--
-- Flyway runs as whichever role the application connects with, so every TABLE ends up owned by
-- that role instead. The schema's AUTHORIZATION does not change that. The visible symptom is
-- specific and confusing: inserting an audit entry fails with
--
--     ERROR: permission denied for table audit_chain_heads
--
-- on a connection that can plainly write to audit_log — because the trigger body runs as
-- bre_ctl_owner, which owns nothing.
--
-- Reassigning ownership here rather than putting `SET ROLE bre_ctl_owner` at the top of V1 is
-- deliberate: SET ROLE lasts for one script, so V2 onwards would still create tables under the
-- migrating role, and the problem would come back silently with the next migration anybody adds.
-- A loop over the schema's current contents cannot miss a table.
--
-- This also makes FORCE ROW LEVEL SECURITY mean what §5.2 intends. FORCE makes RLS apply to the
-- table's owner as well as to everybody else — which is the point, since the application may well
-- be a member of the owning role — and that only holds once the owner is the role the policies
-- were written for.
DO $$
DECLARE obj record;
BEGIN
  FOR obj IN
    SELECT c.relname, c.relkind
      FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE n.nspname = 'bre_ctl' AND c.relkind IN ('r','p','v','S')
  LOOP
    IF obj.relkind IN ('r','p') THEN
      EXECUTE format('ALTER TABLE bre_ctl.%I OWNER TO bre_ctl_owner', obj.relname);
    ELSIF obj.relkind = 'v' THEN
      EXECUTE format('ALTER VIEW bre_ctl.%I OWNER TO bre_ctl_owner', obj.relname);
    ELSE
      EXECUTE format('ALTER SEQUENCE bre_ctl.%I OWNER TO bre_ctl_owner', obj.relname);
    END IF;
  END LOOP;

  -- Only the functions that exist NOW — V1 to V10's. The pre-tenant authentication functions are
  -- created further down this script and are deliberately owned by bre_ctl_authn instead; if this
  -- loop is ever moved after them it would silently take that ownership away and break every
  -- login. Hence the explicit exclusion rather than relying on the order.
  FOR obj IN
    SELECT p.proname, pg_get_function_identity_arguments(p.oid) AS args
      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'bre_ctl'
       AND p.proname NOT IN ('login_lookup','login_lookup_by_subject','login_record_failure',
                             'login_record_success','session_lookup','roles_of',
                             'tenant_sod_override')
  LOOP
    EXECUTE format('ALTER FUNCTION bre_ctl.%I(%s) OWNER TO bre_ctl_owner', obj.proname, obj.args);
  END LOOP;
END $$;

-- ---------------------------------------------------------------- 2. RLS on the new tables
--
-- V7 enables and FORCES row-level security on every tenant-owned table, and a test asserts that
-- every table in bre_ctl carrying a tenant_id column has it. That test exists because §5.2's own
-- list had omitted audit_chain_heads (OPEN-QUESTIONS Q7) — and it immediately caught V8's two new
-- tables the moment they were added, which is exactly what it was written for.
--
-- Same policy as V7: compare tenant_id with current_setting('bre.tenant_id', true). The second
-- argument makes an unset setting NULL rather than an error, and `tenant_id = NULL` is NULL, which
-- RLS treats as false — so a session that forgot to set the tenant sees no rows rather than
-- seeing everything. Fail closed.
ALTER TABLE bre_ctl.users ENABLE ROW LEVEL SECURITY;
ALTER TABLE bre_ctl.users FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bre_ctl.users
  USING (tenant_id = current_setting('bre.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('bre.tenant_id', true));

ALTER TABLE bre_ctl.user_sessions ENABLE ROW LEVEL SECURITY;
ALTER TABLE bre_ctl.user_sessions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON bre_ctl.user_sessions
  USING (tenant_id = current_setting('bre.tenant_id', true))
  WITH CHECK (tenant_id = current_setting('bre.tenant_id', true));

-- ---------------------------------------------------------------- 2b. the authentication role
--
-- SECURITY DEFINER is not enough on its own, and finding that out is worth recording.
--
-- The obvious design for the pre-tenant reads below is a SECURITY DEFINER function owned by
-- bre_ctl_owner. It does not work: FORCE ROW LEVEL SECURITY applies the policy to the table's
-- OWNER as well as to everybody else — that is the whole difference between ENABLE and FORCE —
-- so a function running as the owner is subject to the same tenant_isolation policy it is trying
-- to look past, and returns nothing. The symptom is a login that always fails with valid
-- credentials, which looks like a password-hashing bug.
--
-- So the pre-tenant functions get their own owner: a role whose entire purpose is to hold
-- BYPASSRLS, that cannot log in, that nothing else uses, and whose privileges are the narrow set
-- those four functions need. The capability is real, and it is confined to one role that appears
-- in exactly one place.
CREATE ROLE bre_ctl_authn NOLOGIN BYPASSRLS;

COMMENT ON ROLE bre_ctl_authn IS
  'Owns the pre-tenant authentication functions and nothing else. NOLOGIN, so nobody connects as it; BYPASSRLS, because FORCE ROW LEVEL SECURITY applies to a table''s owner, so a SECURITY DEFINER function owned by bre_ctl_owner cannot read across tenants either. This is the only BYPASSRLS role in bre_ctl besides bre_ctl_platform.';

GRANT USAGE ON SCHEMA bre_ctl TO bre_ctl_authn;
GRANT SELECT, UPDATE ON bre_ctl.users          TO bre_ctl_authn;
GRANT SELECT, UPDATE ON bre_ctl.user_sessions  TO bre_ctl_authn;
GRANT SELECT         ON bre_ctl.role_assignments TO bre_ctl_authn;
GRANT SELECT         ON bre_ctl.tenants          TO bre_ctl_authn;

-- ---------------------------------------------------------------- 3. the login paradox
--
-- Putting users under tenant RLS creates a problem the other tables do not have: AUTHENTICATION
-- HAPPENS BEFORE THE TENANT IS KNOWN. A login form asks for an email and a password — a person
-- should not have to type their lender's internal tenant identifier to sign in — so the lookup
-- has to read across tenants, which is precisely what the policy above forbids.
--
-- Three ways out, and two of them are worse:
--
--   * give the application role BYPASSRLS. It then bypasses RLS for EVERY query, and D1's tenant
--     isolation stops being enforced by the database at all. The one control that holds even when
--     application code is wrong would be gone.
--   * leave users without RLS. One lender's administrator could then read another lender's staff
--     directory — names, emails, roles, employee ids.
--   * a SECURITY DEFINER function with a narrow signature. One function, owned by bre_ctl_owner,
--     that takes an email and returns only what a login needs. It is the only cross-tenant read in
--     the system, it is a single auditable object, and it cannot be persuaded to return anything
--     else: there is no parameter that widens it.
--
-- The third. Note what it deliberately does NOT return: no demographics, no roles, no employee
-- reference. Only the fields the password check and the lockout logic need. Roles are read
-- afterwards, under the tenant the authenticated account turned out to belong to.
CREATE FUNCTION bre_ctl.login_lookup(p_email text)
RETURNS TABLE (
  subject              text,
  tenant_id            text,
  email                text,
  full_name            text,
  password_hash        text,
  must_change_password boolean,
  status               text,
  failed_attempts      smallint,
  locked_until         timestamptz)
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog, bre_ctl
AS $$
  SELECT u.subject, u.tenant_id, u.email, u.full_name, u.password_hash, u.must_change_password,
         u.status, u.failed_attempts, u.locked_until
    FROM bre_ctl.users u
   WHERE u.email = lower(p_email)
   ORDER BY u.tenant_id
$$;

ALTER FUNCTION bre_ctl.login_lookup(text) OWNER TO bre_ctl_authn;

COMMENT ON FUNCTION bre_ctl.login_lookup(text) IS
  'The ONLY cross-tenant read in bre_ctl. SECURITY DEFINER, because authentication happens before the tenant is known and users is under tenant RLS. Returns only the fields a password check and the lockout logic need — no demographics, no roles. Roles are read afterwards under the tenant the account belongs to.';

-- The login path also has to UPDATE the row it just read — the failure counter, the lockout, the
-- last-login time — and that update is equally pre-tenant. Same reasoning, same narrow shape: two
-- functions that can do exactly one thing each.
CREATE FUNCTION bre_ctl.login_record_failure(p_subject text, p_threshold int, p_lock_minutes int)
RETURNS smallint
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog, bre_ctl
AS $$
  UPDATE bre_ctl.users
     SET failed_attempts = failed_attempts + 1,
         locked_until = CASE WHEN failed_attempts + 1 >= p_threshold
                             THEN now() + make_interval(mins => p_lock_minutes)
                             ELSE locked_until END,
         updated_at = now()
   WHERE subject = p_subject
  RETURNING failed_attempts
$$;

ALTER FUNCTION bre_ctl.login_record_failure(text, int, int) OWNER TO bre_ctl_authn;

CREATE FUNCTION bre_ctl.login_record_success(p_subject text)
RETURNS void
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog, bre_ctl
AS $$
  UPDATE bre_ctl.users
     SET failed_attempts = 0, locked_until = NULL, last_login_at = now(), updated_at = now()
   WHERE subject = p_subject
$$;

ALTER FUNCTION bre_ctl.login_record_success(text) OWNER TO bre_ctl_authn;

-- Resolving a bearer token is the third pre-tenant read: the token is presented before anybody
-- knows which tenant it belongs to — that is what resolving it establishes.
CREATE FUNCTION bre_ctl.session_lookup(p_token_sha256 bytea)
RETURNS TABLE (subject text, tenant_id text)
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog, bre_ctl
AS $$
  UPDATE bre_ctl.user_sessions
     SET last_seen_at = now()
   WHERE token_sha256 = p_token_sha256
     AND revoked_at IS NULL
     AND expires_at > now()
  RETURNING user_sessions.subject, user_sessions.tenant_id
$$;

ALTER FUNCTION bre_ctl.session_lookup(bytea) OWNER TO bre_ctl_authn;

COMMENT ON FUNCTION bre_ctl.session_lookup(bytea) IS
  'Resolves a bearer token to a subject and tenant, and stamps last_seen_at in the same statement. SECURITY DEFINER for the same reason as login_lookup: the tenant is what this call establishes, so it cannot already be set. Takes the token DIGEST — the token itself is never stored or passed to the database.';

-- ---------------------------------------------------------------- grants for the new objects
--
-- V7's grants ran before these tables and functions existed, so they are not covered.
GRANT SELECT, INSERT, UPDATE ON bre_ctl.users, bre_ctl.user_sessions
  TO bre_ctl_app, bre_ctl_platform;
GRANT SELECT ON bre_ctl.users, bre_ctl.user_sessions TO bre_ctl_auditor;

-- EXECUTE on the four pre-tenant functions. Granted explicitly and listed here so that the
-- complete set of cross-tenant capability in this schema is four lines long and reviewable.
GRANT EXECUTE ON FUNCTION bre_ctl.login_lookup(text) TO bre_ctl_app;
GRANT EXECUTE ON FUNCTION bre_ctl.login_record_failure(text, int, int) TO bre_ctl_app;
GRANT EXECUTE ON FUNCTION bre_ctl.login_record_success(text) TO bre_ctl_app;
GRANT EXECUTE ON FUNCTION bre_ctl.session_lookup(bytea) TO bre_ctl_app;

-- Two more pre-tenant reads, for completeness of the authentication path.
--
-- Resolving a bearer token returns a subject and a tenant. Building the Principal then needs the
-- account's status and name, and its roles — both still before anything has set bre.tenant_id,
-- because setting it is what happens AFTER the principal exists. Without these, the filter would
-- have to set the tenant mid-resolution from a value it is still in the middle of establishing.
--
-- Both are deliberately narrow. login_lookup_by_subject takes one subject. roles_of takes a tenant
-- AND a subject, so it cannot enumerate: a caller already has to know both, and knowing both is
-- what the session lookup just established.
CREATE FUNCTION bre_ctl.login_lookup_by_subject(p_subject text)
RETURNS TABLE (
  subject              text,
  tenant_id            text,
  email                text,
  full_name            text,
  password_hash        text,
  must_change_password boolean,
  status               text,
  failed_attempts      smallint,
  locked_until         timestamptz)
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog, bre_ctl
AS $$
  SELECT u.subject, u.tenant_id, u.email, u.full_name, u.password_hash, u.must_change_password,
         u.status, u.failed_attempts, u.locked_until
    FROM bre_ctl.users u
   WHERE u.subject = p_subject
$$;

ALTER FUNCTION bre_ctl.login_lookup_by_subject(text) OWNER TO bre_ctl_authn;

CREATE FUNCTION bre_ctl.roles_of(p_tenant_id text, p_subject text)
RETURNS TABLE (role text)
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog, bre_ctl
AS $$
  SELECT ra.role FROM bre_ctl.role_assignments ra
   WHERE ra.tenant_id = p_tenant_id AND ra.subject = p_subject
$$;

ALTER FUNCTION bre_ctl.roles_of(text, text) OWNER TO bre_ctl_authn;

COMMENT ON FUNCTION bre_ctl.roles_of(text, text) IS
  'A principal''s roles, readable before bre.tenant_id is set — setting it is what happens after the principal exists. Narrow by construction: it takes the tenant AND the subject, so it cannot enumerate either.';

GRANT EXECUTE ON FUNCTION bre_ctl.login_lookup_by_subject(text) TO bre_ctl_app;
GRANT EXECUTE ON FUNCTION bre_ctl.roles_of(text, text) TO bre_ctl_app;

-- The tenant settings the authentication path reads: allow_admin_sod_override lands on the
-- Principal, and min_policy_reviewers is read while approving. Both are reads of ONE tenant's row
-- by a caller who is already authenticated into that tenant, so they do not need a function —
-- except that the Principal is built before bre.tenant_id is set. One narrow function again.
CREATE FUNCTION bre_ctl.tenant_sod_override(p_tenant_id text)
RETURNS boolean
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog, bre_ctl
AS $$
  SELECT coalesce((SELECT t.allow_admin_sod_override FROM bre_ctl.tenants t
                    WHERE t.tenant_id = p_tenant_id), false)
$$;

ALTER FUNCTION bre_ctl.tenant_sod_override(text) OWNER TO bre_ctl_authn;
GRANT EXECUTE ON FUNCTION bre_ctl.tenant_sod_override(text) TO bre_ctl_app;
