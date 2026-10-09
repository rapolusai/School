package com.akshara.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.akshara.shared.ApiException;

/** Which files are accepted: real contents (magic bytes) must match the extension, within the size limit. */
class FileUploadsTest {

    static final byte[] PDF = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13, 'I', 'H', 'D', 'R'};
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0};
    static final byte[] WEBP = {'R', 'I', 'F', 'F', 26, 0, 0, 0, 'W', 'E', 'B', 'P', 'V', 'P', '8', ' '};
    static final byte[] EXE = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 0, 0};

    @Test
    void recognisesEveryAllowedTypeByItsContents() throws IOException {
        assertThat(FileSniffer.sniff(PDF)).contains(FileType.PDF);
        assertThat(FileSniffer.sniff(PNG)).contains(FileType.PNG);
        assertThat(FileSniffer.sniff(JPEG)).contains(FileType.JPEG);
        assertThat(FileSniffer.sniff(WEBP)).contains(FileType.WEBP);
        assertThat(FileSniffer.sniff(office("word/document.xml"))).contains(FileType.DOCX);
        assertThat(FileSniffer.sniff(office("xl/workbook.xml"))).contains(FileType.XLSX);
        assertThat(FileSniffer.sniff(office("ppt/presentation.xml"))).contains(FileType.PPTX);
        assertThat(FileSniffer.sniff("Answers:\n1. Seven\n2. नौ\n".getBytes(StandardCharsets.UTF_8)))
                .contains(FileType.TXT);
        // A program, a plain zip and binary noise are none of them.
        assertThat(FileSniffer.sniff(EXE)).isEmpty();
        assertThat(FileSniffer.sniff(zip("notes/readme.md"))).isEmpty();
        assertThat(FileSniffer.sniff(new byte[] {0, 1, 2, 3, 4, 5})).isEmpty();
    }

    @Test
    void acceptsAFileWhoseContentsMatchItsName() {
        IncomingFile file = FileUploads.check("Fractions Worksheet.PDF", PDF, "file");
        assertThat(file.type()).isEqualTo(FileType.PDF);
        assertThat(file.name()).isEqualTo("Fractions Worksheet.pdf");
        assertThat(file.size()).isEqualTo(PDF.length);
        assertThat(file.sha256()).hasSize(64).matches("[0-9a-f]+");
        assertThat(FileUploads.check("photo.jpeg", JPEG, "file").type()).isEqualTo(FileType.JPEG);
    }

    @Test
    void refusesDisguisedUnknownEmptyAndOversizedFiles() {
        assertRefused(() -> FileUploads.check("homework.pdf", PNG, "file"), HttpStatus.BAD_REQUEST,
                "not really a .pdf");
        assertRefused(() -> FileUploads.check("homework.pdf", EXE, "file"), HttpStatus.BAD_REQUEST, "Upload a PDF");
        assertRefused(() -> FileUploads.check("setup.exe", EXE, "file"), HttpStatus.BAD_REQUEST, "Upload a PDF");
        assertRefused(() -> FileUploads.check("no-extension", PDF, "file"), HttpStatus.BAD_REQUEST, "Upload a PDF");
        assertRefused(() -> FileUploads.check("page.html", "<html><script>x</script>".getBytes(), "file"),
                HttpStatus.BAD_REQUEST, "Upload a PDF");
        assertRefused(() -> FileUploads.check("empty.txt", new byte[0], "file"), HttpStatus.BAD_REQUEST, "empty");
        byte[] big = new byte[FileUploads.MAX_BYTES + 1];
        System.arraycopy(PDF, 0, big, 0, PDF.length);
        assertRefused(() -> FileUploads.check("big.pdf", big, "file"), HttpStatus.CONTENT_TOO_LARGE, "5 MB");
        byte[] limit = new byte[FileUploads.MAX_BYTES];
        System.arraycopy(PDF, 0, limit, 0, PDF.length);
        assertThat(FileUploads.check("limit.pdf", limit, "file").size()).isEqualTo(FileUploads.MAX_BYTES);
    }

    @Test
    void cleansNamesForListsAndDownloadHeaders() {
        assertThat(FileUploads.cleanName("../../etc/passwd.txt", FileType.TXT)).isEqualTo("passwd.txt");
        assertThat(FileUploads.cleanName("C:\\Users\\arjun\\My Answer.docx", FileType.DOCX))
                .isEqualTo("My Answer.docx");
        assertThat(FileUploads.cleanName("bad\r\nname\"<x>|?.pdf", FileType.PDF)).isEqualTo("badnamex.pdf");
        // A right-to-left override cannot disguise the extension.
        assertThat(FileUploads.cleanName("invoice\u202Efdp.exe.pdf", FileType.PDF)).isEqualTo("invoicefdp.exe.pdf");
        assertThat(FileUploads.cleanName(".pdf", FileType.PDF)).isEqualTo("file.pdf");
        assertThat(FileUploads.cleanName("x".repeat(300) + ".png", FileType.PNG)).hasSize(104);
    }

    private static void assertRefused(Runnable action, HttpStatus status, String message) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.status()).isEqualTo(status);
            assertThat(e.getMessage()).contains(message);
            assertThat(e.errors()).containsKey("file");
        });
    }

    static byte[] office(String part) throws IOException {
        return zip("[Content_Types].xml", part);
    }

    static byte[] zip(String... names) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String name : names) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write("<xml/>".getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }
}
