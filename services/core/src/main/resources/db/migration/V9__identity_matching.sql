-- Identity matching: candidate pairs (review queue, hard blocks) and reversible links. Records are never merged.
CREATE SCHEMA IF NOT EXISTS matching;

CREATE TABLE matching.candidate (
    id           uuid PRIMARY KEY,
    emr_uuid     text NOT NULL,                -- the facility EMR's record
    patient_id   uuid NOT NULL,                -- the patient index record
    emr_record   jsonb NOT NULL,               -- what the EMR held when scored (the evidence shown to the clerk)
    score        numeric(6, 1) NOT NULL,
    band         text NOT NULL CHECK (band IN ('AUTO', 'REVIEW', 'NO_MATCH', 'BLOCKED')),
    agreeing     text[] NOT NULL,
    conflicting  text[] NOT NULL,
    status       text NOT NULL CHECK (status IN ('PENDING', 'LINKED', 'DISMISSED', 'BLOCKED')),
    decided_by   text,
    decided_at   timestamptz,
    reason       text,
    created_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (emr_uuid, patient_id)
);
CREATE INDEX matching_candidate_status_idx ON matching.candidate (status, score DESC);

CREATE TABLE matching.link (
    id                   uuid PRIMARY KEY,
    patient_id           uuid NOT NULL,
    emr_uuid             text NOT NULL,
    link_type            text NOT NULL DEFAULT 'seealso',
    score                numeric(6, 1),
    decided_by           text NOT NULL,          -- clerk subject, or 'auto'
    reason               text,
    decided_at           timestamptz NOT NULL DEFAULT now(),
    written_identifiers  jsonb NOT NULL DEFAULT '{}',   -- offered to the EMR record; taken back on unlink
    candidate_id         uuid,
    active               boolean NOT NULL DEFAULT true,
    unlinked_by          text,
    unlinked_at          timestamptz,
    unlink_reason        text
);
-- One active link per EMR record; history stays
CREATE UNIQUE INDEX matching_link_active_idx ON matching.link (emr_uuid) WHERE active;
