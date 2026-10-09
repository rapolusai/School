package com.akshara.communication;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.communication.AudienceResolver.Audience;
import com.akshara.communication.AudienceResolver.Resolution;
import com.akshara.communication.CommunicationSettings.Values;
import com.akshara.communication.CommunicationTypes.Category;
import com.akshara.communication.CommunicationTypes.NoticeChannel;
import com.akshara.communication.CommunicationTypes.RecipientKind;
import com.akshara.communication.CommunicationTypes.ReviewOutcome;
import com.akshara.communication.CommunicationTypes.Source;
import com.akshara.communication.CommunicationTypes.Status;
import com.akshara.communication.NoticeForms.CircularRequest;
import com.akshara.communication.NoticeForms.EstimateRequest;
import com.akshara.notifications.Channel;
import com.akshara.notifications.MessageLogService;
import com.akshara.notifications.MessageStatus;
import com.akshara.notifications.MessageTemplates;
import com.akshara.notifications.NotificationQueue;
import com.akshara.notifications.NotificationSettingsService;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * Circulars from draft to sent. Anyone with notices.send writes drafts; without notices.approve they address only the
 * parents and students of the sections they are class teacher of, and (when the school's setting says so, the default)
 * their circulars wait for a School Admin or Principal to approve them. A circular is sent at once or at its scheduled
 * time; a sent circular cannot be changed, only withdrawn from notice boards. Every change is audited.
 */
@Service
@Transactional
public class CircularService {

    static final int LIST_LIMIT = 200;
    static final Duration MAX_SCHEDULE_AHEAD = Duration.ofDays(366);

    public record Ref(UUID id, String name) {
    }

    public record RoleRef(String code, String name) {
    }

    /** Who a circular is for, with names. {@code label} is the description saved when it was written. */
    public record AudienceView(boolean wholeSchool, List<Ref> classes, List<Ref> sections, List<RoleRef> roles,
            String label) {
    }

    public record CircularSummary(UUID id, String title, Category category, Status status, Source source,
            AudienceView audience, Set<NoticeChannel> channels, Instant scheduledAt, String createdByName,
            Instant submittedAt, Instant sentAt, Instant updatedAt, int inAppRecipients, long read,
            ReviewOutcome reviewOutcome) {
    }

    public record CircularList(List<CircularSummary> items, Map<Status, Long> counts, boolean canApprove) {
    }

    /** What the caller may do with the circular now. */
    public record Actions(boolean edit, boolean delete, boolean submit, boolean approve, boolean reject,
            boolean cancel, boolean withdraw) {
    }

    public record KindStat(RecipientKind kind, long recipients, long read) {
    }

    /** Messages on one channel by status (QUEUED, SENT, SIMULATED, FAILED, SKIPPED). */
    public record ChannelStat(NoticeChannel channel, long total, Map<String, Long> byStatus) {
    }

    /**
     * What sending produced: people reached on the notice board and how many have read it, the parents, students and
     * staff counted when it was sent, and the messages per channel and status.
     */
    public record Delivery(int inApp, long read, Double readPercent, int staff, int parents, int students,
            List<KindStat> kinds, List<ChannelStat> messages) {
    }

    public record CircularDetail(UUID id, String title, String body, Category category, Status status,
            Source source, AudienceView audience, Set<NoticeChannel> channels, Instant scheduledAt, UUID createdById,
            String createdByName, Instant createdAt, Instant submittedAt, String reviewedByName, Instant reviewedAt,
            ReviewOutcome reviewOutcome, String reviewNote, Instant sentAt, String sentByName, Instant withdrawnAt,
            String withdrawnByName, String withdrawReason, Instant updatedAt, boolean needsApproval, Actions actions,
            Delivery delivery) {
    }

    /** One channel of an estimate: messages, SMS parts (units) and the indicative cost in paise. */
    public record ChannelEstimate(NoticeChannel channel, int messages, int units, long costPaise) {
    }

    /**
     * Who the circular would reach now and what its messages would cost. {@code smsParts} is per SMS; {@code unicode}
     * is true when the text is not in the GSM alphabet (Hindi, for example), which makes SMS parts shorter.
     */
    public record Estimate(int staff, int parents, int students, int inApp, int parentsWithoutPhone, int phones,
            int emails, int smsParts, boolean unicode, List<ChannelEstimate> channels, long costPaise) {
    }

    public record SectionOption(UUID id, String name, String label, boolean own) {
    }

    public record ClassOption(UUID id, String name, List<SectionOption> sections) {
    }

    public record Rates(long smsPartPaise, long whatsappPaise, long emailPaise) {
    }

    /**
     * What the caller may address: every class, section and staff role with notices.approve; otherwise only the
     * sections they are class teacher of.
     */
    public record AudienceOptions(boolean canApprove, boolean needsApproval, boolean canAddressWholeSchool,
            List<ClassOption> classes, List<RoleRef> staffRoles, Rates rates) {
    }

    private final CircularRepository circulars;
    private final CircularTargetRepository targets;
    private final CircularRecipientRepository recipients;
    private final CommunicationSettingsRepository settings;
    private final AudienceResolver audiences;
    private final CircularSender sender;
    private final AcademicsDirectory academics;
    private final MessageLogService messageLog;
    private final NotificationQueue queue;
    private final NotificationSettingsService messageSettings;
    private final CommunicationProperties properties;
    private final AuditService audit;

    CircularService(CircularRepository circulars, CircularTargetRepository targets,
            CircularRecipientRepository recipients, CommunicationSettingsRepository settings,
            AudienceResolver audiences, CircularSender sender, AcademicsDirectory academics,
            MessageLogService messageLog, NotificationQueue queue, NotificationSettingsService messageSettings,
            CommunicationProperties properties, AuditService audit) {
        this.circulars = circulars;
        this.targets = targets;
        this.recipients = recipients;
        this.settings = settings;
        this.audiences = audiences;
        this.sender = sender;
        this.academics = academics;
        this.messageLog = messageLog;
        this.queue = queue;
        this.messageSettings = messageSettings;
        this.properties = properties;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ reading

    /** Approvers see every circular; everyone else sees their own. {@code status} null means all. */
    @Transactional(readOnly = true)
    public CircularList list(NoticeCaller caller, Status status) {
        TenantContext.require();
        Limit limit = Limit.of(LIST_LIMIT);
        List<Circular> found;
        if (caller.canApprove()) {
            found = status == null ? circulars.findAllByOrderByUpdatedAtDescIdAsc(limit)
                    : circulars.findByStatusOrderByUpdatedAtDescIdAsc(status, limit);
        } else {
            found = status == null ? circulars.findByCreatedByIdOrderByUpdatedAtDescIdAsc(caller.userId(), limit)
                    : circulars.findByStatusAndCreatedByIdOrderByUpdatedAtDescIdAsc(status, caller.userId(), limit);
        }
        Map<Status, Long> counts = new EnumMap<>(Status.class);
        for (Status s : Status.values()) {
            counts.put(s, 0L);
        }
        (caller.canApprove() ? circulars.countPerStatus() : circulars.countPerStatusOf(caller.userId()))
                .forEach(r -> counts.put((Status) r[0], ((Number) r[1]).longValue()));
        List<UUID> ids = found.stream().map(Circular::getId).toList();
        Map<UUID, Long> read = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            recipients.readCounts(ids).forEach(r -> read.put((UUID) r[0], ((Number) r[1]).longValue()));
        }
        Names names = names();
        Map<UUID, List<CircularTarget>> byCircular = ids.isEmpty() ? Map.of() : targets.findByCircularIdIn(ids)
                .stream().collect(Collectors.groupingBy(CircularTarget::getCircularId));
        List<CircularSummary> items = found.stream().map(c -> new CircularSummary(c.getId(), c.getTitle(),
                c.getCategory(), c.getStatus(), c.getSource(),
                audienceView(c, byCircular.getOrDefault(c.getId(), List.of()), names), c.channels(),
                c.getScheduledAt(), c.getCreatedByName(), c.getSubmittedAt(), c.getSentAt(), c.getUpdatedAt(),
                c.counts().inApp(), read.getOrDefault(c.getId(), 0L), c.getReviewOutcome())).toList();
        return new CircularList(items, counts, caller.canApprove());
    }

    @Transactional(readOnly = true)
    public CircularDetail detail(NoticeCaller caller, UUID id) {
        TenantContext.require();
        return detailOf(caller, visible(caller, circulars.findById(id).orElse(null)));
    }

    /** The classes, sections and roles the caller may address, and whether their circulars need approval. */
    @Transactional(readOnly = true)
    public AudienceOptions options(NoticeCaller caller) {
        TenantContext.require();
        Map<UUID, List<SectionOption>> sectionsByClass = new LinkedHashMap<>();
        Map<UUID, String> classNames = new LinkedHashMap<>();
        for (SectionInfo s : academics.sections()) {
            boolean own = caller.ownSectionIds().contains(s.id());
            if (!caller.canApprove() && !own) {
                continue;
            }
            classNames.putIfAbsent(s.classId(), s.className());
            sectionsByClass.computeIfAbsent(s.classId(), k -> new ArrayList<>())
                    .add(new SectionOption(s.id(), s.name(), s.label(), own));
        }
        List<ClassOption> classes = sectionsByClass.entrySet().stream()
                .map(e -> new ClassOption(e.getKey(), classNames.get(e.getKey()), e.getValue())).toList();
        List<RoleRef> staffRoles = caller.canApprove() ? audiences.roleNames().entrySet().stream()
                .filter(e -> NoticeAccess.isStaffRole(e.getKey()))
                .map(e -> new RoleRef(e.getKey(), e.getValue())).toList() : List.of();
        CommunicationProperties.Cost cost = properties.cost();
        return new AudienceOptions(caller.canApprove(), needsApproval(caller), caller.canApprove(), classes,
                staffRoles, new Rates(cost.smsPartPaise(), cost.whatsappPaise(), cost.emailPaise()));
    }

    /** Who the audience reaches now and what the chosen channels would cost. Nothing is saved. */
    @Transactional(readOnly = true)
    public Estimate estimate(NoticeCaller caller, EstimateRequest request) {
        TenantContext.require();
        boolean nothingChosen = !request.audience().wholeSchool() && request.audience().classIds().isEmpty()
                && request.audience().sectionIds().isEmpty() && request.audience().roles().isEmpty();
        Resolution who = nothingChosen ? Resolution.NOBODY
                : audiences.resolve(audiences.check(request.audience(), caller));
        String title = request.title() == null ? "" : cleanTitle(request.title());
        String body = request.body() == null ? "" : cleanBody(request.body());
        Map<String, String> params = Map.of("school", sender.schoolName(), "title", title, "summary",
                CircularSender.summary(body, CircularSender.SUMMARY_LENGTH));
        String text = MessageTemplates.render(MessageTemplates.CIRCULAR, messageSettings.current().alertLanguage(),
                params);
        int parts = SmsParts.count(text);
        CommunicationProperties.Cost cost = properties.cost();
        List<ChannelEstimate> channels = new ArrayList<>();
        long total = 0;
        for (NoticeChannel channel : NoticeChannel.values()) {
            if (!request.channels().contains(channel)) {
                continue;
            }
            ChannelEstimate e = switch (channel) {
                case SMS -> new ChannelEstimate(channel, who.phones().size(), who.phones().size() * parts,
                        (long) who.phones().size() * parts * cost.smsPartPaise());
                case WHATSAPP -> new ChannelEstimate(channel, who.phones().size(), who.phones().size(),
                        (long) who.phones().size() * cost.whatsappPaise());
                case EMAIL -> new ChannelEstimate(channel, who.emails().size(), who.emails().size(),
                        (long) who.emails().size() * cost.emailPaise());
            };
            total += e.costPaise();
            channels.add(e);
        }
        Map<RecipientKind, Long> kinds = new EnumMap<>(RecipientKind.class);
        who.people().forEach(p -> kinds.merge(p.kind(), 1L, Long::sum));
        return new Estimate(kinds.getOrDefault(RecipientKind.STAFF, 0L).intValue(), who.parents(),
                kinds.getOrDefault(RecipientKind.STUDENT, 0L).intValue(), who.people().size(),
                who.parentsWithoutPhone(), who.phones().size(), who.emails().size(), parts, !SmsParts.isGsm(text),
                channels, total);
    }

    // ------------------------------------------------------------------ writing drafts

    public CircularDetail create(NoticeCaller caller, CircularRequest request, Instant now) {
        TenantContext.require();
        requireSend(caller);
        Circular circular = new Circular(Source.STAFF, null, caller.actor(), now);
        Audience audience = apply(caller, circular, request, now);
        circulars.saveAndFlush(circular);
        sender.saveAudience(circular, audience);
        audit.record(caller.actor(), "circular.created", "circular", circular.getId(), describe(circular));
        return detailOf(caller, circular);
    }

    public CircularDetail update(NoticeCaller caller, UUID id, CircularRequest request, Instant now) {
        TenantContext.require();
        requireSend(caller);
        Circular circular = lockVisible(caller, id);
        requireStatus(circular, Status.DRAFT, "Only drafts can be changed. Cancel the approval request or the "
                + "schedule first; a sent circular can only be withdrawn.");
        Audience audience = apply(caller, circular, request, now);
        circulars.saveAndFlush(circular);
        sender.saveAudience(circular, audience);
        audit.record(caller.actor(), "circular.updated", "circular", circular.getId(), describe(circular));
        return detailOf(caller, circular);
    }

    public void delete(NoticeCaller caller, UUID id) {
        TenantContext.require();
        requireSend(caller);
        Circular circular = lockVisible(caller, id);
        requireStatus(circular, Status.DRAFT, "Only drafts can be deleted. A sent circular can be withdrawn.");
        Map<String, Object> details = describe(circular);
        circulars.delete(circular);
        circulars.flush();
        audit.record(caller.actor(), "circular.deleted", "circular", id, details);
    }

    // ------------------------------------------------------------------ moving along

    /**
     * Submits a draft: for approval when the caller needs it, otherwise scheduled (when it has a time in the future)
     * or sent now.
     */
    public CircularDetail submit(NoticeCaller caller, UUID id, Instant now) {
        TenantContext.require();
        requireSend(caller);
        Circular circular = lockVisible(caller, id);
        requireStatus(circular, Status.DRAFT, "Only a draft can be submitted.");
        Audience audience = sender.audienceOf(circular);
        if (!caller.canApprove()) {
            AudienceResolver.checkTeacherLimit(audience, caller.ownSectionIds());
        }
        checkScheduleStillAhead(circular, now);
        Resolution who = audiences.resolve(audience);
        requireSomeone(who);
        if (needsApproval(caller)) {
            circular.submitForApproval(now);
            circulars.saveAndFlush(circular);
            audit.record(caller.actor(), "circular.submitted", "circular", circular.getId(), describe(circular));
        } else {
            release(caller.actor(), circular, now);
        }
        return detailOf(caller, circular);
    }

    public CircularDetail approve(NoticeCaller caller, UUID id, String note, Instant now) {
        TenantContext.require();
        requireApprove(caller);
        Circular circular = lockVisible(caller, id);
        requireStatus(circular, Status.PENDING_APPROVAL, "Only a circular waiting for approval can be approved.");
        requireSomeone(audiences.resolve(sender.audienceOf(circular)));
        circular.review(ReviewOutcome.APPROVED, caller.actor(), blankToNull(note), now);
        circulars.saveAndFlush(circular);
        Map<String, Object> details = describe(circular);
        if (circular.getReviewNote() != null) {
            details.put("note", circular.getReviewNote());
        }
        audit.record(caller.actor(), "circular.approved", "circular", circular.getId(), details);
        release(caller.actor(), circular, now);
        return detailOf(caller, circular);
    }

    public CircularDetail reject(NoticeCaller caller, UUID id, String note, Instant now) {
        TenantContext.require();
        requireApprove(caller);
        Circular circular = lockVisible(caller, id);
        requireStatus(circular, Status.PENDING_APPROVAL, "Only a circular waiting for approval can be sent back.");
        String reason = blankToNull(note);
        if (reason == null) {
            throw ApiException.badRequest("Say what should change.", "note");
        }
        circular.review(ReviewOutcome.REJECTED, caller.actor(), reason, now);
        circulars.saveAndFlush(circular);
        Map<String, Object> details = describe(circular);
        details.put("note", reason);
        audit.record(caller.actor(), "circular.rejected", "circular", circular.getId(), details);
        return detailOf(caller, circular);
    }

    /** Takes back a request for approval or a schedule: the circular becomes a draft again. */
    public CircularDetail cancel(NoticeCaller caller, UUID id) {
        TenantContext.require();
        requireSend(caller);
        Circular circular = lockVisible(caller, id);
        if (circular.getStatus() != Status.PENDING_APPROVAL && circular.getStatus() != Status.SCHEDULED) {
            throw conflict("Only a circular waiting for approval or scheduled for later can be taken back.");
        }
        Status before = circular.getStatus();
        circular.backToDraft();
        circulars.saveAndFlush(circular);
        Map<String, Object> details = describe(circular);
        details.put("from", before.name());
        audit.record(caller.actor(), "circular.cancelled", "circular", circular.getId(), details);
        return detailOf(caller, circular);
    }

    /**
     * Takes a sent circular off every notice board, with a reason. Messages already sent stay sent; ones still
     * waiting in the outbox (for example during quiet hours) are not sent.
     */
    public CircularDetail withdraw(NoticeCaller caller, UUID id, String reason, Instant now) {
        TenantContext.require();
        requireSend(caller);
        Circular circular = lockVisible(caller, id);
        requireStatus(circular, Status.SENT, "Only a sent circular can be withdrawn.");
        String why = blankToNull(reason);
        if (why == null) {
            throw ApiException.badRequest("Say why the circular is withdrawn.", "reason");
        }
        circular.withdraw(caller.actor(), why, now);
        circulars.saveAndFlush(circular);
        int skipped = queue.skipRelated("circular", circular.getId(), "Circular withdrawn before it was sent");
        Map<String, Object> details = describe(circular);
        details.put("reason", why);
        details.put("messagesStopped", skipped);
        audit.record(caller.actor(), "circular.withdrawn", "circular", circular.getId(), details);
        return detailOf(caller, circular);
    }

    // ------------------------------------------------------------------ helpers

    /** Sends now, or schedules when the circular has a time in the future. */
    private void release(Actor by, Circular circular, Instant now) {
        if (circular.getScheduledAt() != null && circular.getScheduledAt().isAfter(now)) {
            circular.schedule(now);
            circulars.saveAndFlush(circular);
            Map<String, Object> details = describe(circular);
            details.put("scheduledAt", circular.getScheduledAt().toString());
            audit.record(by, "circular.scheduled", "circular", circular.getId(), details);
        } else {
            sender.send(circular, by, now);
        }
    }

    private Audience apply(NoticeCaller caller, Circular circular, CircularRequest request, Instant now) {
        String title = cleanTitle(request.title());
        String body = cleanBody(request.body());
        if (title.isEmpty()) {
            throw ApiException.badRequest("Give the circular a title.", "title");
        }
        if (body.isEmpty()) {
            throw ApiException.badRequest("Write the circular.", "body");
        }
        if (request.category() == null) {
            throw ApiException.badRequest("Choose a category.", "category");
        }
        Instant scheduledAt = request.scheduledAt();
        if (scheduledAt != null) {
            if (!scheduledAt.isAfter(now)) {
                throw ApiException.badRequest("Pick a time in the future, or leave it empty to send straight away.",
                        "scheduledAt");
            }
            if (scheduledAt.isAfter(now.plus(MAX_SCHEDULE_AHEAD))) {
                throw ApiException.badRequest("Schedule it at most a year ahead.", "scheduledAt");
            }
        }
        Audience audience = audiences.check(request.audience(), caller);
        circular.edit(title, body, request.category(), audience.wholeSchool(), audiences.label(audience),
                request.channels(), scheduledAt);
        return audience;
    }

    private void checkScheduleStillAhead(Circular circular, Instant now) {
        if (circular.getScheduledAt() != null && !circular.getScheduledAt().isAfter(now)) {
            throw ApiException.badRequest("The scheduled time has passed. Pick a new time, or clear it to send "
                    + "straight away.", "scheduledAt");
        }
    }

    private static void requireSomeone(Resolution who) {
        if (!who.reachesAnyone()) {
            throw ApiException.badRequest("Nobody in this audience can receive circulars yet: no one has a sign-in, "
                    + "a mobile number or an email address. Choose another audience.", AudienceResolver.AUDIENCE);
        }
    }

    boolean needsApproval(NoticeCaller caller) {
        if (caller.canApprove()) {
            return false;
        }
        return settings.findCurrent().map(CommunicationSettings::values).orElse(Values.DEFAULTS)
                .teacherCircularsNeedApproval();
    }

    private static void requireSend(NoticeCaller caller) {
        if (!caller.canSend()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Not allowed", "You do not have permission to do this.");
        }
    }

    private static void requireApprove(NoticeCaller caller) {
        if (!caller.canApprove()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Not allowed", "Only a School Admin or Principal can "
                    + "approve circulars.");
        }
    }

    private static void requireStatus(Circular circular, Status status, String message) {
        if (circular.getStatus() != status) {
            throw conflict(message);
        }
    }

    private static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "Not possible now", message, Map.of("status", message));
    }

    /** The circular when the caller may see it: approvers see all, others their own. Otherwise 404. */
    private static Circular visible(NoticeCaller caller, Circular circular) {
        if (circular == null || !(caller.canApprove() || caller.userId().equals(circular.getCreatedById()))) {
            throw ApiException.notFound("Circular");
        }
        return circular;
    }

    private Circular lockVisible(NoticeCaller caller, UUID id) {
        return visible(caller, circulars.lockById(id).orElse(null));
    }

    private CircularDetail detailOf(NoticeCaller caller, Circular c) {
        boolean mine = caller.userId() != null && caller.userId().equals(c.getCreatedById());
        boolean manage = caller.canSend() && (mine || caller.canApprove()) && c.getSource() == Source.STAFF;
        Status s = c.getStatus();
        Actions actions = new Actions(manage && s == Status.DRAFT, manage && s == Status.DRAFT,
                manage && s == Status.DRAFT, caller.canApprove() && s == Status.PENDING_APPROVAL,
                caller.canApprove() && s == Status.PENDING_APPROVAL,
                manage && (s == Status.PENDING_APPROVAL || s == Status.SCHEDULED),
                caller.canSend() && (mine || caller.canApprove()) && s == Status.SENT);
        Delivery delivery = s == Status.SENT || s == Status.WITHDRAWN ? delivery(c) : null;
        return new CircularDetail(c.getId(), c.getTitle(), c.getBody(), c.getCategory(), s, c.getSource(),
                audienceView(c, targets.findByCircularId(c.getId()), names()), c.channels(), c.getScheduledAt(),
                c.getCreatedById(), c.getCreatedByName(), c.getCreatedAt(), c.getSubmittedAt(), c.getReviewedByName(),
                c.getReviewedAt(), c.getReviewOutcome(), c.getReviewNote(), c.getSentAt(), c.getSentByName(),
                c.getWithdrawnAt(), c.getWithdrawnByName(), c.getWithdrawReason(), c.getUpdatedAt(),
                needsApproval(caller), actions, delivery);
    }

    private Delivery delivery(Circular c) {
        List<KindStat> kinds = new ArrayList<>();
        long inApp = 0;
        long read = 0;
        for (Object[] row : recipients.countsByKind(c.getId())) {
            long n = ((Number) row[1]).longValue();
            long r = ((Number) row[2]).longValue();
            kinds.add(new KindStat((RecipientKind) row[0], n, r));
            inApp += n;
            read += r;
        }
        kinds.sort(Comparator.comparing(KindStat::kind));
        List<ChannelStat> messages = new ArrayList<>();
        Map<Channel, Map<MessageStatus, Long>> counts = messageLog.countsFor(c.getId());
        for (NoticeChannel channel : NoticeChannel.values()) {
            Map<MessageStatus, Long> byStatus = counts.get(Channel.valueOf(channel.name()));
            if (byStatus == null && !c.channels().contains(channel)) {
                continue;
            }
            Map<String, Long> named = new LinkedHashMap<>();
            if (byStatus != null) {
                byStatus.forEach((status, n) -> named.put(status.name(), n));
            }
            messages.add(new ChannelStat(channel, named.values().stream().mapToLong(Long::longValue).sum(), named));
        }
        Circular.Counts sent = c.counts();
        Double percent = inApp == 0 ? null : Math.round(read * 1000.0 / inApp) / 10.0;
        return new Delivery((int) inApp, read, percent, sent.staff(), sent.parents(), sent.students(), kinds,
                messages);
    }

    /** Class, section and role names of the school, read once per request. */
    private record Names(Map<UUID, String> classes, Map<UUID, String> sections, Map<String, String> roles) {
    }

    private Names names() {
        Map<UUID, String> classes = new LinkedHashMap<>();
        Map<UUID, String> sections = new LinkedHashMap<>();
        for (SectionInfo s : academics.sections()) {
            classes.putIfAbsent(s.classId(), s.className());
            sections.put(s.id(), s.label());
        }
        return new Names(classes, sections, audiences.roleNames());
    }

    private static AudienceView audienceView(Circular c, List<CircularTarget> list, Names names) {
        List<Ref> classes = new ArrayList<>();
        List<Ref> sections = new ArrayList<>();
        List<RoleRef> roles = new ArrayList<>();
        for (CircularTarget t : list) {
            if (t.getClassId() != null) {
                classes.add(new Ref(t.getClassId(), names.classes().getOrDefault(t.getClassId(), "")));
            } else if (t.getSectionId() != null) {
                sections.add(new Ref(t.getSectionId(), names.sections().getOrDefault(t.getSectionId(), "")));
            } else if (t.getRoleCode() != null) {
                roles.add(new RoleRef(t.getRoleCode(), names.roles().getOrDefault(t.getRoleCode(), t.getRoleCode())));
            }
        }
        classes.sort(Comparator.comparing(r -> r.name().toLowerCase(Locale.ROOT)));
        sections.sort(Comparator.comparing(r -> r.name().toLowerCase(Locale.ROOT)));
        roles.sort(Comparator.comparing(RoleRef::code));
        return new AudienceView(c.isWholeSchool(), classes, sections, roles, c.getAudienceLabel());
    }

    private static Map<String, Object> describe(Circular c) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("title", c.getTitle());
        details.put("category", c.getCategory().name());
        details.put("audience", c.getAudienceLabel());
        details.put("status", c.getStatus().name());
        return details;
    }

    /** One line, no control characters, single spaces. */
    static String cleanTitle(String value) {
        return value == null ? "" : value.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
    }

    /**
     * Plain text with line breaks: Windows and old Mac line ends become \n, other control characters are dropped,
     * and more than two blank lines in a row become two. Never HTML: the web shows it as text.
     */
    static String cleanBody(String value) {
        if (value == null) {
            return "";
        }
        String text = value.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "")
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{4,}", "\n\n\n");
        return text.strip();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
