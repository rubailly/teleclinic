-- Audit module: one hash-chained trail for every source (application interceptor, database via pgAudit).
CREATE SCHEMA IF NOT EXISTS audit;

CREATE TABLE audit.event (
    seq             bigint PRIMARY KEY,               -- assigned by the writer, no gaps
    event_id        uuid NOT NULL UNIQUE,             -- a redelivered event is written once
    recorded_at     timestamptz NOT NULL,             -- when the access happened
    written_at      timestamptz NOT NULL DEFAULT now(),
    source          text NOT NULL CHECK (source IN ('APPLICATION', 'DATABASE')),
    actor           text NOT NULL,                    -- IremboAccount subject, or the database role
    actor_role      text,
    actor_name      text,
    action          text NOT NULL,                    -- READ, SEARCH, CREATE, DECIDE, VERIFY, or the SQL class
    resource_type   text,
    resource_id     text,
    patient_id      uuid,
    purpose         text,                             -- X-Purpose-Of-Use
    consent_basis   text,
    outcome         text NOT NULL,                    -- SUCCESS or DENIED
    reason          text,                             -- denial reason or break-glass justification
    correlation_id  text,
    detail          jsonb NOT NULL DEFAULT '{}',
    prev_hash       text NOT NULL,
    hash            text NOT NULL
);
CREATE INDEX audit_event_patient_idx ON audit.event (patient_id, seq);
CREATE INDEX audit_event_source_idx ON audit.event (source, seq);

-- The end of the chain; locked by each append so concurrent writers (blue and green) cannot fork it
CREATE TABLE audit.chain_head (
    id    int PRIMARY KEY CHECK (id = 1),
    seq   bigint NOT NULL,
    hash  text NOT NULL
);
INSERT INTO audit.chain_head VALUES (1, 0, repeat('0', 64));

-- Append-only for everyone. Changing a row needs DDL first (disabling this trigger), which pgAudit records,
-- and the change itself breaks the hash chain.
CREATE FUNCTION audit.refuse_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit.event is append-only (% refused)', TG_OP;
END $$;
CREATE TRIGGER audit_event_append_only BEFORE UPDATE OR DELETE ON audit.event
    FOR EACH ROW EXECUTE FUNCTION audit.refuse_change();
CREATE TRIGGER audit_event_no_truncate BEFORE TRUNCATE ON audit.event
    FOR EACH STATEMENT EXECUTE FUNCTION audit.refuse_change();
