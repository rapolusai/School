package com.akshara.shared;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Holds the school (tenant) the current thread works for. The database connection and Hibernate both read it,
 * so set it before a transaction starts and never change it inside one.
 */
public final class TenantContext {

    private static final ThreadLocal<UUID> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static Optional<UUID> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static UUID require() {
        UUID id = CURRENT.get();
        if (id == null) {
            throw new IllegalStateException("No school selected for this request");
        }
        return id;
    }

    public static void set(UUID tenantId) {
        CURRENT.set(tenantId);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Runs the work as the given school and restores the previous value afterwards. */
    public static <T> T runAs(UUID tenantId, Supplier<T> work) {
        UUID previous = CURRENT.get();
        CURRENT.set(tenantId);
        try {
            return work.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
