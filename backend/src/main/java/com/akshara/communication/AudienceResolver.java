package com.akshara.communication;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.communication.CommunicationTypes.RecipientKind;
import com.akshara.communication.NoticeForms.AudienceRequest;
import com.akshara.identity.Role;
import com.akshara.identity.RoleCatalog;
import com.akshara.identity.RoleRepository;
import com.akshara.identity.UserAccount;
import com.akshara.identity.UserRepository;
import com.akshara.shared.ApiException;
import com.akshara.students.StudentRoster;
import com.akshara.students.StudentRoster.Family;
import com.akshara.students.StudentRoster.FamilyGuardian;

/**
 * Checks a circular's audience and works out who it reaches when it is sent: parents are every linked parent or
 * guardian of the active students enrolled this year in the chosen classes and sections, students are those students
 * who have a sign-in, and staff are the active users with the chosen roles. Each person and each phone number or email
 * address is counted once, however many children or roles connect them.
 */
@Component
class AudienceResolver {

    static final String AUDIENCE = "audience";
    static final String TEACHER_LIMIT = "You can send circulars only to the parents and students of the sections you "
            + "are class teacher of.";
    private static final String PHONE = "^\\+?[0-9]{8,15}$";

    /** A checked audience. Family roles (Parent, Student) apply to the chosen classes and sections. */
    record Audience(boolean wholeSchool, Set<UUID> classIds, Set<UUID> sectionIds, Set<String> roles) {

        Audience {
            classIds = Set.copyOf(classIds);
            sectionIds = Set.copyOf(sectionIds);
            roles = Set.copyOf(roles);
        }

        static Audience everyone() {
            return new Audience(true, Set.of(), Set.of(), Set.of());
        }

        boolean parents() {
            return wholeSchool || roles.contains(RoleCatalog.PARENT);
        }

        boolean students() {
            return wholeSchool || roles.contains(RoleCatalog.STUDENT);
        }

        Set<String> staffRoles() {
            return roles.stream().filter(NoticeAccess::isStaffRole).collect(Collectors.toSet());
        }

        boolean isEmpty() {
            return !wholeSchool && classIds.isEmpty() && sectionIds.isEmpty() && roles.isEmpty();
        }
    }

    /** Someone with a sign-in who will see the circular on their notice board. */
    record Person(UUID userId, RecipientKind kind) {
    }

    /** A phone number or email address to send to; {@code key} makes the message's dedupe key. */
    record Contact(String key, String address, String name) {
    }

    record Resolution(List<Person> people, int staff, int parents, int students, List<Contact> phones,
            List<Contact> emails, int parentsWithoutPhone) {

        static final Resolution NOBODY = new Resolution(List.of(), 0, 0, 0, List.of(), List.of(), 0);

        boolean reachesAnyone() {
            return !people.isEmpty() || !phones.isEmpty() || !emails.isEmpty();
        }
    }

    private final AcademicsDirectory academics;
    private final StudentRoster roster;
    private final UserRepository users;
    private final RoleRepository roles;

    AudienceResolver(AcademicsDirectory academics, StudentRoster roster, UserRepository users, RoleRepository roles) {
        this.academics = academics;
        this.roster = roster;
        this.users = users;
        this.roles = roles;
    }

    // ------------------------------------------------------------------ checking

