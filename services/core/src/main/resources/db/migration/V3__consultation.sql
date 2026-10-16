-- Consultation module: the booking and payment saga (Section 4.3, Figure 7).
CREATE TABLE consultation.consultation (
    id                uuid PRIMARY KEY,
    patient_id        uuid NOT NULL,
    booked_by_subject text NOT NULL,
    channel           text NOT NULL CHECK (channel IN ('WEB', 'MOBILE', 'USSD', 'VOICE')),
    state             text NOT NULL,
    urgency           text,
    red_flags         text[] NOT NULL DEFAULT '{}',
    patient_advice    text,
    ruleset_version   text,
    intake            jsonb NOT NULL,
    fee               numeric(12, 2),
    amount_due        numeric(12, 2),
    currency          text,
    coverage_scheme   text,
    payment_due_by    timestamptz,
    idempotency_key   text UNIQUE,
    version           integer NOT NULL DEFAULT 0,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX consultation_state_idx ON consultation.consultation (state, payment_due_by);

-- Every state change, for support staff, auditors and the demo
CREATE TABLE consultation.state_transition (
    id               bigserial PRIMARY KEY,
    consultation_id  uuid NOT NULL REFERENCES consultation.consultation (id),
    from_state       text,
    to_state         text NOT NULL,
    reason           text NOT NULL,
    at               timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX state_transition_case_idx ON consultation.state_transition (consultation_id, id);
