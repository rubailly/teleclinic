package rw.teleclinic.core.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import rw.teleclinic.core.patientindex.Patient;

class MatchScoreTests {

    private static Patient tc(String given, String family, String birth, String sex, String nid, String upi) {
        return new Patient(UUID.randomUUID(), null, nid, upi, given, family, LocalDate.parse(birth), sex, null);
    }

    private static EmrPatient emr(String given, String family, String birth, String sex, String nid, String upi) {
        return new EmrPatient("emr-1", given, family, sex, LocalDate.parse(birth), null, nid, upi, null);
    }

    @Test
    void the_worked_example_goes_to_a_clerk() {
        // design 4.5.3: names in a different order, day and month swapped, no national ID at the facility
        MatchScore s = MatchScore.of(tc("Marie", "Uwimana", "1990-04-03", "F", "0000199000000004", null),
                emr("Uwimana", "Marie", "1990-03-04", "F", null, null));
        assertThat(s.band()).isEqualTo(MatchScore.Band.REVIEW);
        assertThat(s.agreeing()).contains("name", "birth date (day and month swapped)", "sex");
        assertThat(s.strongAgreements()).isZero();
    }

    @Test
    void different_national_ids_are_never_linked_however_close_the_rest() {
        MatchScore s = MatchScore.of(tc("Jean", "Habimana", "1985-06-10", "M", "0000198500000088", null),
                emr("Jean", "Habimana", "1985-06-10", "M", "0000198500000077", null));
        assertThat(s.band()).isEqualTo(MatchScore.Band.BLOCKED);
    }

    @Test
    void two_strong_identifiers_link_automatically() {
        MatchScore s = MatchScore.of(tc("Aline", "Mukeshimana", "2016-09-21", "F", null, "160921-0000-0042"),
                emr("Aline", "Mukeshimana", "2016-09-21", "F", null, "160921-0000-0042"));
        assertThat(s.band()).isEqualTo(MatchScore.Band.AUTO);
        assertThat(s.strongAgreements()).isEqualTo(2);
    }

    @Test
    void one_strong_identifier_alone_is_not_enough_for_an_automatic_link() {
        // same UPI but a birth date that disagrees: a clerk decides
        MatchScore s = MatchScore.of(tc("Aline", "Mukeshimana", "2016-09-21", "F", null, "160921-0000-0042"),
                emr("Aline", "Mukeshimana", "2015-02-11", "F", null, "160921-0000-0042"));
        assertThat(s.band()).isEqualTo(MatchScore.Band.REVIEW);
    }

    @Test
    void unrelated_people_are_no_match() {
        MatchScore s = MatchScore.of(tc("Eric", "Mugisha", "1988-02-03", "M", null, null),
                emr("Pacifique", "Nkurunziza", "1975-11-02", "M", null, null));
        assertThat(s.band()).isEqualTo(MatchScore.Band.NO_MATCH);
    }

    @Test
    void names_ignore_order_case_and_accents() {
        assertThat(MatchScore.nameSimilarity("Marie Uwimana", "UWIMANA marié")).isEqualTo(1.0);
        assertThat(MatchScore.nameSimilarity("Jean Habimana", "Jean Habimanna")).isGreaterThan(0.95);
    }
}
