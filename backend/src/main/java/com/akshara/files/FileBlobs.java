package com.akshara.files;

/**
 * Where the bytes of uploaded files go when they are not kept in the database: object storage, addressed by key
 * ({@code <tenant id>/<file id>}). The file's record, and so who may read it, stays in {@code files.stored_file}.
 * Only {@link S3FileBlobs} exists; without it ({@code akshara.files.store=database}) bytes stay in the database.
 */
interface FileBlobs {

    void put(String key, byte[] bytes, String contentType);

    byte[] get(String key);

    void delete(String key);
}
