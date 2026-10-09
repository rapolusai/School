package com.akshara.files;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Stores uploaded files for the current school. Call it inside your own transaction, so a file is kept only when the
 * change it belongs to is. The pilot keeps the bytes in PostgreSQL ({@code files.stored_file}, protected by row-level
 * security); an object-storage implementation can replace it without changing callers (docs/adr/0004-file-storage.md).
 *
 * <p>Ownership rules: a file always belongs to exactly one owner record. It can be deleted only through that owner
 * ({@link #delete}), and the owning module deletes all of a record's files when it deletes the record
 * ({@link #deleteAll}).
 */
public interface FileStore {

    /** Stores the file for the owner and returns its details. {@code uploadedBy} is a user id, or null. */
    StoredFileInfo put(FileOwner owner, IncomingFile file, UUID uploadedBy);

    Optional<StoredFileInfo> info(UUID fileId);

    Optional<FileContent> content(UUID fileId);

    /** The owner's files, oldest first. */
    List<StoredFileInfo> list(FileOwner owner);

    /** The files of many owners of one kind, oldest first per owner. Owners without files are missing. */
    Map<UUID, List<StoredFileInfo>> list(String module, String type, Collection<UUID> ownerIds);

    /** Deletes the file only if it belongs to the owner. Returns false when it does not (or does not exist). */
    boolean delete(FileOwner owner, UUID fileId);

    /** Deletes every file of the owner, for when the owning record goes. Returns how many were deleted. */
    int deleteAll(FileOwner owner);
}
