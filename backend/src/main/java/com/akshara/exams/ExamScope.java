package com.akshara.exams;

import java.util.Set;
import java.util.UUID;

import com.akshara.timetable.TimetableDirectory.Teaching;

/**
 * What a member of staff may do with marks. With exams.publish (School Admin, Principal) every section and subject;
 * with exams.manage the subjects they teach in each section (teacher assignments and timetable) and every subject of
 * the sections they are class teacher of. The class teacher also enters co-scholastic grades, remarks and the
 * holistic progress card.
 */
public record ExamScope(boolean wholeSchool, boolean canEnter, Set<Teaching> teaching, Set<UUID> classTeacherOf) {

    public static final ExamScope NONE = new ExamScope(false, false, Set.of(), Set.of());
    public static final ExamScope WHOLE_SCHOOL = new ExamScope(true, true, Set.of(), Set.of());

    public ExamScope {
        teaching = Set.copyOf(teaching);
        classTeacherOf = Set.copyOf(classTeacherOf);
    }

    boolean canEnterMarks(UUID sectionId, UUID subjectId) {
        return canEnter && (wholeSchool || classTeacherOf.contains(sectionId)
                || teaching.contains(new Teaching(sectionId, subjectId)));
    }

    boolean canEditRecords(UUID sectionId) {
        return canEnter && (wholeSchool || classTeacherOf.contains(sectionId));
    }
}
