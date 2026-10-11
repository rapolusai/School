package com.akshara.onboarding;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.audit.AuditService.Actor;
import com.akshara.chat.ChatSeeder;
import com.akshara.chat.ChatSeeder.SeedMessage;
import com.akshara.chat.MeetingMode;
import com.akshara.chat.PtmForms.BreakForm;
import com.akshara.chat.PtmForms.EventForm;
import com.akshara.chat.Side;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentRoster;
import com.akshara.students.StudentRoster.RosterStudent;

/**
 * The demo school's messages and parent-teacher meeting: Anitha Sharma ({@code parent@demo.akshara.test}) and Ravi
 * Kumar, Arjun's class teacher, wrote to each other about Arjun over the last two days, and her last message is still
 * unread for him; and a PTM for Class 5 (on the school calendar's day, or the next Saturday when that has passed) with
 * reminders on, where Anitha has booked a slot with Ravi Kumar and the office has booked two of Arjun's classmates.
 */
@Component
class DemoChatData {

    static final String ARJUN = "Arjun Sharma";
    static final String PTM_TITLE = "Half-yearly parent-teacher meeting";
    /** The day the demo calendar gives the parent-teacher meeting. */
    static final LocalDate CALENDAR_PTM = LocalDate.of(2026, 10, 24);
    static final LocalTime ARJUN_SLOT = LocalTime.of(9, 30);

    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final ChatSeeder chat;
    private final AcademicsDirectory academics;
    private final StudentRoster roster;
    private final TransactionTemplate tx;

    DemoChatData(ChatSeeder chat, AcademicsDirectory academics, StudentRoster roster,
            PlatformTransactionManager transactionManager) {
        this.chat = chat;
        this.academics = academics;
        this.roster = roster;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Returns how many PTM slots were booked. {@code userIds} maps the demo people's emails to their ids. */
    int seed(UUID tenantId, Map<String, UUID> userIds) {
        UUID parentId = userIds.get("parent" + DemoDataSeeder.DEMO_DOMAIN);
        UUID teacherId = userIds.get("teacher" + DemoDataSeeder.DEMO_DOMAIN);
        UUID principalId = userIds.get("principal" + DemoDataSeeder.DEMO_DOMAIN);
        if (parentId == null || teacherId == null || principalId == null) {
            return 0;
        }
        Actor principal = new Actor(principalId, "Lakshmi Iyer");
        Integer booked = TenantContext.runAs(tenantId, () -> tx.execute(status -> {
            Optional<YearInfo> year = academics.currentYear();
            if (year.isEmpty()) {
                return 0;
            }
            UUID yearId = year.get().id();
            RosterStudent arjun = roster.students(roster.childIdsOf(parentId), yearId).values().stream()
                    .filter(s -> ARJUN.equals(s.fullName())).findFirst().orElse(null);
            if (arjun == null) {
                return 0;
            }
            UUID arjunSection = roster.sectionOf(arjun.id(), yearId).orElse(null);
            if (arjunSection == null) {
                return 0;
            }
            chat.conversation(arjun.id(), parentId, teacherId, conversation(Instant.now()));

            LocalDate today = LocalDate.now(INDIA);
            LocalDate day = CALENDAR_PTM.isAfter(today.plusDays(2)) ? CALENDAR_PTM
                    : today.plusDays(3).with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
            if (day.isAfter(year.get().endsOn())) {
                return 0;
            }
            String className = academics.section(arjunSection).map(SectionInfo::className).orElse("Class 5");
            List<UUID> sections = academics.sections().stream().filter(s -> className.equals(s.className()))
                    .map(SectionInfo::id).toList();
            chat.ptmReminders(true, principal);
            UUID eventId = chat.meeting(new EventForm(PTM_TITLE, day, LocalTime.of(9, 0), LocalTime.of(12, 30), 10,
                    List.of(new BreakForm(LocalTime.of(10, 30), LocalTime.of(10, 45))), MeetingMode.IN_PERSON,
                    "Class 5 classrooms, first floor", null, sections, false, 12,
                    "Report cards will be shared. Please bring the school diary."), principal);
            Map<LocalTime, UUID> slots = chat.slots(eventId, teacherId);
            int count = 0;
            if (slots.containsKey(ARJUN_SLOT)) {
                chat.bookAsParent(parentId, "Anitha Sharma", slots.get(ARJUN_SLOT), arjun.id());
                count++;
            }
            List<RosterStudent> classmates = new ArrayList<>(roster.activeInSection(yearId, arjunSection).stream()
                    .filter(s -> !s.id().equals(arjun.id())).limit(2).toList());
            List<LocalTime> times = List.of(LocalTime.of(9, 0), LocalTime.of(9, 10));
            for (int i = 0; i < classmates.size(); i++) {
                UUID slot = slots.get(times.get(i));
                if (slot != null) {
                    chat.bookForFamily(eventId, slot, classmates.get(i).id(), principal);
                    count++;
                }
            }
            return count;
        }));
        return booked == null ? 0 : booked;
    }

    /** Over the last two days; the parent's last message is unread for the teacher. */
    static List<SeedMessage> conversation(Instant now) {
        LocalDate today = now.atZone(INDIA).toLocalDate();
        Instant twoDaysAgo = today.minusDays(2).atTime(19, 5).atZone(INDIA).toInstant();
        Instant yesterdayMorning = today.minusDays(1).atTime(9, 12).atZone(INDIA).toInstant();
        Instant yesterdayEvening = today.minusDays(1).atTime(18, 40).atZone(INDIA).toInstant();
        return List.of(
                new SeedMessage(Side.PARENT, "Good evening Sir. Arjun was unwell and missed school on Friday. "
                        + "Could you tell us what was covered in Science so he can catch up?", twoDaysAgo, true),
                new SeedMessage(Side.STAFF, "Good morning. I hope Arjun is feeling better. We started the chapter "
                        + "on plants (pages 34 to 41); the worksheet is in the homework section.", yesterdayMorning,
                        true),
                new SeedMessage(Side.PARENT, "Thank you, Sir. He will finish the worksheet this week and bring it on "
                        + "Monday.", yesterdayEvening, false));
    }
}
