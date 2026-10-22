package com.akshara.frontoffice;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.files.FileOwner;
import com.akshara.files.FileStore;
import com.akshara.files.IncomingFile;
import com.akshara.files.StoredFileInfo;
import com.akshara.frontoffice.FrontOfficeForms.BlockForm;
import com.akshara.frontoffice.FrontOfficeForms.VisitorForm;
import com.akshara.frontoffice.FrontOfficeViews.BlockMatch;
import com.akshara.frontoffice.FrontOfficeViews.BlockedView;
import com.akshara.frontoffice.FrontOfficeViews.StaffRef;
import com.akshara.frontoffice.FrontOfficeViews.VisitorDay;
import com.akshara.frontoffice.FrontOfficeViews.VisitorView;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.students.Phones;

/**
 * The visitor log: checking visitors in (with a badge number and a photo) and out, pre-approving visitors expected
 * on a day, and the blocklist. A visitor on the blocklist is refused at check-in unless the office overrides it;
 * every change is audited.
 */
@Service
@Transactional
public class VisitorService {

    /** The problem type the web app recognises when a visitor is on the blocklist. */
    public static final String BLOCKED_TYPE = "urn:akshara:frontoffice:blocked";
    static final String MODULE = "frontoffice";
    static final String VISITOR_PHOTO = "visitor_photo";
    /** Visitors expected within this many days are listed on the visitors screen. */
    static final int EXPECTED_DAYS = 14;

    private final VisitorRepository visitors;
    private final BlockedVisitorRepository blocked;
    private final FrontOfficePeople people;
    private final FileStore files;
    private final AuditService audit;

