-- Patient index module. Identifiers are sliced by system (Section 4.5.1): IremboAccount subject,
-- national ID (NIDA), UPI from the national client registry. A child may have only a UPI.
CREATE TABLE patient_index.patient (
    id              uuid PRIMARY KEY,
    irembo_subject  text UNIQUE,
    national_id     text UNIQUE,
    upi             text UNIQUE,
    given_name      text NOT NULL,
    family_name     text NOT NULL,
    birth_date      date NOT NULL,
    sex             text NOT NULL CHECK (sex IN ('F', 'M', 'OTHER', 'UNKNOWN')),
    phone           text,
    created_at      timestamptz NOT NULL DEFAULT now()
);

-- A guardian acting for a dependent: FHIR RelatedPerson. The guardian is identified by their IremboAccount subject.
CREATE TABLE patient_index.related_person (
    id                uuid PRIMARY KEY,
    patient_id        uuid NOT NULL REFERENCES patient_index.patient (id),
    guardian_subject  text NOT NULL,
    relationship      text NOT NULL,          -- HL7 v3 RoleCode, for example MTH, FTH, GRD
    verified          boolean NOT NULL DEFAULT false,
    created_at        timestamptz NOT NULL DEFAULT now(),
    UNIQUE (patient_id, guardian_subject)
);
CREATE INDEX related_person_guardian_idx ON patient_index.related_person (guardian_subject);
