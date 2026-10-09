package com.akshara.students;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.identity.RoleCatalog;
import com.akshara.identity.UserAccount;
import com.akshara.identity.UserRepository;
import com.akshara.identity.UserService;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentForms.CreateStudent;
import com.akshara.students.StudentForms.GuardianFields;
import com.akshara.students.StudentForms.Leave;
import com.akshara.students.StudentForms.Promote;
import com.akshara.students.StudentForms.UpdateStudent;

/**
 * Students of the current school: admissions, profile changes, guardians, sign-ins for parents and students,
 * transfers and year-end promotion. Every change is audited in the same transaction.
 */
@Service
@Transactional
public class StudentService {

    public static final int MAX_PAGE_SIZE = 100;

    // ------------------------------------------------------------------ views

    public record StudentRow(UUID id, String admissionNo, String firstName, String lastName, String fullName,
            Gender gender, LocalDate dateOfBirth, StudentStatus status, UUID classId, String className,
            UUID sectionId, String sectionName, Integer rollNo, String guardianName, String guardianPhone) {
    }

    public record StudentPage(List<StudentRow> items, int page, int size, long total, UUID academicYearId) {
    }

    public record GuardianView(UUID id, String name, GuardianRelation relation, String phone, String email,
            String occupation, boolean primary, boolean hasSignIn, String signInEmail) {
    }

    public record SiblingView(UUID id, String fullName, String admissionNo, StudentStatus status, String className,
            String sectionName) {
    }

    public record EnrollmentView(UUID id, UUID academicYearId, String academicYearName, boolean currentYear,
            UUID classId, String className, UUID sectionId, String sectionName, Integer rollNo) {
    }

    public record StudentDetail(UUID id, String admissionNo, String firstName, String lastName, String fullName,
            LocalDate dateOfBirth, Gender gender, LocalDate admissionDate, StudentStatus status, String bloodGroup,
            String address, String previousSchool, String apaarId, LocalDate leftOn, String leavingReason,
            boolean hasSignIn, String signInEmail, EnrollmentView currentEnrollment, List<GuardianView> guardians,
            List<SiblingView> siblings, List<EnrollmentView> enrollments) {
    }

    /** A student as their parent (or the student themself) sees them. */
    public record ChildView(UUID id, String fullName, String admissionNo, StudentStatus status, String className,
            String sectionName, Integer rollNo, String classTeacherName, String academicYearName) {
    }

    public record Skipped(UUID studentId, String fullName, String reason) {
    }

    public record PromotionResult(int promoted, int graduated, List<Skipped> skipped) {
    }

    /** Filters for the student list. Null means "any". */
    public record StudentQuery(UUID academicYearId, UUID classId, UUID sectionId, StudentStatus status, String search,
            int page, int size) {
    }

    private final StudentRepository students;
    private final GuardianRepository guardians;
    private final StudentGuardianRepository links;
    private final EnrollmentRepository enrollments;
    private final AcademicsDirectory academics;
    private final UserRepository users;
    private final UserService userService;
    private final AuditService audit;
    private final EntityManager entityManager;

    public StudentService(StudentRepository students, GuardianRepository guardians, StudentGuardianRepository links,
            EnrollmentRepository enrollments, AcademicsDirectory academics, UserRepository users,
            UserService userService, AuditService audit, EntityManager entityManager) {
        this.students = students;
        this.guardians = guardians;
        this.links = links;
        this.enrollments = enrollments;
        this.academics = academics;
        this.users = users;
        this.userService = userService;
        this.audit = audit;
        this.entityManager = entityManager;
    }

    // ------------------------------------------------------------------ list and detail

