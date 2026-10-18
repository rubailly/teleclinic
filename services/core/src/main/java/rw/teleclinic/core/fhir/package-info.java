/**
 * FHIR R4 facade (design 4.5): translates the canonical model to FHIR at the boundary. Every clinical read goes
 * through the audit module's access policy (purpose of use, consent) and is recorded.
 */
@org.springframework.modulith.ApplicationModule(displayName = "FHIR facade")
package rw.teleclinic.core.fhir;