    /**
     * Checks the audience against the school's classes, sections and roles (400 for anything unknown), and the
     * caller's limits: without notices.approve, only the sections they are class teacher of (403).
     */
    Audience check(AudienceRequest input, NoticeCaller caller) {
        if (input == null) {
            throw ApiException.badRequest("Choose who should get this circular.", AUDIENCE);
        }
        Audience audience;
        if (input.wholeSchool()) {
            audience = Audience.everyone();
        } else {
            List<SectionInfo> sections = academics.sections();
            Set<UUID> knownClasses = sections.stream().map(SectionInfo::classId).collect(Collectors.toSet());
            Set<UUID> knownSections = sections.stream().map(SectionInfo::id).collect(Collectors.toSet());
            if (!knownClasses.containsAll(input.classIds())) {
                throw ApiException.badRequest("Pick classes from the list.", AUDIENCE);
            }
            if (!knownSections.containsAll(input.sectionIds())) {
                throw ApiException.badRequest("Pick sections from the list.", AUDIENCE);
            }
            Set<String> codes = input.roles().stream().map(r -> r.strip().toUpperCase(Locale.ROOT))
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            Set<String> knownRoles = roles.findAll().stream().map(Role::getCode).collect(Collectors.toSet());
            knownRoles.add(RoleCatalog.PARENT);
            knownRoles.add(RoleCatalog.STUDENT);
            if (!knownRoles.containsAll(codes)) {
                throw ApiException.badRequest("Pick roles from the list.", AUDIENCE);
            }
            audience = new Audience(false, new LinkedHashSet<>(input.classIds()),
                    new LinkedHashSet<>(input.sectionIds()), codes);
            if (audience.isEmpty()) {
                throw ApiException.badRequest("Choose who should get this circular.", AUDIENCE);
            }
            boolean scoped = !audience.classIds().isEmpty() || !audience.sectionIds().isEmpty();
            if (scoped && !audience.parents() && !audience.students()) {
                throw ApiException.badRequest("Choose parents, students or both for the chosen classes and sections.",
                        AUDIENCE);
            }
        }
        if (!caller.canApprove()) {
            checkTeacherLimit(audience, caller.ownSectionIds());
        }
        return audience;
    }