    /** Students enrolled in the chosen year (the current year by default), filtered and paged. */
    @Transactional(readOnly = true)
    public StudentPage list(StudentQuery query) {
        TenantContext.require();
        int size = Math.min(Math.max(query.size(), 1), MAX_PAGE_SIZE);
        int page = Math.max(query.page(), 0);
        Optional<YearInfo> year = query.academicYearId() == null ? academics.currentYear()
                : Optional.of(academics.year(query.academicYearId())
                        .orElseThrow(() -> ApiException.notFound("Academic year")));
        if (year.isEmpty()) {
            return new StudentPage(List.of(), page, size, 0, null);
        }
        Map<UUID, SectionInfo> sectionById = sectionsById();
        Set<UUID> sectionFilter = null;
        if (query.sectionId() != null) {
            SectionInfo section = Optional.ofNullable(sectionById.get(query.sectionId()))
                    .orElseThrow(() -> ApiException.notFound("Section"));
            if (query.classId() != null && !section.classId().equals(query.classId())) {
                return new StudentPage(List.of(), page, size, 0, year.get().id());
            }
            sectionFilter = Set.of(section.id());
        } else if (query.classId() != null) {
            sectionFilter = sectionById.values().stream().filter(s -> s.classId().equals(query.classId()))
                    .map(SectionInfo::id).collect(Collectors.toSet());
            if (sectionFilter.isEmpty()) {
                return new StudentPage(List.of(), page, size, 0, year.get().id());
            }
        }

        StringBuilder where = new StringBuilder(
                " from Student s join Enrollment e on e.studentId = s.id where e.academicYearId = :year");
        Map<String, Object> params = new HashMap<>();
        params.put("year", year.get().id());
        if (sectionFilter != null) {
            where.append(" and e.sectionId in :sections");
            params.put("sections", sectionFilter);
        }
        if (query.status() != null) {
            where.append(" and s.status = :status");
            params.put("status", query.status());
        }
        String search = query.search() == null ? "" : query.search().trim().toLowerCase(Locale.ROOT);
        if (!search.isEmpty()) {
            where.append(" and (lower(concat(s.firstName, ' ', coalesce(s.lastName, ''))) like :q escape '\\'"
                    + " or lower(coalesce(s.lastName, '')) like :q escape '\\'"
                    + " or lower(s.admissionNo) like :q escape '\\')");
            params.put("q", "%" + escapeLike(search) + "%");
        }
        String order = query.sectionId() != null
                ? " order by e.rollNo asc nulls last, lower(s.firstName), lower(s.lastName), s.admissionNo"
                : " order by lower(s.firstName), lower(s.lastName), s.admissionNo";

        TypedQuery<Long> count = entityManager.createQuery("select count(s)" + where, Long.class);
        TypedQuery<Object[]> rows = entityManager.createQuery("select s, e" + where + order, Object[].class);
        params.forEach((k, v) -> {
            count.setParameter(k, v);
            rows.setParameter(k, v);
        });
        long total = count.getSingleResult();
        List<Object[]> found = rows.setFirstResult(page * size).setMaxResults(size).getResultList();

        Map<UUID, Guardian> primary = primaryGuardians(found.stream().map(r -> ((Student) r[0]).getId()).toList());
        List<StudentRow> items = found.stream().map(r -> {
            Student s = (Student) r[0];
            Enrollment e = (Enrollment) r[1];
            SectionInfo section = sectionById.get(e.getSectionId());
            Guardian g = primary.get(s.getId());
            return new StudentRow(s.getId(), s.getAdmissionNo(), s.getFirstName(), s.getLastName(), s.fullName(),
                    s.getGender(), s.getDateOfBirth(), s.getStatus(), section == null ? null : section.classId(),
                    section == null ? null : section.className(), e.getSectionId(),
                    section == null ? null : section.name(), e.getRollNo(), g == null ? null : g.getName(),
                    g == null ? null : g.getPhone());
        }).toList();
        return new StudentPage(items, page, size, total, year.get().id());
    }

