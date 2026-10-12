package com.akshara.frontoffice;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.admissions.AdmissionForms.ApplicationRequest;
import com.akshara.admissions.AdmissionForms.GuardianInput;
import com.akshara.admissions.AdmissionsService;
import com.akshara.admissions.AdmissionsService.ApplicationDetail;
import com.akshara.admissions.ApplicationSource;
import com.akshara.admissions.ApplicationStage;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.frontoffice.FrontOfficeForms.CallForm;
import com.akshara.frontoffice.FrontOfficeForms.CloseForm;
import com.akshara.frontoffice.FrontOfficeForms.ConvertForm;
import com.akshara.frontoffice.FrontOfficeForms.EnquiryForm;
import com.akshara.frontoffice.FrontOfficeViews.CallView;
import com.akshara.frontoffice.FrontOfficeViews.EnquiryView;
import com.akshara.frontoffice.FrontOfficeViews.FollowUp;
import com.akshara.frontoffice.FrontOfficeViews.FollowUpQueue;
import com.akshara.frontoffice.FrontOfficeViews.StaffRef;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.students.GuardianRelation;

/**
 * The call log and the enquiry log with their follow-up queue. An admission enquiry can be converted, in one step,
 * into an application in the admissions module (through its public service), which then owns the funnel.
 */
@Service
@Transactional
public class EnquiryService {

    static final int LATEST = 200;

    private final CallLogRepository calls;
    private final EnquiryRepository enquiries;
    private final FrontOfficePeople people;
    private final AdmissionsService admissions;
    private final AuditService audit;

    EnquiryService(CallLogRepository calls, EnquiryRepository enquiries, FrontOfficePeople people,
            AdmissionsService admissions, AuditService audit) {
        this.calls = calls;
        this.enquiries = enquiries;
        this.people = people;
        this.admissions = admissions;
        this.audit = audit;
    }

    /* ------------------------------------------------------------------ calls */

    /** Calls of a day, or the latest 200 when no day is given. */
    @Transactional(readOnly = true)
    public List<CallView> calls(LocalDate date) {
        TenantContext.require();
        List<CallLog> rows = date == null ? calls.latest(Limit.of(LATEST))
                : calls.between(FrontOfficeText.startOfDay(date), FrontOfficeText.startOfDay(date.plusDays(1)));
        return rows.stream().map(EnquiryService::view).toList();
    }

    public CallView logCall(CallForm form, Actor actor) {
        TenantContext.require();
        CallLog call = calls.save(new CallLog(callDetails(form), actor, Instant.now()));
        audit.record(actor, "call.logged", "call", call.getId(), callAudit(call));
        calls.flush();
        return view(call);
    }

    public CallView updateCall(UUID id, CallForm form, Actor actor) {
        TenantContext.require();
        CallLog call = requireCall(id);
        call.apply(callDetails(form));
        if (call.getFollowUpOn() == null && call.getStatus() == CallStatus.OPEN) {
            call.close(null);
        }
        audit.record(actor, "call.updated", "call", id, callAudit(call));
        calls.flush();
        return view(call);
    }

    public CallView closeCall(UUID id, CloseForm form, Actor actor) {
        TenantContext.require();
        CallLog call = requireCall(id);
        if (call.getStatus() == CallStatus.DONE) {
            throw new ApiException(HttpStatus.CONFLICT, "Already done", "This call's follow-up is already done.");
        }
        call.close(FrontOfficeText.clean(form == null ? null : form.outcome()));
        audit.record(actor, "call.closed", "call", id, callAudit(call));
        calls.flush();
        return view(call);
    }

    /* ------------------------------------------------------------------ enquiries */

    /** Enquiries of a day, or the latest 200 when no day is given. */
    @Transactional(readOnly = true)
    public List<EnquiryView> enquiries(LocalDate date) {
        TenantContext.require();
        List<Enquiry> rows = date == null ? enquiries.latest(Limit.of(LATEST))
                : enquiries.between(FrontOfficeText.startOfDay(date),
                        FrontOfficeText.startOfDay(date.plusDays(1)));
        return rows.stream().map(EnquiryService::view).toList();
    }

    @Transactional(readOnly = true)
    public EnquiryView enquiry(UUID id) {
        TenantContext.require();
        return view(requireEnquiry(id));
    }

    public EnquiryView logEnquiry(EnquiryForm form, Actor actor) {
        TenantContext.require();
        Enquiry enquiry = enquiries.save(new Enquiry(enquiryDetails(form), actor, Instant.now()));
        audit.record(actor, "enquiry.logged", "enquiry", enquiry.getId(), enquiryAudit(enquiry));
        enquiries.flush();
        return view(enquiry);
    }

