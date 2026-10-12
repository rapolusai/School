package com.akshara.frontoffice;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * The one-time password of a gate pass: six digits, drawn from a secure random source, kept only as a SHA-256 hash
 * salted with the pass id (so equal OTPs of two passes hash differently), valid for {@link #VALIDITY}.
 */
final class Otp {

    /** How long an OTP works after the office approves the pass. */
    static final Duration VALIDITY = Duration.ofHours(4);
    static final int LENGTH = 6;

    private static final SecureRandom RANDOM = new SecureRandom();

    private Otp() {
    }

    /** A fresh six-digit code, with leading zeros kept ("042817"). */
    static String generate() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    /** The salted hash stored on the pass. */
    static String hash(UUID passId, String otp) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(passId.toString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) ':');
            digest.update(normalize(otp).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** True when the typed code hashes to the stored hash. Spaces in the typed code are ignored. */
    static boolean matches(UUID passId, String storedHash, String typed) {
        if (storedHash == null || typed == null) {
            return false;
        }
        String candidate = normalize(typed);
        if (!candidate.matches("^[0-9]{" + LENGTH + "}$")) {
            return false;
        }
        return MessageDigest.isEqual(hash(passId, candidate).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }

    static Instant expiry(Instant approvedAt) {
        return approvedAt.plus(VALIDITY);
    }

    static boolean expired(Instant expiresAt, Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }

    private static String normalize(String otp) {
        return otp.replaceAll("\\s", "");
    }
}