    @Transactional(readOnly = true)
    public StudentDetail detail(UUID id) {
        TenantContext.require();
        Student student = students.findById(id).orElseThrow(() -> ApiException.notFound("Student"));
        Map<UUID, SectionInfo> sectionById = sectionsById();
        Map<UUID, YearInfo> yearById = academics.years().stream()
                .collect(Collectors.toMap(YearInfo::id, Function.identity()));

        List<EnrollmentView> history = enrollments.findByStudentId(id).stream()
                .map(e -> enrollmentView(e, sectionById, yearById))
                .sorted(Comparator.comparing((EnrollmentView v) -> yearById.get(v.academicYearId()).startsOn())
                        .reversed())
                .toList();
        EnrollmentView current = history.stream().filter(EnrollmentView::currentYear).findFirst().orElse(null);

        List<StudentGuardian> studentLinks = links.findByStudentId(id);
        Map<UUID, Guardian> guardianById = guardians.findAllById(studentLinks.stream()
                .map(StudentGuardian::getGuardianId).toList()).stream()
                .collect(Collectors.toMap(Guardian::getId, Function.identity()));
        Set<UUID> userIds = new HashSet<>();
        if (student.getUserAccountId() != null) {
            userIds.add(student.getUserAccountId());
        }
        guardianById.values().stream().map(Guardian::getUserAccountId).filter(Objects::nonNull).forEach(userIds::add);
        Map<UUID, String> emails = userIds.isEmpty() ? Map.of() : users.findAllById(userIds).stream()
                .collect(Collectors.toMap(UserAccount::getId, UserAccount::getEmail));

        List<GuardianView> guardianViews = studentLinks.stream()
                .filter(l -> guardianById.containsKey(l.getGuardianId()))
                .sorted(Comparator.comparing(StudentGuardian::isPrimary).reversed()
                        .thenComparing(l -> guardianById.get(l.getGuardianId()).getName().toLowerCase()))
                .map(l -> guardianView(guardianById.get(l.getGuardianId()), l.isPrimary(), emails))
                .toList();

        Set<UUID> siblingIds = links.findByGuardianIdIn(guardianById.keySet()).stream()
                .map(StudentGuardian::getStudentId).filter(sid -> !sid.equals(id))
                .collect(Collectors.toSet());
        Optional<YearInfo> currentYear = academics.currentYear();
        Map<UUID, Enrollment> siblingEnrollments = currentYear.isEmpty() || siblingIds.isEmpty() ? Map.of()
                : enrollments.findByStudentIdInAndAcademicYearId(siblingIds, currentYear.get().id()).stream()
                        .collect(Collectors.toMap(Enrollment::getStudentId, Function.identity()));
        List<SiblingView> siblings = students.findAllById(siblingIds).stream()
                .sorted(Comparator.comparing(Student::getDateOfBirth))
                .map(s -> {
                    Enrollment e = siblingEnrollments.get(s.getId());
                    SectionInfo section = e == null ? null : sectionById.get(e.getSectionId());
                    return new SiblingView(s.getId(), s.fullName(), s.getAdmissionNo(), s.getStatus(),
                            section == null ? null : section.className(), section == null ? null : section.name());
                })
                .toList();

        return new StudentDetail(student.getId(), student.getAdmissionNo(), student.getFirstName(),
                student.getLastName(), student.fullName(), student.getDateOfBirth(), student.getGender(),
                student.getAdmissionDate(), student.getStatus(), student.getBloodGroup(), student.getAddress(),
                student.getPreviousSchool(), student.getApaarId(), student.getLeftOn(), student.getLeavingReason(),
                student.getUserAccountId() != null,
                student.getUserAccountId() == null ? null : emails.get(student.getUserAccountId()), current,
                guardianViews,
                siblings, history);
    }

    // ------------------------------------------------------------------ admission and profile

    /** Admits a student into a section of the current year, with at least one guardian. */
    public StudentDetail create(CreateStudent form, Actor actor) {
        TenantContext.require();
        YearInfo year = academics.currentYear().orElseThrow(() -> ApiException.badRequest(
                "Set up the current academic year in School setup first.", "sectionId"));
        return admit(form, year, actor);
    }

    /**
     * Admits a student into a section of the given academic year (the admissions office admits children for next
     * year's intake as well as this year's). Otherwise exactly like {@link #create}.
     */
    public StudentDetail createInYear(CreateStudent form, UUID academicYearId, Actor actor) {
        TenantContext.require();
        YearInfo year = academics.year(academicYearId)
                .orElseThrow(() -> ApiException.badRequest("Pick an academic year.", "academicYearId"));
        return admit(form, year, actor);
    }

    private StudentDetail admit(CreateStudent form, YearInfo year, Actor actor) {
        SectionInfo section = academics.section(form.sectionId())
                .orElseThrow(() -> ApiException.badRequest("Pick a section.", "sectionId"));
        Student.Profile profile = profile(form.admissionNo(), form.firstName(), form.lastName(), form.dateOfBirth(),
                form.gender(), form.admissionDate(), form.bloodGroup(), form.address(), form.previousSchool(),
                form.apaarId());
        checkProfile(null, profile);
        List<CleanGuardian> cleanGuardians = cleanGuardians(form.guardians());
        checkSeat(year, section, 1, "sectionId");
        if (form.rollNo() != null && enrollments.rollNoTaken(year.id(), section.id(), form.rollNo(), new UUID(0, 0))) {
            throw ApiException.conflict("Roll number " + form.rollNo() + " is already used in " + section.label() + ".",
                    "rollNo");
        }

        Student student = students.save(new Student(profile));
        enrollments.save(new Enrollment(student.getId(), year.id(), section.id(), form.rollNo()));
        Map<String, Guardian> cache = new HashMap<>();
        for (CleanGuardian g : cleanGuardians) {
            Guardian guardian = findOrCreateGuardian(g, cache);
            links.save(new StudentGuardian(student.getId(), guardian.getId(), g.primary()));
        }
        students.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("admissionNo", profile.admissionNo());
        details.put("section", section.label());
        details.put("academicYear", year.name());
        record(actor, "student.created", "student", student.getId(), details);
        return detail(student.getId());
    }

