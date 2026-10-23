package com.akshara.assist;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.akshara.assist.AssistSettings.Weights;
import com.akshara.assist.AssistViews.Reason;

/**
 * The rule-based early warning score: each signal contributes a share of its weight (0 to 100 in all) and every
 * contribution is listed as a reason in plain words. No model: the same signals always give the same score.
 */
final class RiskScorer {

    static final String ATTENDANCE_30 = "ATTENDANCE_30";
    static final String ATTENDANCE_90 = "ATTENDANCE_90";
    static final String MARKS_DROP = "MARKS_DROP";
    static final String MARKS_LOW = "MARKS_LOW";
    static final String FEE_DUES = "FEE_DUES";
    static final String HOMEWORK = "HOMEWORK";

    /**
     * A student's signals; null means the signal is not available for them. Percentages are 0 to 100; {@code
     * duesPaise} is what is overdue and {@code duesAgeDays} how long the oldest overdue instalment has waited.
     */
    record Signals(Double attendance30, Double attendance90, Double latestPercent, Double previousPercent,
            String latestExam, long duesPaise, int duesAgeDays, Double homeworkPercent) {
    }

    record Score(int score, RiskLevel level, List<Reason> reasons) {
    }

    private RiskScorer() {
    }

    /** The score and reasons; {@code level} is null when the score is below the school's threshold. */
    static Score score(Signals s, Weights w) {
        List<Reason> reasons = new ArrayList<>();
        double total = 0;
        if (s.attendance30() != null) {
            double severity = attendanceSeverity(s.attendance30());
            total += add(reasons, ATTENDANCE_30, w.attendance30(), severity, s.attendance30(),
                    "Attendance " + pct(s.attendance30()) + "% in the last 30 days");
        }
        if (s.attendance90() != null) {
            double severity = attendanceSeverity(s.attendance90());
            total += add(reasons, ATTENDANCE_90, w.attendance90(), severity, s.attendance90(),
                    "Attendance " + pct(s.attendance90()) + "% in the last 90 days");
        }
        if (s.latestPercent() != null) {
            String exam = s.latestExam() == null ? "the last exam" : s.latestExam();
            if (s.previousPercent() != null && s.previousPercent() - s.latestPercent() >= 8) {
                double drop = s.previousPercent() - s.latestPercent();
                double severity = drop >= 20 ? 1.0 : drop >= 15 ? 0.8 : 0.5;
                total += add(reasons, MARKS_DROP, w.marksTrend(), severity, drop, "Marks fell by "
                        + pct(drop) + " points between the last two exams (" + pct(s.previousPercent()) + "% to "
                        + pct(s.latestPercent()) + "%)");
            } else if (s.latestPercent() < 40) {
                double severity = s.latestPercent() < 33 ? 1.0 : 0.6;
                total += add(reasons, MARKS_LOW, w.marksTrend(), severity, s.latestPercent(),
                        pct(s.latestPercent()) + "% in " + exam);
            }
        }
        if (s.duesPaise() > 0 && s.duesAgeDays() > 15) {
            double severity = s.duesAgeDays() > 90 ? 1.0 : s.duesAgeDays() > 45 ? 0.6 : 0.3;
            total += add(reasons, FEE_DUES, w.feeDues(), severity, (double) s.duesAgeDays(), "Fees of "
                    + rupees(s.duesPaise()) + " overdue for " + s.duesAgeDays() + " days");
        }
        if (s.homeworkPercent() != null) {
            double severity = s.homeworkPercent() < 40 ? 1.0 : s.homeworkPercent() < 60 ? 0.6
                    : s.homeworkPercent() < 80 ? 0.3 : 0;
            total += add(reasons, HOMEWORK, w.homework(), severity, s.homeworkPercent(),
                    "Handed in " + pct(s.homeworkPercent()) + "% of online homework in the last 30 days");
        }
        int score = (int) Math.min(100, Math.round(total));
        RiskLevel level = score >= 70 ? RiskLevel.HIGH : score >= 50 ? RiskLevel.MEDIUM
                : score >= w.threshold() ? RiskLevel.LOW : null;
        return new Score(score, level, List.copyOf(reasons));
    }

    private static double attendanceSeverity(double percent) {
        return percent < 60 ? 1.0 : percent < 75 ? 0.6 : percent < 85 ? 0.3 : 0;
    }

    private static double add(List<Reason> reasons, String signal, int weight, double severity, double value,
            String detail) {
        if (severity <= 0 || weight <= 0) {
            return 0;
        }
        int points = (int) Math.round(weight * severity);
        if (points > 0) {
            reasons.add(new Reason(signal, points, value, detail));
        }
        return points;
    }

    static String pct(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.format(Locale.ROOT, "%.1f", value);
    }

    private static String rupees(long paise) {
        return "₹" + String.format(Locale.ROOT, "%,d", paise / 100);
    }
}
