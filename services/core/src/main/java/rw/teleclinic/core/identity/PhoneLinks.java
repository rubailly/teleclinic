package rw.teleclinic.core.identity;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import rw.teleclinic.core.shared.ApiException;

/**
 * Identity on a feature phone (Section 4.3): the phone number was linked once to an IremboAccount subject, the PIN
 * unlocks it, and the USSD session gets a short-lived token limited to the channel. Three wrong PINs lock the link.
 */
@Service
public class PhoneLinks {

    public record ChannelSession(String token, String subject, String msisdn, Instant expiresAt) {
    }

    static final int MAX_ATTEMPTS = 3;
    static final Duration LOCK = Duration.ofMinutes(15);
    static final Duration TOKEN_TTL = Duration.ofMinutes(5);

    private final JdbcClient jdbc;
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder();
    private final SecureRandom random = new SecureRandom();

    PhoneLinks(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isLinked(String msisdn) {
        return jdbc.sql("SELECT count(*) FROM identity.phone_link WHERE msisdn = ?").param(msisdn).query(Long.class).single() > 0;
    }

    /** Token exchange: phone number and PIN in, a USSD-scoped token out. */
    @Transactional(noRollbackFor = ApiException.class)
    public ChannelSession unlock(String msisdn, String pin) {
        Map<String, Object> link = jdbc.sql("SELECT * FROM identity.phone_link WHERE msisdn = ? FOR UPDATE").param(msisdn)
                .query().listOfRows().stream().findFirst()
                .orElseThrow(() -> ApiException.notFound("This phone number is not linked to an IremboAccount"));
        Timestamp lockedUntil = (Timestamp) link.get("locked_until");
        if (lockedUntil != null && lockedUntil.toInstant().isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.LOCKED, "locked", "Too many wrong PINs; try again later");
        }
        if (pin == null || !bcrypt.matches(pin, (String) link.get("pin_hash"))) {
            int attempts = (Integer) link.get("failed_attempts") + 1;
            boolean lock = attempts >= MAX_ATTEMPTS;
            jdbc.sql("UPDATE identity.phone_link SET failed_attempts = ?, locked_until = ? WHERE msisdn = ?")
                    .params(lock ? 0 : attempts, lock ? Timestamp.from(Instant.now().plus(LOCK)) : null, msisdn).update();
            throw new ApiException(HttpStatus.UNAUTHORIZED, lock ? "locked" : "wrong-pin",
                    lock ? "Too many wrong PINs; locked for 15 minutes" : "Wrong PIN, " + (MAX_ATTEMPTS - attempts) + " tries left");
        }
        jdbc.sql("UPDATE identity.phone_link SET failed_attempts = 0, locked_until = NULL WHERE msisdn = ?").param(msisdn).update();
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        String token = "ussd_" + HexFormat.of().formatHex(bytes);
        Instant expires = Instant.now().plus(TOKEN_TTL);
        String subject = (String) link.get("subject");
        jdbc.sql("INSERT INTO identity.channel_token (token, subject, msisdn, scope, expires_at) VALUES (?, ?, ?, 'ussd', ?)")
                .params(token, subject, msisdn, Timestamp.from(expires)).update();
        return new ChannelSession(token, subject, msisdn, expires);
    }

    /** The subject behind a live USSD token, or 401. */
    @Transactional(readOnly = true)
    public ChannelSession resolve(String token) {
        return Optional.ofNullable(token).flatMap(t -> jdbc.sql(
                        "SELECT token, subject, msisdn, expires_at FROM identity.channel_token WHERE token = ? AND expires_at > now()")
                .param(t)
                .query((rs, n) -> new ChannelSession(rs.getString("token"), rs.getString("subject"), rs.getString("msisdn"),
                        rs.getTimestamp("expires_at").toInstant()))
                .optional())
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "session-expired", "USSD session expired; dial again"));
    }
}
