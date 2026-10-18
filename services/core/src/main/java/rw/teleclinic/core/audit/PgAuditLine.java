package rw.teleclinic.core.audit;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns one PostgreSQL log line written by pgAudit into an audit entry. The log line prefix is set in Patroni
 * ({@code %m [%p] user=%u db=%d app=%a client=%h }); the AUDIT part is pgAudit's CSV:
 * type, statement id, substatement id, class, command, object type, object name, statement, parameter.
 */
final class PgAuditLine {

    // Sensitive schemas; statements on other objects (the HA probe table, catalogs) are not health data
    private static final Pattern SENSITIVE = Pattern.compile("(?i)\\b(patient_index|consent|consultation|triage|payments|identity|audit)\\.");
    private static final Pattern PREFIX = Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) \\w+ \\[(\\d+)\\] user=(\\S*) db=(\\S*) app=(.*?) client=(\\S*) .*?AUDIT: (.*)$");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private PgAuditLine() {
    }

    /** Empty when the line is not an audit line or concerns no sensitive data. */
    static Optional<AuditEntry> parse(String line, String node) {
        Matcher m = PREFIX.matcher(line.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        List<String> f = csv(m.group(7));
        if (f.size() < 8) {
            return Optional.empty();
        }
        String auditClass = f.get(3);
        String command = f.get(4);
        String object = f.get(6);
        String statement = f.get(7);
        boolean sensitive = SENSITIVE.matcher(object).find() || SENSITIVE.matcher(statement).find();
        if (!sensitive && !auditClass.equals("ROLE")) {
            return Optional.empty();
        }
        Instant at = LocalDateTime.parse(m.group(1), TS).toInstant(ZoneOffset.UTC);
        // Same line delivered twice gives the same id, so the chain writes it once
        String key = node + "|" + m.group(2) + "|" + m.group(1) + "|" + f.get(1) + "|" + f.get(2) + "|" + object;
        UUID id = UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
        Map<String, String> detail = new LinkedHashMap<>();
        detail.put("node", node);
        detail.put("statement", statement.length() > 300 ? statement.substring(0, 300) + "…" : statement);
        detail.put("application", m.group(5));
        detail.put("client", m.group(6));
        return Optional.of(new AuditEntry(id, at, AuditEntry.DATABASE, m.group(3), "database role", null,
                auditClass + " " + command, object.isEmpty() ? null : "table", object.isEmpty() ? null : object, null,
                null, null, AuditEntry.SUCCESS, null, null, detail));
    }

    /** pgAudit quotes a field that contains a comma or a quote, doubling inner quotes, like standard CSV. */
    static List<String> csv(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        out.add(field.toString());
        return out;
    }
}
