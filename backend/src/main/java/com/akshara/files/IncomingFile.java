package com.akshara.files;

/**
 * An uploaded file that passed the checks in {@link FileUploads}: at most 5 MB, an allowed type confirmed by its
 * contents, and a safe display name. {@code sha256} is the lowercase hex digest of {@code bytes}.
 */
public record IncomingFile(String name, FileType type, byte[] bytes, String sha256) {

    public int size() {
        return bytes.length;
    }
}
