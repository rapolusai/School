package com.akshara.homework;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.Permissions;
import com.akshara.timetable.TimetableDirectory;

/** Works out the signed-in person's {@link HomeworkScope} from their permissions, timetable and class sections. */
@Component
class HomeworkAccess {

    private final AcademicsDirectory academics;
    private final TimetableDirectory timetable;

    HomeworkAccess(AcademicsDirectory academics, TimetableDirectory timetable) {
        this.academics = academics;
        this.timetable = timetable;
    }

    static Set<String> permissions() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? Set.of() : auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
    }

    HomeworkScope current() {
        Set<String> permissions = permissions();
        if (!permissions.contains(Permissions.HOMEWORK_MANAGE)) {
            return HomeworkScope.NONE;
        }
        if (permissions.contains(Permissions.TIMETABLE_MANAGE)) {
            return HomeworkScope.WHOLE_SCHOOL;
        }
        UUID me = CurrentUser.requireId();
        Optional<YearInfo> year = academics.currentYear();
        Set<UUID> own = academics.sections().stream()
                .filter(s -> me.equals(s.classTeacherId()))
                .map(SectionInfo::id)
                .collect(Collectors.toSet());
        return new HomeworkScope(false, year.map(y -> timetable.teachingOf(me, y.id())).orElse(Set.of()), own);
    }
}
