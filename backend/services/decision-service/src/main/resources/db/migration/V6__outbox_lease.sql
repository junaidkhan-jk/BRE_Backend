-- outbox lease
--
-- The relay's first version held a database transaction across the HTTP call to the downstream
-- system, because the advisory lock that keeps one drainer per tenant is transaction-scoped. That
-- is how a connection pool dies: a downstream answering slowly holds a connection for the whole
-- batch, and with a five-second timeout and a batch of fifty that is over four minutes. Observed,
-- not theorised — a burst of decisions against a working receiver exhausted all sixteen
-- connections and the decision API started returning 500s for decisions the engine had already
-- made correctly.
--
-- A lease moves the "one drainer per tenant" guarantee out of the transaction. The relay claims a
-- tenant in a short transaction, releases the connection, delivers with nothing held, and records
-- each outcome in its own brief transaction. A pod that crashes mid-drain leaves a lease that
-- expires, rather than a lock that needs a human.
--
-- WHY ONE DRAINER PER TENANT AT ALL. Order. Two pods draining one tenant could deliver a later
-- decision for the same application before an earlier one, so a downstream could see today's
-- approval superseded by a decline that was made before it. That is a wrong answer, not merely an
-- out-of-order one.

CREATE TABLE bre_dec.outbox_lease (
  tenant_id    text        PRIMARY KEY,
  leased_by    text        NOT NULL,
  leased_until timestamptz NOT NULL,
  leased_at    timestamptz NOT NULL DEFAULT now()
);

COMMENT ON TABLE bre_dec.outbox_lease IS
  'One row per tenant being drained. Held for a short, renewable period rather than for the '
  'length of a delivery, so no database connection is held across a call to a third party.';

COMMENT ON COLUMN bre_dec.outbox_lease.leased_until IS
  'When this lease expires. A pod that dies mid-drain blocks its tenant only until then — which '
  'is why the lease is short and renewed rather than long and optimistic.';
