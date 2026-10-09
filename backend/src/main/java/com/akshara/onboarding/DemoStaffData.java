package com.akshara.onboarding;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.TenantContext;
import com.akshara.staff.DepartmentService;
import com.akshara.staff.EmploymentType;
import com.akshara.staff.LeaveService;
import com.akshara.staff.LeaveService.Caller;
import com.akshara.staff.LeaveService.LeaveRequestView;
import com.akshara.staff.LeaveService.LeaveTypeView;
import com.akshara.staff.StaffAttendanceService;
import com.akshara.staff.StaffAttendanceService.DayRow;
import com.akshara.staff.StaffAttendanceStatus;
import com.akshara.staff.StaffForms.BalanceFields;
import com.akshara.staff.StaffForms.CreateStaff;
import com.akshara.staff.StaffForms.DayEntry;
import com.akshara.staff.StaffForms.DepartmentFields;
import com.akshara.staff.StaffForms.LeaveApplication;
import com.akshara.staff.StaffForms.ProfileFields;
import com.akshara.staff.StaffService;

/**
 * The demo school's staff records: departments (three with heads who approve their staff's leave), profiles for every
 * demo staff member plus {@value #EXTRA_TEACHERS} more teachers, the standard leave types, a few approved, pending,
 * rejected and cancelled leave requests (one pending from Ravi Kumar for the principal), and staff attendance for the
 * last {@value DemoAttendanceData#SCHOOL_DAYS} working days. Everyone but Ravi Kumar has checked in today, so the
 * demo teacher has the check-in card to try. Dates follow today; marks are random but repeatable.
 */
@Component
class DemoStaffData {

    static final int EXTRA_TEACHERS = 6;

    record Teacher(String name, String mailbox, String code, String designation, String department,
            EmploymentType type, LocalDate joined, String mobile, String qualifications) {
    }

    static final List<Teacher> TEACHERS = List.of(
            new Teacher("Anjali Deshmukh", "anjali.deshmukh", "AKS-102", "Post Graduate Teacher (PGT)", "Science",
                    EmploymentType.PERMANENT, LocalDate.of(2014, 6, 2), "9848010102", "M.Sc. Chemistry, B.Ed."),
            new Teacher("Rahul Verma", "rahul.verma", "AKS-103", "Trained Graduate Teacher (TGT)", "Science",
                    EmploymentType.PROBATION, LocalDate.of(2026, 6, 1), "9848010103", "B.Sc. Physics, B.Ed."),
            new Teacher("Fatima Shaikh", "fatima.shaikh", "AKS-104", "Post Graduate Teacher (PGT)", "Languages",
                    EmploymentType.PERMANENT, LocalDate.of(2016, 6, 1), "9848010104", "M.A. English, B.Ed."),
            new Teacher("Kiran Joshi", "kiran.joshi", "AKS-105", "Hindi Teacher", "Languages",
                    EmploymentType.PART_TIME, LocalDate.of(2022, 7, 1), "9848010105", "M.A. Hindi"),
            new Teacher("Sandeep Kulkarni", "sandeep.kulkarni", "AKS-106", "Trained Graduate Teacher (TGT)",
                    "Secondary", EmploymentType.PERMANENT, LocalDate.of(2017, 6, 1), "9848010106",
                    "M.Sc. Mathematics, B.Ed."),
            new Teacher("Priyanka Menon", "priyanka.menon", "AKS-107", "Primary Teacher (PRT)", "Primary",
                    EmploymentType.CONTRACT, LocalDate.of(2024, 6, 3), "9848010107", "B.A., D.El.Ed."));

    /** Department heads by department; Primary and Administration have none, so the principal approves. */
    static final Map<String, String> HEADS = Map.of("Science", "anjali.deshmukh", "Languages", "fatima.shaikh",
            "Secondary", "sandeep.kulkarni");

    static final List<String> DEPARTMENTS = List.of("Primary", "Secondary", "Science", "Languages", "Administration");

    private final StaffService staff;
    private final DepartmentService departments;
    private final LeaveService leave;
    private final StaffAttendanceService attendance;
    private final AcademicsDirectory academics;
    private final TransactionTemplate tx;

