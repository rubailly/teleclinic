package rw.teleclinic.core.patientindex;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.teleclinic.core.shared.ApiException;

/**
 * The patient index's public API. Other modules ask it who a person is and whom they may act for;
 * they never read its tables.
 */
@Service
@Transactional(readOnly = true)
public class PatientDirectory {

    /** A patient the signed-in person can book for, and how they are related. */
    public record Actable(Patient patient, Relation relation, String relationship) {
    }

    private static final RowMapper<Patient> PATIENT = (rs, n) -> new Patient(
            rs.getObject("id", UUID.class),
            rs.getString("irembo_subject"),
            rs.getString("national_id"),
            rs.getString("upi"),
            rs.getString("given_name"),
            rs.getString("family_name"),
            rs.getObject("birth_date", LocalDate.class),
            rs.getString("sex"),
            rs.getString("phone"));

    private final JdbcClient jdbc;

    PatientDirectory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Patient> findById(UUID id) {
        return jdbc.sql("SELECT * FROM patient_index.patient WHERE id = ?").param(id).query(PATIENT).optional();
    }

    public Patient require(UUID id) {
        return findById(id).orElseThrow(() -> ApiException.notFound("Patient " + id + " not found"));
    }

    /** The person's own record, found by the stable IremboAccount subject (never by name or phone). */
    public Optional<Patient> findBySubject(String subject) {
        return jdbc.sql("SELECT * FROM patient_index.patient WHERE irembo_subject = ?").param(subject)
                .query(PATIENT).optional();
    }

    public Relation relation(String subject, UUID patientId) {
        Optional<Patient> self = findBySubject(subject);
        if (self.isPresent() && self.get().id().equals(patientId)) {
            return Relation.SELF;
        }
        boolean guardian = jdbc.sql("""
                        SELECT count(*) FROM patient_index.related_person
                        WHERE guardian_subject = ? AND patient_id = ? AND verified""")
                .param(subject).param(patientId).query(Long.class).single() > 0;
        return guardian ? Relation.GUARDIAN : Relation.NONE;
    }

    /** Own record first, then verified dependents: the "who is this for" choice. */
    public List<Actable> actableBy(String subject) {
        List<Actable> result = new ArrayList<>();
        findBySubject(subject).ifPresent(p -> result.add(new Actable(p, Relation.SELF, "SELF")));
        jdbc.sql("""
                        SELECT p.*, r.relationship FROM patient_index.related_person r
                        JOIN patient_index.patient p ON p.id = r.patient_id
                        WHERE r.guardian_subject = ? AND r.verified ORDER BY p.given_name""")
                .param(subject)
                .query((rs, n) -> new Actable(PATIENT.mapRow(rs, n), Relation.GUARDIAN, rs.getString("relationship")))
                .list()
                .forEach(result::add);
        return result;
    }
}
