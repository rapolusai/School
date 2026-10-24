package com.akshara.files;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.platform.TenantDirectory;
import com.akshara.shared.TenantContext;

/**
 * Copies the bytes of files stored before S3 was switched on from the database to S3, school by school, a file per
 * transaction: upload, then mark the row S3 and empty its bytes. Turned on for one start with
 * {@code akshara.files.copy-to-s3=true} (FILES_COPY_TO_S3) next to {@code akshara.files.store=s3}; safe to run again,
 * since only rows still in the database are touched. A file that fails stays in the database and keeps working.
 */
@Component
@ConditionalOnProperty(name = "akshara.files.copy-to-s3", havingValue = "true")
class FilesCopyToObjectStorage implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FilesCopyToObjectStorage.class);
    private static final int BATCH = 20;

    private final TenantDirectory tenants;
    private final StoredFileRepository files;
    private final FileBlobs blobs;
    private final TransactionTemplate tx;

    FilesCopyToObjectStorage(TenantDirectory tenants, StoredFileRepository files,
            org.springframework.beans.factory.ObjectProvider<FileBlobs> blobs, PlatformTransactionManager transactions) {
        this.tenants = tenants;
        this.files = files;
        this.blobs = blobs.getIfAvailable();
        this.tx = new TransactionTemplate(transactions);
        if (this.blobs == null) {
            throw new IllegalStateException("akshara.files.copy-to-s3 needs akshara.files.store=s3");
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        int copied = 0;
        int failed = 0;
        for (var school : tenants.summaries()) {
            int[] result = TenantContext.runAs(school.id(), () -> copySchool(school.id()));
            copied += result[0];
            failed += result[1];
        }
        log.info("Copied {} file(s) from the database to object storage; {} could not be copied and stay put",
                copied, failed);
    }

    private int[] copySchool(UUID tenantId) {
        int copied = 0;
        int failed = 0;
        java.util.Set<UUID> skipped = new java.util.HashSet<>();
        while (true) {
            List<UUID> batch = tx.execute(s -> files.idsInDatabase(BATCH + skipped.size()));
            List<UUID> todo = batch == null ? List.of() : batch.stream().filter(id -> !skipped.contains(id)).toList();
            if (todo.isEmpty()) {
                return new int[] {copied, failed};
            }
            for (UUID id : todo) {
                try {
                    tx.executeWithoutResult(s -> {
                        StoredFile file = files.findById(id).orElseThrow();
                        if (file.inObjectStorage()) {
                            return;
                        }
                        blobs.put(DatabaseFileStore.key(tenantId, id), file.getBytes(), file.info().contentType());
                        file.movedToObjectStorage();
                        files.saveAndFlush(file);
                    });
                    copied++;
                } catch (RuntimeException e) {
                    skipped.add(id);
                    failed++;
                    log.warn("File {} stays in the database ({})", id, e.getClass().getSimpleName());
                }
            }
        }
    }
}
