package com.akshara.exams;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.exams.ExamsForms.RecordForm;
import com.akshara.exams.ExamsForms.RecordsForm;
import com.akshara.exams.ExamsViews.CoScholastic;
import com.akshara.exams.ExamsViews.Hpc;
import com.akshara.exams.ExamsViews.StudentRecord;
import com.akshara.exams.ExamsViews.TermRecords;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster;
import com.akshara.students.StudentRoster.RosterStudent;

/**
 * What the class teacher writes for each student per term: co-scholastic grades (work education, art education,
 * health and physical education, discipline) on the co-scholastic scheme, a remark, and for foundational and
 * preparatory classes the NEP 2020 holistic progress card. The class teacher of the section, or School Admin and
 * Principal, enter them. Saves are audited with counts only.
 */
@Service
@Transactional
public class RecordsService {

    private final TermRecordRepository records;
    private final HpcEntryRepository hpcEntries;
    private final ExamSupport support;
    private final ExamsAccess access;
    private final GradingService grading;
    private final StudentRoster roster;
    private final AuditService audit;

    RecordsService(TermRecordRepository records, HpcEntryRepository hpcEntries, ExamSupport support,
            ExamsAccess access, GradingService grading, StudentRoster roster, AuditService audit) {
        this.records = records;
        this.hpcEntries = hpcEntries;
        this.support = support;
        this.access = access;
        this.grading = grading;
        this.roster = roster;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public TermRecords records(UUID termId, UUID sectionId) {
        TenantContext.require();
        Term term = support.term(termId);
        SectionInfo section = support.section(sectionId);
        return view(term, section, support.year(term.getAcademicYearId()));
    }

    public TermRecords save(UUID termId, UUID sectionId, RecordsForm form, Actor actor) {
        return save(termId, sectionId, form, actor, null);
    }

    /** {@link #save(UUID, UUID, RecordsForm, Actor)} with the caller's scope given; null works it out. */
    public TermRecords save(UUID termId, UUID sectionId, RecordsForm form, Actor actor, ExamScope given) {
        TenantContext.require();
        Term term = support.term(termId);
        SectionInfo section = support.section(sectionId);
        YearInfo year = support.year(term.getAcademicYearId());
        ExamScope scope = given != null ? given : access.scope(year.id());
        if (!scope.canEditRecords(sectionId)) {
            throw new AccessDeniedException("Not this person's section");
        }
        List<RosterStudent> students = roster.activeInSection(year.id(), sectionId);
        Set<UUID> allowed = students.stream().map(RosterStudent::id).collect(Collectors.toCollection(HashSet::new));
        List<UUID> ids = form.entries().stream().map(RecordForm::studentId).toList();
        Map<UUID, TermRecord> existing = records.findByTermIdAndStudentIdIn(termId, ids).stream()
                .collect(Collectors.toMap(TermRecord::getStudentId, Function.identity()));
        Map<UUID, HpcEntry> existingHpc = hpcEntries.findByTermIdAndStudentIdIn(termId, ids).stream()
                .collect(Collectors.toMap(HpcEntry::getStudentId, Function.identity()));
        allowed.addAll(existing.keySet());
        List<String> grades = grading.coScholasticGrades();
        boolean hpcEnabled = grading.hpcFor(section.className());
        Set<UUID> seen = new HashSet<>();
        Instant now = Instant.now();
        int changed = 0;
        for (RecordForm f : form.entries()) {
            if (!allowed.contains(f.studentId())) {
                throw ApiException.badRequest("Enter records only for students of " + section.label() + ".",
                        "entries");
            }
            if (!seen.add(f.studentId())) {
                throw ApiException.badRequest("Each student can appear only once.", "entries");
            }
            String work = grade(f.workEducation(), grades);
            String art = grade(f.artEducation(), grades);
            String health = grade(f.healthPhysical(), grades);
            String discipline = grade(f.discipline(), grades);
            String remark = GradingService.clean(f.remark());
            TermRecord record = existing.get(f.studentId());
            boolean anyRecord = work != null || art != null || health != null || discipline != null
                    || remark != null;
            if (record == null && anyRecord) {
                record = new TermRecord(termId, f.studentId(), now);
                record.set(work, art, health, discipline, remark, actor.name(), now);
                records.save(record);
                changed++;
            } else if (record != null && record.set(work, art, health, discipline, remark, actor.name(), now)) {
                changed++;
            }
            Hpc hpc = new Hpc(f.physical(), f.socioEmotional(), f.cognitive(), f.language(), f.numeracy(),
                    f.aesthetic(), GradingService.clean(f.teacherReflection()),
                    GradingService.clean(f.selfReflection()), GradingService.clean(f.parentReflection()));
            HpcEntry entry = existingHpc.get(f.studentId());
            if (!hpc.empty() && !hpcEnabled) {
                throw ApiException.badRequest("The holistic progress card is for classes up to the level set in "
                        + "the exam settings; " + section.className() + " is not one of them.", "entries");
            }
            if (entry == null && !hpc.empty()) {
                entry = new HpcEntry(termId, f.studentId(), now);
                entry.set(hpc, actor.name(), now);
                hpcEntries.save(entry);
                changed++;
            } else if (entry != null && entry.set(hpc, actor.name(), now)) {
                changed++;
            }
        }
        records.flush();
        hpcEntries.flush();
        if (changed > 0) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("term", term.getName());
            details.put("section", section.label());
            details.put("changed", changed);
            audit.record(actor, "term_record.saved", "section", sectionId, details);
        }
        return view(term, section, year);
    }

