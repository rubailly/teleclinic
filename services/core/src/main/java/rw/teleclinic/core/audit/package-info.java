/**
 * Audit module (design Section 4.6.4). Every read and write of health data is recorded with who, what, which
 * patient, why (purpose of use) and on what basis (consent, data subject, break-glass), together with database
 * activity captured by pgAudit, in one hash-chained, append-only trail that anyone with the auditor role can verify.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Audit")
package rw.teleclinic.core.audit;
