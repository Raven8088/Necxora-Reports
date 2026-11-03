-- Nexora Reports A4-B9 (D-B9-4, adjustment 3 of A4-B8): the delivery of an artifact to an HTTP client is recorded
-- apart from its retrieval from the store.
--   ARTIFACT_DELIVERED       every byte was written to the response and flushed without error. It does not prove
--                            that the client received them (a proxy or the network can still lose them).
--   ARTIFACT_DELIVERY_FAILED writing the response failed (for example the client went away); detail says how.
-- V3 is not modified: only the closed list of event kinds is widened, nothing else changes. Events stay append-only.

ALTER TABLE report_run_event DROP CONSTRAINT report_run_event_kind_check;

ALTER TABLE report_run_event ADD CONSTRAINT report_run_event_kind_check
    CHECK (kind IN ('RUN_STARTED', 'RUN_FINISHED', 'RUN_INTERRUPTED', 'ARTIFACT_STORED', 'ARTIFACT_RETRIEVED',
                    'ARTIFACT_INTEGRITY_FAILED', 'ARTIFACT_PURGED', 'ARTIFACT_DELIVERED',
                    'ARTIFACT_DELIVERY_FAILED'));
