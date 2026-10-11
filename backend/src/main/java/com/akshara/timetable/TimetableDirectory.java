package com.akshara.timetable;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.shared.TenantContext;

/** Read-only answers about the timetable for other modules (homework: who teaches what). */
@Service
@Transactional(readOnly = true)
public class TimetableDirectory {

    /** A subject taught in a section. */
    public record Teaching(UUID sectionId, UUID subjectId) {
    }

    private final TeacherAssignmentRepository assignments;
    private final SlotRepository slots;

    TimetableDirectory(TeacherAssignmentRepository assignments, SlotRepository slots) {
        this.assignments = assignments;
        this.slots = slots;
    }

    /** The sections and subjects a teacher teaches in the year: their teacher assignments and timetable periods. */
    public Set<Teaching> teachingOf(UUID teacherId, UUID academicYearId) {
        TenantContext.require();
        Set<Teaching> result = new LinkedHashSet<>();
        assignments.findByAcademicYearIdAndTeacherId(academicYearId, teacherId)
                .forEach(a -> result.add(new Teaching(a.getSectionId(), a.getSubjectId())));
        slots.findByAcademicYearIdAndTeacherId(academicYearId, teacherId)
                .forEach(s -> result.add(new Teaching(s.getSectionId(), s.getSubjectId())));
        return result;
    }

    /** A teacher of a section and a subject they teach there. */
    public record SectionTeacher(UUID teacherId, UUID subjectId) {
    }

    /**
     * Who teaches a section in the year, and what: its teacher assignments and timetable periods (messages and
     * parent-teacher meetings).
     */
    public Set<SectionTeacher> teachersOf(UUID sectionId, UUID academicYearId) {
        TenantContext.require();
        Set<SectionTeacher> result = new LinkedHashSet<>();
        assignments.findByAcademicYearIdAndSectionId(academicYearId, sectionId).stream()
                .filter(a -> a.getTeacherId() != null)
                .forEach(a -> result.add(new SectionTeacher(a.getTeacherId(), a.getSubjectId())));
        slots.findByAcademicYearIdAndSectionId(academicYearId, sectionId).stream()
                .filter(s -> s.getTeacherId() != null)
                .forEach(s -> result.add(new SectionTeacher(s.getTeacherId(), s.getSubjectId())));
        return result;
    }
}
