-- Synthetic demo data. Real facility names, made-up registry codes (0000 prefix). Kimironko and Remera health centres
-- are two tenants of the one eBuzima instance; Kibagabaga District Hospital, their referral hospital, runs OpenMRS.
INSERT INTO patient_index.facility (code, name, level, district, emr_system, emr_tenant) VALUES
    ('0000-HC-0101', 'Kimironko Health Centre', 'HC', 'Gasabo', 'ebuzima', 'Kimironko Health Centre'),
    ('0000-HC-0102', 'Remera Health Centre', 'HC', 'Gasabo', 'ebuzima', 'Remera Health Centre'),
    ('0000-DH-0011', 'Kibagabaga District Hospital', 'DH', 'Gasabo', 'openmrs-kibagabaga', NULL);

-- Uwase and her daughter Keza are registered at Kimironko HC; Eric and the identity-matching patients are followed
-- at Kibagabaga District Hospital
UPDATE patient_index.patient SET home_facility = '0000-HC-0101'
    WHERE id IN ('10000000-0000-4000-b000-000000000001', '10000000-0000-4000-b000-000000000003');
UPDATE patient_index.patient SET home_facility = '0000-DH-0011'
    WHERE id IN ('10000000-0000-4000-b000-000000000002', '10000000-0000-4000-b000-000000000011',
                 '10000000-0000-4000-b000-000000000012', '10000000-0000-4000-b000-000000000013');
