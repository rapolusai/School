package com.akshara.onboarding;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.academics.AcademicsService;
import com.akshara.admissions.AdmissionsService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.identity.UserService;
import com.akshara.platform.Board;
import com.akshara.platform.Plan;
import com.akshara.platform.TenantDirectory;
import com.akshara.platform.TenantStatus;
import com.akshara.shared.TenantContext;
import com.akshara.students.StudentService;

/**
 * Local development and staging only: creates a demo school with one person per standard role, its classes, subjects
 * and academic years, and about sixty students (see {@link DemoSchoolData}). The password comes from configuration
 * (Secrets Manager in AWS) and is never written in the repository. Runs once: an existing demo school is left alone.
 */
@Component
@ConditionalOnProperty(name = "akshara.demo.enabled", havingValue = "true")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    static final String DEMO_CODE = "demo";
    static final String DEMO_DOMAIN = "@demo.akshara.test";

    record DemoPerson(String name, String email, String role) {
    }

    static final List<DemoPerson> PEOPLE = List.of(
            new DemoPerson("Lakshmi Iyer", "principal" + DEMO_DOMAIN, "PRINCIPAL"),
            new DemoPerson("Ravi Kumar", "teacher" + DEMO_DOMAIN, "TEACHER"),
            new DemoPerson("Meena Reddy", "accounts" + DEMO_DOMAIN, "ACCOUNTANT"),
            new DemoPerson("Suresh Rao", "frontoffice" + DEMO_DOMAIN, "FRONT_OFFICE"),
            new DemoPerson("Anitha Sharma", "parent" + DEMO_DOMAIN, "PARENT"),
            new DemoPerson("Arjun Sharma", "student" + DEMO_DOMAIN, "STUDENT"));

    private final SchoolProvisioning provisioning;
    private final TenantDirectory tenants;
    private final UserService users;
    private final PasswordEncoder passwordEncoder;
    private final String password;
    private final AcademicsService academics;
    private final StudentService students;
    private final TransactionTemplate tx;
    private final AdmissionsService admissions;
    private final DemoAttendanceData attendanceData;
    private final DemoFeesData fees;
    private final DemoStaffData staffData;
    private final DemoCommunicationData communicationData;

    public DemoDataSeeder(SchoolProvisioning provisioning, TenantDirectory tenants, UserService users,
            PasswordEncoder passwordEncoder, @Value("${akshara.demo.password:}") String password,
            AcademicsService academics, StudentService students, PlatformTransactionManager transactionManager,
            AdmissionsService admissions, DemoAttendanceData attendanceData, DemoFeesData fees,
            DemoStaffData staffData, DemoCommunicationData communicationData) {
        this.provisioning = provisioning;
        this.tenants = tenants;
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.password = password;
        this.academics = academics;
        this.students = students;
        this.tx = new TransactionTemplate(transactionManager);
        this.admissions = admissions;
        this.attendanceData = attendanceData;
        this.fees = fees;
        this.staffData = staffData;
        this.communicationData = communicationData;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (password.length() < 10) {
            log.warn("Demo data is enabled but akshara.demo.password is missing or shorter than 10 characters; skipping");
            return;
        }
        if (tenants.findByCode(DEMO_CODE).isPresent()) {
            return;
        }
        Actor seeder = new Actor(null, "Demo data");
        var school = provisioning.provision(new SchoolProvisioning.NewSchool("Akshara Demo School", DEMO_CODE,
                Board.CBSE, "Hyderabad", Plan.GROWTH, "Priya Nair", "admin" + DEMO_DOMAIN, password),
                TenantStatus.TRIAL, Instant.now().plus(30, ChronoUnit.DAYS), seeder);
        String hash = passwordEncoder.encode(password);
        Map<String, UUID> userIds = new HashMap<>();
        TenantContext.runAs(school.tenant().id(), () -> {
            PEOPLE.forEach(p -> userIds.put(p.email(),
                    users.createUser(p.name(), p.email(), hash, List.of(p.role()), seeder).getId()));
            return null;
        });
        Integer studentCount = TenantContext.runAs(school.tenant().id(), () -> tx.execute(status ->
                new DemoSchoolData(academics, students, seeder).seed(userIds.get("teacher" + DEMO_DOMAIN),
                        "parent" + DEMO_DOMAIN, "student" + DEMO_DOMAIN)));
        log.info("Seeded demo school '{}' with {} people and {} students", DEMO_CODE, PEOPLE.size() + 1,
                studentCount);
        new DemoAdmissionsData(admissions, academics, tx).seed(school.tenant().id(),
                userIds.get("frontoffice" + DEMO_DOMAIN), userIds.get("principal" + DEMO_DOMAIN));
        log.info("Seeded {} demo attendance registers", attendanceData.seed(school.tenant().id(), userIds));
        TenantContext.runAs(school.tenant().id(), () -> tx.execute(status -> fees.seed(
                new Actor(userIds.get("accounts" + DEMO_DOMAIN), "Meena Reddy"))));
        log.info("Seeded {} demo staff profiles",
                staffData.seed(school.tenant().id(), userIds, school.adminUserId(), hash));
        log.info("Seeded {} demo circulars and the calendar", communicationData.seed(school.tenant().id(), userIds));
    }
}
