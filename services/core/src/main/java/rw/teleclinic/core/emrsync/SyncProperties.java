package rw.teleclinic.core.emrsync;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import rw.teleclinic.core.shared.ApiException;

/**
 * key: shared with the sync gateway's credential (stand-in for OAuth client credentials).
 * targets: the EMRs TeleClinic writes to, by system code (the facility registry's emr_system). Each has a display name
 * and the gateway's webhook triggers for its workflows, called to start a run at once; empty to rely on the schedule.
 */
@ConfigurationProperties("teleclinic.sync")
public record SyncProperties(String key, Map<String, Target> targets, int maxAttempts) {

    public record Target(String name, String outboundHook, String inboundHook) {
    }

    Target target(String system) {
        Target t = system == null ? null : targets.get(system);
        if (t == null) {
            throw ApiException.unprocessable("unknown-system", "Not an EMR TeleClinic syncs with: " + system);
        }
        return t;
    }
}
