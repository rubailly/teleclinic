-- Consent module. The performer is the patient (SELF) or a guardian (GUARDIAN) for a dependent.
CREATE TABLE consent.consent (
    id                 uuid PRIMARY KEY,
    patient_id         uuid NOT NULL,
    performer_type     text NOT NULL CHECK (performer_type IN ('SELF', 'GUARDIAN')),
    performer_subject  text NOT NULL,
    policy_version     text NOT NULL,
    purposes           text[] NOT NULL,
    status             text NOT NULL CHECK (status IN ('ACTIVE', 'WITHDRAWN')),
    created_at         timestamptz NOT NULL DEFAULT now(),
    withdrawn_at       timestamptz
);
CREATE INDEX consent_patient_idx ON consent.consent (patient_id, status);
