package com.akshara.privacy;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ConsentRecordRepository extends JpaRepository<ConsentRecord, UUID> {

    /** Newest first. Ids are time-ordered, which breaks ties between decisions recorded in the same instant. */
    @Query("select c from ConsentRecord c where c.studentId = ?1 order by c.at desc, c.id desc")
    List<ConsentRecord> history(UUID studentId);

    @Query("select c from ConsentRecord c where c.studentId in ?1 order by c.at desc, c.id desc")
    List<ConsentRecord> historyOf(Collection<UUID> studentIds);

    /** Decisions a parent took in the app, newest first (for their own data export). */
    @Query("select c from ConsentRecord c where c.givenById = ?1 order by c.at desc, c.id desc")
    List<ConsentRecord> givenBy(UUID userId);

    /** Students with essential processing agreed against this notice version (it cannot be withdrawn in the app). */
    @Query("select distinct c.studentId from ConsentRecord c where c.purpose = com.akshara.privacy.PrivacyPurpose.ESSENTIAL "
            + "and c.action = com.akshara.privacy.ConsentAction.GIVEN and c.noticeVersion = ?1")
    List<UUID> essentialGivenFor(int noticeVersion);
}
