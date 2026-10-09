package com.akshara.files;

import java.time.Instant;
import java.util.UUID;

/** What is known about a stored file, without its contents. */
public record StoredFileInfo(UUID id, String ownerModule, String ownerType, UUID ownerId, String name,
        String contentType, int size, String sha256, UUID uploadedBy, Instant createdAt) {

    public FileOwner owner() {
        return new FileOwner(ownerModule, ownerType, ownerId);
    }

    public boolean belongsTo(FileOwner owner) {
        return owner().equals(owner);
    }
}
