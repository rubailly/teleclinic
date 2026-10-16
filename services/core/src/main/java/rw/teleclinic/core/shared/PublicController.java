package rw.teleclinic.core.shared;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Unauthenticated endpoints: liveness through the edge and the running release. */
@RestController
@RequestMapping("/api/public")
class PublicController {

    private final ReleaseProperties release;

    PublicController(ReleaseProperties release) {
        this.release = release;
    }

    @GetMapping("/ping")
    Map<String, String> ping() {
        return Map.of("status", "ok", "service", "teleclinic-core");
    }

    @GetMapping("/version")
    Map<String, String> version() {
        return Map.of("version", release.version(), "color", release.color());
    }
}