    /** A grade from the co-scholastic scheme, written as the scheme writes it; blank is none. */
    private static String grade(String value, List<String> grades) {
        String v = GradingService.clean(value);
        if (v == null) {
            return null;
        }
        return grades.stream().filter(g -> g.equalsIgnoreCase(v)).findFirst().orElseThrow(() ->
                ApiException.badRequest("Use the co-scholastic grades " + String.join(", ", grades) + ".",
                        "entries"));
    }

    private TermRecords view(Term term, SectionInfo section, YearInfo year) {
        List<RosterStudent> students = roster.activeInSection(year.id(), section.id());
        List<UUID> ids = students.stream().map(RosterStudent::id).toList();
        Map<UUID, TermRecord> byStudent = ids.isEmpty() ? Map.of()
                : records.findByTermIdAndStudentIdIn(term.getId(), ids).stream()
                        .collect(Collectors.toMap(TermRecord::getStudentId, Function.identity()));
        Map<UUID, HpcEntry> hpcByStudent = ids.isEmpty() ? Map.of()
                : hpcEntries.findByTermIdAndStudentIdIn(term.getId(), ids).stream()
                        .collect(Collectors.toMap(HpcEntry::getStudentId, Function.identity()));
        boolean hpcEnabled = grading.hpcFor(section.className());
        List<StudentRecord> rows = new ArrayList<>();
        for (RosterStudent s : students) {
            TermRecord r = byStudent.get(s.id());
            HpcEntry h = hpcByStudent.get(s.id());
            rows.add(new StudentRecord(s.id(), s.fullName(), s.admissionNo(), s.rollNo(),
                    r == null ? new CoScholastic(null, null, null, null)
                            : new CoScholastic(r.getWorkEducation(), r.getArtEducation(), r.getHealthPhysical(),
                                    r.getDiscipline()),
                    r == null ? null : r.getRemark(), hpcEnabled ? (h == null ? Hpc.EMPTY : h.view()) : null));
        }
        return new TermRecords(term.getId(), term.getName(), section.classId(), section.className(), section.id(),
                section.label(), grading.coScholasticGrades(), hpcEnabled,
                access.scope(year.id()).canEditRecords(section.id()), rows);
    }
}
