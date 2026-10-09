package com.akshara.privacy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.identity.UserAccount;
import com.akshara.identity.UserRepository;
import com.akshara.privacy.PrivacyForms.CloseForm;
import com.akshara.privacy.PrivacyForms.RequestForm;
import com.akshara.privacy.PrivacyViews.EventView;
import com.akshara.privacy.PrivacyViews.ExportView;
import com.akshara.privacy.PrivacyViews.RequestCounts;
import com.akshara.privacy.PrivacyViews.RequestDetail;
import com.akshara.privacy.PrivacyViews.RequestPage;
import com.akshara.privacy.PrivacyViews.RequestRow;
import com.akshara.privacy.PrivacyViews.StaffRef;
import com.akshara.shared.ApiException;
import com.akshara.shared.Permissions;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster;
import com.akshara.students.StudentRoster.RosterStudent;
import com.akshara.students.StudentService;
import com.akshara.students.StudentService.StudentDetail;
import com.akshara.students.StudentStatus;

/**
 * Parents' data requests and the staff queue that answers them: raising a request, assigning it, replies from either
 * side, closing it. Every request is due 30 days after it was raised (the school's policy, well inside the 90 days the
 * DPDP Rules, 2025 allow for grievances). Every step goes on the request's timeline and into the audit trail.
 * Exports and erasure live in {@link DataExportService} and {@link ErasureService}.
 */
@Service
@Transactional
public class DataRequestService {

    /** The school's policy: an answer within 30 days of the request. */
    public static final int DUE_DAYS = 30;
    /** A parent can have at most this many open requests at a time. */
    static final int MAX_OPEN_PER_PARENT = 10;
    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /** Filters for the staff queue. Null means "any"; {@code status} OPEN means anything not closed. */
    public record RequestQuery(String status, RequestType type, String search, int page, int size) {
    }

    private final DataRequestRepository requests;
    private final RequestEventRepository timeline;
    private final DataExportRepository exports;
    private final StudentService students;
    private final StudentRoster roster;
    private final UserRepository users;
    private final AuditService audit;
    private final EntityManager entityManager;

    DataRequestService(DataRequestRepository requests, RequestEventRepository timeline, DataExportRepository exports,
            StudentService students, StudentRoster roster, UserRepository users, AuditService audit,
            EntityManager entityManager) {
        this.requests = requests;
        this.timeline = timeline;
        this.exports = exports;
        this.students = students;
        this.roster = roster;
        this.users = users;
        this.audit = audit;
        this.entityManager = entityManager;
    }

    static LocalDate today() {
        return LocalDate.now(INDIA);
    }

    // ------------------------------------------------------------------ parents

    /** A parent raises a request about themselves or one of their own children (another student is a 400). */
    public RequestDetail submit(UUID userId, RequestForm form, Actor actor) {
        TenantContext.require();
        UUID studentId = null;
        if (form.subject() == RequestSubject.CHILD) {
            if (form.studentId() == null || !roster.childIdsOf(userId).contains(form.studentId())) {
                throw ApiException.badRequest("Pick one of your children.", "studentId");
            }
            studentId = form.studentId();
        }
        String details = form.details() == null ? "" : form.details().strip();
        if (details.isEmpty() && (form.type() == RequestType.CORRECTION || form.type() == RequestType.GRIEVANCE)) {
            throw ApiException.badRequest(form.type() == RequestType.CORRECTION
                    ? "Say what is wrong and what it should be." : "Describe your complaint.", "details");
        }
        if (requests.openByRequester(userId) >= MAX_OPEN_PER_PARENT) {
            throw new ApiException(HttpStatus.CONFLICT, "Too many open requests",
                    "You already have " + MAX_OPEN_PER_PARENT + " open requests. Wait for the school to answer them.");
        }
        DataRequest request = requests.save(new DataRequest(form.type(), form.subject(), studentId, userId,
                actor.name() == null ? "Parent" : actor.name(), details, today().plusDays(DUE_DAYS)));
        timeline.save(new RequestEvent(request.getId(), RequestEventKind.SUBMITTED, userId, actor.name(),
                details.isEmpty() ? null : details, request.getCreatedAt()));
        requests.flush();
        Map<String, Object> audited = new LinkedHashMap<>();
        audited.put("type", form.type().name());
        audited.put("subject", form.subject().name());
        audited.put("studentId", studentId == null ? null : studentId.toString());
        audited.put("dueOn", request.getDueOn().toString());
        audit.record(actor, "data_request.submitted", "data_request", request.getId(), audited);
        return detail(request, false);
    }

