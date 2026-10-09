package com.akshara.files;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;

import com.akshara.shared.ApiException;

/**
 * Checks an uploaded file before any module stores it. The pattern for upload endpoints:
 *
 * <pre>
 * &#64;PostMapping(value = "/{id}/attachments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
 * public FileRef upload(&#64;PathVariable UUID id, &#64;RequestPart("file") MultipartFile file) {
 *     return service.addAttachment(id, FileUploads.read(file, "file"));   // then FileStore.put(owner, incoming, user)
 * }
 * </pre>
 *
 * A file is accepted when it is not empty, at most {@value #MAX_BYTES} bytes, and its contents (magic bytes) show the
 * type its extension claims, out of PDF, JPEG, PNG, WEBP, DOCX, XLSX, PPTX and TXT. The browser's Content-Type is
 * ignored. The name is cleaned for display and for the download header. Refusals are field errors under
 * {@code field}: 413 for a file that is too large, 400 otherwise.
 */
public final class FileUploads {

    public static final int MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_NAME_LENGTH = 100;

    static final String TOO_LARGE = "Files can be at most 5 MB each.";
    static final String NOT_ALLOWED = "Upload a PDF, a JPEG, PNG or WEBP image, a Word, Excel or PowerPoint file, "
            + "or a text file.";

    private FileUploads() {
    }

    /** Reads and checks one multipart part. */
    public static IncomingFile read(MultipartFile part, String field) {
        if (part == null || part.isEmpty()) {
            throw ApiException.badRequest("The file is empty. Choose another file.", field);
        }
        if (part.getSize() > MAX_BYTES) {
            throw tooLarge(field);
        }
        try {
            return check(part.getOriginalFilename(), part.getBytes(), field);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read an uploaded file", e);
        }
    }

    /** Checks a file's name and contents; for uploads that do not come through multipart (demo data, tests). */
    public static IncomingFile check(String originalName, byte[] bytes, String field) {
        if (bytes == null || bytes.length == 0) {
            throw ApiException.badRequest("The file is empty. Choose another file.", field);
        }
        if (bytes.length > MAX_BYTES) {
            throw tooLarge(field);
        }
        FileType claimed = FileType.ofName(originalName)
                .orElseThrow(() -> ApiException.badRequest(NOT_ALLOWED, field));
        FileType actual = FileSniffer.sniff(bytes).orElse(null);
        if (actual != claimed) {
            String extension = originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            throw ApiException.badRequest(actual == null ? NOT_ALLOWED
                    : "This file is not really a ." + extension + " file. Save it in the right format and try again.",
                    field);
        }
        return new IncomingFile(cleanName(originalName, claimed), claimed, bytes.clone(), sha256(bytes));
    }

    /**
     * A display name that is safe in lists and in a Content-Disposition header: no folders, no control or
     * direction-changing characters, none of {@code <>:"/\|?*}, at most {@value #MAX_NAME_LENGTH} characters before
     * the extension, which is kept in lowercase.
     */
    public static String cleanName(String originalName, FileType type) {
        String name = originalName == null ? "" : originalName;
        name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
        name = Normalizer.normalize(name, Normalizer.Form.NFC);
        name = name.replaceAll("[\\p{Cntrl}\\p{Cf}<>:\"/\\\\|?*]", "").replaceAll("\\s+", " ").strip();
        int dot = name.lastIndexOf('.');
        String extension = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (!type.extensions().contains(extension)) {
            extension = type.extensions().getFirst();
        }
        String base = (dot >= 0 ? name.substring(0, dot) : name).replaceAll("^[.\\s]+", "").strip();
        if (base.codePointCount(0, base.length()) > MAX_NAME_LENGTH) {
            base = base.substring(0, base.offsetByCodePoints(0, MAX_NAME_LENGTH)).strip();
        }
        if (base.isEmpty()) {
            base = "file";
        }
        return base + "." + extension;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException tooLarge(String field) {
        return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "File too large", TOO_LARGE, Map.of(field, TOO_LARGE));
    }
}
