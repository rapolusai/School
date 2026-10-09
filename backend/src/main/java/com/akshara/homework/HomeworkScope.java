package com.akshara.homework;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import com.akshara.timetable.TimetableDirectory.Teaching;

/**
 * Which homework a member of staff may set, see and review. Whole school for people who also manage the timetable
 * (School Admin, Principal); otherwise the subjects they teach in each section (teacher assignments and timetable)
 * plus every subject of the sections they are class teacher of.
 */
public record HomeworkScope(boolean wholeSchool, Set<Teaching> teaching, Set<UUID> classTeacherOf) {

    public static final HomeworkScope NONE = new HomeworkScope(false, Set.of(), Set.of());
    public static final HomeworkScope WHOLE_SCHOOL = new HomeworkScope(true, Set.of(), Set.of());

    boolean canManage(UUID sectionId, UUID subjectId) {
        return wholeSchool || classTeacherOf.contains(sectionId)
                || teaching.contains(new Teaching(sectionId, subjectId));
    }

    /** Every section of the homework is the person's. Needed to change or delete it. */
    boolean canManageAll(Collection<UUID> sectionIds, UUID subjectId) {
        return !sectionIds.isEmpty() && sectionIds.stream().allMatch(s -> canManage(s, subjectId));
    }

    /** At least one section of the homework is the person's. Enough to see it and review those sections. */
    boolean canSee(Collection<UUID> sectionIds, UUID subjectId) {
        return sectionIds.stream().anyMatch(s -> canManage(s, subjectId));
    }
}
