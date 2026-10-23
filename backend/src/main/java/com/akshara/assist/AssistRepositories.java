package com.akshara.assist;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface AssistSettingsRepository extends JpaRepository<AssistSettings, UUID> {

    /** The school's settings row (row-level security and the tenant filter leave at most one). */
    @Query("select s from AssistSettings s")
    List<AssistSettings> findForSchool();

    /** Schools with the early warning switched on. Works without a school selected (see V26). */
    @Query(value = "select tenant_id from assist.tenants_with_early_warning()", nativeQuery = true)
    List<UUID> tenantsWithEarlyWarning();
}

interface UsageRepository extends JpaRepository<UsageEntry, UUID> {

    long countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(Instant from, Instant to);

    List<UsageEntry> findByCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(Instant from, Instant to,
            Limit limit);
}

interface RemarkDraftRepository extends JpaRepository<RemarkDraft, UUID> {

    List<RemarkDraft> findByTermIdAndSectionIdOrderByStudentName(UUID termId, UUID sectionId);

    List<RemarkDraft> findByTermIdAndStudentIdIn(UUID termId, Collection<UUID> studentIds);

    List<RemarkDraft> findByAcceptedAtGreaterThanEqualAndAcceptedAtLessThan(Instant from, Instant to);

    List<RemarkDraft> findByCreatedAtGreaterThanEqualAndCreatedAtLessThan(Instant from, Instant to);
}

interface WatchEntryRepository extends JpaRepository<WatchEntry, UUID> {

    @Query("select w from WatchEntry w order by w.score desc, w.studentName")
    List<WatchEntry> findAllByScore();

    Optional<WatchEntry> findByStudentId(UUID studentId);
}

interface WatchNoteRepository extends JpaRepository<WatchNote, UUID> {

    List<WatchNote> findByEntryIdOrderByCreatedAtDesc(UUID entryId);

    long countByEntryId(UUID entryId);
}

interface PaperDraftRepository extends JpaRepository<PaperDraft, UUID> {

    @Query("select p from PaperDraft p order by p.updatedAt desc")
    List<PaperDraft> findAllNewestFirst();
}
