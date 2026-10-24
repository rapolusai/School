package com.akshara.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;

/**
 * Sign-in throttling and the public limits live in PostgreSQL, so they hold across API instances. Two "instances" here
 * are two separate stores and limiters over the same database, as two API servers would be.
 */
class SharedRateLimitIT extends IntegrationTest {

    @Autowired
    DataSource dataSource;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    AksharaProperties properties;

    @Test
    void failuresCountedOnOneInstanceBlockTheOther() throws Exception {
        JdbcRateLimitStore storeA = new JdbcRateLimitStore(dataSource, transactions);
        JdbcRateLimitStore storeB = new JdbcRateLimitStore(dataSource, transactions);
        LoginRateLimiter serverA = new LoginRateLimiter(3, Duration.ofMinutes(5), Clock.systemUTC(), storeA);
        LoginRateLimiter serverB = new LoginRateLimiter(3, Duration.ofMinutes(5), Clock.systemUTC(), storeB);
        String key = "school|10.20.30.40|" + UUID.randomUUID() + "|asha@example.test";

        serverA.recordFailure(key);
        serverB.recordFailure(key);
        assertThatCode(() -> serverA.check(key)).doesNotThrowAnyException();
        serverA.recordFailure(key);
        assertThatThrownBy(() -> serverB.check(key)).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.status().value()).isEqualTo(429));
        assertThatThrownBy(() -> serverA.check(key)).isInstanceOf(ApiException.class);

        // Only the key's hash is stored, never the email or the IP address in it.
        try (Connection owner = ownerConnection(); PreparedStatement s = owner.prepareStatement(
                "select key_hash, hits from platform.rate_limit where key_hash = ?")) {
            s.setString(1, JdbcRateLimitStore.hash("login|" + key));
            try (ResultSet rs = s.executeQuery()) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt("hits")).isEqualTo(3);
                assertThat(rs.getString("key_hash")).hasSize(64).doesNotContain("asha");
            }
        }

        // A successful sign-in on either instance clears it for both.
        serverB.reset(key);
        assertThatCode(() -> serverA.check(key)).doesNotThrowAnyException();
    }

    @Test
    void concurrentHitsFromManyInstancesAreNeverLost() throws Exception {
        List<JdbcRateLimitStore> servers = List.of(new JdbcRateLimitStore(dataSource, transactions),
                new JdbcRateLimitStore(dataSource, transactions), new JdbcRateLimitStore(dataSource, transactions));
        String key = "test|" + UUID.randomUUID();
        Instant now = Instant.now();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Integer>> work = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                JdbcRateLimitStore server = servers.get(i % servers.size());
                work.add(() -> server.hit(key, Duration.ofMinutes(10), now));
            }
            List<Integer> counts = new ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(work)) {
                counts.add(f.get());
            }
            // Every hit got its own count: 1 to 60, each exactly once.
            assertThat(counts).containsExactlyInAnyOrderElementsOf(
                    java.util.stream.IntStream.rangeClosed(1, 60).boxed().toList());
        } finally {
            pool.shutdownNow();
        }
        assertThat(servers.get(1).hits(key, now)).isEqualTo(60);
    }

    @Test
    void aWindowEndsAndOldRowsArePruned() {
        JdbcRateLimitStore store = new JdbcRateLimitStore(dataSource, transactions);
        String key = "test|" + UUID.randomUUID();
        Instant start = Instant.now().minus(Duration.ofHours(2)).atOffset(ZoneOffset.UTC).toInstant();
        assertThat(store.hit(key, Duration.ofMinutes(10), start)).isEqualTo(1);
        assertThat(store.hit(key, Duration.ofMinutes(10), start.plusSeconds(60))).isEqualTo(2);
        // The window that started with the first hit is over: counting starts again.
        assertThat(store.hits(key, start.plus(Duration.ofMinutes(11)))).isZero();
        assertThat(store.hit(key, Duration.ofMinutes(10), start.plus(Duration.ofMinutes(11)))).isEqualTo(1);

        String old = "test|" + UUID.randomUUID();
        store.hit(old, Duration.ofMinutes(1), start);
        assertThat(store.prune(Instant.now())).isGreaterThanOrEqualTo(2);
        assertThat(store.hits(old, start)).isZero();
    }

    @Test
    void theApiAnswers429OnceTheSharedLimitIsReached() throws Exception {
        School school = api.signup();
        int max = properties.auth().maxFailedLogins();
        String wrong = """
                {"schoolCode":"%s","email":"%s","password":"not-the-password"}"""
                .formatted(school.code(), school.adminEmail());
        for (int i = 0; i < max; i++) {
            mvc.perform(MockMvcRequestBuilders.post("/api/auth/login").with(r -> {
                r.setRemoteAddr("192.0.2.77");
                return r;
            }).contentType(MediaType.APPLICATION_JSON).content(wrong)).andExpect(status().isUnauthorized());
        }
        // The right password is refused too while the key is blocked, from this instance or any other.
        mvc.perform(MockMvcRequestBuilders.post("/api/auth/login").with(r -> {
            r.setRemoteAddr("192.0.2.77");
            return r;
        }).contentType(MediaType.APPLICATION_JSON).content("""
                {"schoolCode":"%s","email":"%s","password":"%s"}"""
                .formatted(school.code(), school.adminEmail(), TestApi.PASSWORD)))
                .andExpect(status().isTooManyRequests());
        LoginRateLimiter otherServer = new LoginRateLimiter(properties, new JdbcRateLimitStore(dataSource,
                transactions));
        String key = "school|192.0.2.77|" + school.code() + "|" + school.adminEmail();
        assertThatThrownBy(() -> otherServer.check(key)).isInstanceOf(ApiException.class);
    }
}
