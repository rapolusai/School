package com.akshara.attendance;

import static com.akshara.attendance.AttendanceStatus.ABSENT;
import static com.akshara.attendance.AttendanceStatus.HALF_DAY;
import static com.akshara.attendance.AttendanceStatus.LATE;
import static com.akshara.attendance.AttendanceStatus.LEAVE;
import static com.akshara.attendance.AttendanceStatus.PRESENT;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class AttendanceCountsTest {

    @Test
    void presentAndLateCountFullHalfDayCountsHalf() {
        AttendanceCounts c = AttendanceCounts.of(List.of(PRESENT, ABSENT, LATE, HALF_DAY));
        assertThat(c).isEqualTo(new AttendanceCounts(1, 1, 1, 1, 0));
        assertThat(c.total()).isEqualTo(4);
        assertThat(c.presentPercent()).isEqualTo(62.5);
    }

    @Test
    void leaveIsNotAttendance() {
        assertThat(AttendanceCounts.of(List.of(PRESENT, PRESENT, PRESENT, LEAVE)).presentPercent()).isEqualTo(75.0);
        assertThat(AttendanceCounts.of(List.of(LEAVE)).presentPercent()).isEqualTo(0.0);
    }

    @Test
    void percentagesRoundHalfUpToOneDecimal() {
        assertThat(AttendanceCounts.of(List.of(PRESENT, PRESENT, ABSENT)).presentPercent()).isEqualTo(66.7);
        assertThat(AttendanceCounts.of(List.of(PRESENT, ABSENT, ABSENT)).presentPercent()).isEqualTo(33.3);
        // 1 half of 16 halves is 6.25%.
        AttendanceCounts eight = AttendanceCounts.of(List.of(HALF_DAY, ABSENT, ABSENT, ABSENT, ABSENT, ABSENT,
                ABSENT, ABSENT));
        assertThat(eight.presentPercent()).isEqualTo(6.3);
        assertThat(AttendanceCounts.percent(2, 3)).isEqualTo(66.7);
    }

    @Test
    void nothingMarkedHasNoPercentage() {
        assertThat(AttendanceCounts.NONE.presentPercent()).isNull();
        assertThat(AttendanceCounts.of(List.of()).total()).isZero();
    }

    @Test
    void countsAddUp() {
        AttendanceCounts sum = AttendanceCounts.NONE.plus(PRESENT, 3).plus(new AttendanceCounts(0, 2, 1, 0, 1))
                .plus(HALF_DAY, 1);
        assertThat(sum).isEqualTo(new AttendanceCounts(3, 2, 1, 1, 1));
        assertThat(sum.presentPercent()).isEqualTo(56.3);
    }

    @Test
    void csvCellsAreQuotedAndNeverFormulas() {
        assertThat(AttendanceReportService.cell("Asha Rao")).isEqualTo("Asha Rao");
        assertThat(AttendanceReportService.cell("Rao, Asha")).isEqualTo("\"Rao, Asha\"");
        assertThat(AttendanceReportService.cell("Say \"hi\"")).isEqualTo("\"Say \"\"hi\"\"\"");
        assertThat(AttendanceReportService.cell("=SUM(A1)")).isEqualTo("'=SUM(A1)");
        assertThat(AttendanceReportService.cell("-1")).isEqualTo("'-1");
        assertThat(AttendanceReportService.cell(null)).isEmpty();
    }
}
