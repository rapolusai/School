package com.akshara.lessons;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Syllabus coverage of one section and subject, kept pure so it is easy to test. A topic is covered once a lesson
 * plan for it in that section is marked taught. A topic is due once its target month has ended (target before the
 * current month); a due topic that is not covered is overdue, and a section with any overdue topic is behind
 * schedule. Percentages have one decimal, rounded half up; with no topics coverage is 0.
 */
final class CoverageMath {

    /** A syllabus topic with the month it should be taught by (null when not set). */
    record Topic(UUID id, LocalDate targetMonth) {
    }

    record Coverage(int topics, int taught, BigDecimal percent, int due, int overdue, BigDecimal expectedPercent,
            boolean behind) {
    }

    private CoverageMath() {
    }

    static Coverage of(Collection<Topic> topics, Set<UUID> taught, YearMonth current) {
        int total = topics.size();
        int covered = 0;
        int due = 0;
        int overdue = 0;
        for (Topic t : topics) {
            boolean isTaught = taught.contains(t.id());
            if (isTaught) {
                covered++;
            }
            if (isDue(t.targetMonth(), current)) {
                due++;
                if (!isTaught) {
                    overdue++;
                }
            }
        }
        return new Coverage(total, covered, percent(covered, total), due, overdue, percent(due, total), overdue > 0);
    }

    /** True when the topic's target month has ended by the current month. */
    static boolean isDue(LocalDate targetMonth, YearMonth current) {
        return targetMonth != null && YearMonth.from(targetMonth).isBefore(current);
    }

    static BigDecimal percent(int part, int whole) {
        if (whole <= 0) {
            return BigDecimal.ZERO.setScale(1);
        }
        return BigDecimal.valueOf(part * 100L).divide(BigDecimal.valueOf(whole), 1, RoundingMode.HALF_UP);
    }
}
