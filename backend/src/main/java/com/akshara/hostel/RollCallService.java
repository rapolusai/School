package com.akshara.hostel;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.hostel.HostelForms.ResolveForm;
import com.akshara.hostel.HostelForms.RollCallForm;
import com.akshara.hostel.HostelForms.RollCallMark;
import com.akshara.hostel.HostelPeople.Pupil;
import com.akshara.hostel.HostelViews.AlertView;
import com.akshara.hostel.HostelViews.BoarderNights;
import com.akshara.hostel.HostelViews.RollCallNight;
import com.akshara.hostel.HostelViews.RollCallRegister;
import com.akshara.hostel.HostelViews.RollCallRow;
import com.akshara.hostel.HostelViews.RollCallSheet;
import com.akshara.hostel.HostelViews.RollCallSummary;
import com.akshara.notifications.MessageSettings;
import com.akshara.notifications.MessageTemplates;
import com.akshara.notifications.NotificationQueue;
import com.akshara.notifications.NotificationQueue.Recipient;
import com.akshara.notifications.NotificationQueue.Related;
import com.akshara.notifications.NotificationSettingsService;
import com.akshara.platform.TenantDirectory;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster.GuardianContact;

/**
 * The night roll call: the warden marks each boarder present, on leave or absent on their phone. Boarders on an
 * approved pass that night are suggested as on leave. An absentee raises an alert row and a message to the parent
 * through the notifications outbox (simulated). The register lists nights; the monthly summary counts per boarder.
 */
@Service
@Transactional
public class RollCallService {

    static final String RELATED = "hostel_roll_call";
    /** A roll call may be taken or corrected for tonight and the past week. */
    static final int DAYS_BACK = 7;
    private static final String PHONE = "^\\+?[0-9]{8,15}$";

    private final RollCallRepository rollCalls;
    private final RollCallEntryRepository entries;
    private final AllotmentRepository allotments;
    private final HostelPassRepository passes;
    private final HostelAlertRepository alerts;
    private final HostelRepository hostels;
    private final RoomRepository rooms;
    private final BedRepository beds;
    private final HostelPeople people;
    private final HostelScope scope;
    private final NotificationQueue queue;
    private final NotificationSettingsService messageSettings;
    private final TenantDirectory tenants;
    private final AuditService audit;

    RollCallService(RollCallRepository rollCalls, RollCallEntryRepository entries, AllotmentRepository allotments,
            HostelPassRepository passes, HostelAlertRepository alerts, HostelRepository hostels, RoomRepository rooms,
            BedRepository beds, HostelPeople people, HostelScope scope, NotificationQueue queue,
            NotificationSettingsService messageSettings, TenantDirectory tenants, AuditService audit) {
        this.rollCalls = rollCalls;
        this.entries = entries;
        this.allotments = allotments;
        this.passes = passes;
        this.alerts = alerts;
        this.hostels = hostels;
        this.rooms = rooms;
        this.beds = beds;
        this.people = people;
        this.scope = scope;
        this.queue = queue;
        this.messageSettings = messageSettings;
        this.tenants = tenants;
        this.audit = audit;
    }

    /* ------------------------------------------------------------------ the sheet */

