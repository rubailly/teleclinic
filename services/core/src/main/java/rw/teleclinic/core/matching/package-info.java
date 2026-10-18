/**
 * Patient identity matching (design 4.5.3): records from a facility EMR are compared with the patient index by blocking
 * then scoring. Three outcomes: auto-link only when two strong identifiers agree, a clerk's review queue in the middle,
 * no match below. Two different national IDs are a hard block. Records are linked, never merged, and every link and
 * unlink is audited and reversible.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Identity matching")
package rw.teleclinic.core.matching;