    public StudentDetail update(UUID id, UpdateStudent form) {
        TenantContext.require();
        Student student = students.findById(id).orElseThrow(() -> ApiException.notFound("Student"));
        Student.Profile profile = profile(form.admissionNo(), form.firstName(), form.lastName(), form.dateOfBirth(),
                form.gender(), form.admissionDate(), form.bloodGroup(), form.address(), form.previousSchool(),
                form.apaarId());
        checkProfile(id, profile);
        List<String> changed = changedFields(student, profile);
        student.apply(profile);

        if (form.sectionId() != null) {
            YearInfo year = academics.currentYear().orElseThrow(() -> ApiException.badRequest(
                    "Set up the current academic year in School setup first.", "sectionId"));
            SectionInfo section = academics.section(form.sectionId())
                    .orElseThrow(() -> ApiException.badRequest("Pick a section.", "sectionId"));
            Optional<Enrollment> existing = enrollments.findByStudentIdAndAcademicYearId(id, year.id());
            boolean sectionChanges = existing.map(e -> !e.getSectionId().equals(section.id())).orElse(true);
            boolean rollChanges = existing.map(e -> !Objects.equals(e.getRollNo(), form.rollNo())).orElse(true);
            if (sectionChanges || rollChanges) {
                if (!student.isActive()) {
                    throw new ApiException(HttpStatus.CONFLICT, "Not active",
                            "Only active students can change section or roll number.",
                            Map.of("sectionId", "Only active students can change section or roll number."));
                }
                if (sectionChanges) {
                    checkSeat(year, section, 1, "sectionId");
                }
                UUID exceptId = existing.map(Enrollment::getId).orElse(new UUID(0, 0));
                if (form.rollNo() != null && enrollments.rollNoTaken(year.id(), section.id(), form.rollNo(), exceptId)) {
                    throw ApiException.conflict("Roll number " + form.rollNo() + " is already used in "
                            + section.label() + ".", "rollNo");
                }
                if (existing.isPresent()) {
                    existing.get().moveTo(section.id(), form.rollNo());
                } else {
                    enrollments.save(new Enrollment(id, year.id(), section.id(), form.rollNo()));
                }
                changed.add(sectionChanges ? "section" : "rollNo");
            }
        }
        students.flush();
        if (!changed.isEmpty()) {
            record(null, "student.updated", "student", id, Map.of("admissionNo", profile.admissionNo(),
                    "changed", changed));
        }
        return detail(id);
    }

    /** Records that a student transferred to another school or withdrew. */
    public StudentDetail leave(UUID id, Leave form) {
        TenantContext.require();
        Student student = students.findById(id).orElseThrow(() -> ApiException.notFound("Student"));
        if (form.status() != StudentStatus.TRANSFERRED && form.status() != StudentStatus.WITHDRAWN) {
            throw ApiException.badRequest("Choose transferred or withdrawn.", "status");
        }
        if (!student.isActive()) {
            throw new ApiException(HttpStatus.CONFLICT, "Not active",
                    "Only active students can be transferred or withdrawn.", Map.of());
        }
        if (form.leftOn().isBefore(student.getAdmissionDate())) {
            throw ApiException.badRequest("The leaving date cannot be before the admission date.", "leftOn");
        }
        student.leave(form.status(), form.leftOn(), form.reason().trim());
        students.flush();
        String action = form.status() == StudentStatus.TRANSFERRED ? "student.transferred" : "student.withdrawn";
        record(null, action, "student", id, Map.of("admissionNo", student.getAdmissionNo(),
                "leftOn", form.leftOn().toString()));
        return detail(id);
    }

    // ------------------------------------------------------------------ guardians

    /** Adds a guardian. A guardian with the same name and mobile number is reused, which links siblings. */
    public GuardianView addGuardian(UUID studentId, GuardianFields form) {
        TenantContext.require();
        Student student = students.findById(studentId).orElseThrow(() -> ApiException.notFound("Student"));
        CleanGuardian clean = cleanGuardian(form);
        List<StudentGuardian> existingLinks = links.findByStudentId(studentId);
        if (existingLinks.size() >= 4) {
            throw ApiException.badRequest("A student can have at most 4 parents or guardians.", "name");
        }
        Guardian guardian = findOrCreateGuardian(clean, new HashMap<>());
        if (existingLinks.stream().anyMatch(l -> l.getGuardianId().equals(guardian.getId()))) {
            throw ApiException.conflict("This guardian is already linked to the student.", "phone");
        }
        boolean primary = form.primary() || existingLinks.isEmpty();
        if (primary) {
            existingLinks.forEach(l -> l.setPrimary(false));
            links.flush();
        }
        links.save(new StudentGuardian(studentId, guardian.getId(), primary));
        links.flush();
        record(null, "guardian.added", "guardian", guardian.getId(), Map.of("admissionNo", student.getAdmissionNo(),
                "relation", guardian.getRelation().name()));
        return guardianView(guardian, primary, signInEmails(List.of(guardian)));
    }

