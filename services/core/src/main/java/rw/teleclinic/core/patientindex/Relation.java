package rw.teleclinic.core.patientindex;

/** How the signed-in person relates to a patient record. */
public enum Relation {
    /** The record is the person's own. */
    SELF,
    /** The person is a verified guardian of the patient (FHIR RelatedPerson). */
    GUARDIAN,
    /** No relation: the person may not act for this patient. */
    NONE
}
