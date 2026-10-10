package com.akshara.privacy;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface PrivacyNoticeRepository extends JpaRepository<PrivacyNotice, java.util.UUID> {

    @Query("select n from PrivacyNotice n order by n.number desc")
    List<PrivacyNotice> newestFirst(Limit limit);

    @Query("select n from PrivacyNotice n where n.number = ?1")
    Optional<PrivacyNotice> findByNumber(int number);

    @Query("select coalesce(max(n.number), 0) from PrivacyNotice n")
    int latestNumber();

    default Optional<PrivacyNotice> current() {
        return newestFirst(Limit.of(1)).stream().findFirst();
    }
}
