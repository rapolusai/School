package com.akshara.lessons;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.identity.UserAccount;
import com.akshara.identity.UserRepository;
import com.akshara.notifications.Channel;
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
import com.akshara.students.StudentRoster.Family;
import com.akshara.students.StudentRoster.FamilyGuardian;
import com.akshara.students.StudentRoster.RosterStudent;

/**
 * Tells families about a new online test, only when the school switches it on (lessons settings; off by default):
 * each student's primary parent or guardian on the school's alert channel and language, and the student on the email
 * of their own sign-in. Messages go through the notifications outbox (simulated, and held back in quiet hours by the
 * dispatcher); dedupe keys make each go once per test and student.
 */
@Component
class TestNotifications {

    private static final String PHONE = "^\\+?[0-9]{8,15}$";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final LessonsSetupService setup;
    private final NotificationQueue queue;
    private final NotificationSettingsService messageSettings;
    private final StudentRoster roster;
    private final UserRepository users;
    private final TenantDirectory tenants;

    TestNotifications(LessonsSetupService setup, NotificationQueue queue, NotificationSettingsService messageSettings,
            StudentRoster roster, UserRepository users, TenantDirectory tenants) {
        this.setup = setup;
        this.queue = queue;
        this.messageSettings = messageSettings;
        this.roster = roster;
        this.users = users;
        this.tenants = tenants;
    }

    static String guardianKey(UUID testId, UUID studentId) {
        return "online-test:" + testId + ":" + studentId;
    }

    static String studentKey(UUID testId, UUID studentId) {
        return "online-test-student:" + testId + ":" + studentId;
    }

    /** Queues the messages for a test just published to the sections. Returns how many were queued. */
    int onPublished(OnlineTest test, Collection<SectionInfo> sections, String subjectName, Instant at) {
        if (!setup.notificationsEnabled() || sections.isEmpty()) {
            return 0;
        }
        MessageSettings school = messageSettings.current();
        String schoolName = tenants.profile(TenantContext.require()).map(p -> p.name()).orElse("");
        ZonedDateTime closes = test.getClosesAt().atZone(LessonsSupport.INDIA);
        Map<UUID, SectionInfo> sectionById = sections.stream()
                .collect(Collectors.toMap(SectionInfo::id, Function.identity()));
        List<Family> families = roster.families(test.getAcademicYearId(), sectionById.keySet());
        if (families.isEmpty()) {
            return 0;
        }
        Map<UUID, RosterStudent> students = roster.students(families.stream().map(Family::studentId).toList(),
                test.getAcademicYearId());
        Map<UUID, UserAccount> studentUsers = users.findAllById(families.stream().map(Family::studentUserId)
                .filter(id -> id != null).toList()).stream()
                .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
        Related related = new Related("online_test", test.getId(), test.getTitle());
        int queued = 0;
        for (Family family : families) {
            RosterStudent student = students.get(family.studentId());
            SectionInfo section = sectionById.get(family.sectionId());
            if (student == null || section == null) {
                continue;
            }
            Map<String, String> params = new LinkedHashMap<>();
            params.put("student", student.fullName());
            params.put("class", section.label());
            params.put("school", schoolName);
            params.put("subject", subjectName);
            params.put("title", test.getTitle());
            params.put("date", closes.toLocalDate().toString());
            params.put("time", closes.format(TIME));
            FamilyGuardian primary = family.guardians().stream().filter(FamilyGuardian::primary).findFirst()
                    .orElse(null);
            if (primary != null && primary.phone() != null && primary.phone().matches(PHONE)) {
                queued += count(queue.enqueue(MessageTemplates.ONLINE_TEST, school.alertLanguage(), params, related,
                        List.of(Recipient.phone(school.absenceAlertChannel(), primary.phone(), primary.name(),
                                Map.of("guardian", primary.name()), guardianKey(test.getId(), student.id()))), at));
            }
            UserAccount own = family.studentUserId() == null ? null : studentUsers.get(family.studentUserId());
            if (own != null && own.isActive() && own.getEmail() != null && own.getEmail().contains("@")) {
                queued += count(queue.enqueue(MessageTemplates.ONLINE_TEST_STUDENT, school.alertLanguage(), params,
                        related, List.of(new Recipient(Channel.EMAIL, own.getEmail(), student.fullName(), Map.of(),
                                null, studentKey(test.getId(), student.id()))), at));
            }
        }
        return queued;
    }

    private static int count(List<NotificationQueue.Queued> result) {
        return (int) result.stream().filter(q -> q.outcome() != Outcome.DUPLICATE).count();
    }
}
