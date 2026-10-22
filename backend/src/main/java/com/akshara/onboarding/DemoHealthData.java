package com.akshara.onboarding;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.audit.AuditService.Actor;
import com.akshara.health.AllergySeverity;
import com.akshara.health.AuditArea;
import com.akshara.health.AuditSeverity;
import com.akshara.health.CommitteeRole;
import com.akshara.health.DrillKind;
import com.akshara.health.HealthForms.AuditForm;
import com.akshara.health.HealthForms.CloseForm;
import com.akshara.health.HealthForms.CommitteeForm;
import com.akshara.health.HealthForms.ConsentForm;
import com.akshara.health.HealthForms.DrillForm;
import com.akshara.health.HealthForms.GivenForm;
import com.akshara.health.HealthForms.GrowthForm;
import com.akshara.health.HealthForms.IncidentForm;
import com.akshara.health.HealthForms.ItemForm;
import com.akshara.health.HealthForms.KitCheckForm;
import com.akshara.health.HealthForms.KitForm;
import com.akshara.health.HealthForms.MedicineForm;
import com.akshara.health.HealthForms.MeetingForm;
import com.akshara.health.HealthForms.MemberForm;
import com.akshara.health.HealthForms.RecordForm;
import com.akshara.health.HealthForms.TrainingForm;
import com.akshara.health.HealthForms.VerificationForm;
import com.akshara.health.HealthForms.VisitForm;
import com.akshara.health.HealthText;
import com.akshara.health.HealthViews.KitView;
import com.akshara.health.HealthViews.MedicineView;
import com.akshara.health.HealthViews.VisitView;
import com.akshara.health.IncidentKind;
import com.akshara.health.IncidentSeverity;
import com.akshara.health.InfirmaryService;
import com.akshara.health.ItemKind;
import com.akshara.health.MedicalRecordService;
import com.akshara.health.PersonKind;
import com.akshara.health.PocsoService;
import com.akshara.health.SafetyService;
import com.akshara.health.StaffChecksService;
import com.akshara.payroll.PayStaffService;
import com.akshara.payroll.PayrollForms.PayProfileForm;
import com.akshara.payroll.PayrollForms.StructureForm;
import com.akshara.payroll.PayrollViews.ComponentView;
import com.akshara.payroll.SalaryComponentService;
import com.akshara.health.TrainingKind;
import com.akshara.health.VerificationStatus;
import com.akshara.health.VisitOutcome;
import com.akshara.shared.TenantContext;
import com.akshara.staff.DepartmentService;
import com.akshara.staff.EmploymentType;
import com.akshara.staff.StaffForms.CreateStaff;
import com.akshara.staff.StaffService;
import com.akshara.staff.StaffService.StaffQuery;
import com.akshara.staff.StaffService.StaffRow;
import com.akshara.staff.StaffStatus;
import com.akshara.students.StudentService;
import com.akshara.students.StudentService.StudentQuery;
import com.akshara.students.StudentService.StudentRow;
import com.akshara.students.StudentStatus;

/**
 * The demo school's health and safety: a nurse (Sister Mary Thomas, {@code nurse@demo.akshara.test}), medical
 * records for about ten students (Diya Sharma with a severe peanut allergy), a small medicine stock, yesterday's
 * infirmary visits (one sent home), a drill last month and an older one whose next drill is due, two open safety
 * findings, an incident, two first-aid kits, a POCSO committee of three with minutes, and a verification row for
 * every member of staff, two of them pending. Dates follow today.
 */
@Component
class DemoHealthData {

    private static final Logger log = LoggerFactory.getLogger(DemoHealthData.class);

    static final String NURSE_EMAIL = "nurse" + DemoDataSeeder.DEMO_DOMAIN;
    static final String NURSE_NAME = "Mary Thomas";

    private static final List<String> BLOOD_GROUPS = List.of("O+", "A+", "B+", "AB+", "O-", "A-", "B+", "O+", "A+",
            "B-");

    private final StaffService staff;
    private final DepartmentService departments;
    private final StudentService students;
    private final MedicalRecordService records;
    private final InfirmaryService infirmary;
    private final SafetyService safety;
    private final PocsoService pocso;
    private final StaffChecksService checks;
    private final SalaryComponentService components;
    private final PayStaffService payStaff;
    private final TransactionTemplate tx;

