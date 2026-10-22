package com.akshara.health;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.health.HealthForms.CommitteeForm;
import com.akshara.health.HealthForms.ComplaintForm;
import com.akshara.health.HealthForms.MeetingForm;
import com.akshara.health.HealthForms.MemberForm;
import com.akshara.health.HealthPeople.Staff;
import com.akshara.health.HealthViews.CommitteeView;
import com.akshara.health.HealthViews.ComplaintView;
import com.akshara.health.HealthViews.MeetingView;
import com.akshara.health.HealthViews.MemberView;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * The POCSO committee (members from the staff, the formation date, meeting minutes) and its complaints register.
 * The register is read by the committee only: the Principal (pocso.committee) and the named members. Every read of a
 * complaint's details is audited; the complainant's note is shown on the detail alone.
 */
@Service
@Transactional
public class PocsoService {

    static final String COMPLAINT = "pocso_complaint";

    private final PocsoCommitteeRepository committees;
    private final PocsoMemberRepository members;
    private final PocsoMeetingRepository meetings;
    private final PocsoComplaintRepository complaints;
    private final HealthPeople people;
    private final AuditService audit;

    PocsoService(PocsoCommitteeRepository committees, PocsoMemberRepository members,
            PocsoMeetingRepository meetings, PocsoComplaintRepository complaints, HealthPeople people,
            AuditService audit) {
        this.committees = committees;
        this.members = members;
        this.meetings = meetings;
        this.complaints = complaints;
        this.people = people;
        this.audit = audit;
    }

    /** True when the user may read the complaints register: the permission, or a seat on the committee. */
    @Transactional(readOnly = true)
    public boolean isCommittee(UUID userId, boolean hasPermission) {
        TenantContext.require();
        return hasPermission || members.existsByUserId(userId);
    }