    /** The parent's own requests, newest first. */
    @Transactional(readOnly = true)
    public List<RequestRow> mine(UUID userId) {
        TenantContext.require();
        return rows(requests.byRequester(userId), false);
    }

    /** One of the parent's own requests; anyone else's is 404. */
    @Transactional(readOnly = true)
    public RequestDetail mine(UUID userId, UUID requestId) {
        TenantContext.require();
        return detail(own(userId, requestId), false);
    }

    public RequestDetail parentReply(UUID userId, UUID requestId, String body, Actor actor) {
        TenantContext.require();
        DataRequest request = own(userId, requestId);
        requireOpen(request);
        Instant now = Instant.now();
        timeline.save(new RequestEvent(requestId, RequestEventKind.PARENT_REPLY, userId, actor.name(), body.strip(),
                now));
        request.touchedBy(now);
        requests.flush();
        audit.record(actor, "data_request.parent_replied", "data_request", requestId, Map.of());
        return detail(request, false);
    }

    DataRequest own(UUID userId, UUID requestId) {
        return requests.findById(requestId).filter(r -> userId.equals(r.getRequestedById()))
                .orElseThrow(() -> ApiException.notFound("Request"));
    }

    // ------------------------------------------------------------------ staff

    /** The queue: open requests by due date (overdue first), then closed ones, newest first; with counts. */
    @Transactional(readOnly = true)
    public RequestPage queue(RequestQuery query) {
        TenantContext.require();
        int size = PrivacyForms.pageSize(query.size());
        int page = Math.max(query.page(), 0);
        StringBuilder where = new StringBuilder(" from DataRequest r where 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if ("OPEN".equals(query.status())) {
            where.append(" and r.status <> com.akshara.privacy.RequestStatus.CLOSED");
        } else if (query.status() != null && !query.status().isBlank()) {
            RequestStatus status;
            try {
                status = RequestStatus.valueOf(query.status());
            } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("Unknown status.", "status");
            }
            where.append(" and r.status = :status");
            params.put("status", status);
        }
        if (query.type() != null) {
            where.append(" and r.type = :type");
            params.put("type", query.type());
        }
        String search = query.search() == null ? "" : query.search().trim().toLowerCase(Locale.ROOT);
        if (!search.isEmpty()) {
            where.append(" and lower(r.requesterName) like :q escape '\\'");
            params.put("q", "%" + escapeLike(search) + "%");
        }
        String order = " order by case when r.status = com.akshara.privacy.RequestStatus.CLOSED then 1 else 0 end, "
                + "case when r.status = com.akshara.privacy.RequestStatus.CLOSED then null else r.dueOn end asc, "
                + "r.createdAt desc, r.id desc";
        TypedQuery<Long> count = entityManager.createQuery("select count(r)" + where, Long.class);
        TypedQuery<DataRequest> rows = entityManager.createQuery("select r" + where + order, DataRequest.class);
        params.forEach((k, v) -> {
            count.setParameter(k, v);
            rows.setParameter(k, v);
        });
        List<DataRequest> found = rows.setFirstResult(page * size).setMaxResults(size).getResultList();
        return new RequestPage(rows(found, true), page, size, count.getSingleResult(), counts());
    }

    @Transactional(readOnly = true)
    public RequestDetail detail(UUID requestId) {
        TenantContext.require();
        return detail(find(requestId), true);
    }

    /** People who can be given a request: active staff with privacy.manage. */
    @Transactional(readOnly = true)
    public List<StaffRef> staff() {
        TenantContext.require();
        return users.findAllWithRoles().stream()
                .filter(UserAccount::isActive)
                .filter(u -> u.permissions().contains(Permissions.PRIVACY_MANAGE))
                .map(u -> new StaffRef(u.getId(), u.getName()))
                .toList();
    }

    public RequestDetail assign(UUID requestId, UUID assigneeId, Actor actor) {
        TenantContext.require();
        DataRequest request = find(requestId);
        requireOpen(request);
        StaffRef assignee = staff().stream().filter(s -> s.id().equals(assigneeId)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Pick someone who handles data requests.", "assigneeId"));
        Instant now = Instant.now();
        request.assign(assignee.id(), assignee.name(), now);
        timeline.save(new RequestEvent(requestId, RequestEventKind.ASSIGNED, actor.id(), actor.name(),
                assignee.name(), now));
        requests.flush();
        audit.record(actor, "data_request.assigned", "data_request", requestId,
                Map.of("assigneeId", assignee.id().toString(), "assignee", assignee.name()));
        return detail(request, true);
    }

    public RequestDetail reply(UUID requestId, String body, Actor actor) {
        TenantContext.require();
        DataRequest request = find(requestId);
        requireOpen(request);
        Instant now = Instant.now();
        timeline.save(new RequestEvent(requestId, RequestEventKind.STAFF_REPLY, actor.id(), actor.name(), body.strip(),
                now));
        request.activity(now);
        requests.flush();
        audit.record(actor, "data_request.replied", "data_request", requestId, Map.of());
        return detail(request, true);
    }

    /**
     * Closes a request as completed or declined. Declining needs a reason for the parent; an erasure request about a
     * child is completed only after the data was erased.
     */
    public RequestDetail close(UUID requestId, CloseForm form, Actor actor) {
        TenantContext.require();
        DataRequest request = requests.findForUpdate(requestId).orElseThrow(() -> ApiException.notFound("Request"));
        requireOpen(request);
        String note = PrivacyNoticeService.blankToNull(form.note());
        if (form.resolution() == RequestResolution.DECLINED && note == null) {
            throw ApiException.badRequest("Tell the parent why the request is declined.", "note");
        }
        if (form.resolution() == RequestResolution.COMPLETED && request.getType() == RequestType.ERASURE
                && request.getSubject() == RequestSubject.CHILD && request.getErasedAt() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "Not erased yet",
                    "Erase the child's data before closing this request as completed, or decline it with a reason.",
                    Map.of("resolution", "Erase the child's data first, or decline the request."));
        }
        Instant now = Instant.now();
        request.close(form.resolution(), note, actor.id(), actor.name(), now);
        timeline.save(new RequestEvent(requestId, RequestEventKind.CLOSED, actor.id(), actor.name(), note, now));
        requests.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("resolution", form.resolution().name());
        details.put("type", request.getType().name());
        details.put("overdue", today().isAfter(request.getDueOn()));
        audit.record(actor, "data_request.closed", "data_request", requestId, details);
        return detail(request, true);
    }

    // ------------------------------------------------------------------ shared with exports and erasure

    DataRequest find(UUID requestId) {
        return requests.findById(requestId).orElseThrow(() -> ApiException.notFound("Request"));
    }

    DataRequest findForUpdate(UUID requestId) {
        return requests.findForUpdate(requestId).orElseThrow(() -> ApiException.notFound("Request"));
    }

    static void requireOpen(DataRequest request) {
        if (request.isClosed()) {
            throw new ApiException(HttpStatus.CONFLICT, "Closed", "This request is closed.");
        }
    }

    void event(DataRequest request, RequestEventKind kind, Actor actor, String body, Instant at) {
        timeline.save(new RequestEvent(request.getId(), kind, actor.id(), actor.name(), body, at));
    }

    /** The request as staff ({@code staffView}) or the parent who raised it see it. */
    RequestDetail detail(DataRequest r, boolean staffView) {
        StudentDetail student = r.getStudentId() == null ? null : students.detail(r.getStudentId());
        LocalDate today = today();
        List<ExportView> exportViews = exports.summaries(r.getId()).stream().map(DataRequestService::exportView)
                .toList();
        Instant now = Instant.now();
        ExportView downloadable = exportViews.stream()
                .filter(e -> e.status() == ExportStatus.READY && e.expiresAt().isAfter(now))
                .findFirst().orElse(null);
        List<EventView> events = timeline.timeline(r.getId()).stream()
                .map(e -> new EventView(e.getId(), e.getKind(), e.getActorName(),
                        e.getActorId() != null && e.getActorId().equals(r.getRequestedById()), e.getBody(), e.getAt()))
                .toList();
        boolean canErase = staffView && r.getType() == RequestType.ERASURE && r.getSubject() == RequestSubject.CHILD
                && !r.isClosed() && r.getErasedAt() == null && student != null
                && student.status() != StudentStatus.ACTIVE;
        return new RequestDetail(r.getId(), r.getType(), r.getSubject(), r.getStatus(), r.getResolution(),
                r.getDetails(), r.getStudentId(), student == null ? null : student.fullName(),
                student == null ? null : student.admissionNo(), student == null ? null : student.status(),
                r.getRequesterName(), staffView ? assignee(r) : null, r.getCreatedAt(), r.getDueOn(),
                daysLeft(r, today), overdue(r, today), r.getClosingNote(), r.getClosedAt(), r.getClosedByName(),
                r.getErasedAt(), canErase, downloadable, exportViews, events);
    }

    // ------------------------------------------------------------------ helpers

    private List<RequestRow> rows(List<DataRequest> list, boolean staffView) {
        if (list.isEmpty()) {
            return List.of();
        }
        Set<UUID> studentIds = new HashSet<>();
        list.stream().map(DataRequest::getStudentId).filter(Objects::nonNull).forEach(studentIds::add);
        Map<UUID, RosterStudent> names = roster.students(studentIds, null);
        Map<UUID, ExportStatus> exportStatus = latestExportStatus(list.stream().map(DataRequest::getId).toList());
        LocalDate today = today();
        List<RequestRow> rows = new ArrayList<>();
        for (DataRequest r : list) {
            RosterStudent s = r.getStudentId() == null ? null : names.get(r.getStudentId());
            rows.add(new RequestRow(r.getId(), r.getType(), r.getSubject(), r.getStatus(), r.getResolution(),
                    r.getStudentId(), s == null ? null : s.fullName(), s == null ? null : s.admissionNo(),
                    r.getRequesterName(), staffView ? assignee(r) : null, r.getCreatedAt(), r.getDueOn(),
                    daysLeft(r, today), overdue(r, today), r.getLastActivityAt(), exportStatus.get(r.getId())));
        }
        return rows;
    }

    private Map<UUID, ExportStatus> latestExportStatus(Collection<UUID> requestIds) {
        Map<UUID, ExportStatus> result = new HashMap<>();
        Instant now = Instant.now();
        for (Object[] row : exports.statuses(requestIds)) {
            UUID requestId = (UUID) row[0];
            if (result.containsKey(requestId)) {
                continue;
            }
            ExportStatus status = (ExportStatus) row[1];
            Instant expiresAt = (Instant) row[2];
            // A file past its expiry is gone for the parent even before the clean-up job deletes it.
            result.put(requestId, status == ExportStatus.READY && !expiresAt.isAfter(now) ? ExportStatus.EXPIRED
                    : status);
        }
        return result;
    }

    private RequestCounts counts() {
        Map<RequestStatus, Long> byStatus = new EnumMap<>(RequestStatus.class);
        for (RequestStatus s : RequestStatus.values()) {
            byStatus.put(s, 0L);
        }
        for (Object[] row : requests.countByStatus()) {
            byStatus.put((RequestStatus) row[0], ((Number) row[1]).longValue());
        }
        long submitted = byStatus.get(RequestStatus.SUBMITTED);
        long inProgress = byStatus.get(RequestStatus.IN_PROGRESS);
        return new RequestCounts(submitted + inProgress, submitted, inProgress, byStatus.get(RequestStatus.CLOSED),
                requests.countOverdue(today()));
    }

    private static StaffRef assignee(DataRequest r) {
        return r.getAssignedToId() == null && r.getAssignedToName() == null ? null
                : new StaffRef(r.getAssignedToId(), r.getAssignedToName());
    }

    private static long daysLeft(DataRequest r, LocalDate today) {
        return r.isClosed() ? 0 : ChronoUnit.DAYS.between(today, r.getDueOn());
    }

    private static boolean overdue(DataRequest r, LocalDate today) {
        return !r.isClosed() && today.isAfter(r.getDueOn());
    }

    static ExportView exportView(Object[] row) {
        return new ExportView((UUID) row[0], (String) row[1], ((Number) row[2]).longValue(), (ExportStatus) row[3],
                (String) row[4], (Instant) row[5], (Instant) row[6], (Instant) row[7], ((Number) row[8]).intValue(),
                (Instant) row[9]);
    }

    /** LIKE escaping, as the students module does for its own search. */
    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
