package com.akshara.frontoffice;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.akshara.audit.AuditService.Actor;

class OtpTest {

    private static final UUID PASS = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void generatesSixDigitsWithLeadingZerosKept() {
        for (int i = 0; i < 200; i++) {
            assertThat(Otp.generate()).matches("^[0-9]{6}$");
        }
    }

    @Test
    void hashesAreSaltedWithThePassAndNeverContainTheCode() {
        String hash = Otp.hash(PASS, "042817");
        assertThat(hash).hasSize(64).doesNotContain("042817");
        assertThat(Otp.hash(UUID.randomUUID(), "042817")).isNotEqualTo(hash);
        assertThat(Otp.matches(PASS, hash, "042817")).isTrue();
        assertThat(Otp.matches(PASS, hash, "042 817")).as("spaces are ignored").isTrue();
        assertThat(Otp.matches(PASS, hash, "042818")).isFalse();
        assertThat(Otp.matches(PASS, hash, "42817")).isFalse();
        assertThat(Otp.matches(PASS, hash, "abcdef")).isFalse();
        assertThat(Otp.matches(PASS, hash, null)).isFalse();
        assertThat(Otp.matches(PASS, null, "042817")).isFalse();
    }

    @Test
    void expiresFourHoursAfterApproval() {
        Instant approved = Instant.parse("2026-10-11T05:00:00Z");
        Instant expiry = Otp.expiry(approved);
        assertThat(expiry).isEqualTo(approved.plus(Duration.ofHours(4)));
        assertThat(Otp.expired(expiry, expiry.minusSeconds(1))).isFalse();
        assertThat(Otp.expired(expiry, expiry)).isTrue();
        assertThat(Otp.expired(null, expiry)).isFalse();
    }

    @Test
    void fiveWrongAttemptsLockThePass() {
        Instant now = Instant.parse("2026-10-11T05:00:00Z");
        Actor office = new Actor(UUID.randomUUID(), "Office");
        GatePass pass = new GatePass(UUID.randomUUID(), PassSource.OFFICE, office, now.plusSeconds(3600),
                "Dentist", CollectorKind.SELF, null, null, now);
        pass.approve(Otp.hash(pass.getId(), "123456"), Otp.expiry(now), null, office, now);
        assertThat(pass.getStatus()).isEqualTo(PassStatus.APPROVED);
        assertThat(pass.attemptsLeft()).isEqualTo(5);
        for (int i = 1; i <= 4; i++) {
            assertThat(pass.wrongAttempt(now)).as("attempt " + i).isFalse();
            assertThat(pass.getStatus()).isEqualTo(PassStatus.APPROVED);
            assertThat(pass.attemptsLeft()).isEqualTo(5 - i);
        }
        assertThat(pass.wrongAttempt(now)).as("fifth attempt").isTrue();
        assertThat(pass.getStatus()).isEqualTo(PassStatus.LOCKED);
        assertThat(pass.attemptsLeft()).isZero();
        assertThat(pass.otpExpired(now.plus(Duration.ofHours(4)))).isTrue();
        assertThat(pass.otpExpired(now.plus(Duration.ofHours(3)))).isFalse();
    }
}