    VisitorService(VisitorRepository visitors, BlockedVisitorRepository blocked, FrontOfficePeople people,
            FileStore files, AuditService audit) {
        this.visitors = visitors;
        this.blocked = blocked;
        this.people = people;
        this.files = files;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public VisitorDay day(LocalDate date) {
        TenantContext.require();
        LocalDate day = date == null ? FrontOfficeText.today() : date;
        List<BlockedVisitor> list = blocked.findAll();
        List<VisitorView> rows = visitors.checkedInBetween(FrontOfficeText.startOfDay(day),
                FrontOfficeText.startOfDay(day.plusDays(1))).stream().map(v -> view(v, list, false)).toList();
        List<VisitorView> expected = visitors.expectedFrom(FrontOfficeText.today()).stream()
                .filter(v -> !v.getExpectedOn().isAfter(FrontOfficeText.today().plusDays(EXPECTED_DAYS)))
                .map(v -> view(v, list, false)).toList();
        return new VisitorDay(day, rows, (int) visitors.countInside(), expected);
    }

    /** The daily register in check-in order. */
    @Transactional(readOnly = true)
    public List<VisitorView> register(LocalDate day) {
        TenantContext.require();
        return visitors.registerBetween(FrontOfficeText.startOfDay(day), FrontOfficeText.startOfDay(day.plusDays(1)))
                .stream().map(v -> view(v, List.of(), false)).toList();
    }

    @Transactional(readOnly = true)
    public VisitorView detail(UUID id) {
        TenantContext.require();
        return view(require(id), blocked.findAll(), true);
    }

    @Transactional(readOnly = true)
    public List<VisitorView> inside() {
        TenantContext.require();
        return visitors.inside().stream().map(v -> view(v, List.of(), false)).toList();
    }

    @Transactional(readOnly = true)
    public List<StaffRef> staff() {
        TenantContext.require();
        return people.activeStaff();
    }

    /** Checks a visitor in now, or (with {@code expectedOn}) pre-approves them for a day. */
    public VisitorView add(VisitorForm form, Actor actor) {
        TenantContext.require();
        Visitor.Details details = details(form);
        Instant now = Instant.now();
        List<BlockedVisitor> list = blocked.findAll();
        Optional<BlockedVisitor> match = match(list, details.name(), details.phone());
        Visitor visitor;
        if (form.expectedOn() != null) {
            if (form.expectedOn().isBefore(FrontOfficeText.today())) {
                throw ApiException.badRequest("The expected day cannot be in the past.", "expectedOn");
            }
            visitor = Visitor.expected(details, form.expectedOn(), actor, now);
        } else {
            refuseIfBlocked(match, form.override());
            visitor = Visitor.arriving(details, badge(form.badgeNo(), now), actor, now);
        }
        visitors.save(visitor);
        Map<String, Object> audited = auditDetails(visitor);
        if (match.isPresent() && visitor.getStatus() == VisitorStatus.IN) {
            audited.put("blocklistOverridden", true);
            audited.put("blockReason", match.get().getReason());
        }
        audit.record(actor, visitor.getStatus() == VisitorStatus.EXPECTED ? "visitor.expected" : "visitor.checked_in",
                "visitor", visitor.getId(), audited);
        visitors.flush();
        return view(visitor, list, true);
    }

    /** Edits a visitor's details (not their times). */
    public VisitorView update(UUID id, VisitorForm form, Actor actor) {
        TenantContext.require();
        Visitor visitor = require(id);
        Visitor.Details details = details(form);
        visitor.apply(details);
        if (visitor.getStatus() == VisitorStatus.EXPECTED && form.expectedOn() != null) {
            if (form.expectedOn().isBefore(FrontOfficeText.today())) {
                throw ApiException.badRequest("The expected day cannot be in the past.", "expectedOn");
            }
            visitor.reschedule(form.expectedOn());
        }
        audit.record(actor, "visitor.updated", "visitor", id, auditDetails(visitor));
        visitors.flush();
        return view(visitor, blocked.findAll(), true);
    }

    /** An expected visitor arrives. */
    public VisitorView checkIn(UUID id, String badgeNo, Boolean override, Actor actor) {
        TenantContext.require();
        Visitor visitor = require(id);
        if (visitor.getStatus() != VisitorStatus.EXPECTED) {
            throw new ApiException(HttpStatus.CONFLICT, "Already checked in",
                    visitor.getName() + " was already checked in.");
        }
        List<BlockedVisitor> list = blocked.findAll();
        Optional<BlockedVisitor> match = match(list, visitor.getName(), visitor.getPhone());
        refuseIfBlocked(match, override);
        Instant now = Instant.now();
        visitor.checkIn(badge(badgeNo, now), actor, now);
        Map<String, Object> audited = auditDetails(visitor);
        match.ifPresent(b -> {
            audited.put("blocklistOverridden", true);
            audited.put("blockReason", b.getReason());
        });
        audit.record(actor, "visitor.checked_in", "visitor", id, audited);
        visitors.flush();
        return view(visitor, list, true);
    }

    public VisitorView checkOut(UUID id, Actor actor) {
        TenantContext.require();
        Visitor visitor = require(id);
        if (visitor.getStatus() != VisitorStatus.IN) {
            throw new ApiException(HttpStatus.CONFLICT, "Not inside", visitor.getStatus() == VisitorStatus.OUT
                    ? visitor.getName() + " was already checked out."
                    : visitor.getName() + " has not checked in yet.");
        }
        visitor.checkOut(actor, Instant.now());
        audit.record(actor, "visitor.checked_out", "visitor", id, auditDetails(visitor));
        visitors.flush();
        return view(visitor, List.of(), true);
    }

    /** Stores the visitor's photo (taken with the phone camera), replacing an earlier one. */
    public VisitorView addPhoto(UUID id, IncomingFile photo, Actor actor) {
        TenantContext.require();
        Visitor visitor = require(id);
        if (!photo.type().mediaType().startsWith("image/")) {
            throw ApiException.badRequest("Upload a JPEG, PNG or WEBP photo.", "file");
        }
        FileOwner owner = new FileOwner(MODULE, VISITOR_PHOTO, id);
        // Replacing the old photo clears the persistence context, so the visitor is loaded again afterwards.
        files.deleteAll(owner);
        StoredFileInfo stored = files.put(owner, photo, actor.id());
        visitor = require(id);
        visitor.setPhotoFileId(stored.id());
        audit.record(actor, "visitor.photo_added", "visitor", id, Map.of("name", visitor.getName(),
                "size", stored.size()));
        visitors.flush();
        return view(visitor, List.of(), true);
    }

    /* ------------------------------------------------------------------ blocklist */

    @Transactional(readOnly = true)
    public List<BlockedView> blocklist() {
        TenantContext.require();
        return blocked.findAllByOrderByNameAsc().stream().map(VisitorService::view).toList();
    }

    public BlockedView block(BlockForm form, Actor actor) {
        TenantContext.require();
        BlockedVisitor entry = blocked.save(new BlockedVisitor(form.name().trim(), phone(form.phone(), "phone"),
                form.reason().trim(), actor, Instant.now()));
        audit.record(actor, "visitor_block.added", "visitor_block", entry.getId(), Map.of("name", entry.getName(),
                "reason", entry.getReason()));
        blocked.flush();
        return view(entry);
    }

    public BlockedView updateBlock(UUID id, BlockForm form, Actor actor) {
        TenantContext.require();
        BlockedVisitor entry = blocked.findById(id).orElseThrow(() -> ApiException.notFound("Blocklist entry"));
        entry.update(form.name().trim(), phone(form.phone(), "phone"), form.reason().trim());
        audit.record(actor, "visitor_block.updated", "visitor_block", id, Map.of("name", entry.getName(),
                "reason", entry.getReason()));
        blocked.flush();
        return view(entry);
    }

    public void unblock(UUID id, Actor actor) {
        TenantContext.require();
        BlockedVisitor entry = blocked.findById(id).orElseThrow(() -> ApiException.notFound("Blocklist entry"));
        blocked.delete(entry);
        audit.record(actor, "visitor_block.removed", "visitor_block", id, Map.of("name", entry.getName()));
        blocked.flush();
    }

    /* ------------------------------------------------------------------ helpers */

    private Visitor require(UUID id) {
        return visitors.findById(id).orElseThrow(() -> ApiException.notFound("Visitor"));
    }

    private Visitor.Details details(VisitorForm form) {
        UUID toMeetId = form.toMeetUserId();
        String toMeetName = FrontOfficeText.clean(form.toMeetName());
        if (toMeetId != null) {
            toMeetName = people.requireStaff(toMeetId, "toMeetUserId").name();
        }
        return new Visitor.Details(form.name().trim(), phone(form.phone(), "phone"), form.purpose(),
                FrontOfficeText.clean(form.purposeNote()), toMeetId, toMeetName,
                form.peopleCount() == null ? 1 : form.peopleCount(),
                form.idType() == null ? IdType.NONE : form.idType());
    }

    static String phone(String raw, String field) {
        String cleaned = FrontOfficeText.clean(raw);
        if (cleaned == null) {
            return null;
        }
        String normalized = Phones.normalize(cleaned);
        if (normalized == null) {
            throw ApiException.badRequest(Phones.MESSAGE, field);
        }
        return normalized;
    }

    /** The badge number as typed, or the next free number of the day ("V-07"). */
    private String badge(String typed, Instant now) {
        String cleaned = FrontOfficeText.clean(typed);
        if (cleaned != null) {
            return cleaned;
        }
        LocalDate day = FrontOfficeText.dayOf(now);
        String last = visitors.lastBadgeBetween(FrontOfficeText.startOfDay(day),
                FrontOfficeText.startOfDay(day.plusDays(1)));
        int next = 1;
        if (last != null && last.matches("^V-[0-9]{2,}$")) {
            next = Integer.parseInt(last.substring(2)) + 1;
        }
        return String.format("V-%02d", next);
    }

    private static Optional<BlockedVisitor> match(List<BlockedVisitor> list, String name, String phone) {
        return list.stream().filter(b -> b.matches(name, phone)).findFirst();
    }

    private static void refuseIfBlocked(Optional<BlockedVisitor> match, Boolean override) {
        if (match.isPresent() && !Boolean.TRUE.equals(override)) {
            throw new ApiException(HttpStatus.CONFLICT, "On the blocklist",
                    "This visitor is on the school's blocklist: " + match.get().getReason(),
                    Map.of("name", match.get().getReason()), BLOCKED_TYPE);
        }
    }

    private static Map<String, Object> auditDetails(Visitor v) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", v.getName());
        details.put("purpose", v.getPurpose().name());
        details.put("status", v.getStatus().name());
        if (v.getBadgeNo() != null) {
            details.put("badgeNo", v.getBadgeNo());
        }
        if (v.getToMeetName() != null) {
            details.put("toMeet", v.getToMeetName());
        }
        return details;
    }

    static VisitorView view(Visitor v, List<BlockedVisitor> list, boolean withPhone) {
        BlockMatch blockMatch = match(list, v.getName(), v.getPhone())
                .map(b -> new BlockMatch(b.getId(), b.getReason())).orElse(null);
        return new VisitorView(v.getId(), v.getName(), FrontOfficeText.maskPhone(v.getPhone()),
                withPhone ? v.getPhone() : null, v.getPurpose(), v.getPurposeNote(), v.getToMeetUserId(),
                v.getToMeetName(), v.getPeopleCount(), v.getIdType(), v.getBadgeNo(), v.getPhotoFileId(),
                v.getStatus(), v.getExpectedOn(), v.getCheckedInAt(), v.getCheckedOutAt(), v.getAddedByName(),
                v.getCheckedInByName(), v.getCheckedOutByName(), blockMatch);
    }

    private static BlockedView view(BlockedVisitor b) {
        return new BlockedView(b.getId(), b.getName(), FrontOfficeText.maskPhone(b.getPhone()), b.getReason(),
                b.getAddedByName(), b.getCreatedAt());
    }
}
