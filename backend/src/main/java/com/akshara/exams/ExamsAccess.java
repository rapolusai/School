package com.akshara.exams;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.Permissions;
import com.akshara.timetable.TimetableDirectory;

/** Works out the signed-in person's {@link ExamScope} for an academic year from their permissions and timetable. */
@Component
class ExamsAccess {

    private final AcademicsDirectory academics;
    private final TimetableDirectory timetable;

    ExamsAccess(AcademicsDirectory academics, TimetableDirectory timetable) {
        this.academics = academics;
        this.timetable = timetable;
    }

    static Set<String> permissions() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? Set.of() : auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    static boolean can(String permission) {
        return permissions().contains(permission);
    }

    /** The caller's scope in the year (whose timetable says what they teach). */
    ExamScope scope(UUID academicYearId) {
        Set<String> permissions = permissions();
        if (permissions.contains(Permissions.EXAMS_PUBLISH)) {
            return ExamScope.WHOLE_SCHOOL;
        }
        if (!permissions.contains(Permissions.EXAMS_MANAGE)) {
            return ExamScope.NONE;
        }
        UUID me = CurrentUser.requireId();
        Set<UUID> own = academics.sections().stream()
                .filter(s -> me.equals(s.classTeacherId()))
                .map(SectionInfo::id)
                .collect(Collectors.toSet());
        return new ExamScope(false, true, academicYearId == null ? Set.of() : timetable.teachingOf(me, academicYearId),
                own);
    }
}
