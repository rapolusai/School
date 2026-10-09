package com.akshara.onboarding;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.communication.CalendarService;
import com.akshara.communication.CircularService;
import com.akshara.communication.CommunicationTypes.CalendarAudience;
import com.akshara.communication.CommunicationTypes.Category;
import com.akshara.communication.CommunicationTypes.EntryKind;
import com.akshara.communication.CommunicationTypes.NoticeChannel;
import com.akshara.communication.CommunicationTypes.ReminderChannel;
import com.akshara.communication.NoticeBoardService;
import com.akshara.communication.NoticeCaller;
import com.akshara.communication.NoticeForms.AudienceRequest;
import com.akshara.communication.NoticeForms.CircularRequest;
import com.akshara.communication.NoticeForms.EntryRequest;
import com.akshara.shared.TenantContext;

/**
 * The demo school's circulars and calendar. Eight circulars: five sent (one from the Class 5 A class teacher, approved
 * by the principal, and one urgent), one scheduled, one draft and one from the teacher waiting for approval, with
 * read receipts from the demo parent, student and teacher. The 2026-27 calendar has the national holidays, a few
 * festivals the school has confirmed, events, an exam week and a parent-teacher meeting. Added after attendance, so
 * attendance marked earlier on a day that is now a holiday is simply left out of the month's totals.
 */
@Component
class DemoCommunicationData {

    static final int CIRCULARS = 8;

    private final CircularService circulars;
    private final NoticeBoardService board;
    private final CalendarService calendar;
    private final AcademicsDirectory academics;

    DemoCommunicationData(CircularService circulars, NoticeBoardService board, CalendarService calendar,
            AcademicsDirectory academics) {
        this.circulars = circulars;
        this.board = board;
        this.calendar = calendar;
        this.academics = academics;
    }

    /** Returns the number of circulars created. {@code userIds} maps the demo people's emails to their ids. */
    int seed(UUID tenantId, Map<String, UUID> userIds) {
        return TenantContext.runAs(tenantId, () -> {
            UUID teacherId = userIds.get("teacher" + DemoDataSeeder.DEMO_DOMAIN);
            Set<UUID> teacherSections = academics.sections().stream()
                    .filter(s -> teacherId != null && teacherId.equals(s.classTeacherId()))
                    .map(SectionInfo::id).collect(Collectors.toSet());
            NoticeCaller principal = new NoticeCaller(userIds.get("principal" + DemoDataSeeder.DEMO_DOMAIN),
                    "Lakshmi Iyer", true, true, true, true, Set.of());
            NoticeCaller teacher = new NoticeCaller(teacherId, "Ravi Kumar", true, false, false, true,
                    teacherSections);
            seedCalendar(principal);
            if (teacherSections.isEmpty()) {
                return 0;
            }
            return seedCirculars(principal, teacher, teacherSections.iterator().next(), userIds);
        });
    }

    private void seedCalendar(NoticeCaller principal) {
        List<EntryRequest> entries = new ArrayList<>();
        entries.add(holiday("Independence Day", LocalDate.of(2026, 8, 15), null));
        entries.add(holiday("Gandhi Jayanti", LocalDate.of(2026, 10, 2), null));
        entries.add(holiday("Dussehra", LocalDate.of(2026, 10, 20), null));
        entries.add(holiday("Diwali break", LocalDate.of(2026, 11, 7), LocalDate.of(2026, 11, 10)));
        entries.add(holiday("Christmas", LocalDate.of(2026, 12, 25), null));
        entries.add(holiday("Republic Day", LocalDate.of(2027, 1, 26), null));
        entries.add(new EntryRequest(EntryKind.PTM, "Parent-teacher meeting", "Meet your child's class teacher to "
                + "discuss the half-yearly progress. Report cards will be shared.", LocalDate.of(2026, 10, 24), null,
                LocalTime.of(9, 0), LocalTime.of(12, 30), CalendarAudience.SCHOOL, List.of(), 2,
                Set.of(ReminderChannel.SMS)));
        entries.add(new EntryRequest(EntryKind.OTHER, "Staff meeting", "Planning for the half-yearly examinations.",
                LocalDate.of(2026, 10, 17), null, LocalTime.of(15, 30), LocalTime.of(17, 0), CalendarAudience.STAFF,
                List.of(), null, Set.of()));
        entries.add(new EntryRequest(EntryKind.EVENT, "Annual Sports Day", "Track and field events for all classes. "
                + "Parents are welcome.", LocalDate.of(2026, 11, 21), null, LocalTime.of(8, 30), LocalTime.of(13, 0),
                CalendarAudience.SCHOOL, List.of(), 3, Set.of()));
        Set<String> senior = Set.of("Class 6", "Class 7", "Class 8", "Class 9", "Class 10");
        List<UUID> seniorClasses = academics.sections().stream()
                .filter(s -> senior.contains(s.className()))
                .map(SectionInfo::classId).distinct().toList();
        if (!seniorClasses.isEmpty()) {
            entries.add(new EntryRequest(EntryKind.EVENT, "Science exhibition", "Projects by Classes 6 to 10 in "
                    + "the school hall.", LocalDate.of(2026, 12, 12), null, LocalTime.of(10, 0), LocalTime.of(14, 0),
                    CalendarAudience.CLASSES, seniorClasses, null, Set.of()));
        }
        entries.add(new EntryRequest(EntryKind.EXAM, "Half-yearly examinations", "The timetable is on the notice "
                + "board. Exams start at 9:00 each day.", LocalDate.of(2026, 12, 7), LocalDate.of(2026, 12, 12), null,
                null, CalendarAudience.SCHOOL, List.of(), 5, Set.of()));
        calendar.createAll(principal, entries);
    }

