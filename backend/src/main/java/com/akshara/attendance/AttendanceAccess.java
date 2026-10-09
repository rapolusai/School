package com.akshara.attendance;

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

/** Works out the signed-in person's {@link AttendanceScope} from their permissions and class-teacher sections. */
@Component
class AttendanceAccess {

    private final AcademicsDirectory academics;

    AttendanceAccess(AcademicsDirectory academics) {
        this.academics = academics;
    }

    AttendanceScope current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Set<String> permissions = auth == null ? Set.of() : auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        boolean manage = permissions.contains(Permissions.ATTENDANCE_MANAGE);
        boolean mark = manage || permissions.contains(Permissions.ATTENDANCE_MARK);
        if (manage) {
            return AttendanceScope.WHOLE_SCHOOL;
        }
        UUID me = CurrentUser.requireId();
        Set<UUID> own = academics.sections().stream()
                .filter(s -> me.equals(s.classTeacherId()))
                .map(SectionInfo::id)
                .collect(Collectors.toSet());
        return new AttendanceScope(false, own, mark);
    }
}
