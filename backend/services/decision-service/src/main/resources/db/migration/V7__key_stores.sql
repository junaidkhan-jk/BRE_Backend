-- Where wrapped keys live between pods (§9.2, M4-04).
--
-- **Until this migration the key stores were HashMaps**, and that made store-facts unusable in
-- anything but a test. The crypto was correct — envelope encryption, a tenant data key per hour,
-- a data-principal key per person — and every key existed only in the heap of the pod that made
-- it. A restart lost them all, and every sealed fact row then became permanently unreadable:
-- not an outage, a silent and irreversible data loss, discovered the first time a regulator asked
-- a lender to replay a decision.
--
-- **Only WRAPPED key material is stored here.** Every row is ciphertext under a KEK the KMS holds
-- and this database has never seen, so a dump of this schema — a backup tape, a replica, a
-- developer's copy — yields nothing without the KMS. That is the whole point of the envelope: the
-- blast radius of losing the database is bounded by the blast radius of losing the KMS, and the
-- two are deliberately different systems.

-- ---------------------------------------------------------------- tenant data keys (TDK)
CREATE TABLE bre_dec.tenant_data_keys (
  kid         text        NOT NULL PRIMARY KEY,
  tenant_id   text        NOT NULL,
  wrapped     bytea       NOT NULL,
  kek_kid     text        NOT NULL,
  created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX tenant_data_keys_by_tenant ON bre_dec.tenant_data_keys (tenant_id, created_at);

COMMENT ON TABLE bre_dec.tenant_data_keys IS
  'Wrapped TDKs, one per tenant per hour (§9.2). Ciphertext only: unwrapping needs the KMS.';
COMMENT ON COLUMN bre_dec.tenant_data_keys.kid IS
  'The key identifier the envelope records, so a reader knows which key sealed it.';

-- ---------------------------------------------------------------- data principal keys (DPK)
--
-- The erasure key (§9.5). Deleting one row makes every identifying field ever sealed for that
-- person unreadable, everywhere, including on write-once archive storage that cannot be edited —
-- which is the only way to reconcile a ten-year WORM retention with a right to erasure.
CREATE TABLE bre_dec.principal_data_keys (
  tenant_id     text        NOT NULL,
  principal_ref text        NOT NULL,
  key_id        text        NOT NULL,
  wrapped       bytea       NOT NULL,
  pkwk_kid      text        NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (tenant_id, principal_ref)
);

COMMENT ON TABLE bre_dec.principal_data_keys IS
  'Wrapped DPKs, one per data principal (§9.2). DELETING A ROW IS AN ERASURE (§9.5) and is '
  'irreversible by design — there is no soft delete here, because a soft-deleted key is not '
  'destroyed and the erasure would be a lie.';

-- No RLS on either table, deliberately, and the reason is the opposite of carelessness.
--
-- bre_dec is reached by one service account that is already tenant-scoped at the application
-- layer, and every row here is ciphertext. What DOES matter is the separation §9.2 asks for: the
-- account that writes decisions must not be able to destroy keys, and the account that destroys
-- keys must not be able to read decisions. That is a grant, and it is left to the installation
-- because the role names are the installation's. The erasure endpoint is capability-gated in the
-- application meanwhile, and that is stated as the weaker control it is.
