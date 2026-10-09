package com.akshara.identity;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record UserSummary(UUID id, String name, String email, List<String> roles, UserStatus status,
        Instant lastLoginAt, Instant createdAt) {

    public static UserSummary of(UserAccount user) {
        return new UserSummary(user.getId(), user.getName(), user.getEmail(), user.roleCodes(), user.getStatus(),
                user.getLastLoginAt(), user.getCreatedAt());
    }
}
