package com.akshara.files;

/**
 * Implemented by each module that owns files: says whether the signed-in person may download one of its files.
 * Called by {@code GET /api/files/{id}} inside a read-only transaction as the current school. A file whose owner
 * module has no policy, or whose policy says no, is answered with 404, exactly like a file that does not exist.
 */
public interface FileAccessPolicy {

    /** The {@link FileOwner#module()} this policy speaks for, for example "homework". */
    String ownerModule();

    boolean canRead(StoredFileInfo file);
}
