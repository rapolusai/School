package com.akshara.communication;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.identity.RoleCatalog;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.JwtService;
import com.akshara.shared.Permissions;

/** Works out who is calling from the verified access token: their permissions, roles and class-teacher sections. */
@Component
class NoticeAccess {

    private final AcademicsDirectory academics;

    NoticeAccess(AcademicsDirectory academics) {
        this.academics = academics;
    }

    NoticeCaller current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Set<String> permissions = auth == null ? Set.of() : auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        UUID me = CurrentUser.requireId();
        List<String> roles = CurrentUser.token().map(jwt -> jwt.getClaimAsStringList(JwtService.CLAIM_ROLES))
                .orElse(List.of());
        boolean staff = roles != null && roles.stream().anyMatch(NoticeAccess::isStaffRole);
        boolean send = permissions.contains(Permissions.NOTICES_SEND);
        Set<UUID> own = send ? academics.sections().stream()
                .filter(s -> me.equals(s.classTeacherId()))
                .map(SectionInfo::id)
                .collect(Collectors.toSet()) : Set.of();
        return new NoticeCaller(me, CurrentUser.name().orElse(null), send,
                permissions.contains(Permissions.NOTICES_APPROVE), permissions.contains(Permissions.CALENDAR_MANAGE),
                staff, own);
    }

    /** Every role except Parent and Student is a staff role. */
    static boolean isStaffRole(String code) {
        return code != null && !RoleCatalog.PARENT.equals(code) && !RoleCatalog.STUDENT.equals(code);
    }
}
