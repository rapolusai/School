package com.akshara.communication;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.communication.CommunicationTypes.Category;
import com.akshara.communication.CommunicationTypes.Source;
import com.akshara.communication.CommunicationTypes.Status;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * The in-app notice board of a signed-in person (staff, parent or student): the sent circulars addressed to them,
 * newest first, with URGENT circulars of the last week pinned at the top, and whether they have read each one.
 * Withdrawn circulars disappear from boards. Reading a circular is recorded once, as the read receipt the sender sees.
 */
@Service
@Transactional
public class NoticeBoardService {

    public static final Duration PINNED_FOR = Duration.ofDays(7);
    public static final int MAX_PAGE_SIZE = 50;

    public record BoardItem(UUID id, String title, String body, Category category, Instant sentAt, String sentByName,
            boolean calendarReminder, boolean pinned, boolean read, Instant readAt) {
    }

    public record BoardPage(List<BoardItem> items, int page, int size, long total, long unread) {
    }

    private final CircularRecipientRepository recipients;
    private final CircularRepository circulars;

    NoticeBoardService(CircularRecipientRepository recipients, CircularRepository circulars) {
        this.recipients = recipients;
        this.circulars = circulars;
    }

    @Transactional(readOnly = true)
    public BoardPage board(UUID userId, int page, int size, boolean unreadOnly, Instant now) {
        TenantContext.require();
        int p = Math.max(0, page);
        int s = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        Instant pinnedSince = now.minus(PINNED_FOR);
        List<BoardItem> items = recipients.board(userId, Status.SENT, unreadOnly, Category.URGENT, pinnedSince,
                PageRequest.of(p, s)).stream()
                .map(row -> item((CircularRecipient) row[0], (Circular) row[1], pinnedSince))
                .toList();
        long total = recipients.boardCount(userId, Status.SENT, unreadOnly);
        long unread = unreadOnly ? total : recipients.boardCount(userId, Status.SENT, true);
        return new BoardPage(items, p, s, total, unread);
    }

    /** One circular on the person's board; 404 when it is not addressed to them or was withdrawn. */
    @Transactional(readOnly = true)
    public BoardItem item(UUID userId, UUID circularId, Instant now) {
        TenantContext.require();
        CircularRecipient r = recipient(userId, circularId);
        return item(r, sent(circularId), now.minus(PINNED_FOR));
    }

    /** Records that the person has read the circular. Reading again changes nothing. */
    public BoardItem markRead(UUID userId, UUID circularId, Instant now) {
        TenantContext.require();
        CircularRecipient r = recipient(userId, circularId);
        Circular c = sent(circularId);
        if (r.read(now)) {
            recipients.saveAndFlush(r);
        }
        return item(r, c, now.minus(PINNED_FOR));
    }

    /** Marks every circular on the person's board as read. Returns how many were unread. */
    public int markAllRead(UUID userId, Instant now) {
        TenantContext.require();
        List<CircularRecipient> unread = recipients.unreadOf(userId, Status.SENT);
        unread.forEach(r -> r.read(now));
        recipients.saveAllAndFlush(unread);
        return unread.size();
    }

    private CircularRecipient recipient(UUID userId, UUID circularId) {
        return recipients.findByCircularIdAndUserId(circularId, userId)
                .orElseThrow(() -> ApiException.notFound("Notice"));
    }

    private Circular sent(UUID circularId) {
        return circulars.findById(circularId).filter(c -> c.getStatus() == Status.SENT)
                .orElseThrow(() -> ApiException.notFound("Notice"));
    }

    private static BoardItem item(CircularRecipient r, Circular c, Instant pinnedSince) {
        boolean pinned = c.getCategory() == Category.URGENT && c.getSentAt() != null
                && !c.getSentAt().isBefore(pinnedSince);
        return new BoardItem(c.getId(), c.getTitle(), c.getBody(), c.getCategory(), c.getSentAt(), c.getSentByName(),
                c.getSource() == Source.CALENDAR, pinned, r.getReadAt() != null, r.getReadAt());
    }
}