    /** Refuses with 403 unless the user is on the committee. */
    void requireCommittee(UUID userId, boolean hasPermission) {
        if (!isCommittee(userId, hasPermission)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Committee only",
                    "Only the POCSO committee may open the complaints register.");
        }
    }

    @Transactional(readOnly = true)
    public CommitteeView committee(UUID userId, boolean hasPermission, boolean canManage) {
        TenantContext.require();
        Map<UUID, Staff> staff = new java.util.HashMap<>();
        people.allStaff().forEach(s -> staff.put(s.userId(), s));
        List<MemberView> rows = members.findAllByOrderByRoleAscNameAsc().stream()
                .map(m -> new MemberView(m.getId(), m.getUserId(), m.getName(),
                        staff.containsKey(m.getUserId()) ? staff.get(m.getUserId()).designation() : null, m.getRole(),
                        m.getAppointedOn()))
                .toList();
        List<MeetingView> minutes = meetings.findAllByOrderByMeetingDateDesc().stream()
                .map(m -> new MeetingView(m.getId(), m.getMeetingDate(), m.getMinutes(), m.getRecordedByName()))
                .toList();
        return new CommitteeView(committees.one().map(PocsoCommittee::getFormedOn).orElse(null), rows, minutes,
                isCommittee(userId, hasPermission), canManage);
    }

    public void saveCommittee(CommitteeForm form, Actor actor) {
        TenantContext.require();
        PocsoCommittee c = committees.one().orElseGet(() -> committees.save(new PocsoCommittee(Instant.now())));
        c.setFormedOn(form.formedOn());
        audit.record(actor, "pocso_committee.updated", "pocso_committee", c.getId(), Map.of("formedOn",
                form.formedOn() == null ? "" : form.formedOn().toString()));
        committees.flush();
    }

    public MemberView addMember(MemberForm form, Actor actor) {
        TenantContext.require();
        Staff staff = people.requireStaff(form.userId(), "userId");
        if (members.existsByUserId(form.userId())) {
            throw ApiException.conflict(staff.name() + " is already on the committee.", "userId");
        }
        committees.one().orElseGet(() -> committees.saveAndFlush(new PocsoCommittee(Instant.now())));
        PocsoMember m = members.save(new PocsoMember(staff.userId(), staff.name(), form.role(), form.appointedOn(),
                Instant.now()));
        audit.record(actor, "pocso_member.added", "pocso_committee", m.getId(), Map.of("name", staff.name(), "role",
                form.role().name()));
        members.flush();
        return new MemberView(m.getId(), m.getUserId(), m.getName(), staff.designation(), m.getRole(),
                m.getAppointedOn());
    }

    public void removeMember(UUID id, Actor actor) {
        TenantContext.require();
        PocsoMember m = members.findById(id).orElseThrow(() -> ApiException.notFound("Member"));
        members.delete(m);
        audit.record(actor, "pocso_member.removed", "pocso_committee", id, Map.of("name", m.getName()));
        members.flush();
    }

    public MeetingView addMeeting(MeetingForm form, Actor actor) {
        TenantContext.require();
        if (form.meetingDate().isAfter(HealthText.today())) {
            throw ApiException.badRequest("The meeting cannot be in the future.", "meetingDate");
        }
        PocsoMeeting m = meetings.save(new PocsoMeeting(form.meetingDate(), form.minutes().trim(), actor.name(),
                Instant.now()));
        audit.record(actor, "pocso_meeting.recorded", "pocso_committee", m.getId(), Map.of("meetingDate",
                form.meetingDate().toString()));
        meetings.flush();
        return new MeetingView(m.getId(), m.getMeetingDate(), m.getMinutes(), m.getRecordedByName());
    }

    /* ------------------------------------------------------------------ complaints */

    /** The register without the complainant notes. Audited as one read of the register. */
    public List<ComplaintView> complaints(Actor actor) {
        TenantContext.require();
        List<PocsoComplaint> rows = complaints.findAllByOrderByComplaintDateDescCreatedAtDesc();
        audit.record(actor, "pocso_complaint.list_viewed", COMPLAINT, null, Map.of("count", rows.size()));
        return rows.stream().map(c -> view(c, false)).toList();
    }

    /** One complaint with its complainant note. Audited every time. */
    public ComplaintView complaint(UUID id, Actor actor) {
        TenantContext.require();
        PocsoComplaint c = complaints.findById(id).orElseThrow(() -> ApiException.notFound("Complaint"));
        audit.record(actor, "pocso_complaint.viewed", COMPLAINT, id, Map.of("category", c.getCategory().name(),
                "status", c.getStatus().name()));
        return view(c, true);
    }

    public ComplaintView addComplaint(ComplaintForm form, Actor actor) {
        TenantContext.require();
        PocsoComplaint c = complaints.save(new PocsoComplaint(details(form, ComplaintStatus.OPEN), actor.name(),
                Instant.now()));
        audit.record(actor, "pocso_complaint.recorded", COMPLAINT, c.getId(), auditDetails(c));
        complaints.flush();
        return view(c, true);
    }

    public ComplaintView updateComplaint(UUID id, ComplaintForm form, Actor actor) {
        TenantContext.require();
        PocsoComplaint c = complaints.findById(id).orElseThrow(() -> ApiException.notFound("Complaint"));
        c.apply(details(form, c.getStatus()));
        audit.record(actor, "pocso_complaint.updated", COMPLAINT, id, auditDetails(c));
        complaints.flush();
        return view(c, true);
    }

    private static PocsoComplaint.Details details(ComplaintForm form, ComplaintStatus current) {
        if (form.complaintDate().isAfter(HealthText.today())) {
            throw ApiException.badRequest("The complaint date cannot be in the future.", "complaintDate");
        }
        return new PocsoComplaint.Details(form.complaintDate(), form.category(), form.description().trim(),
                form.status() == null ? current : form.status(), HealthText.clean(form.actions()),
                HealthText.clean(form.complainantNote()));
    }

    /** Never the description or the complainant: the audit trail is read more widely than the register. */
    private static Map<String, Object> auditDetails(PocsoComplaint c) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("complaintDate", c.getComplaintDate().toString());
        details.put("category", c.getCategory().name());
        details.put("status", c.getStatus().name());
        return details;
    }

    private static ComplaintView view(PocsoComplaint c, boolean withNote) {
        return new ComplaintView(c.getId(), c.getComplaintDate(), c.getCategory(), c.getDescription(), c.getStatus(),
                c.getActions(), withNote ? c.getComplainantNote() : null, c.getRecordedByName(), c.getUpdatedAt());
    }
}
