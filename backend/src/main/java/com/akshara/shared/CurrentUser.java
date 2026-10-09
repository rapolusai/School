package com.akshara.shared;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/** Reads the signed-in user from the verified access token. */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static Optional<Jwt> token() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            return Optional.of(jwt);
        }
        return Optional.empty();
    }

    public static Optional<UUID> id() {
        return token().map(jwt -> UUID.fromString(jwt.getSubject()));
    }

    public static UUID requireId() {
        return id().orElseThrow(() -> ApiException.unauthorized("Sign in to continue."));
    }

    public static Optional<String> name() {
        return token().map(jwt -> jwt.getClaimAsString(JwtService.CLAIM_NAME));
    }

    public static boolean isPlatformAdmin() {
        return token().map(jwt -> "platform".equals(jwt.getClaimAsString(JwtService.CLAIM_TYPE))).orElse(false);
    }
}
