-- Synthetic demo data only. Identifiers use a 0000 prefix and facility code 0000 so none can belong to a real person.
-- Subjects match the fixed user ids in config/keycloak/irembo-mock-realm.json.
INSERT INTO patient_index.patient (id, irembo_subject, national_id, upi, given_name, family_name, birth_date, sex, phone) VALUES
    ('10000000-0000-4000-b000-000000000001', '00000000-0000-4000-a000-000000000001', '0000199200000001', NULL,
     'Uwase', 'Demo', '1992-07-21', 'F', '+250788000001'),
    ('10000000-0000-4000-b000-000000000002', '00000000-0000-4000-a000-000000000002', '0000198800000002', NULL,
     'Eric', 'Demo', '1988-02-03', 'M', '+250788000002'),
    -- Keza: Uwase's daughter, no IremboAccount and no national ID; identified by a UPI (YYMMDD-FOSAID-RAND4)
    ('10000000-0000-4000-b000-000000000003', NULL, NULL, '180514-0000-0001',
     'Keza', 'Demo', '2018-05-14', 'F', NULL);

INSERT INTO patient_index.related_person (id, patient_id, guardian_subject, relationship, verified) VALUES
    ('20000000-0000-4000-b000-000000000001', '10000000-0000-4000-b000-000000000003',
     '00000000-0000-4000-a000-000000000001', 'MTH', true);
