package com.akshara.files;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.shared.TenantContext;

/**
 * The pilot's {@link FileStore}: bytes in {@code files.stored_file}, so a file is visible only to its own school
 * (row-level security) and is written and rolled back with the change it belongs to.
 */
@Service
@Transactional
class DatabaseFileStore implements FileStore {

    private final StoredFileRepository files;

    DatabaseFileStore(StoredFileRepository files) {
        this.files = files;
    }

    @Override
    public StoredFileInfo put(FileOwner owner, IncomingFile file, UUID uploadedBy) {
        TenantContext.require();
        return files.saveAndFlush(new StoredFile(owner, file, uploadedBy)).info();
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
        TenantContext.require();
        return files.findById(fileId).map(f -> new FileContent(f.info(), f.getBytes()));
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
        TenantContext.require();
        return files.deleteOwned(fileId, owner.module(), owner.type(), owner.id()) > 0;
    }

    @Override
    public int deleteAll(FileOwner owner) {
        TenantContext.require();
        return files.deleteByOwner(owner.module(), owner.type(), owner.id());
    }
}
