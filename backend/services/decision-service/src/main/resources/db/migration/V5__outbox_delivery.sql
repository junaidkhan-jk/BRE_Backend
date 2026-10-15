-- outbox delivery
--
-- The outbox table has existed since V1 and every decision has written a row to it, in the same
-- transaction as the decision itself (D6). Nothing ever drained it, so `published_at` was never
-- set and no decision ever reached another system. This adds what a relay needs to deliver one.
--
-- WHY A RELAY AND NOT A DIRECT CALL. Publishing to a downstream system inside the decision
-- transaction would mean a decision that is recorded and not published, or published and not
-- recorded, depending on which side failed — and the second is worse, because a downstream system
-- would have acted on a decision this installation cannot show it made. The outbox exists so
-- there is no second phase to fail: the decision and the intent to publish commit together, and
-- delivery is a separate, retryable act.
--
-- AT-LEAST-ONCE, AND SAID OUT LOUD. The relay marks a row published after a successful delivery.
-- A crash between the two redelivers. There is no way to make that exactly-once without the
-- receiver participating, so the event carries a stable decision id and the receiver is expected
-- to deduplicate on it. Claiming otherwise would be a lie a downstream team would build on.

ALTER TABLE bre_dec.outbox
  ADD COLUMN attempts        smallint    NOT NULL DEFAULT 0,
  ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN last_error      text,
  -- Set when a row has exhausted its attempts. A decision that can never be delivered has to be
  -- VISIBLE: retrying it silently forever means a downstream system is missing decisions and
  -- nobody is told, which is the failure mode an operator finds out about from the business.
  ADD COLUMN failed_at       timestamptz;

-- The relay asks for rows that are unpublished, not failed, and due. The partial index from V1
-- covered only the first of those, so a backlog of future-dated retries would still be scanned.
DROP INDEX IF EXISTS bre_dec.outbox_unpublished;
CREATE INDEX outbox_due ON bre_dec.outbox (next_attempt_at, outbox_id)
  WHERE published_at IS NULL AND failed_at IS NULL;

-- Kept separate so an operator can answer "what is stuck" without scanning the whole table.
CREATE INDEX outbox_failed ON bre_dec.outbox (tenant_id, outbox_id)
  WHERE failed_at IS NOT NULL;

COMMENT ON COLUMN bre_dec.outbox.attempts IS
  'Delivery attempts so far. Backoff is computed from this rather than stored, so changing the '
  'backoff curve does not require rewriting pending rows.';

COMMENT ON COLUMN bre_dec.outbox.failed_at IS
  'Set when attempts are exhausted. The row is then INERT: it is never retried automatically, and '
  'an operator replays it deliberately after fixing whatever refused it. Silent infinite retry '
  'hides a downstream outage; a dead-letter row does not.';
