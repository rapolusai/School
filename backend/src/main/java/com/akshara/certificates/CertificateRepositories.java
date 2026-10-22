package com.akshara.certificates;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The certificate module's Spring Data repositories, one interface per entity, kept in one file because each is a
 * few lines. Row-level security already keeps every query to the current school.
 */
final class CertificateRepositories {

    private CertificateRepositories() {
    }
}

interface CertificateSettingsRepository extends JpaRepository<CertificateSettings, UUID> {

    Optional<CertificateSettings> findFirstBy();
}

interface CertificateTemplateRepository extends JpaRepository<CertificateTemplate, UUID> {

    Optional<CertificateTemplate> findByType(CertificateType type);

    List<CertificateTemplate> findAllByOrderByType();
}

interface CertificateRepository extends JpaRepository<Certificate, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Certificate c where c.id = :id")
    Optional<Certificate> lockById(@Param("id") UUID id);

    Optional<Certificate> findByVerifyToken(String token);

    List<Certificate> findByStudentIdInOrderByRequestedAtDesc(Collection<UUID> studentIds);

    List<Certificate> findByStaffUserIdOrderByRequestedAtDesc(UUID staffUserId);

    List<Certificate> findBySupersedesId(UUID supersedesId);
}

interface IdCardRepository extends JpaRepository<IdCard, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from IdCard c where c.id = :id")
    Optional<IdCard> lockById(@Param("id") UUID id);

    Optional<IdCard> findByStudentIdAndStatus(UUID studentId, CardStatus status);

    Optional<IdCard> findByStaffUserIdAndStatus(UUID staffUserId, CardStatus status);

    List<IdCard> findByStudentIdInAndStatus(Collection<UUID> studentIds, CardStatus status);

    List<IdCard> findByStaffUserIdInAndStatus(Collection<UUID> staffUserIds, CardStatus status);

    List<IdCard> findBySubjectTypeAndStatusOrderByCreatedAtDesc(SubjectType subjectType, CardStatus status);

    List<IdCard> findBySubjectTypeOrderByCreatedAtDesc(SubjectType subjectType);

    List<IdCard> findByStudentIdInOrderByCreatedAtDesc(Collection<UUID> studentIds);
}
