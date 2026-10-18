package rw.teleclinic.core.emrsync;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * key: shared with the sync gateway's credential (stand-in for OAuth client credentials).
 * outboundHook and inboundHook: the gateway's webhook triggers, called to start a run at once; empty to rely on its schedule.
 */
@ConfigurationProperties("teleclinic.sync")
public record SyncProperties(String key, String outboundHook, String inboundHook, String externalSystem, int maxAttempts) {
}
