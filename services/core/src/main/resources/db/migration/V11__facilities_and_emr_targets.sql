-- Facilities and the EMR each one keeps its records in (Section 4.5.2): health centres are tenants of the central
-- eBuzima instance; district, referral and teaching hospitals run their own OpenMRS. A patient's home facility decides
-- where the outcome of a teleconsultation is written.
CREATE TABLE patient_index.facility (
    code        text PRIMARY KEY,               -- facility registry code
    name        text NOT NULL,
    level       text NOT NULL CHECK (level IN ('HC', 'DH', 'RH', 'TH')),
    district    text NOT NULL,
    emr_system  text NOT NULL,                  -- sync target: 'ebuzima', or 'openmrs-<hospital>'
    emr_tenant  text                            -- the eBuzima company the facility is; NULL for its own installation
);

ALTER TABLE patient_index.patient ADD COLUMN home_facility text REFERENCES patient_index.facility (code);

-- Every sync record names the EMR it belongs to: the same patient can be known to eBuzima and to a hospital's
-- OpenMRS, and their record ids do not share a namespace
ALTER TABLE sync.outbound ADD COLUMN target text;
UPDATE sync.outbound SET target = 'openmrs-kibagabaga';
ALTER TABLE sync.outbound ALTER COLUMN target SET NOT NULL,
    DROP CONSTRAINT outbound_kind_reference_key,
    ADD CONSTRAINT outbound_target_kind_reference_key UNIQUE (target, kind, reference);
DROP INDEX sync.sync_outbound_due_idx;
CREATE INDEX sync_outbound_due_idx ON sync.outbound (target, status, next_attempt_at, created_at);

ALTER TABLE sync.ledger ADD COLUMN system text;
UPDATE sync.ledger SET system = 'openmrs-kibagabaga';
ALTER TABLE sync.ledger ALTER COLUMN system SET NOT NULL,
    DROP CONSTRAINT ledger_pkey,
    DROP CONSTRAINT ledger_resource_type_emr_uuid_key,
    ADD PRIMARY KEY (system, resource_type, teleclinic_id),
    ADD CONSTRAINT ledger_system_resource_type_emr_uuid_key UNIQUE (system, resource_type, emr_uuid);

ALTER TABLE sync.event ADD COLUMN system text;

ALTER TABLE matching.candidate ADD COLUMN system text;
UPDATE matching.candidate SET system = 'openmrs-kibagabaga';
ALTER TABLE matching.candidate ALTER COLUMN system SET NOT NULL,
    DROP CONSTRAINT candidate_emr_uuid_patient_id_key,
    ADD CONSTRAINT candidate_system_emr_uuid_patient_id_key UNIQUE (system, emr_uuid, patient_id);

ALTER TABLE matching.link ADD COLUMN system text;
UPDATE matching.link SET system = 'openmrs-kibagabaga';
ALTER TABLE matching.link ALTER COLUMN system SET NOT NULL;
DROP INDEX matching.matching_link_active_idx;
CREATE UNIQUE INDEX matching_link_active_idx ON matching.link (system, emr_uuid) WHERE active;
