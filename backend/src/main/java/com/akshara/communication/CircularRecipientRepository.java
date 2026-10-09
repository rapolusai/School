package com.akshara.communication;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.akshara.communication.CommunicationTypes.Category;
import com.akshara.communication.CommunicationTypes.Status;

interface CircularRecipientRepository extends JpaRepository<CircularRecipient, UUID> {

    Optional<CircularRecipient> findByCircularIdAndUserId(UUID circularId, UUID userId);

    /** Recipients and readers per kind of one circular: rows of (kind, recipients, read). */
    @Query("select r.kind, count(r), count(r.readAt) from CircularRecipient r where r.circularId = ?1 group by r.kind")
    List<Object[]> countsByKind(UUID circularId);

    /** Readers of each circular: rows of (circularId, read). */
    @Query("select r.circularId, count(r.readAt) from CircularRecipient r where r.circularId in ?1 group by r.circularId")
    List<Object[]> readCounts(Collection<UUID> circularIds);

    /**
     * A person's notice board: rows of (recipient, circular) for circulars in {@code status}, circulars of
     * {@code pinned} category sent since {@code pinnedSince} first, then the newest first.
     */
    @Query("select r, c from CircularRecipient r join Circular c on c.id = r.circularId "
            + "where r.userId = ?1 and c.status = ?2 and (?3 = false or r.readAt is null) "
            + "order by case when c.category = ?4 and c.sentAt >= ?5 then 0 else 1 end, c.sentAt desc, c.id")
    List<Object[]> board(UUID userId, Status status, boolean unreadOnly, Category pinned, Instant pinnedSince,
            Pageable page);

    @Query("select count(r) from CircularRecipient r join Circular c on c.id = r.circularId "
            + "where r.userId = ?1 and c.status = ?2 and (?3 = false or r.readAt is null)")
    long boardCount(UUID userId, Status status, boolean unreadOnly);

    /** The person's unread recipient rows of circulars in {@code status}. */
    @Query("select r from CircularRecipient r join Circular c on c.id = r.circularId where r.userId = ?1 "
            + "and r.readAt is null and c.status = ?2")
    List<CircularRecipient> unreadOf(UUID userId, Status status);
}
