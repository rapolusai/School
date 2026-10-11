package com.akshara.exams;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.notifications.MessageSettings;
import com.akshara.notifications.MessageTemplates;
import com.akshara.notifications.NotificationQueue;
import com.akshara.notifications.NotificationQueue.Recipient;
import com.akshara.notifications.NotificationQueue.Related;
import com.akshara.notifications.NotificationSettingsService;
import com.akshara.platform.TenantDirectory;
import com.akshara.students.StudentRoster;
import com.akshara.students.StudentRoster.GuardianContact;
import com.akshara.students.StudentRoster.RosterStudent;

/**
 * Tells each student's primary parent or guardian that results are out, through the notifications outbox in the same
 * transaction as the publication (so a rolled-back publication sends nothing). The school's alert channel, language
 * and quiet hours apply as for absence alerts. A student without a valid mobile number gets no message. Publishing
 * again after a withdrawal sends a new message.
 */
@Component
class ResultMessages {

    static final String RELATED = "exam_results";
    private static final String PHONE = "^\\+?[0-9]{8,15}$";

    private final NotificationQueue queue;
    private final NotificationSettingsService settings;
    private final StudentRoster roster;
    private final AcademicsDirectory academics;
    private final TenantDirectory tenants;

    ResultMessages(NotificationQueue queue, NotificationSettingsService settings, StudentRoster roster,
            AcademicsDirectory academics, TenantDirectory tenants) {
        this.queue = queue;
        this.settings = settings;
        this.roster = roster;
        this.academics = academics;
        this.tenants = tenants;
    }

    static String key(UUID examClassId, UUID studentId, Instant publishedAt) {
        return "exam-results:" + examClassId + ":" + studentId + ":" + publishedAt.getEpochSecond();
    }

    @EventListener
    void published(ExamResultsPublished event) {
        MessageSettings school = settings.current();
        String schoolName = tenants.profile(event.tenantId()).map(p -> p.name()).orElse("");
        Related related = new Related(RELATED, event.examClassId(), event.examName() + " (" + event.className() + ")");
        for (SectionInfo section : academics.sectionsOfClass(event.classId())) {
            List<RosterStudent> students = roster.activeInSection(event.academicYearId(), section.id());
            if (students.isEmpty()) {
                continue;
            }
            Map<UUID, GuardianContact> contacts = roster.primaryGuardians(students.stream().map(RosterStudent::id)
                    .toList());
            for (RosterStudent student : students) {
                GuardianContact contact = contacts.get(student.id());
                if (contact == null || contact.phone() == null || !contact.phone().matches(PHONE)) {
                    continue;
                }
                Map<String, String> params = new LinkedHashMap<>();
                params.put("student", student.fullName());
                params.put("class", section.label());
                params.put("school", schoolName);
                params.put("exam", event.examName());
                queue.enqueue(MessageTemplates.RESULTS_PUBLISHED, school.alertLanguage(), params, related,
                        List.of(Recipient.phone(school.absenceAlertChannel(), contact.phone(), contact.name(),
                                Map.of("guardian", contact.name()),
                                key(event.examClassId(), student.id(), event.publishedAt()))),
                        event.publishedAt());
            }
        }
    }
}
