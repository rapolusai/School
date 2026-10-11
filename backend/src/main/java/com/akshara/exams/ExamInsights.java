package com.akshara.exams;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.exams.ExamsViews.SheetRow;
import com.akshara.shared.Permissions;
import com.akshara.shared.TenantContext;

/**
 * Read-only exam figures for the staff dashboard, in the current year: mark sheets of papers already written that
 * the signed-in person may fill in and has not locked yet (for School Admin and Principal, every such sheet), and
 * exam classes whose marks are all locked but whose results are not published yet (exams.publish only).
 */
@Service
@Transactional(readOnly = true)
public class ExamInsights {

    /** {@code resultsToPublish} is 0 without exams.publish. */
    public record Due(int marksToEnter, int resultsToPublish) {
    }

    private final AcademicsDirectory academics;
    private final ExamsAccess access;
    private final MarksService marks;

    ExamInsights(AcademicsDirectory academics, ExamsAccess access, MarksService marks) {
        this.academics = academics;
        this.access = access;
        this.marks = marks;
    }

    public Due due() {
        TenantContext.require();
        Optional<YearInfo> year = academics.currentYear();
        if (year.isEmpty()) {
            return new Due(0, 0);
        }
        ExamScope scope = access.scope(year.get().id());
        LocalDate today = ExamSupport.today();
        List<SheetRow> rows = marks.rows(year.get(), scope);
        int toEnter = (int) rows.stream()
                .filter(r -> r.canEdit() && !r.resultsPublished() && r.paperDate() != null
                        && !r.paperDate().isAfter(today))
                .count();
        int toPublish = 0;
        if (ExamsAccess.can(Permissions.EXAMS_PUBLISH)) {
            Map<UUID, List<SheetRow>> byExamClass = rows.stream()
                    .collect(Collectors.groupingBy(SheetRow::examClassId));
            toPublish = (int) byExamClass.values().stream()
                    .filter(list -> !list.getFirst().resultsPublished()
                            && list.stream().allMatch(r -> SheetStatus.LOCKED.name().equals(r.status())))
                    .count();
        }
        return new Due(toEnter, toPublish);
    }
}