    @Transactional(readOnly = true)
    public RollCallSheet sheet(UUID hostelId, LocalDate night) {
        TenantContext.require();
        Hostel hostel = scope.require(hostelId);
        LocalDate day = night == null ? HostelText.today() : night;
        RollCall taken = rollCalls.findByHostelIdAndNight(hostelId, day).orElse(null);
        Map<UUID, RollCallEntry> marks = taken == null ? Map.of() : entries.findByRollCallId(taken.getId()).stream()
                .collect(Collectors.toMap(RollCallEntry::getStudentId, e -> e, (a, b) -> a));
        Map<UUID, HostelPass> away = passes.awayOn(hostelId, day).stream()
                .collect(Collectors.toMap(HostelPass::getStudentId, p -> p, (a, b) -> a));
        List<Allotment> boarders = allotments.activeInHostel(hostelId);
        Map<UUID, Pupil> pupils = people.pupils(boarders.stream().map(Allotment::getStudentId).toList());
        Map<UUID, String> roomNumbers = rooms.findAllById(boarders.stream().map(Allotment::getRoomId).toList())
                .stream().collect(Collectors.toMap(Room::getId, Room::getNumber));
        Map<UUID, String> bedLabels = beds.findAllById(boarders.stream().map(Allotment::getBedId).toList()).stream()
                .collect(Collectors.toMap(Bed::getId, Bed::getLabel));
        List<RollCallRow> rows = new ArrayList<>();
        int present = 0;
        int onLeave = 0;
        int absent = 0;
        for (Allotment a : boarders) {
            Pupil p = pupils.get(a.getStudentId());
            RollCallEntry e = marks.get(a.getStudentId());
            HostelPass pass = away.get(a.getStudentId());
            RollCallStatus status = e == null ? null : e.getStatus();
            if (status == RollCallStatus.PRESENT) {
                present++;
            } else if (status == RollCallStatus.ON_LEAVE) {
                onLeave++;
            } else if (status == RollCallStatus.ABSENT) {
                absent++;
            }
            rows.add(new RollCallRow(a.getStudentId(), p == null ? null : p.fullName(),
                    p == null ? null : p.admissionNo(), p == null ? null : p.className(),
                    roomNumbers.get(a.getRoomId()), bedLabels.get(a.getBedId()), status,
                    e == null ? null : e.getNote(), pass == null ? null : pass.getId(), pass != null));
        }
        rows.sort(Comparator.comparing((RollCallRow r) -> r.roomNumber() == null ? "" : r.roomNumber())
                .thenComparing(r -> r.bedLabel() == null ? "" : r.bedLabel()));
        return new RollCallSheet(hostelId, hostel.getName(), day, taken != null,
                taken == null ? null : taken.getTakenByName(), taken == null ? null : taken.getTakenAt(),
                present, onLeave, absent, rows, scope.canWarden(hostel));
    }

    /** Saves the night's marks (every boarder of the hostel, once); absentees raise alerts and parent messages. */
    public RollCallSheet take(UUID hostelId, LocalDate night, RollCallForm form, Actor actor) {
        TenantContext.require();
        Hostel hostel = scope.requireWarden(hostelId);
        LocalDate today = HostelText.today();
        if (night.isAfter(today) || night.isBefore(today.minusDays(DAYS_BACK))) {
            throw ApiException.badRequest("Roll call is taken for tonight or the last " + DAYS_BACK + " nights.",
                    "night");
        }
        List<Allotment> boarders = allotments.activeInHostel(hostelId);
        Set<UUID> boarderIds = boarders.stream().map(Allotment::getStudentId).collect(Collectors.toSet());
        Map<UUID, RollCallMark> marks = new LinkedHashMap<>();
        for (RollCallMark m : form.entries()) {
            if (!boarderIds.contains(m.studentId())) {
                throw ApiException.badRequest("One of the students is not a boarder of " + hostel.getName() + ".",
                        "entries");
            }
            if (marks.put(m.studentId(), m) != null) {
                throw ApiException.badRequest("Each boarder once.", "entries");
            }
        }
        if (marks.size() != boarderIds.size()) {
            throw ApiException.badRequest("Mark every boarder: " + marks.size() + " of " + boarderIds.size()
                    + " marked.", "entries");
        }
        Instant now = Instant.now();
        RollCall call = rollCalls.findByHostelIdAndNight(hostelId, night)
                .orElseGet(() -> rollCalls.save(new RollCall(hostelId, night, actor.name(), now)));
        Map<UUID, RollCallEntry> existing = entries.findByRollCallId(call.getId()).stream()
                .collect(Collectors.toMap(RollCallEntry::getStudentId, e -> e, (a, b) -> a));
        Map<UUID, Pupil> pupils = people.pupils(boarderIds);
        Map<UUID, GuardianContact> guardians = people.primaryGuardians(boarderIds);
        MessageSettings school = messageSettings.current();
        String schoolName = tenants.profile(TenantContext.require()).map(p -> p.name()).orElse("");
        int present = 0;
        int onLeave = 0;
        int absent = 0;
        List<String> absentees = new ArrayList<>();
        for (RollCallMark m : marks.values()) {
            String note = HostelText.clean(m.note());
            RollCallEntry e = existing.get(m.studentId());
            boolean wasAbsent = e != null && e.getStatus() == RollCallStatus.ABSENT;
            if (e == null) {
                entries.save(new RollCallEntry(call.getId(), m.studentId(), m.status(), note, now));
            } else {
                e.mark(m.status(), note);
            }
            switch (m.status()) {
            case PRESENT -> present++;
            case ON_LEAVE -> onLeave++;
            case ABSENT -> absent++;
            }
            Pupil p = pupils.get(m.studentId());
            if (m.status() == RollCallStatus.ABSENT) {
                absentees.add(p == null ? m.studentId().toString() : p.fullName());
                if (!wasAbsent && p != null) {
                    raiseAbsentAlert(hostel, p, guardians.get(p.id()), night, school, schoolName, now);
                }
            }
        }
        call.taken(actor.name(), now, present, onLeave, absent);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("hostel", hostel.getName());
        details.put("night", night.toString());
        details.put("present", present);
        details.put("onLeave", onLeave);
        details.put("absent", absent);
        details.put("absentees", absentees);
        audit.record(actor, "hostel_roll_call.taken", "hostel_roll_call", call.getId(), details);
        rollCalls.flush();
        entries.flush();
        return sheet(hostelId, night);
    }

