package com.akshara.timetable;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster;
import com.akshara.students.StudentService;
import com.akshara.students.StudentService.ChildView;
import com.akshara.timetable.TimetableViews.FamilyTimetable;
import com.akshara.timetable.TimetableViews.YearRef;

/** The section timetable a student sees for themselves, or a parent sees for their own child. */
@Service
@Transactional(readOnly = true)
class FamilyTimetableService {

    private final StudentService students;
    private final StudentRoster roster;
    private final TimetableSupport support;
    private final TimetableService timetable;
    private final SubstitutionService substitutions;

    FamilyTimetableService(StudentService students, StudentRoster roster, TimetableSupport support,
            TimetableService timetable, SubstitutionService substitutions) {
        this.students = students;
        this.roster = roster;
        this.support = support;
        this.timetable = timetable;
        this.substitutions = substitutions;
    }

    /** The signed-in student's own timetable. 404 when the sign-in is not linked to a student record. */
    public FamilyTimetable forStudent(UUID userId) {
        TenantContext.require();
        ChildView me = students.studentOf(userId).orElseThrow(() -> ApiException.notFound("Student record"));
        return build(me);
    }

    /** A parent's child's timetable. Any student who is not the parent's own child is 404. */
    public FamilyTimetable forChild(UUID userId, UUID studentId) {
        TenantContext.require();
        if (!roster.childIdsOf(userId).contains(studentId)) {
            throw ApiException.notFound("Student");
        }
        ChildView child = students.childrenOf(userId).stream().filter(c -> c.id().equals(studentId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Student"));
        return build(child);
    }

    private FamilyTimetable build(ChildView student) {
        Bells bells = support.bells();
        LocalDate today = TimetableSupport.today();
        Optional<YearInfo> year = support.currentYearIfAny();
        Optional<UUID> sectionId = year.flatMap(y -> roster.sectionOf(student.id(), y.id()));
        if (year.isEmpty() || sectionId.isEmpty()) {
            return new FamilyTimetable(student.id(), student.fullName(), null, null,
                    year.map(YearRef::of).orElse(null), bells.view(), List.of(), today, today.getDayOfWeek(),
                    bells.isWorking(today.getDayOfWeek()), List.of());
        }
        SectionInfo section = support.section(sectionId.get());
        return new FamilyTimetable(student.id(), student.fullName(), section.id(), section.label(),
                YearRef.of(year.get()), bells.view(), timetable.sectionSlots(year.get().id(), section.id()), today,
                today.getDayOfWeek(), bells.isWorking(today.getDayOfWeek()),
                substitutions.sectionDay(section.id(), today));
    }
}
