package com.akshara.files;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.akshara.shared.TenantContext;

/**
 * The {@link FileStore}: every file's record is a row of {@code files.stored_file}, so a file is visible only to its
 * own school (row-level security) and is written and rolled back with the change it belongs to. The bytes are kept
 * in that row, or, when object storage is configured ({@link FileBlobs}, {@code akshara.files.store=s3}), in S3
 * under {@code <tenant id>/<file id>}:
 *
 * <ul>
 *   <li>A new file is uploaded before the row commits; if the change rolls back, the object is deleted again.</li>
 *   <li>A deleted file's object is deleted after the row's deletion commits (the bucket keeps old versions for a
 *       while, so a mistake can still be undone by an operator).</li>
 *   <li>Files stored before S3 was switched on keep working from the database until they are copied
 *       ({@link FilesCopyToObjectStorage}).</li>
 * </ul>
 */
@Service
@Transactional
class DatabaseFileStore implements FileStore {

    private static final Logger log = LoggerFactory.getLogger(DatabaseFileStore.class);

    private final StoredFileRepository files;
    private final FileBlobs blobs;

    DatabaseFileStore(StoredFileRepository files, ObjectProvider<FileBlobs> blobs) {
        this.files = files;
        this.blobs = blobs.getIfAvailable();
    }

    @Override
    public StoredFileInfo put(FileOwner owner, IncomingFile file, UUID uploadedBy) {
        UUID tenantId = TenantContext.require();
        StoredFile stored = files.saveAndFlush(new StoredFile(owner, file, uploadedBy, blobs == null));
        if (blobs != null) {
            String key = key(tenantId, stored.getId());
            blobs.put(key, file.bytes(), file.type().mediaType());
            afterRollback(() -> blobs.delete(key));
        }
        return stored.info();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredFileInfo> info(UUID fileId) {
        TenantContext.require();
        return files.findInfo(fileId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FileContent> content(UUID fileId) {
        UUID tenantId = TenantContext.require();
        return files.findById(fileId).map(f -> new FileContent(f.info(), bytesOf(tenantId, f)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoredFileInfo> list(FileOwner owner) {
        TenantContext.require();
        return files.findInfoByOwners(owner.module(), owner.type(), Set.of(owner.id()));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, List<StoredFileInfo>> list(String module, String type, Collection<UUID> ownerIds) {
        TenantContext.require();
        if (ownerIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<StoredFileInfo>> result = new LinkedHashMap<>();
        for (StoredFileInfo info : files.findInfoByOwners(module, type, Set.copyOf(ownerIds))) {
            result.computeIfAbsent(info.ownerId(), k -> new ArrayList<>()).add(info);
        }
        return result;
    }

    @Override
    public boolean delete(FileOwner owner, UUID fileId) {
        UUID tenantId = TenantContext.require();
        boolean inObjectStorage = files.isInObjectStorage(fileId, owner.module(), owner.type(), owner.id());
        boolean deleted = files.deleteOwned(fileId, owner.module(), owner.type(), owner.id()) > 0;
        if (deleted && inObjectStorage) {
            deleteObjectsAfterCommit(tenantId, List.of(fileId));
        }
        return deleted;
    }

    @Override
    public int deleteAll(FileOwner owner) {
        UUID tenantId = TenantContext.require();
        List<UUID> inObjectStorage = files.idsInObjectStorage(owner.module(), owner.type(), owner.id());
        int deleted = files.deleteByOwner(owner.module(), owner.type(), owner.id());
        if (!inObjectStorage.isEmpty()) {
            deleteObjectsAfterCommit(tenantId, inObjectStorage);
        }
        return deleted;
    }

    static String key(UUID tenantId, UUID fileId) {
        return tenantId + "/" + fileId;
    }

    private byte[] bytesOf(UUID tenantId, StoredFile file) {
        if (!file.inObjectStorage()) {
            return file.getBytes();
        }
        if (blobs == null) {
            throw new IllegalStateException("File " + file.getId() + " is in object storage, which is not configured");
        }
        return blobs.get(key(tenantId, file.getId()));
    }

    private void deleteObjectsAfterCommit(UUID tenantId, List<UUID> fileIds) {
        if (blobs == null) {
            log.warn("{} file(s) in object storage were deleted while object storage is not configured", fileIds.size());
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                for (UUID id : fileIds) {
                    try {
                        blobs.delete(key(tenantId, id));
                    } catch (RuntimeException e) {
                        // The record is gone, so nobody can reach the object; it is only storage to tidy up.
                        log.warn("Could not delete object of file {} ({})", id, e.getClass().getSimpleName());
                    }
                }
            }
        });
    }

    private static void afterRollback(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try {
                        action.run();
                    } catch (RuntimeException e) {
                        log.warn("Could not remove an object uploaded by a rolled-back change ({})",
                                e.getClass().getSimpleName());
                    }
                }
            }
        });
    }
}
