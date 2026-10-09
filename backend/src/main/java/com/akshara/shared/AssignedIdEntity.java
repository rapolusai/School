package com.akshara.shared;

import java.util.UUID;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

/**
 * Base for entities whose UUIDv7 id is assigned in Java. Tells Spring Data a fresh object is new, so saving it is a
 * plain insert instead of a select followed by an insert.
 */
@MappedSuperclass
public abstract class AssignedIdEntity implements Persistable<UUID> {

    @Transient
    private boolean persisted;

    @PostPersist
    @PostLoad
    void markPersisted() {
        persisted = true;
    }

    @Override
    public boolean isNew() {
        return !persisted;
    }
}