    public EnquiryView updateEnquiry(UUID id, EnquiryForm form, Actor actor) {
        TenantContext.require();
        Enquiry enquiry = requireEnquiry(id);
        if (enquiry.getStatus() == EnquiryStatus.CONVERTED) {
            throw new ApiException(HttpStatus.CONFLICT, "Already converted",
                    "This enquiry became an admission application; edit it there.");
        }
        enquiry.apply(enquiryDetails(form));
        audit.record(actor, "enquiry.updated", "enquiry", id, enquiryAudit(enquiry));
        enquiries.flush();
        return view(enquiry);
    }

    public EnquiryView closeEnquiry(UUID id, CloseForm form, Actor actor) {
        TenantContext.require();
        Enquiry enquiry = requireEnquiry(id);
        if (!enquiry.getStatus().open()) {
            throw new ApiException(HttpStatus.CONFLICT, "Already closed", "This enquiry is already "
                    + enquiry.getStatus().name().toLowerCase() + ".");
        }
        String outcome = FrontOfficeText.clean(form == null ? null : form.outcome());
        if (outcome == null) {
            throw ApiException.badRequest("Say how the enquiry was answered.", "outcome");
        }
        enquiry.close(outcome);
        audit.record(actor, "enquiry.closed", "enquiry", id, enquiryAudit(enquiry));
        enquiries.flush();
        return view(enquiry);
    }

    public EnquiryView reopenEnquiry(UUID id, LocalDate followUpOn, Actor actor) {
        TenantContext.require();
        Enquiry enquiry = requireEnquiry(id);
        if (enquiry.getStatus() != EnquiryStatus.CLOSED) {
            throw new ApiException(HttpStatus.CONFLICT, "Not closed", "Only a closed enquiry can be reopened.");
        }
        enquiry.reopen(followUpOn);
        audit.record(actor, "enquiry.reopened", "enquiry", id, enquiryAudit(enquiry));
        enquiries.flush();
        return view(enquiry);
    }

    /** One click: an admission enquiry becomes an application in the admissions module. */
    public EnquiryView convert(UUID id, ConvertForm form, Actor actor) {
        TenantContext.require();
        Enquiry enquiry = requireEnquiry(id);
        if (enquiry.getStatus() == EnquiryStatus.CONVERTED) {
            throw new ApiException(HttpStatus.CONFLICT, "Already converted",
                    "This enquiry is already an admission application.");
        }
        if (enquiry.getTopic() != EnquiryTopic.ADMISSION) {
            throw new ApiException(HttpStatus.CONFLICT, "Not an admission enquiry",
                    "Only an admission enquiry can become an application.");
        }
        String firstName = FrontOfficeText.clean(form.firstName());
        String lastName = FrontOfficeText.clean(form.lastName());
        if (firstName == null) {
            String child = FrontOfficeText.clean(enquiry.getChildName());
            if (child == null) {
                throw ApiException.badRequest("Give the child's first name.", "firstName");
            }
            String[] parts = child.split("\\s+", 2);
            firstName = parts[0];
            lastName = lastName != null ? lastName : parts.length > 1 ? parts[1] : null;
        }
        String phone = VisitorService.phone(form.phone(), "phone");
        if (phone == null) {
            phone = enquiry.getPhone();
        }
        if (phone == null) {
            throw ApiException.badRequest("Give the parent's mobile number.", "phone");
        }
        ApplicationRequest request = new ApplicationRequest(ApplicationStage.ENQUIRY, firstName, lastName,
                form.dateOfBirth(), null, null, form.classId(), form.academicYearId(),
                enquiry.getSource() == EnquirySource.PHONE ? ApplicationSource.PHONE : ApplicationSource.WALK_IN,
                enquiry.getAssignedToUserId(), enquiry.getFollowUpOn(),
                "From the front office enquiry log: " + enquiry.getDetails(),
                List.of(new GuardianInput(enquiry.getName(), form.relation() == null ? GuardianRelation.GUARDIAN
                        : form.relation(), phone, null, true)));
        ApplicationDetail application = admissions.create(request, actor);
        enquiry.convert(application.id());
        Map<String, Object> details = enquiryAudit(enquiry);
        details.put("applicationId", application.id().toString());
        audit.record(actor, "enquiry.converted", "enquiry", id, details);
        enquiries.flush();
        return view(enquiry);
    }

    /* ------------------------------------------------------------------ follow-ups */

