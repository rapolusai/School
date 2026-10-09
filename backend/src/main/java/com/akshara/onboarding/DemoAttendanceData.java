package com.akshara.onboarding;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.attendance.AttendanceScope;
import com.akshara.attendance.AttendanceService;
import com.akshara.attendance.AttendanceService.EntryInput;
import com.akshara.attendance.AttendanceStatus;
import com.akshara.audit.AuditService.Actor;
import com.akshara.notifications.MessageDispatcher;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster;
import com.akshara.students.StudentRoster.RosterStudent;

/**
 * The demo school's attendance: the last {@value #SCHOOL_DAYS} school days (Monday to Saturday) of every section,
 * about 93% present with a few late and on leave. Class 5 A is marked by its class teacher and left unmarked today so
 * the teacher has something to do; the other sections are marked by the principal. Absence alerts go through the
 * outbox and the simulated sender like real ones, so the message log has entries. Marks are random but repeatable.
 */
@Component
class DemoAttendanceData {

    static final int SCHOOL_DAYS = 20;
    static final LocalTime MARKED_AT = LocalTime.of(9, 10);

    private final AttendanceService attendance;
    private final AcademicsDirectory academics;
    private final StudentRoster roster;
    private final MessageDispatcher dispatcher;
    private final TransactionTemplate tx;

    DemoAttendanceData(AttendanceService attendance, AcademicsDirectory academics, StudentRoster roster,
            MessageDispatcher dispatcher, PlatformTransactionManager transactionManager) {
        this.attendance = attendance;
        this.academics = academics;
        this.roster = roster;
        this.dispatcher = dispatcher;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Returns the number of registers saved. {@code userIds} maps the demo people's emails to their ids. */
    int seed(UUID tenantId, Map<String, UUID> userIds) {
        Actor teacher = actor(userIds, "teacher");
        Actor principal = actor(userIds, "principal");
        LocalDate today = AttendanceService.today();
        Instant now = Instant.now();
        YearInfo year = TenantContext.runAs(tenantId, () -> academics.currentYear().orElse(null));
        if (year == null) {
            return 0;
        }
        List<LocalDate> days = schoolDays(today.isAfter(year.endsOn()) ? year.endsOn() : today, year.startsOn());
        Random random = new Random(20_261_009L);
        int saved = 0;
        for (int d = 0; d < days.size(); d++) {
            LocalDate day = days.get(d);
            boolean secondLastDay = d == days.size() - 2;
            Instant at = day.atTime(MARKED_AT).atZone(AttendanceService.INDIA).toInstant();
            Instant markedAt = at.isAfter(now) ? now : at;
            Integer count = TenantContext.runAs(tenantId, () -> tx.execute(status -> {
                int registers = 0;
                for (SectionInfo section : academics.sections()) {
                    boolean classTeacherSection = DemoSchoolData.CLASS_TEACHER_SECTION
                            .equals(section.className() + "|" + section.name());
                    if (classTeacherSection && day.equals(today)) {
                        continue;
                    }
                    List<RosterStudent> students = roster.activeInSection(year.id(), section.id());
                    if (students.isEmpty()) {
                        continue;
                    }
                    List<EntryInput> marks = new ArrayList<>();
                    for (RosterStudent s : students) {
                        marks.add(new EntryInput(s.id(), mark(random, s, secondLastDay)));
                    }
                    attendance.save(AttendanceScope.WHOLE_SCHOOL, section.id(), day, marks,
                            classTeacherSection ? teacher : principal, markedAt);
                    registers++;
                }
                return registers;
            }));
            saved += count == null ? 0 : count;
            // Send that day's absence alerts a minute after marking, as the dispatcher would have.
            dispatcher.dispatchSchool(tenantId, markedAt.plusSeconds(60));
        }
        return saved;
    }

    /** The last school days up to {@code last} (today), oldest first, none before the year starts. */
    static List<LocalDate> schoolDays(LocalDate last, LocalDate yearStart) {
        List<LocalDate> days = new ArrayList<>();
        for (LocalDate d = last; days.size() < SCHOOL_DAYS && !d.isBefore(yearStart); d = d.minusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                days.addFirst(d);
            }
        }
        return days;
    }

    /** About 93% present (late counts as present): 5% absent, 1.5% on leave, 2% late, 0.5% half day. */
    private static AttendanceStatus mark(Random random, RosterStudent student, boolean secondLastDay) {
        double r = random.nextDouble();
        if (secondLastDay && "Arjun Sharma".equals(student.fullName())) {
            // The demo parent then has a recent absence and an alert to look at.
            return AttendanceStatus.ABSENT;
        }
        if (secondLastDay && "Diya Sharma".equals(student.fullName())) {
            return AttendanceStatus.LATE;
        }
        if (r < 0.05) {
            return AttendanceStatus.ABSENT;
        }
        if (r < 0.065) {
            return AttendanceStatus.LEAVE;
        }
        if (r < 0.085) {
            return AttendanceStatus.LATE;
        }
        if (r < 0.09) {
            return AttendanceStatus.HALF_DAY;
        }
        return AttendanceStatus.PRESENT;
    }

    private static Actor actor(Map<String, UUID> userIds, String mailbox) {
        String email = mailbox + DemoDataSeeder.DEMO_DOMAIN;
        String name = DemoDataSeeder.PEOPLE.stream().filter(p -> p.email().equals(email))
                .map(DemoDataSeeder.DemoPerson::name).findFirst().orElse("Demo data");
        return new Actor(userIds.get(email), name);
    }
}
