package com.akshara.govreport;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.govreport.GovReportForms.SchemeForm;
import com.akshara.govreport.GovReportForms.SchemeUpdateForm;
import com.akshara.govreport.GovReportTypes.CodeListType;
import com.akshara.govreport.GovReportViews.CodeView;
import com.akshara.govreport.GovReportViews.SchemeView;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentService;
import com.akshara.students.StudentService.StudentDetail;

/** The scholarship and scheme register: which students are enrolled in which scheme in a year, with an export. */
@Service
@Transactional
public class SchemeService {

    private final SchemeEnrolmentRepository enrolments;
    private final StudentService students;
    private final CodeListService codes;
    private final SchoolProfileService profiles;
    private final AuditService audit;

    SchemeService(SchemeEnrolmentRepository enrolments, StudentService students, CodeListService codes,
            SchoolProfileService profiles, AuditService audit) {
        this.enrolments = enrolments;
        this.students = students;
        this.codes = codes;
        this.profiles = profiles;
        this.audit = audit;
    }

    @Transactional
    public List<SchemeView> list(UUID yearId, String schemeCode) {
        YearInfo year = profiles.year(yearId);
        List<SchemeEnrolment> list = schemeCode == null || schemeCode.isBlank()
                ? enrolments.findByAcademicYearIdOrderByCreatedAtDesc(year.id())
                : enrolments.findByAcademicYearIdAndSchemeCodeOrderByCreatedAtDesc(year.id(), schemeCode);
        Map<String, CodeView> schemes = codes.inForce(CodeListType.SCHEME, GovReportAuth.today());
        return list.stream().map(e -> view(e, schemes)).toList();
    }

    public SchemeView add(SchemeForm form, Actor actor) {
        YearInfo year = profiles.year(form.academicYearId());
        StudentDetail student;
        try {
            student = students.detail(form.studentId());
        } catch (ApiException e) {
            throw ApiException.badRequest("Choose a student of this school.", "studentId");
        }
        Map<String, CodeView> schemes = codes.inForce(CodeListType.SCHEME, GovReportAuth.today());
        if (!schemes.containsKey(form.schemeCode())) {
            throw ApiException.badRequest("Choose a scheme from the list.", "schemeCode");
        }
        if (enrolments.findByStudentIdAndAcademicYearIdAndSchemeCode(student.id(), year.id(), form.schemeCode())
                .isPresent()) {
            throw ApiException.conflict("This student is already in the scheme for the year.", "schemeCode");
        }
        notFuture(form.appliedOn());
        SchemeEnrolment e = new SchemeEnrolment(student.id(), year.id(), form.schemeCode());
        e.update(form.status(), form.amountPaise(), GovReportAuth.blankToNull(form.referenceNo()), form.appliedOn(),
                GovReportAuth.blankToNull(form.notes()));
        enrolments.save(e);
        audit.record(actor, "govreport_scheme.enrolled", "govreport_scheme", e.getId(),
                details(e, student.fullName()));
        return view(e, schemes);
    }

    public SchemeView update(UUID id, SchemeUpdateForm form, Actor actor) {
        SchemeEnrolment e = find(id);
        notFuture(form.appliedOn());
        e.update(form.status(), form.amountPaise(), GovReportAuth.blankToNull(form.referenceNo()), form.appliedOn(),
                GovReportAuth.blankToNull(form.notes()));
        Map<String, CodeView> schemes = codes.inForce(CodeListType.SCHEME, GovReportAuth.today());
        SchemeView view = view(e, schemes);
        audit.record(actor, "govreport_scheme.updated", "govreport_scheme", e.getId(), details(e, view.fullName()));
        return view;
    }

    /** An application date is a day that has happened (400 on appliedOn otherwise). */
    private static void notFuture(LocalDate appliedOn) {
        if (appliedOn != null && appliedOn.isAfter(GovReportAuth.today())) {
            throw ApiException.badRequest("The application date cannot be in the future.", "appliedOn");
        }
    }

    public void remove(UUID id, Actor actor) {
        SchemeEnrolment e = find(id);
        Map<String, Object> details = details(e, null);
        enrolments.delete(e);
        audit.record(actor, "govreport_scheme.removed", "govreport_scheme", id, details);
    }

    /** The register of one scheme (or all) as a CSV for the scheme's portal. */
    @Transactional
    public GovCsv export(UUID yearId, String schemeCode) {
        GovCsv csv = new GovCsv("Admission no", "Student name", "Class", "Section", "Scheme", "Status",
                "Amount (INR)", "Reference no", "Applied on", "Notes");
        for (SchemeView v : list(yearId, schemeCode)) {
            csv.row(v.admissionNo(), v.fullName(), v.className(), v.sectionName(), v.schemeLabel(),
                    v.status().name(), v.amountPaise() == null ? null : v.amountPaise() / 100, v.referenceNo(),
                    v.appliedOn(), v.notes());
        }
        return csv;
    }

    SchemeEnrolment find(UUID id) {
        TenantContext.require();
        return enrolments.findById(id).orElseThrow(() -> ApiException.notFound("Scheme enrolment"));
    }

    private static Map<String, Object> details(SchemeEnrolment e, String studentName) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("scheme", e.getSchemeCode());
        d.put("status", e.getStatus().name());
        if (studentName != null) {
            d.put("student", studentName);
        }
        return d;
    }

    SchemeView view(SchemeEnrolment e, Map<String, CodeView> schemes) {
        StudentDetail s;
        try {
            s = students.detail(e.getStudentId());
        } catch (ApiException ex) {
            s = null;
        }
        return new SchemeView(e.getId(), e.getStudentId(), s == null ? "—" : s.fullName(),
                s == null ? null : s.admissionNo(),
                s == null || s.currentEnrollment() == null ? null : s.currentEnrollment().className(),
                s == null || s.currentEnrollment() == null ? null : s.currentEnrollment().sectionName(),
                e.getAcademicYearId(), e.getSchemeCode(), codes.label(schemes, e.getSchemeCode()), e.getStatus(),
                e.getAmountPaise(), e.getReferenceNo(), e.getAppliedOn(), e.getNotes(), e.getUpdatedAt());
    }
}
