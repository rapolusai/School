package com.akshara;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Keeps module boundaries honest: no cycles, and modules only use each other's public types. */
class ModularityTests {

    @Test
    void modulesRespectTheirBoundaries() {
        ApplicationModules.of(AksharaApplication.class).verify();
    }
}
