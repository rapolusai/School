package com.akshara.files;

/** A stored file with its contents, for downloading. */
public record FileContent(StoredFileInfo info, byte[] bytes) {
}
