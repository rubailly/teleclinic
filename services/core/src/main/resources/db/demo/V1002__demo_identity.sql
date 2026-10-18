-- Synthetic patients known to TeleClinic who also have a record at the demo facility (OpenMRS), for identity matching.
-- The facility's side is seeded by scripts/emr/seed.py. Identifiers use the 0000 prefix: none can belong to a real person.
INSERT INTO patient_index.patient (id, irembo_subject, national_id, upi, given_name, family_name, birth_date, sex, phone) VALUES
    -- Worked example in design 4.5.3: the facility holds "Uwimana Marie" born 1990-03-04 without a national ID
    ('10000000-0000-4000-b000-000000000011', NULL, '0000199000000004', NULL, 'Marie', 'Uwimana', '1990-04-03', 'F', NULL),
    -- Same name and birth date at the facility, but a different national ID: a hard block, never offered
    ('10000000-0000-4000-b000-000000000012', NULL, '0000198500000088', NULL, 'Jean', 'Habimana', '1985-06-10', 'M', NULL),
    -- The facility holds the same UPI: two strong identifiers agree (UPI, exact name and birth date), linked automatically
    ('10000000-0000-4000-b000-000000000013', NULL, NULL, '160921-0000-0042', 'Aline', 'Mukeshimana', '2016-09-21', 'F', NULL);
