package com.akshara.attendance;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;

/**
 * How many marks of each kind a register, a student or a school has. Attendance percentages count present and late
 * as a full day and a half day as half; absent and excused leave count as not attended.
 */
public record AttendanceCounts(int present, int absent, int late, int halfDay, int leave) {

    public static final AttendanceCounts NONE = new AttendanceCounts(0, 0, 0, 0, 0);

    public static AttendanceCounts of(Collection<AttendanceStatus> statuses) {
        int p = 0;
        int a = 0;
        int l = 0;
        int h = 0;
        int e = 0;
        for (AttendanceStatus s : statuses) {
            switch (s) {
                case PRESENT -> p++;
                case ABSENT -> a++;
                case LATE -> l++;
                case HALF_DAY -> h++;
                case LEAVE -> e++;
            }
        }
        return new AttendanceCounts(p, a, l, h, e);
    }

    public AttendanceCounts plus(AttendanceCounts other) {
        return new AttendanceCounts(present + other.present, absent + other.absent, late + other.late,
                halfDay + other.halfDay, leave + other.leave);
    }

    public AttendanceCounts plus(AttendanceStatus status, int count) {
        return switch (status) {
            case PRESENT -> new AttendanceCounts(present + count, absent, late, halfDay, leave);
            case ABSENT -> new AttendanceCounts(present, absent + count, late, halfDay, leave);
            case LATE -> new AttendanceCounts(present, absent, late + count, halfDay, leave);
            case HALF_DAY -> new AttendanceCounts(present, absent, late, halfDay + count, leave);
            case LEAVE -> new AttendanceCounts(present, absent, late, halfDay, leave + count);
        };
    }

    /** Marks of any kind. */
    public int total() {
        return present + absent + late + halfDay + leave;
    }

    /** Attended days in halves, so a half day is exact: present and late are 2, a half day 1. */
    int attendedHalves() {
        return 2 * (present + late) + halfDay;
    }

    /** Attended as a percentage of the marks, one decimal, rounded half up; null when nothing is marked. */
    public Double presentPercent() {
        return percent(attendedHalves(), 2 * total());
    }

    static Double percent(int numerator, int denominator) {
        if (denominator <= 0) {
            return null;
        }
        return BigDecimal.valueOf(numerator).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
