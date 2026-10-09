package com.akshara.staff;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.identity.RoleCatalog;
import com.akshara.identity.UserAccount;
import com.akshara.identity.UserRepository;
import com.akshara.shared.TenantContext;

/**
 * The school's staff: every sign-in with at least one staff role (anything but Parent and Student), with their staff
 * profile when one exists. A staff member without a profile shows as "profile incomplete".
 */
@Component
@Transactional(readOnly = true)
class StaffRoster {

    static final List<String> FAMILY_ROLES = List.of(RoleCatalog.PARENT, RoleCatalog.STUDENT);

    /** One staff member. {@code profile} is null while the profile is incomplete. */
    record Member(UUID userId, String name, String email, List<String> roles, boolean accountActive,
            Instant lastLoginAt, StaffProfile profile) {

        static Member of(UserAccount user, StaffProfile profile) {
            return new Member(user.getId(), user.getName(), user.getEmail(), user.roleCodes(), user.isActive(),
                    user.getLastLoginAt(), profile);
        }

        /** LEFT once a leaving date is recorded or the sign-in is disabled. */
        StaffStatus status() {
            return !accountActive || (profile != null && profile.hasLeft()) ? StaffStatus.LEFT : StaffStatus.ACTIVE;
        }

        boolean active() {
            return status() == StaffStatus.ACTIVE;
        }

        UUID departmentId() {
            return profile == null ? null : profile.getDepartmentId();
        }

        String employeeCode() {
            return profile == null ? null : profile.getEmployeeCode();
        }
    }

    private final UserRepository users;
    private final StaffProfileRepository profiles;

    StaffRoster(UserRepository users, StaffProfileRepository profiles) {
        this.users = users;
        this.profiles = profiles;
    }

    /** Everyone on the staff, active or not, by name. */
    List<Member> all() {
        TenantContext.require();
        Map<UUID, StaffProfile> byUser = profiles.findAll().stream()
                .collect(Collectors.toMap(StaffProfile::getUserId, Function.identity()));
        return users.findAllWithAnyRoleExcept(FAMILY_ROLES).stream()
                .map(u -> Member.of(u, byUser.get(u.getId())))
                .toList();
    }

    /** Staff who can sign in today. */
    List<Member> active() {
        return all().stream().filter(Member::active).toList();
    }

    /** A staff member of this school; empty for unknown ids, other schools' people, parents and students. */
    Optional<Member> find(UUID userId) {
        TenantContext.require();
        if (userId == null) {
            return Optional.empty();
        }
        return users.findByIdWithRoles(userId)
                .filter(u -> u.roleCodes().stream().anyMatch(RoleCatalog::isStaffRole))
                .map(u -> Member.of(u, profiles.findByUserId(userId).orElse(null)));
    }
}
