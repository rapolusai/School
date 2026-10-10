package com.akshara.onboarding;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.attendance.AttendanceService;
import com.akshara.attendance.AttendanceScope;
import com.akshara.attendance.ChildLeaveService;
import com.akshara.attendance.ChildLeaveService.Application;
import com.akshara.attendance.ChildLeaveService.ChildLeaveView;
import com.akshara.audit.AuditService.Actor;
import com.akshara.communication.SchoolCalendar;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentService;
import com.akshara.students.StudentService.ChildView;

/**
 * The demo parent's leave notes for the parent and student app: Diya's two days of leave for a family wedding,
 * approved by the principal, and a half day for Arjun's dentist appointment waiting for his class teacher, Ravi Kumar.
 * Both are on coming school days, so today's register and alerts are untouched. Added after everything else, once the
 * calendar's holidays exist.
 */
@Component
class DemoPortalData {

    /** How many coming school days to look at for the demo dates. */
    static final int LOOK_AHEAD = 60;

    private final ChildLeaveService childLeave;
    private final StudentService students;
    private final AcademicsDirectory academics;
    private final SchoolCalendar calendar;
    private final TransactionTemplate tx;

    DemoPortalData(ChildLeaveService childLeave, StudentService students, AcademicsDirectory academics,
            SchoolCalendar calendar, PlatformTransactionManager transactionManager) {
        this.childLeave = childLeave;
        this.students = students;
        this.academics = academics;
        this.calendar = calendar;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Returns the number of leave requests created. {@code userIds} maps the demo people's emails to their ids. */
    int seed(UUID tenantId, Map<String, UUID> userIds) {
        UUID parentId = userIds.get("parent" + DemoDataSeeder.DEMO_DOMAIN);
        UUID principalId = userIds.get("principal" + DemoDataSeeder.DEMO_DOMAIN);
        if (parentId == null || principalId == null) {
            return 0;
        }
        Actor parent = new Actor(parentId, "Anitha Sharma");
        Actor principal = new Actor(principalId, "Lakshmi Iyer");
        LocalDate today = AttendanceService.today();
        Integer created = TenantContext.runAs(tenantId, () -> tx.execute(status -> {
            Optional<YearInfo> year = academics.currentYear();
            List<ChildView> children = students.childrenOf(parentId);
            Optional<ChildView> arjun = child(children, "Arjun Sharma");
            Optional<ChildView> diya = child(children, "Diya Sharma");
            if (year.isEmpty() || arjun.isEmpty() || diya.isEmpty()) {
                return 0;
            }
            // Coming school days in this year: Mondays to Saturdays that are not holidays.
            List<LocalDate> days = new ArrayList<>();
            for (LocalDate d = today.plusDays(1); days.size() < LOOK_AHEAD && !d.isAfter(year.get().endsOn());
                    d = d.plusDays(1)) {
                if (d.getDayOfWeek() != DayOfWeek.SUNDAY && !calendar.isHoliday(d)) {
                    days.add(d);
                }
            }
            if (days.size() < 6) {
                return 0;
            }
            Instant now = Instant.now();
            // Two consecutive school days (a Saturday and a Monday count) for Diya, approved.
            LocalDate from = days.get(4);
            LocalDate to = days.get(5);
            ChildLeaveView wedding = childLeave.apply(parentId, diya.get().id(), new Application(from, to, false,
                    "Family wedding in Vijayawada. We will be back for school the next day."), parent, today,
                    now.minusSeconds(2 * 24 * 3600));
            childLeave.approve(AttendanceScope.WHOLE_SCHOOL, wedding.id(), "Approved. Enjoy the wedding.", principal,
                    today, now.minusSeconds(24 * 3600));
            // A half day for Arjun, waiting for Ravi Kumar.
            LocalDate dentist = days.get(1);
            childLeave.apply(parentId, arjun.get().id(), new Application(dentist, dentist, true,
                    "Dentist appointment at 1 pm. I will pick Arjun up after lunch."), parent, today, now);
            return 2;
        }));
        return created == null ? 0 : created;
    }

    private static Optional<ChildView> child(List<ChildView> children, String name) {
        return children.stream().filter(c -> c.fullName().equals(name)).findFirst();
    }
}
