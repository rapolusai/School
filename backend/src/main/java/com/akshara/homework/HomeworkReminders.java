package com.akshara.homework;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsService;
import com.akshara.academics.AcademicsService.SubjectView;
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
 * Homework reminders to parents, only when the school switches them on (homework settings; off by default): one
 * message when homework is set, and one the evening before it is due for students who have not submitted. Messages
 * go to each student's primary parent or guardian through the notifications outbox, on the school's alert channel
 * and language. Dedupe keys make each reminder go once per homework and student.
 */
@Component
class HomeworkReminders {

    private static final String PHONE = "^\\+?[0-9]{8,15}$";

    private final HomeworkSettingsRepository settings;
    private final HomeworkRepository homework;
    private final HomeworkSectionRepository links;
    private final SubmissionRepository submissions;
    private final NotificationQueue queue;
    private final NotificationSettingsService messageSettings;
    private final StudentRoster roster;
    private final AcademicsDirectory academics;
    private final AcademicsService academicsService;
    private final TenantDirectory tenants;

    HomeworkReminders(HomeworkSettingsRepository settings, HomeworkRepository homework, HomeworkSectionRepository links,
            SubmissionRepository submissions, NotificationQueue queue, NotificationSettingsService messageSettings,
            StudentRoster roster, AcademicsDirectory academics, AcademicsService academicsService,
            TenantDirectory tenants) {
        this.settings = settings;
        this.homework = homework;
        this.links = links;
        this.submissions = submissions;
        this.queue = queue;
        this.messageSettings = messageSettings;
        this.roster = roster;
        this.academics = academics;
        this.academicsService = academicsService;
        this.tenants = tenants;
    }

    static String assignedKey(UUID homeworkId, UUID studentId) {
        return "homework-assigned:" + homeworkId + ":" + studentId;
    }

    static String dueKey(UUID homeworkId, UUID studentId) {
        return "homework-due:" + homeworkId + ":" + studentId;
    }

    boolean enabled() {
        return settings.findFirstBy().map(HomeworkSettings::isRemindersEnabled).orElse(false);
    }

    /** Tells the parents of every student in the sections about new homework. Returns how many were queued. */
    int onAssigned(Homework hw, Collection<SectionInfo> sections, Instant at) {
        if (!enabled()) {
            return 0;
        }
        int queued = 0;
        for (SectionInfo section : sections) {
            List<RosterStudent> students = roster.activeInSection(hw.getAcademicYearId(), section.id());
            queued += send(MessageTemplates.HOMEWORK_ASSIGNED, hw, section, students, true, at);
        }
        return queued;
    }

    /**
     * The evening-before reminders for homework due on {@code dueOn} in the current school: parents of students who
     * have not submitted. Each homework is reminded once; returns how many messages were queued.
     */
    int dueReminders(LocalDate dueOn, Instant at) {
        TenantContext.require();
        if (!enabled()) {
            return 0;
        }
        int queued = 0;
        for (Homework hw : homework.findDueWithoutReminder(dueOn)) {
            Set<UUID> submitted = submissions.findByHomeworkId(hw.getId()).stream().map(Submission::getStudentId)
                    .collect(Collectors.toSet());
            for (HomeworkSection link : links.findByHomeworkId(hw.getId())) {
                SectionInfo section = academics.section(link.getSectionId()).orElse(null);
                if (section == null) {
                    continue;
                }
                List<RosterStudent> waiting = roster.activeInSection(hw.getAcademicYearId(), section.id()).stream()
                        .filter(s -> !submitted.contains(s.id()))
                        .toList();
                queued += send(MessageTemplates.HOMEWORK_DUE, hw, section, waiting, false, at);
            }
            hw.dueReminderSent(at);
        }
        homework.flush();
        return queued;
    }

    private int send(String template, Homework hw, SectionInfo section, List<RosterStudent> students,
            boolean assigned, Instant at) {
        if (students.isEmpty()) {
            return 0;
        }
        MessageSettings school = messageSettings.current();
        String schoolName = tenants.profile(TenantContext.require()).map(p -> p.name()).orElse("");
        String subject = academicsService.subjects().stream().filter(s -> s.id().equals(hw.getSubjectId()))
                .map(SubjectView::name).findFirst().orElse("");
        Map<UUID, GuardianContact> contacts = roster.primaryGuardians(students.stream().map(RosterStudent::id)
                .toList());
        int queued = 0;
        for (RosterStudent student : students) {
            GuardianContact contact = contacts.get(student.id());
            if (contact == null || contact.phone() == null || !contact.phone().matches(PHONE)) {
                continue;
            }
            Map<String, String> params = new LinkedHashMap<>();
            params.put("student", student.fullName());
            params.put("class", section.label());
            params.put("school", schoolName);
            params.put("subject", subject);
            params.put("title", hw.getTitle());
            params.put("date", hw.getDueOn().toString());
            String key = assigned ? assignedKey(hw.getId(), student.id()) : dueKey(hw.getId(), student.id());
            Recipient recipient = Recipient.phone(school.absenceAlertChannel(), contact.phone(), contact.name(),
                    Map.of("guardian", contact.name()), key);
            queued += (int) queue.enqueue(template, school.alertLanguage(), params,
                    new Related("homework", hw.getId(), hw.getTitle() + " (" + section.label() + ")"),
                    List.of(recipient), at).stream()
                    .filter(q -> q.outcome() != Outcome.DUPLICATE)
                    .count();
        }
        return queued;
    }
}
