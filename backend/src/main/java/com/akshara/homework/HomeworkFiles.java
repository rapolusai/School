package com.akshara.homework;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.akshara.files.FileAccessPolicy;
import com.akshara.files.StoredFileInfo;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.Permissions;
import com.akshara.students.StudentRoster;

/**
 * Who may download homework files. Homework attachments: staff whose scope covers the homework, students of its
 * sections (once it is set) and parents of those students. Submission files: staff whose scope covers the student's
 * section, the student who submitted, and that student's parents.
 */
@Component
class HomeworkFiles implements FileAccessPolicy {

    private final HomeworkRepository homework;
    private final SubmissionRepository submissions;
    private final HomeworkAccess access;
    private final StudentHomeworkService learners;
    private final StudentRoster roster;

    HomeworkFiles(HomeworkRepository homework, SubmissionRepository submissions, HomeworkAccess access,
            StudentHomeworkService learners, StudentRoster roster) {
        this.homework = homework;
        this.submissions = submissions;
        this.access = access;
        this.learners = learners;
        this.roster = roster;
    }

    @Override
    public String ownerModule() {
        return HomeworkService.MODULE;
    }

    @Override
    public boolean canRead(StoredFileInfo file) {
        Optional<UUID> user = CurrentUser.id();
        if (user.isEmpty()) {
            return false;
        }
        return switch (file.ownerType()) {
            case HomeworkService.HOMEWORK_FILE -> canReadAttachment(user.get(), file.ownerId());
            case HomeworkService.SUBMISSION_FILE -> canReadSubmission(user.get(), file.ownerId());
            default -> false;
        };
    }

    private boolean canReadAttachment(UUID user, UUID homeworkId) {
        Homework hw = homework.findById(homeworkId).orElse(null);
        if (hw == null) {
            return false;
        }
        List<UUID> sections = learners.sectionIds(homeworkId);
        Set<String> permissions = HomeworkAccess.permissions();
        if (permissions.contains(Permissions.HOMEWORK_MANAGE)
                && access.current().canSee(sections, hw.getSubjectId())) {
            return true;
        }
        if (hw.getAssignedOn().isAfter(HomeworkService.today())) {
            return false;
        }
        Optional<UUID> student = learners.studentIdOf(user);
        if (student.isPresent() && learners.canSee(student.get(), hw, HomeworkService.today())) {
            return true;
        }
        return permissions.contains(Permissions.CHILD_VIEW)
                && !learners.childrenIn(user, hw.getAcademicYearId(), sections).isEmpty();
    }

    private boolean canReadSubmission(UUID user, UUID submissionId) {
        Submission sub = submissions.findById(submissionId).orElse(null);
        if (sub == null) {
            return false;
        }
        Homework hw = homework.findById(sub.getHomeworkId()).orElse(null);
        if (hw == null) {
            return false;
        }
        Set<String> permissions = HomeworkAccess.permissions();
        if (permissions.contains(Permissions.HOMEWORK_MANAGE)) {
            Optional<UUID> section = roster.sectionOf(sub.getStudentId(), hw.getAcademicYearId());
            if (section.isPresent() && access.current().canManage(section.get(), hw.getSubjectId())) {
                return true;
            }
        }
        if (learners.studentIdOf(user).filter(sub.getStudentId()::equals).isPresent()) {
            return true;
        }
        return permissions.contains(Permissions.CHILD_VIEW) && learners.isParentOf(user, List.of(sub.getStudentId()));
    }
}