    private void raiseAbsentAlert(Hostel hostel, Pupil p, GuardianContact guardian, LocalDate night,
            MessageSettings school, String schoolName, Instant now) {
        if (alerts.find(hostel.getId(), p.id(), AlertKind.ABSENT_AT_ROLL_CALL, night).isPresent()) {
            return;
        }
        alerts.save(new HostelAlert(hostel.getId(), p.id(), AlertKind.ABSENT_AT_ROLL_CALL, night,
                p.fullName() + " was absent at the roll call of " + night + " in " + hostel.getName() + ".", now));
        if (guardian == null || guardian.phone() == null || !guardian.phone().trim().matches(PHONE)) {
            return;
        }
        Map<String, String> params = new LinkedHashMap<>();
        params.put("guardian", guardian.name());
        params.put("student", p.fullName());
        params.put("school", schoolName);
        params.put("hostel", hostel.getName());
        params.put("date", night.toString());
        Recipient to = Recipient.phone(school.absenceAlertChannel(), guardian.phone().trim(), guardian.name(),
                Map.of(), "hostel_roll_call:" + hostel.getId() + ":" + p.id() + ":" + night);
        queue.enqueue(MessageTemplates.HOSTEL_ROLL_CALL_ABSENT, school.alertLanguage(), params,
                new Related(RELATED, hostel.getId(), p.fullName() + (p.className() == null ? ""
                        : " (" + p.className() + ")")), List.of(to), now);
    }

    /* ------------------------------------------------------------------ register and summary */

    @Transactional(readOnly = true)
    public RollCallRegister register(UUID hostelId, LocalDate from, LocalDate to) {
        TenantContext.require();
        Hostel hostel = scope.require(hostelId);
        LocalDate end = to == null ? HostelText.today() : to;
        LocalDate start = from == null ? end.minusDays(30) : from;
        if (start.isAfter(end)) {
            throw ApiException.badRequest("The range ends before it starts.", "to");
        }
        List<RollCall> nights = rollCalls.between(hostelId, start, end);
        return new RollCallRegister(hostelId, hostel.getName(), start, end, nightViews(nights));
    }

    List<RollCallNight> nightViews(Collection<RollCall> nights) {
        List<RollCallEntry> all = nights.isEmpty() ? List.of()
                : entries.findByRollCallIdIn(nights.stream().map(RollCall::getId).toList());
        Map<UUID, List<RollCallEntry>> byCall = all.stream().collect(Collectors.groupingBy(
                RollCallEntry::getRollCallId));
        Map<UUID, Pupil> pupils = people.pupils(all.stream().filter(e -> e.getStatus() == RollCallStatus.ABSENT)
                .map(RollCallEntry::getStudentId).collect(Collectors.toSet()));
        List<RollCallNight> result = new ArrayList<>();
        for (RollCall r : nights) {
            List<String> absentees = byCall.getOrDefault(r.getId(), List.of()).stream()
                    .filter(e -> e.getStatus() == RollCallStatus.ABSENT)
                    .map(e -> pupils.containsKey(e.getStudentId()) ? pupils.get(e.getStudentId()).fullName()
                            : "?")
                    .sorted().toList();
            result.add(new RollCallNight(r.getId(), r.getNight(), r.getTakenByName(), r.getTakenAt(),
                    r.getPresent(), r.getOnLeave(), r.getAbsent(), absentees));
        }
        return result;
    }