    DemoStaffData(StaffService staff, DepartmentService departments, LeaveService leave,
            StaffAttendanceService attendance, AcademicsDirectory academics,
            PlatformTransactionManager transactionManager) {
        this.staff = staff;
        this.departments = departments;
        this.leave = leave;
        this.attendance = attendance;
        this.academics = academics;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * Returns the number of staff profiles created. {@code userIds} maps the demo people's emails to their ids, the
     * admin's included; {@code passwordHash} is the demo password, already hashed, for the extra teachers.
     */
    int seed(UUID tenantId, Map<String, UUID> userIds, UUID adminId, String passwordHash) {
        Map<String, UUID> ids = new HashMap<>(userIds);
        ids.put(email("admin"), adminId);
        Actor admin = new Actor(adminId, "Priya Nair");
        Integer profiles = TenantContext.runAs(tenantId, () -> tx.execute(status -> profiles(ids, admin,
                passwordHash)));
        TenantContext.runAs(tenantId, () -> tx.execute(status -> {
            leaveRequests(ids, admin);
            return null;
        }));
        TenantContext.runAs(tenantId, () -> tx.execute(status -> {
            pastAttendance(admin);
            return null;
        }));
        TenantContext.runAs(tenantId, () -> tx.execute(status -> {
            checkInsToday(ids);
            return null;
        }));
        return profiles == null ? 0 : profiles;
    }

    private int profiles(Map<String, UUID> ids, Actor admin, String passwordHash) {
        Map<String, UUID> departmentIds = new HashMap<>();
        for (String name : DEPARTMENTS) {
            departmentIds.put(name, departments.create(new DepartmentFields(name, null), admin).id());
        }
        record Existing(String mailbox, String code, String designation, String department, EmploymentType type,
                LocalDate joined, String mobile, String qualifications, String emergencyName, String emergencyMobile) {
        }
        List<Existing> existing = List.of(
                new Existing("admin", "AKS-001", "School Administrator", "Administration", EmploymentType.PERMANENT,
                        LocalDate.of(2015, 6, 1), "9848010001", "MBA", "Arun Nair", "9848020001"),
                new Existing("principal", "AKS-002", "Principal", "Administration", EmploymentType.PERMANENT,
                        LocalDate.of(2012, 4, 2), "9848010002", "M.Sc. Physics, M.Ed.", "Venkat Iyer", "9848020002"),
                new Existing("teacher", "AKS-101", "Primary Teacher (PRT)", "Primary", EmploymentType.PERMANENT,
                        LocalDate.of(2019, 6, 10), "9848010101", "B.Sc., B.Ed.", "Sunita Kumar", "9848020101"),
                new Existing("accounts", "AKS-201", "Accountant", "Administration", EmploymentType.PERMANENT,
                        LocalDate.of(2018, 7, 2), "9848010201", "B.Com., CA Inter", null, null),
                new Existing("frontoffice", "AKS-202", "Front Office Executive", "Administration",
                        EmploymentType.CONTRACT, LocalDate.of(2023, 1, 2), "9848010202", "B.A.", null, null));
        int created = 0;
        for (Existing e : existing) {
            staff.saveProfile(ids.get(email(e.mailbox())), new ProfileFields(e.code(), e.designation(),
                    departmentIds.get(e.department()), e.type(), e.joined(), e.mobile(), e.qualifications(),
                    e.emergencyName(), e.emergencyMobile()), admin);
            created++;
        }
        for (Teacher t : TEACHERS) {
            UUID id = staff.create(new CreateStaff(t.name(), email(t.mailbox()), null, List.of("TEACHER"), t.code(),
                    t.designation(), departmentIds.get(t.department()), t.type(), t.joined(), t.mobile(),
                    t.qualifications(), null, null), passwordHash, admin).userId();
            ids.put(email(t.mailbox()), id);
            created++;
        }
        HEADS.forEach((department, mailbox) -> departments.update(departmentIds.get(department),
                new DepartmentFields(department, ids.get(email(mailbox))), admin));
        return created;
    }

    private void leaveRequests(Map<String, UUID> ids, Actor admin) {
        Map<String, UUID> types = new HashMap<>();
        for (LeaveTypeView t : leave.addStandardTypes(admin)) {
            types.put(t.code(), t.id());
        }
        YearInfo year = academics.currentYear().orElse(null);
        if (year == null) {
            return;
        }
        // Earned leave brought over from the school's old records.
        leave.setBalance(new BalanceFields(ids.get(email("anjali.deshmukh")), types.get("EL"), year.id(),
                new BigDecimal("30"), new BigDecimal("15")), admin);

        Caller principal = new Caller(actor(ids, "principal", "Lakshmi Iyer"), true);
        Caller priya = new Caller(admin, true);
        Caller anjali = new Caller(actor(ids, "anjali.deshmukh", "Anjali Deshmukh"), false);
        Caller fatima = new Caller(actor(ids, "fatima.shaikh", "Fatima Shaikh"), false);
        LocalDate today = StaffAttendanceService.today();
        Instant now = Instant.now();
        Requests demo = new Requests(year, today, types, now);

        // Approved in the past (their days show as on leave), one of them a half day.
        demo.apply(caller(ids, "accounts", "Meena Reddy"), "EL", -12, -10, false, "Family function in Vijayawada")
                .ifPresent(r -> leave.approve(r.id(), "Approved. Suresh will cover fee collection.", principal, now));
        demo.apply(caller(ids, "kiran.joshi", "Kiran Joshi"), "CL", -6, -6, true, "Doctor's appointment in the morning")
                .ifPresent(r -> leave.approve(r.id(), null, fatima, now));
        demo.apply(caller(ids, "teacher", "Ravi Kumar"), "SL", -15, -15, false, "Fever")
                .ifPresent(r -> leave.approve(r.id(), "Get well soon.", priya, now));
        // Approved for the coming days: the timetable would find substitutes.
        demo.apply(caller(ids, "sandeep.kulkarni", "Sandeep Kulkarni"), "CL", 2, 2, false,
                "Parent-teacher meeting at my son's school")
                .ifPresent(r -> leave.approve(r.id(), null, principal, now));
        // Rejected by the department head.
        demo.apply(caller(ids, "rahul.verma", "Rahul Verma"), "EL", 8, 12, false, "Sister's wedding in Lucknow")
                .ifPresent(r -> leave.reject(r.id(), "Practical exams that week. Please apply for the week after.",
                        anjali, now));
        // Cancelled by the person who asked.
        demo.apply(caller(ids, "priyanka.menon", "Priyanka Menon"), "CL", 10, 10, false, "Personal work")
                .ifPresent(r -> leave.cancel(r.id(), "Plans changed", caller(ids, "priyanka.menon",
                        "Priyanka Menon"), now));
        // Waiting: Rahul's for his department head, Ravi's for the principal.
        demo.apply(caller(ids, "rahul.verma", "Rahul Verma"), "CL", 3, 3, false, "Bank work for my education loan");
        demo.apply(caller(ids, "teacher", "Ravi Kumar"), "CL", 5, 6, false, "Cousin's wedding in Warangal");
    }

    /** Applies for leave on working days counted from today (negative: before), when they fall in the year. */
    private final class Requests {

        private final YearInfo year;
        private final LocalDate today;
        private final Map<String, UUID> types;
        private final Instant now;

        Requests(YearInfo year, LocalDate today, Map<String, UUID> types, Instant now) {
            this.year = year;
            this.today = today;
            this.types = types;
            this.now = now;
        }

        Optional<LeaveRequestView> apply(Caller who, String type, int from, int to, boolean halfDay,
                String reason) {
            LocalDate first = workingDay(today, from);
            LocalDate last = workingDay(today, to);
            if (first.isBefore(year.startsOn()) || last.isAfter(year.endsOn())) {
                return Optional.empty();
            }
            return Optional.of(leave.apply(who, new LeaveApplication(types.get(type), first, last, halfDay,
                    reason), now));
        }
    }

    /** The last working days before today: about 92% present, 3% half days, 5% absent, with times. */
    private void pastAttendance(Actor admin) {
        LocalDate today = StaffAttendanceService.today();
        YearInfo year = academics.currentYear().orElse(null);
        LocalDate start = year == null ? today.minusMonths(1) : year.startsOn();
        List<LocalDate> days = DemoAttendanceData.schoolDays(today.minusDays(1), start);
        Random random = new Random(20_261_010L);
        for (LocalDate day : days) {
            List<DayEntry> entries = new ArrayList<>();
            for (DayRow row : attendance.day(day, true).rows()) {
                if (!row.onRoll() || row.status() != null) {
                    continue;
                }
                double r = random.nextDouble();
                String in = time(8, 10 + random.nextInt(45));
                if (r < 0.05) {
                    entries.add(new DayEntry(row.userId(), StaffAttendanceStatus.ABSENT, null, null));
                } else if (r < 0.08) {
                    entries.add(new DayEntry(row.userId(), StaffAttendanceStatus.HALF_DAY, in,
                            time(12, 15 + random.nextInt(30))));
                } else {
                    entries.add(new DayEntry(row.userId(), StaffAttendanceStatus.PRESENT, in,
                            time(15, 45 + random.nextInt(14))));
                }
            }
            if (!entries.isEmpty()) {
                attendance.saveDay(day, entries, admin);
            }
        }
    }

    /** Everyone on the staff today checks in (between 08:05 and 08:50), except the demo teacher. */
    private void checkInsToday(Map<String, UUID> ids) {
        LocalDate today = StaffAttendanceService.today();
        if (today.getDayOfWeek() == DayOfWeek.SUNDAY) {
            return;
        }
        Instant now = Instant.now();
        Random random = new Random(20_261_011L);
        UUID ravi = ids.get(email("teacher"));
        for (DayRow row : attendance.day(today, true).rows()) {
            Instant at = today.atTime(LocalTime.of(8, 5 + random.nextInt(45)))
                    .atZone(StaffAttendanceService.INDIA).toInstant();
            if (!row.onRoll() || row.userId().equals(ravi) || !attendance.myToday(row.userId()).canCheckIn()) {
                continue;
            }
            attendance.checkIn(new Actor(row.userId(), row.name()), null, at.isAfter(now) ? now : at);
        }
    }

    private static LocalDate workingDay(LocalDate today, int offset) {
        LocalDate d = today;
        int step = offset < 0 ? -1 : 1;
        for (int moved = 0; moved < Math.abs(offset);) {
            d = d.plusDays(step);
            if (d.getDayOfWeek() != DayOfWeek.SUNDAY) {
                moved++;
            }
        }
        return d;
    }

    private static String time(int hour, int minute) {
        return String.format("%02d:%02d", hour + minute / 60, minute % 60);
    }

    private static Caller caller(Map<String, UUID> ids, String mailbox, String name) {
        return new Caller(actor(ids, mailbox, name), false);
    }

    private static Actor actor(Map<String, UUID> ids, String mailbox, String name) {
        return new Actor(ids.get(email(mailbox)), name);
    }

    private static String email(String mailbox) {
        return mailbox + DemoDataSeeder.DEMO_DOMAIN;
    }
}
