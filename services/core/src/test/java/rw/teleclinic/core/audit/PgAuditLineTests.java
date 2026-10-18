package rw.teleclinic.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PgAuditLineTests {

    // As written by PostgreSQL with the Patroni log_line_prefix (services/patroni/patroni.yml)
    static final String DBA_READ = "2026-10-18 07:30:26.709 UTC [308] user=postgres db=teleclinic app=psql client=[local] LOG:  "
            + "AUDIT: SESSION,1,1,READ,SELECT,TABLE,patient_index.patient,\"SELECT given_name, national_id FROM patient_index.patient\",<not logged>";
    static final String PROBE = "2026-10-18 07:30:11.942 UTC [220] user=teleclinic db=teleclinic app=[unknown] client=192.168.112.10 LOG:  "
            + "AUDIT: SESSION,1,1,DDL,ALTER SEQUENCE,SEQUENCE,public.ha_probe_id_seq,\"CREATE TABLE IF NOT EXISTS ha_probe (id bigserial)\",<not logged>";

    @Test
    void an_administrators_read_of_patient_data_becomes_a_database_entry() {
        AuditEntry e = PgAuditLine.parse(DBA_READ, "pg-2").orElseThrow();
        assertThat(e.source()).isEqualTo(AuditEntry.DATABASE);
        assertThat(e.actor()).isEqualTo("postgres");
        assertThat(e.action()).isEqualTo("READ SELECT");
        assertThat(e.resourceId()).isEqualTo("patient_index.patient");
        assertThat(e.detail()).containsEntry("node", "pg-2").containsEntry("client", "[local]")
                .containsEntry("statement", "SELECT given_name, national_id FROM patient_index.patient");
        assertThat(e.recordedAt().toString()).isEqualTo("2026-10-18T07:30:26.709Z");
    }

    @Test
    void the_same_line_shipped_twice_has_the_same_id() {
        assertThat(PgAuditLine.parse(DBA_READ, "pg-2").orElseThrow().eventId())
                .isEqualTo(PgAuditLine.parse(DBA_READ, "pg-2").orElseThrow().eventId());
    }

    @Test
    void activity_on_objects_without_health_data_is_left_out() {
        assertThat(PgAuditLine.parse(PROBE, "pg-1")).isEmpty();
        assertThat(PgAuditLine.parse("2026-10-18 07:30:11.942 UTC [1] LOG:  checkpoint complete", "pg-1")).isEmpty();
    }

    @Test
    void quoted_fields_keep_commas_and_doubled_quotes() {
        assertThat(PgAuditLine.csv("a,\"b, c\",\"say \"\"hi\"\"\",d")).containsExactly("a", "b, c", "say \"hi\"", "d");
    }
}
