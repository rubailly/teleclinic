-- Triage module: the human-in-the-loop state machine (Section 4.7.3, Figure 15).
-- The module keeps its own copy of what the clinician queue needs, so the queue never joins other modules' tables.
-- Flyway creates the configured schemas only on a brand-new database, so create this one here as well.
CREATE SCHEMA IF NOT EXISTS triage;

CREATE TABLE triage.triage_case (
    consultation_id    uuid PRIMARY KEY,
    patient_id         uuid NOT NULL,
    channel            text NOT NULL,
    status             text NOT NULL CHECK (status IN ('RULES_ONLY', 'AI_PROPOSED', 'UNDER_REVIEW', 'CONFIRMED',
                                                       'OVERRIDDEN', 'FINAL', 'UNREVIEWED_TIMEOUT')),
    rules_urgency      text NOT NULL,
    red_flags          text[] NOT NULL DEFAULT '{}',
    ruleset_version    text NOT NULL,
    model_urgency      text,
    model_confidence   numeric(4, 3),
    model_factors      text[] NOT NULL DEFAULT '{}',
    model_version      text,
    model_mode         text,
    merged_urgency     text NOT NULL,           -- max(rules, model): the model can only raise urgency
    final_urgency      text,
    queued_at          timestamptz,
    sla_due_at         timestamptz,
    reviewer_subject   text,
    reviewer_name      text,
    review_decision    text,
    review_reason      text,
    reviewed_at        timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX triage_case_queue_idx ON triage.triage_case (status, queued_at);

CREATE TABLE triage.case_transition (
    id               bigserial PRIMARY KEY,
    consultation_id  uuid NOT NULL REFERENCES triage.triage_case (consultation_id),
    from_status      text,
    to_status        text NOT NULL,
    reason           text NOT NULL,
    actor            text NOT NULL,
    at               timestamptz NOT NULL DEFAULT now()
);

-- Who produced or verified each triage decision: the model version as a device agent, the clinician as verifier.
-- Rendered as FHIR Provenance at the boundary.
CREATE TABLE triage.provenance (
    id               bigserial PRIMARY KEY,
    consultation_id  uuid NOT NULL REFERENCES triage.triage_case (consultation_id),
    activity         text NOT NULL CHECK (activity IN ('AI_SUGGESTION', 'CLINICIAN_REVIEW')),
    agent_type       text NOT NULL CHECK (agent_type IN ('DEVICE', 'PRACTITIONER')),
    agent_id         text NOT NULL,
    detail           jsonb NOT NULL,
    recorded_at      timestamptz NOT NULL DEFAULT now()
);
