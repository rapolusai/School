package com.akshara.library;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.notifications.MessageSettings;
import com.akshara.notifications.MessageTemplates;
import com.akshara.notifications.NotificationQueue;
import com.akshara.notifications.NotificationQueue.Outcome;
import com.akshara.notifications.NotificationQueue.Recipient;
import com.akshara.notifications.NotificationQueue.Related;
import com.akshara.notifications.NotificationSettingsService;
import com.akshara.platform.TenantDirectory;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster;
import com.akshara.students.StudentRoster.GuardianContact;
import com.akshara.students.StudentRoster.RosterStudent;

/**
 * Library messages to parents, only when the school switches them on (library settings; off by default), through the
 * notifications outbox on the school's alert channel and language (simulated: nothing really leaves the system): a
 * reminder when a student's book is overdue (on the first day and then once a week while it stays out), and a note
 * when a book the student reserved is ready to collect. Dedupe keys make each message go once.
 */
@Component
class LibraryReminders {

    private static final String PHONE = "^\\+?[0-9]{8,15}$";
    /** Days between two reminders about the same overdue book. */
    static final int REMIND_EVERY_DAYS = 7;

    private final LibrarySettingsRepository settings;
    private final LoanRepository loans;
    private final TitleRepository titles;
    private final NotificationQueue queue;
    private final NotificationSettingsService messageSettings;
    private final StudentRoster roster;
    private final AcademicsDirectory academics;
    private final TenantDirectory tenants;

    LibraryReminders(LibrarySettingsRepository settings, LoanRepository loans, TitleRepository titles,
            NotificationQueue queue, NotificationSettingsService messageSettings, StudentRoster roster,
            AcademicsDirectory academics, TenantDirectory tenants) {
        this.settings = settings;
        this.loans = loans;
        this.titles = titles;
        this.queue = queue;
        this.messageSettings = messageSettings;
        this.roster = roster;
        this.academics = academics;
        this.tenants = tenants;
    }

    static String overdueKey(UUID loanId, long daysOverdue) {
        return "library-overdue:" + loanId + ":" + ((daysOverdue - 1) / REMIND_EVERY_DAYS);
    }

    static String holdKey(UUID reservationId) {
        return "library-hold:" + reservationId;
    }

    boolean enabled() {
        return settings.findFirstBy().map(LibrarySettings::isRemindersEnabled).orElse(false);
    }

    /**
     * Reminders for students' books past their due date in the current school, at most one per book per week.
     * Returns how many messages were queued.
     */
    int overdue(LocalDate today, Instant at) {
        TenantContext.require();
        if (!enabled()) {
            return 0;
        }
        int queued = 0;
        for (Loan loan : loans.overdueOfStudents(today)) {
            long days = ChronoUnit.DAYS.between(loan.getDueOn(), today);
            Title title = titles.findById(loan.getTitleId()).orElse(null);
            if (title == null || days < 1) {
                continue;
            }
            queued += send(MessageTemplates.LIBRARY_OVERDUE, loan.getStudentId(), title, loan.getDueOn(),
                    overdueKey(loan.getId(), days), new Related("library_loan", loan.getId(), title.getTitle()), at);
        }
        return queued;
    }

    /** Tells a student's parent that the reserved book is kept for them until {@code until}. */
    void holdReady(Reservation hold, Title title, LocalDate until) {
        if (hold.getMemberType() != MemberType.STUDENT || !enabled()) {
            return;
        }
        send(MessageTemplates.LIBRARY_HOLD_READY, hold.member().id(), title, until, holdKey(hold.getId()),
                new Related("library_hold", hold.getId(), title.getTitle()), Instant.now());
    }

    private int send(String template, UUID studentId, Title title, LocalDate date, String key, Related related,
            Instant at) {
        UUID yearId = academics.currentYear().map(YearInfo::id).orElse(null);
        Optional<RosterStudent> student = roster.student(studentId, yearId);
        GuardianContact contact = roster.primaryGuardians(List.of(studentId)).get(studentId);
        if (student.isEmpty() || !student.get().active() || contact == null || contact.phone() == null
                || !contact.phone().matches(PHONE)) {
            return 0;
        }
        MessageSettings school = messageSettings.current();
        Map<String, String> params = new LinkedHashMap<>();
        params.put("student", student.get().fullName());
        params.put("title", title.getTitle());
        params.put("school", tenants.profile(TenantContext.require()).map(p -> p.name()).orElse(""));
        params.put("date", date.toString());
        Recipient recipient = Recipient.phone(school.absenceAlertChannel(), contact.phone(), contact.name(),
                Map.of("guardian", contact.name()), key);
        return (int) queue.enqueue(template, school.alertLanguage(), params, related, List.of(recipient), at).stream()
                .filter(q -> q.outcome() != Outcome.DUPLICATE)
                .count();
    }
}
