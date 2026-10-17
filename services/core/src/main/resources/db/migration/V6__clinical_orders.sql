-- Orders, referrals and prescriptions. Only allowed once triage is FINAL (enforced in the consultation module).
CREATE TABLE consultation.clinical_order (
    id                uuid PRIMARY KEY,
    consultation_id   uuid NOT NULL REFERENCES consultation.consultation (id),
    kind              text NOT NULL CHECK (kind IN ('PRESCRIPTION', 'REFERRAL', 'LAB')),
    detail            text NOT NULL,
    ordered_by        text NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now()
);
