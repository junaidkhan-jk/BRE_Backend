-- §9.2 envelopes for the fact snapshot, replacing V2's development-only plaintext column (M2-06).
--
-- V2 wrote the whole facts document as jsonb, in the clear, behind an off-by-default flag and a
-- comment saying so. That was honest but it was not usable: replay (D7) needs the facts, so the
-- feature that needs them could only be used by storing personal data unprotected. This is the
-- column set that makes `store-facts` safe to turn on.
--
-- Two envelopes, because erasure requires it (§9.5). Destroying one person's key must make that
-- person's identifying data unreadable in the hot store, in the WORM archive and in backups, while
-- the lending decision itself stays intact and auditable — a lender is required to retain the
-- decision. One envelope per decision could not do that: erasing a person would mean either
-- rewriting immutable archive objects or deleting the decision record.
--
--   facts_envelope      the non-identifying facts, sealed under the tenant's TDK for the decision
--                       hour. Amounts, scores, counts, event dates.
--   principal_envelopes the identifying fields, one envelope per party, each sealed under that
--                       party's data principal's DPK. A jsonb map of partyRef -> base64 envelope
--                       rather than one bytea, because they are opened independently: a decision
--                       for a five-member group whose second member has been erased must still
--                       render the other four.
--   facts_hmac          HMAC-SHA256(K_int[tenant], BRE-JCS(facts)) (§9.2). Replays verify it
--                       before evaluating.
--
-- V2's facts_sha256 is dropped with the plaintext column. A plain digest stored beside the data it
-- digests detects corruption but not tampering: anyone able to rewrite the row can rewrite the
-- digest. The HMAC is keyed, and the key is not in this database.

ALTER TABLE bre_dec.decision_facts
  ADD COLUMN facts_envelope      bytea,
  ADD COLUMN principal_envelopes jsonb  NOT NULL DEFAULT '{}'::jsonb,
  ADD COLUMN facts_hmac          text,
  -- Which TDK sealed facts_envelope. Also inside the envelope itself (the 0x01 format carries its
  -- kid), and duplicated here deliberately: an operator needs to answer "which decisions are
  -- sealed under the key we are about to retire" with a query, not by decrypting every row.
  ADD COLUMN facts_kid           text;

-- Dropped rather than deprecated. V2's column is development-only by its own comment, nothing has
-- been deployed, and leaving a plaintext PII column in place "for compatibility" is how a column
-- that nobody writes to ends up being written to.
ALTER TABLE bre_dec.decision_facts
  DROP COLUMN facts_json_plaintext,
  DROP COLUMN facts_sha256;

COMMENT ON TABLE bre_dec.decision_facts IS
  'Fact snapshots for replay (D7), sealed per §9.2. The non-identifying half under the tenant-hour TDK; each party''s identifying fields under that data principal''s DPK, so §9.5 erasure is key destruction rather than row deletion.';

COMMENT ON COLUMN bre_dec.decision_facts.facts_envelope IS
  'Non-identifying facts: 0x01 || kidLen || kid || nonce(12) || AES-256-GCM(zstd(json)). AAD binds tenant, purpose=facts-ni and decision id, so an envelope cannot be replayed into another decision or another tenant.';

COMMENT ON COLUMN bre_dec.decision_facts.principal_envelopes IS
  'partyRef -> base64 envelope, each under that party''s principal DPK with AAD purpose=facts-id and the principal in it. A partyRef with no entry here, or whose DPK has been destroyed, renders as shredded (§9.5) and is an expected state, not an error.';

COMMENT ON COLUMN bre_dec.decision_facts.facts_hmac IS
  'HMAC-SHA256(K_int[tenant], BRE-JCS(facts)) (§9.2). A replay verifies this before evaluating; a mismatch reports matched=false with reason INTEGRITY rather than producing an explanation of a decision nobody made.';

COMMENT ON COLUMN bre_dec.decision_facts.facts_kid IS
  'The TDK that sealed facts_envelope, duplicated out of the envelope so key retirement can be planned with a query.';