    /** Edits a guardian. The record is shared, so siblings see the change too. */
    public GuardianView updateGuardian(UUID studentId, UUID guardianId, GuardianFields form) {
        TenantContext.require();
        StudentGuardian link = links.findByStudentIdAndGuardianId(studentId, guardianId)
                .orElseThrow(() -> ApiException.notFound("Guardian"));
        Guardian guardian = guardians.findById(guardianId).orElseThrow(() -> ApiException.notFound("Guardian"));
        CleanGuardian clean = cleanGuardian(form);
        guardian.update(clean.name(), clean.relation(), clean.phone(), clean.email(), clean.occupation());
        if (form.primary() && !link.isPrimary()) {
            links.findByStudentId(studentId).forEach(l -> l.setPrimary(false));
            links.flush();
            link.setPrimary(true);
        }
        guardians.flush();
        links.flush();
        record(null, "guardian.updated", "guardian", guardianId, Map.of("relation", clean.relation().name()));
        return guardianView(guardian, link.isPrimary(), signInEmails(List.of(guardian)));
    }

    /** Unlinks a guardian. A guardian who no longer has any student is deleted, so no orphaned contact data stays. */
    public void removeGuardian(UUID studentId, UUID guardianId) {
        TenantContext.require();
        StudentGuardian link = links.findByStudentIdAndGuardianId(studentId, guardianId)
                .orElseThrow(() -> ApiException.notFound("Guardian"));
        List<StudentGuardian> all = links.findByStudentId(studentId);
        if (all.size() <= 1) {
            throw new ApiException(HttpStatus.CONFLICT, "In use",
                    "A student needs at least one parent or guardian. Add another one first.");
        }
        links.delete(link);
        links.flush();
        if (link.isPrimary()) {
            all.stream().filter(l -> !l.getId().equals(link.getId())).findFirst().ifPresent(l -> l.setPrimary(true));
            links.flush();
        }
        if (links.countByGuardianId(guardianId) == 0) {
            guardians.findById(guardianId).ifPresent(g -> {
                guardians.delete(g);
                guardians.flush();
            });
        }
        record(null, "guardian.removed", "guardian", guardianId, Map.of("studentId", studentId.toString()));
    }

    // ------------------------------------------------------------------ sign-ins

    /**
     * Gives a guardian a parent sign-in: a new PARENT user (when {@code passwordHash} is set) or an existing PARENT
     * user with that email.
     */
    public GuardianView linkGuardianSignIn(UUID studentId, UUID guardianId, String email, String passwordHash,
            Actor actor) {
        TenantContext.require();
        StudentGuardian link = links.findByStudentIdAndGuardianId(studentId, guardianId)
                .orElseThrow(() -> ApiException.notFound("Guardian"));
        Guardian guardian = guardians.findById(guardianId).orElseThrow(() -> ApiException.notFound("Guardian"));
        if (guardian.getUserAccountId() != null) {
            throw ApiException.conflict("This guardian already has a sign-in.", "email");
        }
        UserAccount user = signInUser(email, passwordHash, guardian.getName(), RoleCatalog.PARENT, actor);
        if (guardians.findByUserAccountId(user.getId()).isPresent()) {
            throw ApiException.conflict("That sign-in already belongs to another guardian.", "email");
        }
        guardian.linkUser(user.getId());
        guardians.flush();
        record(actor, "guardian.sign_in_linked", "guardian", guardianId,
                Map.of("userId", user.getId().toString(), "created", passwordHash != null));
        return guardianView(guardian, link.isPrimary(), Map.of(user.getId(), user.getEmail()));
    }

