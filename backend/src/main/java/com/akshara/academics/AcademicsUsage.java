package com.akshara.academics;

import java.util.Map;
import java.util.UUID;

/**
 * Lets modules that build on school setup (students today; timetables and exams later) say whether a year or a
 * section is still in use, so setup never deletes something that other records point to. Implemented by those
 * modules; this module depends only on the interface, which keeps the dependency one-way.
 */
public interface AcademicsUsage {

    /** Records of any year that refer to the section. */
    long sectionUseCount(UUID sectionId);

    /** Records that refer to the academic year. */
    long yearUseCount(UUID yearId);

    /** Students enrolled per section in the given year, for headcounts on the setup page. */
    Map<UUID, Long> enrolledPerSection(UUID yearId);
}