    /** The month "YYYY-MM": nights taken and each boarder's counts. */
    @Transactional(readOnly = true)
    public RollCallSummary summary(UUID hostelId, String month) {
        TenantContext.require();
        Hostel hostel = scope.require(hostelId);
        YearMonth ym;
        try {
            ym = month == null ? YearMonth.from(HostelText.today()) : YearMonth.parse(month);
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.badRequest("Give the month as YYYY-MM.", "month");
        }
        List<RollCall> nights = rollCalls.between(hostelId, ym.atDay(1), ym.atEndOfMonth());
        List<RollCallEntry> all = nights.isEmpty() ? List.of()
                : entries.findByRollCallIdIn(nights.stream().map(RollCall::getId).toList());
        Map<UUID, int[]> counts = new HashMap<>();
        for (RollCallEntry e : all) {
            int[] c = counts.computeIfAbsent(e.getStudentId(), k -> new int[3]);
            c[e.getStatus().ordinal()]++;
        }
        Map<UUID, Pupil> pupils = people.pupils(counts.keySet());
        List<BoarderNights> boarders = new ArrayList<>();
        counts.forEach((id, c) -> {
            Pupil p = pupils.get(id);
            boarders.add(new BoarderNights(id, p == null ? null : p.fullName(), p == null ? null : p.className(),
                    c[RollCallStatus.PRESENT.ordinal()], c[RollCallStatus.ON_LEAVE.ordinal()],
                    c[RollCallStatus.ABSENT.ordinal()]));
        });
        boarders.sort(Comparator.comparing((BoarderNights b) -> b.className() == null ? "" : b.className())
                .thenComparing(b -> b.studentName() == null ? "" : b.studentName()));
        return new RollCallSummary(hostelId, hostel.getName(), ym.toString(), nights.size(), boarders);
    }

    /* ------------------------------------------------------------------ alerts */

    @Transactional(readOnly = true)
    public List<AlertView> openAlerts() {
        TenantContext.require();
        Set<UUID> ids = scope.visibleIds();
        return ids.isEmpty() ? List.of() : alertViews(alerts.openIn(ids));
    }

    public AlertView resolveAlert(UUID id, ResolveForm form, Actor actor) {
        TenantContext.require();
        HostelAlert alert = alerts.findById(id).orElseThrow(() -> ApiException.notFound("Alert"));
        Hostel hostel = scope.requireWarden(alert.getHostelId());
        if (alert.isResolved()) {
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "Already resolved",
                    "This alert was already resolved.");
        }
        alert.resolve(HostelText.clean(form == null ? null : form.note()), actor, Instant.now());
        Pupil p = people.pupils(List.of(alert.getStudentId())).get(alert.getStudentId());
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("hostel", hostel.getName());
        details.put("student", p == null ? "" : p.fullName());
        details.put("kind", alert.getKind().name());
        details.put("day", alert.getDay().toString());
        details.put("note", alert.getResolutionNote() == null ? "" : alert.getResolutionNote());
        audit.record(actor, "hostel_alert.resolved", "hostel_alert", id, details);
        alerts.flush();
        return alertViews(List.of(alert)).get(0);
    }

    List<AlertView> alertViews(Collection<HostelAlert> rows) {
        Map<UUID, Pupil> pupils = people.pupils(rows.stream().map(HostelAlert::getStudentId)
                .collect(Collectors.toSet()));
        Map<UUID, String> names = hostels.findAllById(rows.stream().map(HostelAlert::getHostelId).toList())
                .stream().collect(Collectors.toMap(Hostel::getId, Hostel::getName));
        return rows.stream().map(a -> {
            Pupil p = pupils.get(a.getStudentId());
            return new AlertView(a.getId(), a.getHostelId(), names.get(a.getHostelId()), a.getStudentId(),
                    p == null ? null : p.fullName(), p == null ? null : p.className(), a.getKind(), a.getDay(),
                    a.getMessage(), a.isResolved(), a.getResolvedByName(), a.getResolvedAt(),
                    a.getResolutionNote(), a.getCreatedAt());
        }).toList();
    }
}
