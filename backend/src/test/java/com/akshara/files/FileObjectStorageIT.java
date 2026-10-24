package com.akshara.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.platform.TenantDirectory;
import com.akshara.shared.TenantContext;
import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi.School;

/**
 * Files whose bytes live in object storage (an in-memory stand-in for S3): the record stays in the database, a
 * rolled-back upload leaves no object, a deleted file's object goes after the commit, and older files are copied over
 * by {@link FilesCopyToObjectStorage}.
 */
@Import(FileObjectStorageIT.InMemoryBlobs.class)
class FileObjectStorageIT extends IntegrationTest {

    static final Map<String, byte[]> OBJECTS = new ConcurrentHashMap<>();

    @TestConfiguration
    static class InMemoryBlobs {
        @Bean
        @Primary
        FileBlobs fileBlobs() {
            return new FileBlobs() {
                @Override
                public void put(String key, byte[] bytes, String contentType) {
                    OBJECTS.put(key, bytes.clone());
                }

                @Override
                public byte[] get(String key) {
                    byte[] bytes = OBJECTS.get(key);
                    if (bytes == null) {
                        throw new IllegalStateException("no object " + key);
                    }
                    return bytes.clone();
                }

                @Override
                public void delete(String key) {
                    OBJECTS.remove(key);
                }
            };
        }
    }

    private static final byte[] PDF = "%PDF-1.4\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF\n".getBytes(StandardCharsets.UTF_8);

    @Autowired
    FileStore store;

    @Autowired
    StoredFileRepository repository;

    @Autowired
    TenantDirectory tenants;

    @Autowired
    PlatformTransactionManager transactions;

    @Autowired
    org.springframework.beans.factory.ObjectProvider<FileBlobs> blobs;

    TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactions);
    }

    @Test
    void theBytesGoToObjectStorageAndTheRecordStaysInTheDatabase() throws Exception {
        School school = api.signup();
        FileOwner owner = new FileOwner("test", "thing", UUID.randomUUID());

        StoredFileInfo info = inSchool(school, () -> tx.execute(s -> store.put(owner, pdf("worksheet.pdf"), null)));

        String key = school.tenantId() + "/" + info.id();
        assertThat(OBJECTS).containsKey(key);
        assertThat(row(info.id())).containsEntry("storage", "S3").containsEntry("bytes", null);
        FileContent content = inSchool(school, () -> tx.execute(s -> store.content(info.id()).orElseThrow()));
        assertThat(content.bytes()).isEqualTo(PDF);
        assertThat(content.info().name()).isEqualTo("worksheet.pdf");

        inSchool(school, () -> tx.execute(s -> store.deleteAll(owner)));
        assertThat(OBJECTS).doesNotContainKey(key);
        java.util.Optional<StoredFileInfo> gone = inSchool(school, () -> tx.execute(s -> store.info(info.id())));
        assertThat(gone).isEmpty();
    }

    @Test
    void aRolledBackUploadLeavesNoObject() throws Exception {
        School school = api.signup();
        FileOwner owner = new FileOwner("test", "thing", UUID.randomUUID());
        int before = OBJECTS.size();

        assertThatThrownBy(() -> inSchool(school, () -> tx.execute(s -> {
            store.put(owner, pdf("draft.pdf"), null);
            throw new IllegalStateException("the change failed after the upload");
        }))).isInstanceOf(IllegalStateException.class);

        assertThat(OBJECTS).hasSize(before);
        java.util.List<StoredFileInfo> left = inSchool(school, () -> tx.execute(s -> store.list(owner)));
        assertThat(left).isEmpty();
    }

    @Test
    void anotherSchoolCannotReadTheFileEvenThoughItsBytesAreElsewhere() throws Exception {
        School owner = api.signup();
        School other = api.signup();
        StoredFileInfo info = inSchool(owner,
                () -> tx.execute(s -> store.put(new FileOwner("test", "thing", UUID.randomUUID()), pdf("a.pdf"), null)));

        java.util.Optional<FileContent> seen = inSchool(other, () -> tx.execute(s -> store.content(info.id())));
        assertThat(seen).isEmpty();
    }

    @Test
    void olderFilesAreCopiedFromTheDatabaseAndKeepWorking() throws Exception {
        School school = api.signup();
        UUID id = UUID.randomUUID();
        try (Connection c = ownerConnection();
                PreparedStatement insert = c.prepareStatement("insert into files.stored_file (id, tenant_id, "
                        + "owner_module, owner_type, owner_id, original_name, content_type, size_bytes, sha256, bytes, "
                        + "created_at, storage) values (?, ?, 'test', 'thing', ?, 'old.pdf', 'application/pdf', ?, ?, ?, "
                        + "now(), 'DATABASE')")) {
            insert.setObject(1, id);
            insert.setObject(2, school.tenantId());
            insert.setObject(3, UUID.randomUUID());
            insert.setInt(4, PDF.length);
            insert.setString(5, FileUploads.check("old.pdf", PDF, "file").sha256());
            insert.setBytes(6, PDF);
            insert.executeUpdate();
        }

        new FilesCopyToObjectStorage(tenants, repository, blobs, transactions).run(null);

        assertThat(row(id)).containsEntry("storage", "S3").containsEntry("bytes", null);
        assertThat(OBJECTS.get(school.tenantId() + "/" + id)).isEqualTo(PDF);
        byte[] read = inSchool(school, () -> tx.execute(s -> store.content(id).orElseThrow().bytes()));
        assertThat(read).isEqualTo(PDF);
    }

    private static IncomingFile pdf(String name) {
        return FileUploads.check(name, PDF, "file");
    }

    private static <T> T inSchool(School school, java.util.function.Supplier<T> work) {
        return TenantContext.runAs(school.tenantId(), work);
    }

    private static Map<String, Object> row(UUID id) throws Exception {
        try (Connection c = ownerConnection();
                PreparedStatement select = c.prepareStatement(
                        "select storage, bytes from files.stored_file where id = ?")) {
            select.setObject(1, id);
            try (ResultSet rows = select.executeQuery()) {
                assertThat(rows.next()).isTrue();
                Map<String, Object> row = new java.util.HashMap<>();
                row.put("storage", rows.getString(1));
                row.put("bytes", rows.getBytes(2));
                return row;
            }
        }
    }
}