    /** Gives a student their own sign-in with the STUDENT role, new or existing. */
    public StudentDetail linkStudentSignIn(UUID studentId, String email, String passwordHash, Actor actor) {
        TenantContext.require();
        Student student = students.findById(studentId).orElseThrow(() -> ApiException.notFound("Student"));
        if (student.getUserAccountId() != null) {
            throw ApiException.conflict("This student already has a sign-in.", "email");
        }
        UserAccount user = signInUser(email, passwordHash, student.fullName(), RoleCatalog.STUDENT, actor);
        if (students.findByUserAccountId(user.getId()).isPresent()) {
            throw ApiException.conflict("That sign-in already belongs to another student.", "email");
        }
        student.linkUser(user.getId());
        students.flush();
        record(actor, "student.sign_in_linked", "student", studentId,
                Map.of("userId", user.getId().toString(), "created", passwordHash != null));
        return detail(studentId);
    }

    private UserAccount signInUser(String email, String passwordHash, String name, String role, Actor actor) {
        if (passwordHash != null) {
            return userService.createUser(name, email, passwordHash, List.of(role), actor);
        }
        UserAccount user = users.findByEmailWithRoles(email.trim())
                .orElseThrow(() -> ApiException.badRequest("No one in this school signs in with that email.", "email"));
        if (!user.roleCodes().contains(role)) {
            String roleName = RoleCatalog.PARENT.equals(role) ? "Parent" : "Student";
            throw ApiException.badRequest("That person does not have the " + roleName + " role.", "email");
        }
        return user;
    }

    // ------------------------------------------------------------------ promotion

    /** Year-end move of a whole section: into a section of a later year, or out of the school as alumni. */
    public PromotionResult promote(Promote form, Actor actor) {
        TenantContext.require();
        YearInfo fromYear = form.fromYearId() == null
                ? academics.currentYear().orElseThrow(() -> ApiException.badRequest(
                        "Set up the current academic year in School setup first.", "fromYearId"))
                : academics.year(form.fromYearId())
                        .orElseThrow(() -> ApiException.badRequest("Pick an academic year.", "fromYearId"));
        SectionInfo fromSection = academics.section(form.fromSectionId())
                .orElseThrow(() -> ApiException.badRequest("Pick the section to promote.", "fromSectionId"));
        List<Object[]> rows = enrollments.activeIn(fromYear.id(), fromSection.id());
        List<Student> cohort = rows.stream().map(r -> (Student) r[0]).toList();

        if (form.graduate()) {
            for (Student s : cohort) {
                s.graduate(fromYear.endsOn());
                record(actor, "student.graduated", "student", s.getId(), Map.of("admissionNo", s.getAdmissionNo(),
                        "section", fromSection.label(), "academicYear", fromYear.name()));
            }
            students.flush();
            return new PromotionResult(0, cohort.size(), List.of());
        }

        if (form.toYearId() == null) {
            throw ApiException.badRequest("Pick the academic year to promote into.", "toYearId");
        }
        YearInfo toYear = academics.year(form.toYearId())
                .orElseThrow(() -> ApiException.badRequest("Pick the academic year to promote into.", "toYearId"));
        if (!toYear.startsOn().isAfter(fromYear.startsOn())) {
            throw ApiException.badRequest("Promote into a later academic year than " + fromYear.name() + ".",
                    "toYearId");
        }
        if (form.toSectionId() == null) {
            throw ApiException.badRequest("Pick the section to promote into.", "toSectionId");
        }
        SectionInfo toSection = academics.section(form.toSectionId())
                .orElseThrow(() -> ApiException.badRequest("Pick the section to promote into.", "toSectionId"));

        Map<UUID, Enrollment> alreadyThere = cohort.isEmpty() ? Map.of()
                : enrollments.findByStudentIdInAndAcademicYearId(cohort.stream().map(Student::getId).toList(),
                        toYear.id()).stream().collect(Collectors.toMap(Enrollment::getStudentId, Function.identity()));
        List<Skipped> skipped = new ArrayList<>();
        List<Student> moving = new ArrayList<>();
        for (Student s : cohort) {
            if (alreadyThere.containsKey(s.getId())) {
                skipped.add(new Skipped(s.getId(), s.fullName(), "Already enrolled in " + toYear.name() + "."));
            } else {
                moving.add(s);
            }
        }
        checkSeat(toYear, toSection, moving.size(), "toSectionId");
        int nextRoll = enrollments.maxRollNo(toYear.id(), toSection.id());
        for (Student s : moving) {
            nextRoll++;
            enrollments.save(new Enrollment(s.getId(), toYear.id(), toSection.id(), nextRoll <= 999 ? nextRoll : null));
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("admissionNo", s.getAdmissionNo());
            details.put("from", fromSection.label() + " (" + fromYear.name() + ")");
            details.put("to", toSection.label() + " (" + toYear.name() + ")");
            record(actor, "student.promoted", "student", s.getId(), details);
        }
        enrollments.flush();
        return new PromotionResult(moving.size(), 0, skipped);
    }

