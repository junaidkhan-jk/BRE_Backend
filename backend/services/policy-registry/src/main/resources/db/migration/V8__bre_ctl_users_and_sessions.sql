-- Local user accounts, passwords and sessions.
--
-- WHY THIS IS NOT IN §5.2. The specification assumes an external OIDC identity provider (§3.12,
-- M3-09): `role_assignments.subject` is documented as "OIDC 'sub'", and there is deliberately no
-- user table, because a credit platform should not be in the business of storing passwords when
-- the lender already has an identity provider that does it properly — with their own MFA, their
-- own password policy, their own joiner/leaver process and their own audit.
--
-- This table exists because the platform has to be usable before that integration exists. It is a
-- STAND-IN, and the shape is chosen so the stand-in can be removed without touching anything
-- above it: `subject` is the same column `role_assignments` already keys on, so when an OIDC
-- provider arrives its `sub` values replace these rows and every authorisation rule is unchanged.
--
-- What this is NOT: a replacement for an enterprise IdP. There is no MFA, no SSO, no password
-- rotation policy, no account recovery, and no federation. Those belong to the IdP.

-- ---------------------------------------------------------------- roles the DDL did not list
-- §3.12 defines eight roles; §5.2's CHECK constraint lists seven. PLATFORM_ADMIN — the operator
-- side, who manages cells, tenants and quotas — is missing from the constraint. That is a
-- specification inconsistency rather than a deliberate omission: a role the RBAC table names and
-- the database rejects cannot be granted to anybody. Added here, and recorded as an open question.
ALTER TABLE bre_ctl.role_assignments DROP CONSTRAINT role_assignments_role_check;
ALTER TABLE bre_ctl.role_assignments ADD CONSTRAINT role_assignments_role_check
  CHECK (role IN ('POLICY_AUTHOR','POLICY_REVIEWER','DEPLOYMENT_APPROVER',
                  'RISK_ANALYST','AUDITOR','OPS_VIEWER','TENANT_ADMIN','PLATFORM_ADMIN'));

-- ---------------------------------------------------------------- users
CREATE TABLE bre_ctl.users (
  subject          text        PRIMARY KEY CHECK (subject ~ '^[a-z0-9][a-z0-9._@+-]{2,127}$'),
  tenant_id        text        NOT NULL REFERENCES bre_ctl.tenants (tenant_id),

  -- Login identity. Lowercased by the application before it reaches here, so two accounts cannot
  -- differ only by case — which is how one person ends up with two sets of role grants.
  email            text        NOT NULL CHECK (email = lower(email) AND position('@' in email) > 1),

  -- Demographics. Deliberately a short list: this is a staff directory for authorisation and an
  -- audit trail, not an HR system. Everything here appears in an audit entry's `actor` context, so
  -- each field has to be something a lender would want attributed to an approval years later.
  full_name        text        NOT NULL CHECK (length(full_name) BETWEEN 1 AND 200),
  employee_ref     text,                                              -- the lender's own staff id
  department       text,
  designation      text,
  -- E.164 without the plus, or a 10-digit Indian mobile. Stored because an approver sometimes has
  -- to be reached about a deployment, not for notification: there is no notification path.
  phone            text        CHECK (phone IS NULL OR phone ~ '^[0-9]{10,15}$'),
  branch_code      text,

  -- Credential. The ALGORITHM AND ITS PARAMETERS ARE STORED WITH THE HASH, in one string, so a
  -- future increase in the iteration count does not invalidate existing passwords: a login
  -- verifies against the parameters the hash was made with and the application re-hashes on the
  -- next successful login. A schema that stored only the digest would force every user to reset.
  password_hash    text        NOT NULL,
  password_set_at  timestamptz NOT NULL DEFAULT now(),
  must_change_password boolean NOT NULL DEFAULT false,

  status           text        NOT NULL DEFAULT 'ACTIVE'
                               CHECK (status IN ('ACTIVE','SUSPENDED','LOCKED','CLOSED')),

  -- Lockout state. Kept on the row rather than in a cache because it has to survive a restart:
  -- an attacker who can restart the service must not be able to reset the counter.
  failed_attempts  smallint    NOT NULL DEFAULT 0 CHECK (failed_attempts >= 0),
  locked_until     timestamptz,
  last_login_at    timestamptz,

  created_by       text        NOT NULL,
  row_version      bigint      NOT NULL DEFAULT 1,
  created_at       timestamptz NOT NULL DEFAULT now(),
  updated_at       timestamptz NOT NULL DEFAULT now(),

  -- One account per email per tenant. Across tenants the same person may legitimately have two
  -- accounts — a consultant working for two lenders — and those are separate identities with
  -- separate role grants, which is correct: one of them being suspended must not affect the other.
  UNIQUE (tenant_id, email)
);

