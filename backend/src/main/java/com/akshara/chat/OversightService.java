package com.akshara.chat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.chat.ChatViews.Attachment;
import com.akshara.chat.ChatViews.OversightDetail;
import com.akshara.chat.ChatViews.OversightMessage;
import com.akshara.chat.ChatViews.OversightPage;
import com.akshara.chat.ChatViews.OversightRow;
import com.akshara.chat.ChatViews.ReadOnlyReason;
import com.akshara.chat.ChatViews.ReportView;
import com.akshara.chat.ConversationService.Context;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * The Principal's view of messages (chat.oversee): the list of every conversation without its content, reading one in
 * full (each reading is written to the audit log, with deleted messages shown as they were), closing and reopening
 * conversations, and reviewing what parents and teachers reported.
 */
@Service
@Transactional
class OversightService {

    /** Which conversations the list shows. */
    enum Filter {
        ALL, REPORTED, CLOSED
    }

    static final int MAX_PAGE_SIZE = 100;

    private final ConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final ChatReportRepository reports;
    private final ConversationService service;
    private final ChatNotifier notifier;
    private final AuditService audit;

    OversightService(ConversationRepository conversations, ChatMessageRepository messages,
            ChatReportRepository reports, ConversationService service, ChatNotifier notifier, AuditService audit) {
        this.conversations = conversations;
        this.messages = messages;
        this.reports = reports;
        this.service = service;
        this.notifier = notifier;
        this.audit = audit;
    }

    /** Every conversation, newest activity first, without any message content. Not audited: it shows no content. */
    @Transactional(readOnly = true)
    OversightPage list(Filter filter, String q, int page, int size) {
        TenantContext.require();
        if (page < 0) {
            throw ApiException.badRequest("Give a page of 0 or more.", "page");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw ApiException.badRequest("Give a page size from 1 to " + MAX_PAGE_SIZE + ".", "size");
        }
        List<Conversation> all = conversations.everything().stream().filter(c -> c.getMessageCount() > 0).toList();
        Map<UUID, Integer> openReports = new HashMap<>();
        reports.withStatus(ChatReport.Status.OPEN).forEach(r -> openReports.merge(r.getConversationId(), 1,
                Integer::sum));
        List<Conversation> filtered = all.stream().filter(c -> switch (filter) {
            case ALL -> true;
            case REPORTED -> openReports.containsKey(c.getId());
            case CLOSED -> c.isClosed();
        }).toList();
        Context ctx = service.context(filtered);
        String needle = q == null || q.isBlank() ? null : q.strip().toLowerCase(Locale.ROOT);
        List<OversightRow> rows = new ArrayList<>();
        for (Conversation c : filtered) {
            OversightRow row = row(c, ctx, openReports.getOrDefault(c.getId(), 0));
            if (needle == null || matches(row, needle)) {
                rows.add(row);
            }
        }
        int from = Math.min(rows.size(), page * size);
        int to = Math.min(rows.size(), from + size);
        return new OversightPage(rows.subList(from, to), page, size, rows.size(),
                reports.countByStatus(ChatReport.Status.OPEN));
    }

    /** A conversation in full, deleted messages included. Every reading is written to the audit log. */
    OversightDetail read(UUID id, Actor actor) {
        return view(find(id), actor, true);
    }

