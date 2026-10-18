package rw.teleclinic.core.emrsync;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import rw.teleclinic.core.matching.IdentityEvents.PatientLinked;
import rw.teleclinic.core.matching.IdentityEvents.PatientUnlinked;
import rw.teleclinic.core.triage.TriageFinalised;

/** Turns domain events into outbound sync work, then asks the gateway to run now (its schedule is the safety net). */
@Component
class SyncListeners {

    private static final Logger log = LoggerFactory.getLogger(SyncListeners.class);
    private final SyncQueue queue;
    private final SyncProperties props;
    private final RestClient http;

    SyncListeners(SyncQueue queue, SyncProperties props, RestClient.Builder builder) {
        this.queue = queue;
        this.props = props;
        var timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(Duration.ofSeconds(2));
        timeouts.setReadTimeout(Duration.ofSeconds(3));
        this.http = builder.clone().requestFactory(timeouts).build();
    }

    @ApplicationModuleListener
    void on(TriageFinalised e) {
        String target = queue.enqueueEncounter(e);
        if (target != null) {
            kick(props.target(target).outboundHook());
        }
    }

    @ApplicationModuleListener
    void on(PatientLinked e) {
        if (queue.enqueueIdentifiers("ADD_IDENTIFIER", e.system(), e.linkId(), e.patientId(), e.emrUuid(), e.identifiers())) {
            kick(props.target(e.system()).outboundHook());
        }
    }

    @ApplicationModuleListener
    void on(PatientUnlinked e) {
        if (queue.enqueueIdentifiers("REMOVE_IDENTIFIER", e.system(), e.linkId(), e.patientId(), e.emrUuid(), e.identifiers())) {
            kick(props.target(e.system()).outboundHook());
        }
    }

    void kick(String hook) {
        if (hook == null || hook.isBlank()) {
            return;
        }
        try {
            http.post().uri(hook).body("{\"reason\":\"work queued\"}").header("Content-Type", "application/json")
                    .retrieve().toBodilessEntity();
        } catch (RuntimeException ex) {
            // Best effort: the gateway's schedule picks the work up anyway
            log.info("Sync gateway not reachable to start a run ({}); the next scheduled run takes the work", ex.getMessage());
        }
    }
}