    DemoHealthData(StaffService staff, DepartmentService departments, StudentService students,
            MedicalRecordService records, InfirmaryService infirmary, SafetyService safety, PocsoService pocso,
            StaffChecksService checks, SalaryComponentService components, PayStaffService payStaff,
            PlatformTransactionManager transactionManager) {
        this.staff = staff;
        this.departments = departments;
        this.students = students;
        this.records = records;
        this.infirmary = infirmary;
        this.safety = safety;
        this.pocso = pocso;
        this.checks = checks;
        this.components = components;
        this.payStaff = payStaff;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Returns how many medical records were written. {@code passwordHash} is the demo password for the nurse. */
    int seed(UUID tenantId, Map<String, UUID> userIds, UUID adminId, String passwordHash) {
        Actor admin = new Actor(adminId, "Priya Nair");
        UUID nurseId = TenantContext.runAs(tenantId, () -> tx.execute(status -> nurse(admin, passwordHash)));
        Actor nurse = new Actor(nurseId, NURSE_NAME);
        Actor parent = new Actor(userIds.get("parent" + DemoDataSeeder.DEMO_DOMAIN), "Anitha Sharma");
        Actor accountant = new Actor(userIds.get("accounts" + DemoDataSeeder.DEMO_DOMAIN), "Meena Reddy");
        LocalDate today = HealthText.today();
        step(tenantId, "the nurse's salary", () -> salary(nurseId, accountant));

        int count = TenantContext.runAs(tenantId, () -> tx.execute(status -> medicalRecords(nurse, parent, today)));
        step(tenantId, "the medicine stock and yesterday's visits", () -> infirmary(nurse, today));
        step(tenantId, "the safety registers", () -> safetyRegisters(admin, today));
        step(tenantId, "the POCSO committee", () -> committee(admin, userIds, today));
        step(tenantId, "staff verifications and trainings", () -> staffChecks(admin, nurseId, userIds, today));
        return count;
    }

    private UUID nurse(Actor admin, String passwordHash) {
        UUID administration = departments.list().stream().filter(d -> d.name().equals("Administration"))
                .map(d -> d.id()).findFirst().orElse(null);
        return staff.create(new CreateStaff(NURSE_NAME, NURSE_EMAIL, null, List.of("NURSE"), "AKS-601",
                "School Nurse", administration, EmploymentType.PERMANENT, LocalDate.of(2021, 6, 1), "9848030601",
                "B.Sc. Nursing", null, null), passwordHash, admin).userId();
    }

    /** Like every other member of staff, the nurse has (fake) pay details and a salary structure. */
    private Object salary(UUID nurseId, Actor accountant) {
        Map<String, UUID> ids = components.list().stream()
                .collect(Collectors.toMap(ComponentView::code, ComponentView::id, (a, b) -> a));
        if (!ids.containsKey("BASIC")) {
            return null;
        }
        payStaff.saveProfile(nurseId, new PayProfileForm("000000100601", "AKSH0000001", null, "DEMOP0601A",
                "000000000601", "0000000601", true, false), accountant);
        payStaff.createStructure(nurseId, new StructureForm(YearMonth.now(HealthText.INDIA).toString(),
                "Demo salary", DemoPayrollData.lines(ids, 28_000L)), accountant);
        return null;
    }

    /** Records for the first ten active students; Diya Sharma's is the severe one, kept by her mother. */
    private int medicalRecords(Actor nurse, Actor parent, LocalDate today) {
        List<StudentRow> rows = students.list(new StudentQuery(null, null, null, StudentStatus.ACTIVE, null, 0,
                100)).items();
        List<StudentRow> chosen = new java.util.ArrayList<>(rows.stream()
                .filter(r -> "Sharma".equals(r.lastName())).toList());
        rows.stream().filter(r -> chosen.stream().noneMatch(c -> c.id().equals(r.id()))).limit(10 - chosen.size())
                .forEach(chosen::add);
        int i = 0;
        for (StudentRow s : chosen) {
            records.saveHead(s.id(), new RecordForm(BLOOD_GROUPS.get(i % BLOOD_GROUPS.size()), "Dr. Kavitha Rao",
                    "9848011122", i % 3 == 0 ? "Star Health" : null, i % 3 == 0 ? "SH-2026-00" + (1000 + i) : null,
                    null), nurse);
            records.addGrowth(s.id(), new GrowthForm(today.minusMonths(12), cm(110 + i * 3), kg(18 + i)), nurse);
            records.addGrowth(s.id(), new GrowthForm(today.minusMonths(6), cm(113 + i * 3), kg(19 + i)), nurse);
            records.addGrowth(s.id(), new GrowthForm(today.minusDays(7), cm(116 + i * 3), kg(21 + i)), nurse);
            records.addItem(s.id(), new ItemForm(ItemKind.VACCINATION, "MMR", "Second dose", null,
                    today.minusYears(3)), nurse);
            records.addItem(s.id(), new ItemForm(ItemKind.VACCINATION, "Typhoid", null, null,
                    today.minusYears(1).minusMonths(2)), nurse);
            if ("Diya".equals(s.firstName())) {
                records.childAddItem(parent.id(), s.id(), new ItemForm(ItemKind.ALLERGY, "Peanuts",
                        "Swelling and breathing difficulty; EpiPen in the school bag.", AllergySeverity.SEVERE, null),
                        parent);
                records.childConsent(parent.id(), s.id(), new ConsentForm(true, true), parent);
            } else if (i % 4 == 1) {
                records.addItem(s.id(), new ItemForm(ItemKind.ALLERGY, "Dust", "Sneezing", AllergySeverity.MILD,
                        null), nurse);
                records.saveConsent(s.id(), new ConsentForm(true, false), nurse);
            } else if (i % 4 == 2) {
                records.addItem(s.id(), new ItemForm(ItemKind.CONDITION, "Asthma", "Inhaler kept in the infirmary",
                        null, null), nurse);
                records.addItem(s.id(), new ItemForm(ItemKind.MEDICATION, "Salbutamol inhaler", "When wheezing",
                        null, null), nurse);
                records.saveConsent(s.id(), new ConsentForm(true, true), nurse);
            } else {
                records.saveConsent(s.id(), new ConsentForm(true, true), nurse);
            }
            i++;
        }
        return chosen.size();
    }

    private Object infirmary(Actor nurse, LocalDate today) {
        MedicineView paracetamol = infirmary.addMedicine(new MedicineForm("Paracetamol 250 mg", "tablet", 40, 20,
                today.plusMonths(14)), nurse);
        infirmary.addMedicine(new MedicineForm("ORS sachet", "sachet", 8, 10, today.plusMonths(8)), nurse);
        infirmary.addMedicine(new MedicineForm("Antiseptic liquid", "ml", 500, 100, today.plusYears(2)), nurse);
        infirmary.addMedicine(new MedicineForm("Adhesive bandages", "piece", 60, 20, null), nurse);
        List<StudentRow> rows = students.list(new StudentQuery(null, null, null, StudentStatus.ACTIVE, null, 0,
                100)).items();
        if (rows.size() < 3) {
            return null;
        }
        Instant yesterday = HealthText.startOfDay(today.minusDays(1)).plus(Duration.ofHours(10));
        VisitView first = infirmary.add(new VisitForm(PersonKind.STUDENT, rows.get(1).id(), null, yesterday,
                "Fever and headache", new BigDecimal("38.6"), "Rested; cold compress",
                List.of(new GivenForm(paracetamol.id(), 1))), nurse);
        infirmary.close(first.id(), new CloseForm(VisitOutcome.SENT_HOME, yesterday.plus(Duration.ofMinutes(50)),
                null, true), nurse);
        VisitView second = infirmary.add(new VisitForm(PersonKind.STUDENT, rows.get(2).id(), null,
                yesterday.plus(Duration.ofHours(2)), "Scraped knee in the playground", null,
                "Cleaned and dressed", List.of()), nurse);
        infirmary.close(second.id(), new CloseForm(VisitOutcome.BACK_TO_CLASS,
                yesterday.plus(Duration.ofHours(2)).plus(Duration.ofMinutes(20)), null, false), nurse);
        VisitView third = infirmary.add(new VisitForm(PersonKind.STUDENT, rows.get(4).id(), null,
                yesterday.plus(Duration.ofHours(4)), "Stomach ache", new BigDecimal("37.2"), "Warm water; rested",
                List.of()), nurse);
        infirmary.close(third.id(), new CloseForm(VisitOutcome.BACK_TO_CLASS,
                yesterday.plus(Duration.ofHours(4)).plus(Duration.ofMinutes(30)), null, false), nurse);
        return null;
    }

    private Object safetyRegisters(Actor admin, LocalDate today) {
        LocalDate lastMonth = today.minusMonths(1).withDayOfMonth(Math.min(15, today.minusMonths(1).lengthOfMonth()));
        safety.addDrill(new DrillForm(lastMonth, DrillKind.FIRE, "Main block", 212, 612,
                "Two classrooms on the first floor were slow to leave; signage to be added on the stairs.",
                lastMonth.plusMonths(3)), admin);
        LocalDate older = today.minusMonths(3).minusDays(10);
        safety.addDrill(new DrillForm(older, DrillKind.EVACUATION, "Primary block", 185, 240, null,
                today.plusDays(12)), admin);
        safety.addAudit(new AuditForm(AuditArea.ELECTRICAL, today.minusDays(20),
                "Loose wiring near the computer lab switchboard.", AuditSeverity.HIGH, today.plusDays(10)), admin);
        safety.addAudit(new AuditForm(AuditArea.PLAYGROUND, today.minusDays(20),
                "Swing chain rusted; needs replacement.", AuditSeverity.MEDIUM, today.minusDays(2)), admin);
        var water = safety.addAudit(new AuditForm(AuditArea.WATER, today.minusDays(45),
                "Water cooler filter overdue for change.", AuditSeverity.LOW, today.minusDays(30)), admin);
        safety.closeAudit(water.id(), new com.akshara.health.HealthForms.CloseAuditForm(today.minusDays(28),
                "Filter replaced by the vendor."), admin);
        safety.addIncident(new IncidentForm(HealthText.startOfDay(today.minusDays(1)).plus(Duration.ofHours(12)),
                IncidentKind.INJURY, IncidentSeverity.MINOR, null, null, "Playground",
                "A Class 3 student scraped a knee during the games period.",
                "First aid in the infirmary; class teacher informed the parent.", true), admin);
        KitView office = safety.addKit(new KitForm("Front office"), admin);
        safety.checkKit(office.id(), new KitCheckForm(today.minusDays(15), true, today.plusMonths(9), null), admin);
        KitView lab = safety.addKit(new KitForm("Science lab"), admin);
        safety.checkKit(lab.id(), new KitCheckForm(today.minusDays(100), false, today.plusDays(20),
                "Burn gel, sterile gauze"), admin);
        safety.addKit(new KitForm("Bus 1"), admin);
        return null;
    }

    private Object committee(Actor admin, Map<String, UUID> userIds, LocalDate today) {
        LocalDate formed = today.minusYears(1).withDayOfMonth(1);
        pocso.saveCommittee(new CommitteeForm(formed), admin);
        UUID principal = userIds.get("principal" + DemoDataSeeder.DEMO_DOMAIN);
        UUID teacher = userIds.get("teacher" + DemoDataSeeder.DEMO_DOMAIN);
        UUID office = userIds.get("frontoffice" + DemoDataSeeder.DEMO_DOMAIN);
        if (principal != null) {
            pocso.addMember(new MemberForm(principal, CommitteeRole.CHAIRPERSON, formed), admin);
        }
        if (teacher != null) {
            pocso.addMember(new MemberForm(teacher, CommitteeRole.MEMBER, formed), admin);
        }
        if (office != null) {
            pocso.addMember(new MemberForm(office, CommitteeRole.SECRETARY, formed), admin);
        }
        pocso.addMeeting(new MeetingForm(today.minusMonths(2).withDayOfMonth(5),
                "Reviewed the safeguarding notice on every floor. Decided to hold POCSO awareness sessions for all "
                        + "staff before the next term. No complaints pending."), admin);
        return null;
    }

    private Object staffChecks(Actor admin, UUID nurseId, Map<String, UUID> userIds, LocalDate today) {
        List<StaffRow> rows = staff.list(new StaffQuery(null, null, null, StaffStatus.ACTIVE, null, null, 0, 100))
                .items();
        int i = 0;
        for (StaffRow s : rows) {
            boolean pending = s.email().startsWith("security@") || s.userId().equals(nurseId);
            if (pending) {
                checks.saveVerification(s.userId(), new VerificationForm(VerificationStatus.REQUESTED,
                        today.minusDays(20), null, null, null, "Sent to the district police on paper."), admin);
            } else {
                LocalDate cleared = today.minusMonths(6 + (i % 18));
                checks.saveVerification(s.userId(), new VerificationForm(VerificationStatus.CLEARED,
                        cleared.minusDays(45), cleared, "PV/HYD/2025/" + (4100 + i), cleared.plusYears(2), null),
                        admin);
            }
            i++;
        }
        UUID teacher = userIds.get("teacher" + DemoDataSeeder.DEMO_DOMAIN);
        UUID principal = userIds.get("principal" + DemoDataSeeder.DEMO_DOMAIN);
        if (teacher != null) {
            checks.addTraining(new TrainingForm(teacher, TrainingKind.POCSO, today.minusMonths(11),
                    today.plusDays(25), "Childline India"), admin);
            checks.addTraining(new TrainingForm(teacher, TrainingKind.FIRST_AID, today.minusMonths(4),
                    today.plusMonths(20), "St John Ambulance"), admin);
        }
        if (principal != null) {
            checks.addTraining(new TrainingForm(principal, TrainingKind.POCSO, today.minusMonths(2),
                    today.plusMonths(22), "Childline India"), admin);
        }
        checks.addTraining(new TrainingForm(nurseId, TrainingKind.FIRST_AID, today.minusMonths(1),
                today.plusMonths(23), "Indian Red Cross"), admin);
        return null;
    }

    private static BigDecimal cm(int value) {
        return new BigDecimal(value).setScale(1);
    }

    private static BigDecimal kg(int value) {
        return new BigDecimal(value).setScale(1);
    }

    private boolean step(UUID tenantId, String what, Supplier<Object> work) {
        try {
            TenantContext.runAs(tenantId, () -> tx.execute(status -> work.get()));
            return true;
        } catch (RuntimeException e) {
            log.warn("Skipped demo health data for {}: {}", what, e.getMessage());
            return false;
        }
    }
}