    private OversightDetail view(Conversation c, Actor actor, boolean audited) {
        UUID id = c.getId();
        List<ChatMessage> all = messages.allOf(id);
        Map<UUID, List<Attachment>> attached = service.attachments(all, true);
        List<OversightMessage> shown = all.stream().map(m -> new OversightMessage(m.getId(), m.getSenderSide(),
                m.getSenderName(), m.getBody(), attached.getOrDefault(m.getId(), List.of()), m.isDeleted(),
                m.getDeletedAt(), m.isErased(), m.getCreatedAt(), m.getReadAt())).toList();
        Context ctx = service.context(List.of(c));
        List<ReportView> reportViews = reports.findByConversationIdOrderByCreatedAtDesc(id).stream()
                .map(r -> service.reportView(r, c, ctx)).toList();
        ReadOnlyReason reason = ctx.readOnly(c);
        if (audited) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("studentId", c.getStudentId().toString());
            details.put("messages", all.size());
            audit.record(actor, "chat_conversation.oversight_read", "chat_conversation", c.getId(), details);
        }
        return new OversightDetail(c.getId(), ConversationService.ref(c.getStudentId(),
                ctx.students.get(c.getStudentId())), ctx.parentName(c),
                ConversationService.name(ctx.people.get(c.getStaffUserId())), c.getStartedBy(), c.isClosed(),
                reason != null, reason, c.getClosedByName(), c.getCloseReason(), c.getClosedAt(), c.getCreatedAt(),
                shown, reportViews);
    }

    /** Closes the conversation: both sides can still read it but nobody can write. */
    OversightDetail close(UUID id, String reason, Actor actor, Instant now) {
        Conversation c = conversations.lock(find(id).getId()).orElseThrow(() -> ApiException.notFound("Conversation"));
        if (c.isClosed()) {
            throw ApiException.conflict("This conversation is already closed.", "reason");
        }
        if (c.isErased()) {
            throw ApiException.conflict("This conversation was erased with the student's data.", "reason");
        }
        c.close(actor.name(), reason.strip(), now);
        conversations.saveAndFlush(c);
        notifier.silence(c, "Conversation closed");
        audit.record(actor, "chat_conversation.closed", "chat_conversation", c.getId(),
                Map.of("reason", reason.strip()));
        return view(c, actor, false);
    }

    /** Opens a closed conversation again, unless something else keeps it read-only (the student left, ...). */
    OversightDetail reopen(UUID id, Actor actor) {
        Conversation c = conversations.lock(find(id).getId()).orElseThrow(() -> ApiException.notFound("Conversation"));
        if (!c.isClosed()) {
            throw ApiException.conflict("This conversation is not closed.", "conversation");
        }
        c.reopen();
        ReadOnlyReason still = service.context(List.of(c)).readOnly(c);
        if (still != null) {
            throw ApiException.conflict(ConversationService.readOnlyMessage(still), "conversation");
        }
        conversations.saveAndFlush(c);
        audit.record(actor, "chat_conversation.reopened", "chat_conversation", c.getId(), Map.of());
        return view(c, actor, false);
    }

    /** Reports, newest first: only the open ones, or all of them. */
    @Transactional(readOnly = true)
    List<ReportView> reports(boolean openOnly) {
        TenantContext.require();
        List<ChatReport> list = openOnly ? reports.withStatus(ChatReport.Status.OPEN) : reports.newestFirst();
        Map<UUID, Conversation> byId = new HashMap<>();
        conversations.findAllById(list.stream().map(ChatReport::getConversationId).distinct().toList())
                .forEach(c -> byId.put(c.getId(), c));
        Context ctx = service.context(byId.values());
        return list.stream().filter(r -> byId.containsKey(r.getConversationId()))
                .map(r -> service.reportView(r, byId.get(r.getConversationId()), ctx)).toList();
    }

    ReportView review(UUID reportId, String note, Actor actor, Instant now) {
        TenantContext.require();
        ChatReport r = reports.findById(reportId).orElseThrow(() -> ApiException.notFound("Report"));
        if (!r.isOpen()) {
            throw ApiException.conflict("This report has already been reviewed.", "note");
        }
        r.review(actor, note == null || note.isBlank() ? null : note.strip(), now);
        reports.saveAndFlush(r);
        audit.record(actor, "chat_report.reviewed", "chat_report", r.getId(),
                Map.of("conversationId", r.getConversationId().toString()));
        Conversation c = find(r.getConversationId());
        return service.reportView(r, c, service.context(List.of(c)));
    }

    private Conversation find(UUID id) {
        TenantContext.require();
        return conversations.findById(id).orElseThrow(() -> ApiException.notFound("Conversation"));
    }

    private OversightRow row(Conversation c, Context ctx, int openReports) {
        ReadOnlyReason reason = ctx.readOnly(c);
        return new OversightRow(c.getId(), ConversationService.ref(c.getStudentId(),
                ctx.students.get(c.getStudentId())), ctx.parentName(c),
                ConversationService.name(ctx.people.get(c.getStaffUserId())), c.getMessageCount(),
                c.getLastMessageAt(), c.isClosed(), reason != null, reason, openReports, c.getCreatedAt());
    }

    private static boolean matches(OversightRow row, String needle) {
        return contains(row.student().name(), needle) || contains(row.student().classLabel(), needle)
                || contains(row.parentName(), needle) || contains(row.staffName(), needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }
}
