package rw.teleclinic.core;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Fails the build when a module reaches into another module's internals (ADR-001, "seams are real"). */
class ModularityTests {

    @Test
    void moduleBoundariesAreRespected() {
        ApplicationModules.of(TeleClinicApplication.class).verify();
    }
}