    /** Without notices.approve: parents and students of the caller's own class-teacher sections, nothing else. */
    static void checkTeacherLimit(Audience audience, Set<UUID> ownSectionIds) {
        boolean ok = !audience.wholeSchool() && audience.classIds().isEmpty() && !audience.sectionIds().isEmpty()
                && audience.staffRoles().isEmpty() && ownSectionIds.containsAll(audience.sectionIds());
        if (!ok) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Not allowed", TEACHER_LIMIT, Map.of(AUDIENCE,
                    TEACHER_LIMIT));
        }
    }

    // ------------------------------------------------------------------ describing

    /** The audience in words, kept with the circular: "Class 5 A, Class 6 · Parents, Students · Teacher". */
    String label(Audience audience) {
        if (audience.wholeSchool()) {
            return "Whole school";
        }
        List<SectionInfo> sections = academics.sections();
        Map<UUID, String> classNames = new LinkedHashMap<>();
        sections.forEach(s -> classNames.putIfAbsent(s.classId(), s.className()));
        List<String> places = new ArrayList<>();
        audience.classIds().stream().map(classNames::get).filter(n -> n != null).sorted().forEach(places::add);
        sections.stream().filter(s -> audience.sectionIds().contains(s.id())).map(SectionInfo::label)
                .forEach(places::add);
        List<String> who = new ArrayList<>();
        if (audience.parents()) {
            who.add("Parents");
        }
        if (audience.students()) {
            who.add("Students");
        }
        Map<String, String> roleNames = roleNames();
        audience.staffRoles().stream().map(code -> roleNames.getOrDefault(code, code)).sorted().forEach(who::add);
        String text = (places.isEmpty() ? "" : String.join(", ", places) + " · ") + String.join(", ", who);
        return text.length() > 500 ? text.substring(0, 497) + "..." : text;
    }

    Map<String, String> roleNames() {
        Map<String, String> names = new LinkedHashMap<>();
        roles.findAllByOrderByNameAsc().forEach(r -> names.put(r.getCode(), r.getName()));
        return names;
    }

    // ------------------------------------------------------------------ resolving

    /** Everyone the audience reaches now. */
    Resolution resolve(Audience audience) {
        if (audience.isEmpty()) {
            return Resolution.NOBODY;
        }
        Map<UUID, UserAccount> accounts = users.findAllWithRoles().stream()
                .collect(Collectors.toMap(UserAccount::getId, Function.identity()));
        Map<UUID, RecipientKind> people = new LinkedHashMap<>();
        List<Contact> phones = new ArrayList<>();
        List<Contact> emails = new ArrayList<>();
        Set<String> seenPhones = new HashSet<>();
        Set<String> seenEmails = new HashSet<>();

        // Staff first, so someone who is both staff and a parent is counted once, as staff.
        Set<String> staffRoles = audience.wholeSchool() ? null : audience.staffRoles();
        int staff = 0;
        if (staffRoles == null || !staffRoles.isEmpty()) {
            for (UserAccount u : accounts.values()) {
                boolean match = u.isActive() && u.roleCodes().stream().anyMatch(code -> staffRoles == null
                        ? NoticeAccess.isStaffRole(code) : staffRoles.contains(code));
                if (match && people.putIfAbsent(u.getId(), RecipientKind.STAFF) == null) {
                    staff++;
                    addEmail(emails, seenEmails, "u" + u.getId(), u.getEmail(), u.getName());
                }
            }
        }

        int parents = 0;
        int students = 0;
        int withoutPhone = 0;
        if (audience.parents() || audience.students()) {
            Optional<YearInfo> year = academics.currentYear();
            List<Family> families = year.map(y -> roster.families(y.id(), familySections(audience)))
                    .orElse(List.of());
            Set<UUID> seenGuardians = new HashSet<>();
            for (Family family : families) {
                if (audience.students() && family.studentUserId() != null) {
                    UserAccount u = accounts.get(family.studentUserId());
                    if (u != null && u.isActive() && people.putIfAbsent(u.getId(), RecipientKind.STUDENT) == null) {
                        students++;
                    }
                }
                if (!audience.parents()) {
                    continue;
                }
                for (FamilyGuardian g : family.guardians()) {
                    if (!seenGuardians.add(g.guardianId())) {
                        continue;
                    }
                    parents++;
                    UserAccount u = g.userId() == null ? null : accounts.get(g.userId());
                    boolean signedIn = u != null && u.isActive();
                    if (signedIn) {
                        people.putIfAbsent(u.getId(), RecipientKind.PARENT);
                    }
                    String phone = g.phone() == null ? null : g.phone().strip();
                    if (phone != null && phone.matches(PHONE)) {
                        if (seenPhones.add(phone)) {
                            phones.add(new Contact(g.guardianId().toString(), phone, g.name()));
                        }
                    } else {
                        withoutPhone++;
                    }
                    String email = g.email() != null && !g.email().isBlank() ? g.email()
                            : signedIn ? u.getEmail() : null;
                    addEmail(emails, seenEmails, g.guardianId().toString(), email, g.name());
                }
            }
        }
        List<Person> list = people.entrySet().stream().map(e -> new Person(e.getKey(), e.getValue())).toList();
        return new Resolution(list, staff, parents, students, List.copyOf(phones), List.copyOf(emails), withoutPhone);
    }

    /** The sections whose families are in the audience; null means every section. */
    private Collection<UUID> familySections(Audience audience) {
        if (audience.wholeSchool() || (audience.classIds().isEmpty() && audience.sectionIds().isEmpty())) {
            return null;
        }
        Set<UUID> ids = new LinkedHashSet<>(audience.sectionIds());
        academics.sections().stream().filter(s -> audience.classIds().contains(s.classId()))
                .forEach(s -> ids.add(s.id()));
        return ids;
    }

    private static void addEmail(List<Contact> emails, Set<String> seen, String key, String email, String name) {
        if (email == null || !email.contains("@")) {
            return;
        }
        String address = email.strip();
        if (seen.add(address.toLowerCase(Locale.ROOT))) {
            emails.add(new Contact(key, address, name));
        }
    }
}