    /** Open calls and enquiries with a follow-up date, overdue first. */
    @Transactional(readOnly = true)
    public FollowUpQueue followUps() {
        TenantContext.require();
        LocalDate today = FrontOfficeText.today();
        List<FollowUp> items = new ArrayList<>();
        for (CallLog c : calls.withFollowUp()) {
            items.add(new FollowUp("CALL", c.getId(), c.getCallerName(), c.getAbout(), c.getFollowUpOn(),
                    ChronoUnit.DAYS.between(c.getFollowUpOn(), today), c.getAssignedToName(),
                    FrontOfficeText.maskPhone(c.getPhone())));
        }
        for (Enquiry e : enquiries.open()) {
            if (e.getFollowUpOn() != null) {
                items.add(new FollowUp("ENQUIRY", e.getId(), e.getName(), e.getDetails(), e.getFollowUpOn(),
                        ChronoUnit.DAYS.between(e.getFollowUpOn(), today), e.getAssignedToName(),
                        FrontOfficeText.maskPhone(e.getPhone())));
            }
        }
        items.sort((a, b) -> a.followUpOn().compareTo(b.followUpOn()));
        int dueToday = (int) items.stream().filter(i -> i.daysOverdue() == 0).count();
        int overdue = (int) items.stream().filter(i -> i.daysOverdue() > 0).count();
        return new FollowUpQueue(today, items, dueToday, overdue);
    }

    @Transactional(readOnly = true)
    public List<StaffRef> staff() {
        TenantContext.require();
        return people.activeStaff();
    }

    /* ------------------------------------------------------------------ helpers */

    private CallLog requireCall(UUID id) {
        return calls.findById(id).orElseThrow(() -> ApiException.notFound("Call"));
    }

    private Enquiry requireEnquiry(UUID id) {
        return enquiries.findById(id).orElseThrow(() -> ApiException.notFound("Enquiry"));
    }

    private CallLog.Details callDetails(CallForm form) {
        StaffRef assigned = form.assignedToUserId() == null ? null
                : people.requireStaff(form.assignedToUserId(), "assignedToUserId");
        Instant calledAt = form.calledAt() == null ? Instant.now() : form.calledAt();
        if (calledAt.isAfter(Instant.now().plusSeconds(300))) {
            throw ApiException.badRequest("The call time cannot be in the future.", "calledAt");
        }
        return new CallLog.Details(form.direction(), form.callerName().trim(), VisitorService.phone(form.phone(),
                "phone"), form.about().trim(), FrontOfficeText.clean(form.outcome()), form.followUpOn(),
                assigned == null ? null : assigned.id(), assigned == null ? null : assigned.name(), calledAt);
    }

    private Enquiry.Details enquiryDetails(EnquiryForm form) {
        StaffRef assigned = form.assignedToUserId() == null ? null
                : people.requireStaff(form.assignedToUserId(), "assignedToUserId");
        Instant at = form.enquiredAt() == null ? Instant.now() : form.enquiredAt();
        if (at.isAfter(Instant.now().plusSeconds(300))) {
            throw ApiException.badRequest("The time cannot be in the future.", "enquiredAt");
        }
        return new Enquiry.Details(form.source(), form.topic(), form.name().trim(),
                VisitorService.phone(form.phone(), "phone"), FrontOfficeText.clean(form.childName()),
                FrontOfficeText.clean(form.className()), form.details().trim(), form.followUpOn(),
                assigned == null ? null : assigned.id(), assigned == null ? null : assigned.name(), at);
    }

    private static Map<String, Object> callAudit(CallLog c) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("caller", c.getCallerName());
        details.put("direction", c.getDirection().name());
        details.put("status", c.getStatus().name());
        if (c.getFollowUpOn() != null) {
            details.put("followUpOn", c.getFollowUpOn().toString());
        }
        return details;
    }

    private static Map<String, Object> enquiryAudit(Enquiry e) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", e.getName());
        details.put("topic", e.getTopic().name());
        details.put("source", e.getSource().name());
        details.put("status", e.getStatus().name());
        if (e.getFollowUpOn() != null) {
            details.put("followUpOn", e.getFollowUpOn().toString());
        }
        return details;
    }

    static CallView view(CallLog c) {
        return new CallView(c.getId(), c.getDirection(), c.getCallerName(), FrontOfficeText.maskPhone(c.getPhone()),
                c.getAbout(), c.getOutcome(), c.getFollowUpOn(), c.getAssignedToUserId(), c.getAssignedToName(),
                c.getStatus(), c.getLoggedByName(), c.getCalledAt());
    }

    static EnquiryView view(Enquiry e) {
        return new EnquiryView(e.getId(), e.getSource(), e.getTopic(), e.getName(),
                FrontOfficeText.maskPhone(e.getPhone()), e.getChildName(), e.getClassName(), e.getDetails(),
                e.getStatus(), e.getOutcome(), e.getFollowUpOn(), e.getAssignedToUserId(), e.getAssignedToName(),
                e.getApplicationId(), e.getLoggedByName(), e.getEnquiredAt());
    }
}
