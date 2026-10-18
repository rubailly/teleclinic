package rw.teleclinic.core.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import rw.teleclinic.core.IntegrationTest;

class AuditChainTests extends IntegrationTest {

    @Autowired
    AuditChain chain;

    @Autowired
    JdbcClient jdbc;

    private AuditEntry entry(String purpose) {
        return new AuditEntry(UUID.randomUUID(), Instant.now(), AuditEntry.APPLICATION, "subject-1", "clinician", "Dr Test",
                "READ", "Patient", "p-1", UUID.fromString(KEZA_PATIENT_ID), purpose, "consent:x", AuditEntry.SUCCESS, null,
                "cid-1", Map.of("k", "v"));
    }

    /** Runs a change the way a database administrator would have to: switch off the append-only trigger first. */
    private void asAdministrator(String sql) {
        jdbc.sql("ALTER TABLE audit.event DISABLE TRIGGER audit_event_append_only").update();
        try {
            jdbc.sql(sql).update();
        } finally {
            jdbc.sql("ALTER TABLE audit.event ENABLE TRIGGER audit_event_append_only").update();
        }
    }

    @Test
    void an_untouched_chain_verifies_and_a_redelivery_is_written_once() {
        AuditEntry e = entry("TREAT");
        assertThat(chain.append(e)).isTrue();
        assertThat(chain.append(e)).isFalse();
        chain.append(entry("TREAT"));
        assertThat(chain.verify().status()).isEqualTo("INTACT");
    }

    @Test
    void the_trail_is_append_only_for_the_application() {
        chain.append(entry("TREAT"));
        assertThatThrownBy(() -> jdbc.sql("UPDATE audit.event SET purpose = 'OPERATIONS'").update())
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM audit.event").update()).hasMessageContaining("append-only");
    }

    @Test
    void a_changed_field_is_found_at_its_entry() {
        chain.append(entry("TREAT"));
        long target = jdbc.sql("SELECT max(seq) FROM audit.event").query(Long.class).single();
        chain.append(entry("TREAT"));
        asAdministrator("UPDATE audit.event SET purpose = 'BREAK_GLASS' WHERE seq = " + target);
        try {
            AuditChain.Status s = chain.verify();
            assertThat(s.status()).isEqualTo("BROKEN");
            assertThat(s.firstBrokenSeq()).isEqualTo(target);
            assertThat(s.problem()).contains("does not match its hash");
        } finally {
            asAdministrator("UPDATE audit.event SET purpose = 'TREAT' WHERE seq = " + target);
        }
        assertThat(chain.verify().status()).isEqualTo("INTACT");
    }

    @Test
    void a_deleted_entry_is_found() {
        chain.append(entry("TREAT"));
        long target = jdbc.sql("SELECT max(seq) FROM audit.event").query(Long.class).single();
        chain.append(entry("TREAT"));
        jdbc.sql("CREATE TABLE IF NOT EXISTS public.audit_test_saved AS SELECT * FROM audit.event WITH NO DATA").update();
        jdbc.sql("INSERT INTO public.audit_test_saved SELECT * FROM audit.event WHERE seq = " + target).update();
        asAdministrator("DELETE FROM audit.event WHERE seq = " + target);
        try {
            AuditChain.Status s = chain.verify();
            assertThat(s.status()).isEqualTo("BROKEN");
            assertThat(s.firstBrokenSeq()).isEqualTo(target);
            assertThat(s.problem()).contains("missing");
        } finally {
            jdbc.sql("INSERT INTO audit.event SELECT * FROM public.audit_test_saved").update();
            jdbc.sql("DROP TABLE public.audit_test_saved").update();
        }
        assertThat(chain.verify().status()).isEqualTo("INTACT");
    }

    @Test
    void appends_while_verifying_never_look_like_a_break() throws Exception {
        chain.append(entry("TREAT"));
        var writer = new Thread(() -> {
            for (int i = 0; i < 40; i++) {
                chain.append(entry("TREAT"));
            }
        });
        writer.start();
        for (int i = 0; i < 15; i++) {
            assertThat(chain.verify().status()).isEqualTo("INTACT");
        }
        writer.join();
        assertThat(chain.verify().status()).isEqualTo("INTACT");
    }
}
