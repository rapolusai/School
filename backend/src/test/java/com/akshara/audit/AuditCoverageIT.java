package com.akshara.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.akshara.support.IntegrationTest;

/**
 * Every endpoint that changes something must leave an entry in the audit trail, or be listed with the reason it does
 * not (a preview that changes nothing, a GPS ping, a read receipt). The list is
 * {@code src/test/resources/audit/mutating-endpoints.txt}: one line per POST, PUT, PATCH or DELETE endpoint,
 * {@code METHOD /path | audited} or {@code METHOD /path | exempt: why}. A new endpoint fails this test until it is
 * added there, so nobody adds a change without deciding how it is audited; the integration tests of each module check
 * the entries themselves.
 */
class AuditCoverageIT extends IntegrationTest {

    static final String LIST = "/audit/mutating-endpoints.txt";

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void everyEndpointThatChangesSomethingIsAuditedOrExemptWithAReason() throws IOException {
        Set<String> live = new TreeSet<>();
        mappings.getHandlerMethods().forEach((info, method) -> {
            for (RequestMethod m : info.getMethodsCondition().getMethods()) {
                if (m == RequestMethod.POST || m == RequestMethod.PUT || m == RequestMethod.PATCH
                        || m == RequestMethod.DELETE) {
                    for (String pattern : info.getPatternValues()) {
                        live.add(m.name() + " " + pattern);
                    }
                }
            }
        });
        TreeMap<String, String> listed = new TreeMap<>();
        try (InputStream in = getClass().getResourceAsStream(LIST)) {
            assertThat(in).as(LIST).isNotNull();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String trimmed = line.strip();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = trimmed.split("\\s*\\|\\s*", 2);
                assertThat(parts).as("line '%s' needs 'METHOD /path | audited' or '| exempt: why'", trimmed)
                        .hasSize(2);
                String verdict = parts[1];
                assertThat(verdict.equals("audited") || verdict.matches("exempt: .{10,}"))
                        .as("verdict of '%s'", trimmed).isTrue();
                assertThat(listed.put(parts[0], verdict)).as("listed twice: %s", parts[0]).isNull();
            }
        }
        Set<String> unlisted = new TreeSet<>(live);
        unlisted.removeAll(listed.keySet());
        Set<String> gone = new TreeSet<>(listed.keySet());
        gone.removeAll(live);
        assertThat(unlisted).as("endpoints that change something but are not in " + LIST
                + ": add each as audited (and audit it) or exempt with a reason").isEmpty();
        assertThat(gone).as("endpoints in " + LIST + " that no longer exist").isEmpty();
    }
}
