-- Default partitions, so that an insert never fails for want of one.
--
-- §5.3 partitions each decision table by UTC day, and §5.4 specifies the maintenance job that
-- creates tomorrow's partition ahead of time and drops expired ones. Without either, the very
-- first insert fails with "no partition of relation found for row" -- a correct decision lost to a
-- DDL omission, which is the worst possible way to lose one.
--
-- A DEFAULT partition catches every row no day partition claims. It makes the schema work from the
-- first insert, and it is NOT a substitute for §5.4:
--
--   * every row lands in one table, so none of the partition pruning §5.3 exists for, and
--   * retention by DROP PARTITION is impossible, because there are no day partitions to drop --
--     expiry would fall back to DELETE, which §5.3 explicitly rejects.
--
-- Before production: run the §5.4 maintenance job, create the day partitions, and detach these
-- defaults once they are empty. Attaching a day partition while a non-empty default exists
-- requires PostgreSQL to scan the default to prove no row belongs in the new range, so keeping
-- these defaults populated makes that migration progressively slower.

CREATE TABLE bre_dec.decisions_default PARTITION OF bre_dec.decisions DEFAULT;

CREATE TABLE bre_dec.decision_facts_default PARTITION OF bre_dec.decision_facts DEFAULT;

COMMENT ON TABLE bre_dec.decisions_default IS
  'Catch-all partition. Replace with per-day partitions and the §5.4 maintenance job before production; retention by DROP PARTITION is impossible while rows live here.';

COMMENT ON TABLE bre_dec.decision_facts_default IS
  'Catch-all partition. See bre_dec.decisions_default.';
