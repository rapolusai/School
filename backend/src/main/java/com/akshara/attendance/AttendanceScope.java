package com.akshara.attendance;

import java.util.Set;
import java.util.UUID;

/**
 * Which sections someone may see and mark. With attendance.manage that is every section; a teacher with
 * attendance.mark (and attendance.read) works only with the sections they are class teacher of.
 */
public record AttendanceScope(boolean wholeSchool, Set<UUID> ownSectionIds, boolean canMark) {

    /** The whole school, read and write: for school-wide jobs such as the demo data. */
    public static final AttendanceScope WHOLE_SCHOOL = new AttendanceScope(true, Set.of(), true);

    public AttendanceScope {
        ownSectionIds = Set.copyOf(ownSectionIds);
    }

    public boolean canRead(UUID sectionId) {
        return wholeSchool || ownSectionIds.contains(sectionId);
    }

    public boolean canMark(UUID sectionId) {
        return canMark && canRead(sectionId);
    }
}
