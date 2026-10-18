-- EMR sync: the outbound queue the gateway pulls, the ledger (ids, versions, content hashes) and the inbound cursor.
CREATE SCHEMA IF NOT EXISTS sync;

CREATE TABLE sync.outbound (
    id               uuid PRIMARY KEY,
    kind             text NOT NULL CHECK (kind IN ('ENCOUNTER', 'ADD_IDENTIFIER', 'REMOVE_IDENTIFIER')),
    patient_id       uuid NOT NULL,
    reference        text NOT NULL,          -- consultation id, or link id
    payload          jsonb NOT NULL,         -- only what the EMR needs (data minimisation)
    status           text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'DONE', 'DEAD')),
    attempts         integer NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz NOT NULL DEFAULT now(),
    last_error       text,
    outcome          text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    done_at          timestamptz,
    UNIQUE (kind, reference)
);
CREATE INDEX sync_outbound_due_idx ON sync.outbound (status, next_attempt_at, created_at);

CREATE TABLE sync.ledger (
    resource_type  text NOT NULL,
    teleclinic_id  text NOT NULL,
    emr_uuid       text NOT NULL,
    emr_version    text,
    content_hash   text NOT NULL,
    direction      text NOT NULL CHECK (direction IN ('OUTBOUND', 'INBOUND')),
    synced_at      timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (resource_type, teleclinic_id),
    UNIQUE (resource_type, emr_uuid)
);

CREATE TABLE sync.cursor (
    name   text PRIMARY KEY,
    value  timestamptz NOT NULL
);

-- What happened, for operators and the demo (echo dropped, applied, matched, failed)
CREATE TABLE sync.event (
    id             bigserial PRIMARY KEY,
    at             timestamptz NOT NULL DEFAULT now(),
    direction      text NOT NULL,
    resource_type  text,
    teleclinic_id  text,
    emr_uuid       text,
    outcome        text NOT NULL,
    detail         text
);
