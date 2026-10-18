package rw.teleclinic.core.patientindex;

/**
 * A health facility and the EMR it keeps records in. emrSystem is the sync target ('ebuzima' for every health centre,
 * 'openmrs-...' for a hospital's own installation); emrTenant is the eBuzima company the facility is, if any.
 */
public record Facility(String code, String name, String level, String district, String emrSystem, String emrTenant) {
}
