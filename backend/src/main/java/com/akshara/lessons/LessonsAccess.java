package com.akshara.lessons;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.ApiException;
import com.akshara.shared.CurrentUser;
import com.akshara.shared.Permissions;
import com.akshara.timetable.TimetableDirectory;
import com.akshara.timetable.TimetableDirectory.Teaching;

/**
 * Works out what the signed-in member of staff may change. With lessons.approve (School Admin, Principal,
 * coordinators) every section and subject; with lessons.manage the sections and subjects they teach in the year
 * (teacher assignments and timetable). Reading needs lessons.read and is school-wide.
 */
@Component
class LessonsAccess {

    /** The caller's reach in a year. */
    record Scope(UUID userId, boolean wholeSchool, boolean canManage, Set<Teaching> teaching) {

        boolean teaches(UUID sectionId, UUID subjectId) {
            return wholeSchool || teaching.contains(new Teaching(sectionId, subjectId));
        }

        /** True when the caller teaches the subject in any of the sections (a class's sections). */
        boolean teachesAny(Collection<UUID> sectionIds, UUID subjectId) {
            return wholeSchool || sectionIds.stream().anyMatch(s -> teaching.contains(new Teaching(s, subjectId)));
        }

        /** True for the record's author, and for approvers who also manage (School Admin). */
        boolean owns(UUID authorId) {
            return (authorId != null && authorId.equals(userId)) || (wholeSchool && canManage);
        }
    }

    private final TimetableDirectory timetable;

    LessonsAccess(TimetableDirectory timetable) {
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

    Scope scope(UUID academicYearId) {
        Set<String> permissions = permissions();
        UUID me = CurrentUser.id().orElse(null);
        boolean manage = permissions.contains(Permissions.LESSONS_MANAGE);
        if (permissions.contains(Permissions.LESSONS_APPROVE)) {
            return new Scope(me, true, manage, Set.of());
        }
        if (!manage || me == null || academicYearId == null) {
            return new Scope(me, false, manage, Set.of());
        }
        return new Scope(me, false, true, Set.copyOf(timetable.teachingOf(me, academicYearId)));
    }

    static Actor actor() {
        return new Actor(CurrentUser.requireId(), CurrentUser.name().orElse("Staff"));
    }

    /** 403 with a field error: the record or class belongs to someone else's teaching. */
    static ApiException notYours(String detail, String field) {
        return new ApiException(HttpStatus.FORBIDDEN, "Not allowed", detail, Map.of(field, detail));
    }
}