COMMENT ON TABLE bre_ctl.users IS
  'Local staff accounts. A STAND-IN for the OIDC identity provider §3.12 assumes (M3-09): subject is the same column role_assignments keys on, so IdP sub values replace these rows without changing any authorisation rule. No MFA, no SSO, no recovery — those belong to the IdP.';

COMMENT ON COLUMN bre_ctl.users.password_hash IS
  'pbkdf2-sha256$<iterations>$<base64 salt>$<base64 hash>. Parameters travel with the hash so the cost can be raised without invalidating existing passwords.';

COMMENT ON COLUMN bre_ctl.users.failed_attempts IS
  'On the row, not in a cache: an attacker who can restart the service must not be able to reset the counter.';

CREATE INDEX users_by_tenant ON bre_ctl.users (tenant_id, status);

-- ---------------------------------------------------------------- sessions
CREATE TABLE bre_ctl.user_sessions (
  -- The HASH of the bearer token, never the token. A read of this table — a backup, a replica, a
  -- support query, a SQL injection — yields nothing that can be presented as a session. The token
  -- exists only in the response to the login that created it and in the client holding it.
  token_sha256  bytea       PRIMARY KEY,
  subject       text        NOT NULL REFERENCES bre_ctl.users (subject),
  tenant_id     text        NOT NULL REFERENCES bre_ctl.tenants (tenant_id),
  issued_at     timestamptz NOT NULL DEFAULT now(),
  expires_at    timestamptz NOT NULL,
  last_seen_at  timestamptz NOT NULL DEFAULT now(),
  revoked_at    timestamptz,
  revoked_by    text,
  CHECK (expires_at > issued_at)
);

COMMENT ON TABLE bre_ctl.user_sessions IS
  'Opaque bearer sessions, stored as the token digest only. Server-side so a session can be revoked the moment a leaver is processed — a self-contained JWT cannot be, which is the wrong trade for a system where an approval is a regulated act.';

CREATE INDEX user_sessions_by_subject ON bre_ctl.user_sessions (subject, expires_at);

-- ---------------------------------------------------------------- the admin override
--
-- 00 §3.3 requires segregation of duties: the reviewer cannot be the author, and a deployment
-- approver can be neither. A TENANT_ADMIN who can author, approve and deploy in one sitting
-- defeats that control, and maker-checker on credit policy is not an internal nicety — it is what
-- a regulator and an auditor expect to find.
--
-- It is nonetheless necessary: a lender onboarding with one administrator, a pilot, a
-- break-glass fix at 2am. So the override exists, is OFF-able per tenant, and every use writes a
-- POLICY_VERSION.SOD_OVERRIDDEN audit entry naming the person and what they bypassed. An
-- unrecorded bypass would be the actual problem; a recorded one is a finding somebody can review.
ALTER TABLE bre_ctl.tenants
  ADD COLUMN allow_admin_sod_override boolean NOT NULL DEFAULT true;

COMMENT ON COLUMN bre_ctl.tenants.allow_admin_sod_override IS
  'Whether a TENANT_ADMIN may bypass 00 §3.3 segregation of duties — authoring and approving the same version, or approving their own deployment. Every use is audited as SOD_OVERRIDDEN. Set false once the tenant has two or more staff who can review, which is what an auditor will expect.';
