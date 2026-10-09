package com.akshara.notifications;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/** The school's message log: what was queued, for whom, and what became of it. Recipients are always masked. */
@Service
@Transactional(readOnly = true)
public class MessageLogService {

    public static final int MAX_PAGE_SIZE = 100;

    public record MessageRow(UUID id, Instant createdAt, Channel channel, String recipient, String recipientName,
            String templateKey, MessageStatus status, int attempts, Instant sentAt, Instant nextAttemptAt,
            String lastError, String relatedType, UUID relatedId, String relatedLabel) {

        static MessageRow of(Message m) {
            return new MessageRow(m.getId(), m.getCreatedAt(), m.getChannel(), RecipientMask.mask(m.getRecipient()),
                    m.getRecipientName(), m.getTemplateKey(), m.getStatus(), m.getAttempts(), m.getSentAt(),
                    m.getStatus() == MessageStatus.QUEUED ? m.getNextAttemptAt() : null, m.getLastError(),
                    m.getRelatedType(), m.getRelatedId(), m.getRelatedLabel());
        }
    }

    public record MessagePage(List<MessageRow> items, int page, int size, long total) {
    }

    public record MessageDetail(UUID id, Instant createdAt, Channel channel, String recipient, String recipientName,
            String templateKey, String language, String body, MessageStatus status, int attempts, Instant sentAt,
            Instant nextAttemptAt, String lastError, Channel fallbackChannel, String relatedType, UUID relatedId,
            String relatedLabel) {
    }

    /** Filters for the log. Null means "any"; dates are school days in India, both ends included. */
    public record MessageQuery(LocalDate from, LocalDate to, Channel channel, MessageStatus status, String search,
            UUID relatedId, int page, int size) {
    }

    private final EntityManager entityManager;
    private final MessageRepository messages;

    public MessageLogService(EntityManager entityManager, MessageRepository messages) {
        this.entityManager = entityManager;
        this.messages = messages;
    }

    /** Newest first. */
    public MessagePage list(MessageQuery query) {
        TenantContext.require();
        int size = Math.min(Math.max(query.size(), 1), MAX_PAGE_SIZE);
        int page = Math.max(query.page(), 0);
        if (query.from() != null && query.to() != null && query.to().isBefore(query.from())) {
            throw ApiException.badRequest("The end date must not be before the start date.", "to");
        }
        StringBuilder where = new StringBuilder(" from Message m where 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (query.from() != null) {
            where.append(" and m.createdAt >= :from");
            params.put("from", query.from().atStartOfDay(QuietHours.INDIA).toInstant());
        }
        if (query.to() != null) {
            where.append(" and m.createdAt < :to");
            params.put("to", query.to().plusDays(1).atStartOfDay(QuietHours.INDIA).toInstant());
        }
        if (query.channel() != null) {
            where.append(" and m.channel = :channel");
            params.put("channel", query.channel());
        }
        if (query.status() != null) {
            where.append(" and m.status = :status");
            params.put("status", query.status());
        }
        if (query.relatedId() != null) {
            where.append(" and m.relatedId = :related");
            params.put("related", query.relatedId());
        }
        String search = query.search() == null ? "" : query.search().trim().toLowerCase(Locale.ROOT);
        if (!search.isEmpty()) {
            where.append(" and (lower(coalesce(m.relatedLabel, '')) like :q escape '\\'"
                    + " or lower(coalesce(m.recipientName, '')) like :q escape '\\')");
            params.put("q", "%" + escapeLike(search) + "%");
        }
        TypedQuery<Long> count = entityManager.createQuery("select count(m)" + where, Long.class);
        TypedQuery<Message> rows = entityManager.createQuery("select m" + where + " order by m.createdAt desc, m.id desc",
                Message.class);
        params.forEach((k, v) -> {
            count.setParameter(k, v);
            rows.setParameter(k, v);
        });
        long total = count.getSingleResult();
        List<MessageRow> items = rows.setFirstResult(page * size).setMaxResults(size).getResultList().stream()
                .map(MessageRow::of).toList();
        return new MessagePage(items, page, size, total);
    }

    public MessageDetail detail(UUID id) {
        TenantContext.require();
        Message m = messages.findById(id).orElseThrow(() -> ApiException.notFound("Message"));
        return new MessageDetail(m.getId(), m.getCreatedAt(), m.getChannel(), RecipientMask.mask(m.getRecipient()),
                m.getRecipientName(), m.getTemplateKey(), m.getLanguage(), m.getBody(), m.getStatus(), m.getAttempts(),
                m.getSentAt(), m.getStatus() == MessageStatus.QUEUED ? m.getNextAttemptAt() : null, m.getLastError(),
                m.getFallbackChannel(), m.getRelatedType(), m.getRelatedId(), m.getRelatedLabel());
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
