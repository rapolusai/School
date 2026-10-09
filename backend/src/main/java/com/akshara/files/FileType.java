package com.akshara.files;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** The kinds of file a school may upload: documents, images, spreadsheets, slides and plain text. */
public enum FileType {
    PDF("application/pdf", "pdf"),
    JPEG("image/jpeg", "jpg", "jpeg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
    PPTX("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx"),
    TXT("text/plain", "txt");

    private final String mediaType;
    private final List<String> extensions;

    FileType(String mediaType, String... extensions) {
        this.mediaType = mediaType;
        this.extensions = List.of(extensions);
    }

    /** The media type files of this kind are stored and served with. */
    public String mediaType() {
        return mediaType;
    }

    public List<String> extensions() {
        return extensions;
    }

    /** The type a file name claims by its extension ("Notes.PDF" is PDF). */
    public static Optional<FileType> ofName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return Optional.empty();
        }
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (FileType type : values()) {
            if (type.extensions.contains(extension)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