    // ------------------------------------------------------------------ parents and students

    /** The students linked to the guardian records of a parent's sign-in. */
    @Transactional(readOnly = true)
    public List<ChildView> childrenOf(UUID userId) {
        TenantContext.require();
        Optional<Guardian> guardian = guardians.findByUserAccountId(userId);
        if (guardian.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = links.findByGuardianId(guardian.get().getId()).stream()
                .map(StudentGuardian::getStudentId).toList();
        return childViews(students.findAllById(ids));
    }

    /** The student record linked to a student's own sign-in. */
    @Transactional(readOnly = true)
    public Optional<ChildView> studentOf(UUID userId) {
        TenantContext.require();
        return students.findByUserAccountId(userId).map(s -> childViews(List.of(s)).getFirst());
    }

    private List<ChildView> childViews(List<Student> list) {
        if (list.isEmpty()) {
            return List.of();
        }
        Optional<YearInfo> year = academics.currentYear();
        Map<UUID, Enrollment> current = year.isEmpty() ? Map.of()
                : enrollments.findByStudentIdInAndAcademicYearId(list.stream().map(Student::getId).toList(),
                        year.get().id()).stream()
                        .collect(Collectors.toMap(Enrollment::getStudentId, Function.identity()));
        Map<UUID, SectionInfo> sectionById = sectionsById();
        return list.stream()
                .sorted(Comparator.comparing(Student::getDateOfBirth))
                .map(s -> {
                    Enrollment e = current.get(s.getId());
                    SectionInfo section = e == null ? null : sectionById.get(e.getSectionId());
                    return new ChildView(s.getId(), s.fullName(), s.getAdmissionNo(), s.getStatus(),
                            section == null ? null : section.className(), section == null ? null : section.name(),
                            e == null ? null : e.getRollNo(), section == null ? null : section.classTeacherName(),
                            e == null ? null : year.get().name());
                })
                .toList();
    }

    // ------------------------------------------------------------------ shared with the CSV import

    record CleanGuardian(String name, GuardianRelation relation, String phone, String email, String occupation,
            boolean primary) {

        String key() {
            return phone + "|" + name.toLowerCase(Locale.ROOT);
        }
    }

    /** Reuses a guardian with the same mobile number and name (case-insensitive), else creates one. */
    Guardian findOrCreateGuardian(CleanGuardian g, Map<String, Guardian> cache) {
        Guardian cached = cache.get(g.key());
        if (cached != null) {
            cached.fillGaps(g.email(), g.occupation());
            return cached;
        }
        Guardian guardian = guardians.findByPhoneIn(List.of(g.phone())).stream()
                .filter(existing -> existing.getName().equalsIgnoreCase(g.name()))
                .findFirst()
                .orElse(null);
        if (guardian == null) {
            guardian = guardians.save(new Guardian(g.name(), g.relation(), g.phone(), g.email(), g.occupation()));
        } else {
            guardian.fillGaps(g.email(), g.occupation());
        }
        cache.put(g.key(), guardian);
        return guardian;
    }

    /** Fails with 409 when the section has a capacity and fewer than {@code seats} free places this year. */
    void checkSeat(YearInfo year, SectionInfo section, int seats, String field) {
        if (section.capacity() == null || seats <= 0) {
            return;
        }
        long taken = enrollments.countActive(year.id(), section.id());
        if (taken + seats > section.capacity()) {
            throw ApiException.conflict(section.label() + " is full (" + taken + " of " + section.capacity()
                    + " places taken).", field);
        }
    }

    Map<UUID, SectionInfo> sectionsById() {
        return academics.sections().stream().collect(Collectors.toMap(SectionInfo::id, Function.identity()));
    }

    void record(Actor actor, String action, String entityType, UUID entityId, Map<String, ?> details) {
        if (actor == null) {
            audit.record(action, entityType, entityId, details);
        } else {
            audit.record(actor, action, entityType, entityId, details);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Student.Profile profile(String admissionNo, String firstName, String lastName,
            LocalDate dateOfBirth, Gender gender, LocalDate admissionDate, String bloodGroup, String address,
            String previousSchool, String apaarId) {
        return new Student.Profile(admissionNo.trim(), firstName.trim(), blankToNull(lastName), dateOfBirth, gender,
                admissionDate, blankToNull(bloodGroup), blankToNull(address), blankToNull(previousSchool),
                blankToNull(apaarId));
    }

    private void checkProfile(UUID id, Student.Profile p) {
        if (!p.dateOfBirth().isBefore(p.admissionDate())) {
            throw ApiException.badRequest("The date of birth must be before the admission date.", "dateOfBirth");
        }
        if (students.existsByAdmissionNoExcept(p.admissionNo(), id == null ? new UUID(0, 0) : id)) {
            throw ApiException.conflict("Admission number " + p.admissionNo() + " is already used.", "admissionNo");
        }
    }

    /** Guardians of a new student: errors name the list entry, such as {@code guardians[1].phone}. */
    private List<CleanGuardian> cleanGuardians(List<GuardianFields> fields) {
        return cleanGuardians(fields, true);
    }

    private CleanGuardian cleanGuardian(GuardianFields fields) {
        return cleanGuardians(List.of(fields), false).getFirst();
    }

    private List<CleanGuardian> cleanGuardians(List<GuardianFields> fields, boolean nested) {
        List<CleanGuardian> clean = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        long primaries = fields.stream().filter(GuardianFields::primary).count();
        if (primaries > 1) {
            throw ApiException.badRequest("Mark only one parent or guardian as the primary contact.", "guardians");
        }
        for (int i = 0; i < fields.size(); i++) {
            GuardianFields f = fields.get(i);
            String phone = Phones.normalize(f.phone());
            String prefix = nested ? "guardians[" + i + "]." : "";
            if (phone == null) {
                throw ApiException.badRequest(Phones.MESSAGE, prefix + "phone");
            }
            CleanGuardian g = new CleanGuardian(f.name().trim(), f.relation(), phone, blankToNull(f.email()),
                    blankToNull(f.occupation()), primaries == 0 ? i == 0 : f.primary());
            if (!keys.add(g.key())) {
                throw ApiException.badRequest("This guardian is listed twice.", prefix + "name");
            }
            clean.add(g);
        }
        return clean;
    }

    private static List<String> changedFields(Student s, Student.Profile p) {
        List<String> changed = new ArrayList<>();
        compare(changed, "admissionNo", s.getAdmissionNo(), p.admissionNo());
        compare(changed, "firstName", s.getFirstName(), p.firstName());
        compare(changed, "lastName", s.getLastName(), p.lastName());
        compare(changed, "dateOfBirth", s.getDateOfBirth(), p.dateOfBirth());
        compare(changed, "gender", s.getGender(), p.gender());
        compare(changed, "admissionDate", s.getAdmissionDate(), p.admissionDate());
        compare(changed, "bloodGroup", s.getBloodGroup(), p.bloodGroup());
        compare(changed, "address", s.getAddress(), p.address());
        compare(changed, "previousSchool", s.getPreviousSchool(), p.previousSchool());
        compare(changed, "apaarId", s.getApaarId(), p.apaarId());
        return changed;
    }

    private static void compare(List<String> changed, String field, Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changed.add(field);
        }
    }

    private EnrollmentView enrollmentView(Enrollment e, Map<UUID, SectionInfo> sectionById,
            Map<UUID, YearInfo> yearById) {
        SectionInfo section = sectionById.get(e.getSectionId());
        YearInfo year = yearById.get(e.getAcademicYearId());
        return new EnrollmentView(e.getId(), e.getAcademicYearId(), year.name(), year.current(),
                section == null ? null : section.classId(), section == null ? null : section.className(),
                e.getSectionId(), section == null ? null : section.name(), e.getRollNo());
    }

    private static GuardianView guardianView(Guardian g, boolean primary, Map<UUID, String> emails) {
        return new GuardianView(g.getId(), g.getName(), g.getRelation(), g.getPhone(), g.getEmail(), g.getOccupation(),
                primary, g.getUserAccountId() != null,
                g.getUserAccountId() == null ? null : emails.get(g.getUserAccountId()));
    }

    private Map<UUID, String> signInEmails(Collection<Guardian> list) {
        List<UUID> ids = list.stream().map(Guardian::getUserAccountId).filter(Objects::nonNull).toList();
        return ids.isEmpty() ? Map.of() : users.findAllById(ids).stream()
                .collect(Collectors.toMap(UserAccount::getId, UserAccount::getEmail));
    }

    private Map<UUID, Guardian> primaryGuardians(Collection<UUID> studentIds) {
        if (studentIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Guardian> result = new HashMap<>();
        for (Object[] row : links.findPrimaryGuardians(studentIds)) {
            result.put((UUID) row[0], (Guardian) row[1]);
        }
        return result;
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
