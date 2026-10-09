package com.akshara.files;

import java.nio.charset.StandardCharsets;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * How every stored file is sent to the browser: always as a download (Content-Disposition: attachment, with the
 * cleaned name), with the type found when it was uploaded, {@code X-Content-Type-Options: nosniff} so the browser
 * never guesses another type, and no caching.
 */
public final class FileDownloads {

    private FileDownloads() {
    }

    public static ResponseEntity<byte[]> attachment(FileContent file) {
        StoredFileInfo info = file.info();
        MediaType type = MediaType.parseMediaType(info.contentType());
        if ("text".equals(type.getType())) {
            type = new MediaType(type, StandardCharsets.UTF_8);
        }
        return ResponseEntity.ok()
                .contentType(type)
                .contentLength(file.bytes().length)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(info.name(), StandardCharsets.UTF_8).build()
                                .toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore().cachePrivate())
                .body(file.bytes());
    }
}
