package com.akshara.files;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface StoredFileRepository extends JpaRepository<StoredFile, UUID> {

    String INFO = "select new com.akshara.files.StoredFileInfo(f.id, f.ownerModule, f.ownerType, f.ownerId, "
            + "f.originalName, f.contentType, f.sizeBytes, f.sha256, f.uploadedBy, f.createdAt) from StoredFile f ";

    @Query(INFO + "where f.id = ?1")
    Optional<StoredFileInfo> findInfo(UUID id);

    @Query(INFO + "where f.ownerModule = ?1 and f.ownerType = ?2 and f.ownerId in ?3 order by f.createdAt, f.id")
    List<StoredFileInfo> findInfoByOwners(String module, String type, Collection<UUID> ownerIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from StoredFile f where f.id = ?1 and f.ownerModule = ?2 and f.ownerType = ?3 and f.ownerId = ?4")
    int deleteOwned(UUID id, String module, String type, UUID ownerId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from StoredFile f where f.ownerModule = ?1 and f.ownerType = ?2 and f.ownerId = ?3")
    int deleteByOwner(String module, String type, UUID ownerId);
}
