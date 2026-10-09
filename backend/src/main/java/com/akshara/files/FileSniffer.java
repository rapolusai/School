package com.akshara.files;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Works out what a file really is from its first bytes (its "magic number"), never from its name or the browser's
 * Content-Type. Word, Excel and PowerPoint files are ZIP archives: their kind is read from the archive's central
 * directory (the list of entry names at the end of the file) without unpacking anything, so a ZIP bomb costs nothing.
 */
public final class FileSniffer {

    /** Entries read from a ZIP central directory at most; real Office files have far fewer. */
    static final int MAX_ZIP_ENTRIES = 5000;

    private FileSniffer() {
    }

    public static Optional<FileType> sniff(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return Optional.empty();
        }
        if (startsWith(bytes, 0, '%', 'P', 'D', 'F', '-')) {
            return Optional.of(FileType.PDF);
        }
        if (startsWith(bytes, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
            return Optional.of(FileType.PNG);
        }
        if (startsWith(bytes, 0, 0xFF, 0xD8, 0xFF)) {
            return Optional.of(FileType.JPEG);
        }
        if (startsWith(bytes, 0, 'R', 'I', 'F', 'F') && startsWith(bytes, 8, 'W', 'E', 'B', 'P')) {
            return Optional.of(FileType.WEBP);
        }
        if (startsWith(bytes, 0, 'P', 'K', 0x03, 0x04)) {
            return officeType(zipEntryNames(bytes));
        }
        return isPlainText(bytes) ? Optional.of(FileType.TXT) : Optional.empty();
    }

    private static Optional<FileType> officeType(List<String> names) {
        if (!names.contains("[Content_Types].xml")) {
            return Optional.empty();
        }
        if (names.stream().anyMatch(n -> n.startsWith("word/"))) {
            return Optional.of(FileType.DOCX);
        }
        if (names.stream().anyMatch(n -> n.startsWith("xl/"))) {
            return Optional.of(FileType.XLSX);
        }
        if (names.stream().anyMatch(n -> n.startsWith("ppt/"))) {
            return Optional.of(FileType.PPTX);
        }
        return Optional.empty();
    }

    /**
     * Entry names from the ZIP central directory, or an empty list when the archive is damaged. Reads only the
     * directory records; entry contents are never decompressed.
     */
    static List<String> zipEntryNames(byte[] zip) {
        int eocd = -1;
        int earliest = Math.max(0, zip.length - 22 - 0xFFFF);
        for (int i = zip.length - 22; i >= earliest; i--) {
            if (int32(zip, i) == 0x06054b50) {
                eocd = i;
                break;
            }
        }
        if (eocd < 0) {
            return List.of();
        }
        int count = int16(zip, eocd + 10);
        long offset = int32(zip, eocd + 16) & 0xFFFFFFFFL;
        if (offset >= zip.length) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        int p = (int) offset;
        for (int n = 0; n < count && n < MAX_ZIP_ENTRIES; n++) {
            if (p + 46 > zip.length || int32(zip, p) != 0x02014b50) {
                return List.of();
            }
            int nameLength = int16(zip, p + 28);
            int extraLength = int16(zip, p + 30);
            int commentLength = int16(zip, p + 32);
            if (p + 46 + nameLength > zip.length) {
                return List.of();
            }
            names.add(new String(zip, p + 46, nameLength, StandardCharsets.UTF_8));
            p += 46 + nameLength + extraLength + commentLength;
        }
        return names;
    }

    /** Valid UTF-8 with no NUL bytes and no control characters other than tab, line breaks and form feed. */
    static boolean isPlainText(byte[] bytes) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c < 0x20 && c != '\t' && c != '\n' && c != '\r' && c != '\f') || c == 0x7F) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWith(byte[] bytes, int offset, int... expected) {
        if (bytes.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((bytes[offset + i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }

    private static int int16(byte[] b, int i) {
        if (i < 0 || i + 2 > b.length) {
            return 0;
        }
        return (b[i] & 0xFF) | (b[i + 1] & 0xFF) << 8;
    }

    private static int int32(byte[] b, int i) {
        if (i < 0 || i + 4 > b.length) {
            return 0;
        }
        return (b[i] & 0xFF) | (b[i + 1] & 0xFF) << 8 | (b[i + 2] & 0xFF) << 16 | (b[i + 3] & 0xFF) << 24;
    }
}
