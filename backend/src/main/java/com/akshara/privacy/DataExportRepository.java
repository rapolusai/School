package com.akshara.privacy;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface DataExportRepository extends JpaRepository<DataExport, UUID> {

    /**
     * A request's exports, newest first, without the file itself: id, file name, size, status, created by and at,
     * expiry, deletion, downloads and the last download.
     */
    @Query("select e.id, e.fileName, e.sizeBytes, e.status, e.createdByName, e.createdAt, e.expiresAt, e.deletedAt, "
            + "e.downloadCount, e.lastDownloadedAt from DataExport e where e.requestId = ?1 "
            + "order by e.createdAt desc, e.id desc")
    List<Object[]> summaries(UUID requestId);

    /** The latest export of each request that has one, by request id, without the file. */
    @Query("select e.requestId, e.status, e.expiresAt from DataExport e where e.requestId in ?1 "
            + "order by e.createdAt desc, e.id desc")
    List<Object[]> statuses(Collection<UUID> requestIds);

    @Query("select e from DataExport e where e.requestId = ?1 and e.status = com.akshara.privacy.ExportStatus.READY "
            + "order by e.createdAt desc, e.id desc")
    List<DataExport> readyFor(UUID requestId);

    @Query("select e from DataExport e where e.studentId = ?1 and e.status = com.akshara.privacy.ExportStatus.READY")
    List<DataExport> readyForStudent(UUID studentId);

    @Query("select e from DataExport e where e.status = com.akshara.privacy.ExportStatus.READY and e.expiresAt <= ?1")
    List<DataExport> expired(Instant now);

    default Optional<DataExport> latestReady(UUID requestId) {
        return readyFor(requestId).stream().findFirst();
    }

    /** Schools with exports past their expiry. Works without a school selected (see V11). */
    @Query(value = "select tenant_id from privacy.tenants_with_expired_exports(?1, ?2)", nativeQuery = true)
    List<UUID> tenantsWithExpiredExports(Instant now, int limit);
}