    private static EntryRequest holiday(String title, LocalDate startsOn, LocalDate endsOn) {
        return new EntryRequest(EntryKind.HOLIDAY, title, null, startsOn, endsOn, null, null, CalendarAudience.SCHOOL,
                List.of(), null, Set.of());
    }

    private int seedCirculars(NoticeCaller principal, NoticeCaller teacher, UUID teacherSection,
            Map<String, UUID> userIds) {
        Instant now = Instant.now();
        AudienceRequest everyone = AudienceRequest.wholeSchoolAudience();
        AudienceRequest parents = new AudienceRequest(false, List.of(), List.of(), List.of("PARENT"));
        AudienceRequest staff = new AudienceRequest(false, List.of(), List.of(), List.of("TEACHER", "PRINCIPAL",
                "ACCOUNTANT", "FRONT_OFFICE"));
        AudienceRequest classFiveA = new AudienceRequest(false, List.of(), List.of(teacherSection),
                List.of("PARENT", "STUDENT"));

        // Sent, oldest first.
        UUID timetable = send(principal, new CircularRequest("Half-yearly examination timetable",
                "Dear parents and students,\n\nThe half-yearly examinations begin on 7 December. The timetable for "
                        + "each class is attached to the notice board and has been shared with class teachers.\n\n"
                        + "Please make sure your child reaches school by 8:45 on exam days.\n\nPrincipal",
                Category.ACADEMIC, everyone, Set.of(), null), now.minus(Duration.ofDays(6)));
        UUID fees = send(principal, new CircularRequest("Second term fees due by 31 October",
                "Dear parents,\n\nThe second term fees are due by 31 October. You can pay at the school office "
                        + "(9:00 to 13:00 on working days) or by bank transfer. Please quote your child's admission "
                        + "number.\n\nAccounts office",
                Category.FEES, parents, Set.of(NoticeChannel.EMAIL), null), now.minus(Duration.ofDays(4)));
        UUID project = circulars.create(teacher, new CircularRequest("Class 5 A: science project submission",
                "Dear parents,\n\nPlease help your child finish the 'Plants around us' project. Submit it to the "
                        + "class teacher by Friday.\n\nRavi Kumar\nClass teacher, Class 5 A",
                Category.ACADEMIC, classFiveA, Set.of(), null), now.minus(Duration.ofDays(3))).id();
        circulars.submit(teacher, project, now.minus(Duration.ofDays(3)));
        circulars.approve(principal, project, "Looks good.", now.minus(Duration.ofDays(2)));
        UUID staffMeeting = send(principal, new CircularRequest("Staff meeting on Saturday",
                "All teaching and office staff: a short meeting on Saturday at 15:30 in the staff room to plan the "
                        + "half-yearly examinations.",
                Category.GENERAL, staff, Set.of(), null), now.minus(Duration.ofDays(1)));
        send(principal, new CircularRequest("School closed tomorrow: heavy rain warning",
                "Dear parents,\n\nBecause of the heavy rain warning, the school will stay closed tomorrow. Classes "
                        + "resume the day after. Stay safe.\n\nPrincipal",
                Category.URGENT, everyone, Set.of(NoticeChannel.SMS), null), now.minus(Duration.ofHours(2)));

        // Scheduled, draft and waiting for approval.
        UUID sportsDay = circulars.create(principal, new CircularRequest("Annual Sports Day: you are invited",
                "Dear parents,\n\nOur Annual Sports Day is on Saturday, 21 November, from 8:30. Do join us to cheer "
                        + "the children on.\n\nPrincipal",
                Category.EVENT, everyone, Set.of(), now.plus(Duration.ofDays(3))), now).id();
        circulars.submit(principal, sportsDay, now);
        circulars.create(principal, new CircularRequest("Diwali holidays",
                "The school will be closed from 7 to 10 November for Diwali. Classes resume on 11 November.",
                Category.HOLIDAY, everyone, Set.of(), null), now);
        UUID trip = circulars.create(teacher, new CircularRequest("Class 5 A: visit to the science museum",
                "Dear parents,\n\nClass 5 A will visit the science museum next Wednesday. Please send the signed "
                        + "consent slip and a packed lunch.\n\nRavi Kumar",
                Category.EVENT, classFiveA, Set.of(), null), now.minus(Duration.ofHours(5))).id();
        circulars.submit(teacher, trip, now.minus(Duration.ofHours(5)));

        // Read receipts.
        UUID parent = userIds.get("parent" + DemoDataSeeder.DEMO_DOMAIN);
        UUID student = userIds.get("student" + DemoDataSeeder.DEMO_DOMAIN);
        UUID teacherId = teacher.userId();
        read(parent, timetable, now.minus(Duration.ofDays(5)));
        read(parent, fees, now.minus(Duration.ofDays(3)));
        read(student, timetable, now.minus(Duration.ofDays(5)));
        read(student, project, now.minus(Duration.ofDays(1)));
        read(teacherId, staffMeeting, now.minus(Duration.ofHours(20)));
        read(teacherId, timetable, now.minus(Duration.ofDays(6)));
        return CIRCULARS;
    }

    private UUID send(NoticeCaller by, CircularRequest request, Instant at) {
        UUID id = circulars.create(by, request, at).id();
        circulars.submit(by, id, at);
        return id;
    }

    private void read(UUID userId, UUID circularId, Instant at) {
        if (userId != null) {
            board.markRead(userId, circularId, at);
        }
    }
}
