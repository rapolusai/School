package com.akshara.onboarding;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.academics.AcademicsService;
import com.akshara.academics.AcademicsService.ClassView;
import com.akshara.admissions.AdmissionForms.ApplicationRequest;
import com.akshara.admissions.AdmissionForms.GuardianInput;
import com.akshara.admissions.AdmissionsService;
import com.akshara.admissions.ApplicationSource;
import com.akshara.admissions.ApplicationStage;
import com.akshara.audit.AuditService.Actor;
import com.akshara.govreport.GovReportForms.IntakeForm;
import com.akshara.govreport.GovReportForms.LotteryForm;
import com.akshara.govreport.GovReportForms.ProfileForm;
import com.akshara.govreport.GovReportForms.SchemeForm;
import com.akshara.govreport.GovReportForms.ScreeningForm;
import com.akshara.govreport.GovReportForms.StudentFieldsForm;
import com.akshara.govreport.GovReportTypes.Eligibility;
import com.akshara.govreport.GovReportTypes.SchemeStatus;
import com.akshara.govreport.GovReportTypes.UpdatedVia;
import com.akshara.govreport.GovReportViews.IntakeView;
import com.akshara.govreport.RteService;
import com.akshara.govreport.SchemeService;
import com.akshara.govreport.SchoolProfileService;
import com.akshara.govreport.StudentFieldsService;
import com.akshara.shared.TenantContext;
import com.akshara.students.Gender;
import com.akshara.students.GuardianRelation;
import com.akshara.students.StudentService;
import com.akshara.students.StudentService.StudentQuery;
import com.akshara.students.StudentService.StudentRow;
import com.akshara.students.StudentStatus;

/**
 * The demo school's government reporting: the UDISE+ profile for the current year, government fields for every
 * student (a few left incomplete so the completeness report has something to show), an RTE intake for Class 1 with
 * eight applications and one lottery run, and two scheme enrolments. All values are invented; no APAAR ID here
 * belongs to a real child.
 */
@Component
class DemoGovReportData {

    private static final Logger log = LoggerFactory.getLogger(DemoGovReportData.class);

    static final String RTE_CLASS = "Class 1";
    /** The RTE applications the demo adds to admissions (at the Application stage). */
    static final int RTE_APPLICATIONS = 8;
    /** The students whose fields are left incomplete on purpose (no APAAR ID or PEN yet). */
    static final List<String> INCOMPLETE = List.of("Advik Kulkarni", "Kiara Desai", "Tanvi Deshpande");

    private static final String[] CATEGORIES = {"GEN", "OBC", "SC", "GEN", "ST", "OBC", "GEN"};
    private static final String[] RELIGIONS = {"HINDU", "HINDU", "MUSLIM", "CHRISTIAN", "HINDU", "SIKH"};
    private static final String[] TONGUES = {"TELUGU", "HINDI", "TELUGU", "URDU", "TAMIL", "ENGLISH", "KANNADA"};
    private static final String[][] RTE_FAMILIES = {
        {"Aarav", "Naik", "M", "Pradeep Naik", "SC", "0.8"}, {"Meher", "Shaik", "F", "Fatima Shaik", "OBC", "1.6"},
        {"Ritvik", "Boddu", "M", "Srinivas Boddu", "ST", "0.5"}, {"Anvi", "Mudiraj", "F", "Lalitha Mudiraj", "OBC", "2.4"},
        {"Charan", "Pasha", "M", "Mohammed Pasha", "GEN", "4.5"}, {"Lasya", "Gowd", "F", "Padma Gowd", "SC", "0.9"},
        {"Yuvan", "Madiga", "M", "Ramulu Madiga", "SC", "2.9"}, {"Nitya", "Erukala", "F", "Suvarna Erukala", "ST", "7.5"},
    };

    private final SchoolProfileService profiles;
    private final StudentFieldsService fields;
    private final RteService rte;
    private final SchemeService schemes;
    private final AdmissionsService admissions;
    private final AcademicsDirectory academics;
    private final AcademicsService academicsService;
    private final StudentService students;
    private final TransactionTemplate tx;

    DemoGovReportData(SchoolProfileService profiles, StudentFieldsService fields, RteService rte,
            SchemeService schemes, AdmissionsService admissions, AcademicsDirectory academics,
            AcademicsService academicsService, StudentService students, TransactionTemplate tx) {
        this.profiles = profiles;
        this.fields = fields;
        this.rte = rte;
        this.schemes = schemes;
        this.admissions = admissions;
        this.academics = academics;
        this.academicsService = academicsService;
        this.students = students;
        this.tx = tx;
    }

    /** Returns how many students got their government fields. */
    int seed(UUID tenantId, Map<String, UUID> userIds) {
        Actor admin = new Actor(userIds.get("frontoffice" + DemoDataSeeder.DEMO_DOMAIN), "Suresh Rao");
        Actor principal = new Actor(userIds.get("principal" + DemoDataSeeder.DEMO_DOMAIN), "Lakshmi Iyer");
        YearInfo year = step(tenantId, "the year", () -> academics.currentYear().orElseThrow());
        if (year == null) {
            return 0;
        }
        step(tenantId, "the school profile", () -> profiles.save(year.id(), new ProfileForm("36011200912",
                "CBSE", "3630412", LocalDate.of(2030, 3, 31), "CAT3", "PVT_UNAIDED", "3601", "3601", "TS",
                "Directorate of School Education, Telangana", "DSE/HYD/2019/1182", LocalDate.of(2029, 3, 31), "LKG",
                "Class 10", "English", 24, 6, 8, 8, 2, true, "Municipal supply", true, true, 2400, true, 30, true,
                true, true), principal));
        Integer saved = step(tenantId, "students' fields", () -> fields.save(studentForms(), admin,
                UpdatedVia.OFFICE).saved());
        step(tenantId, "the RTE intake", () -> rteIntake(year, admin, principal));
        step(tenantId, "scheme enrolments", () -> {
            List<StudentRow> all = pupils();
            enrol(all, "Sneha Iyengar", year.id(), "NMMS", SchemeStatus.APPROVED, 1_200_000L, "NMMS/TS/2026/4471",
                    admin);
            enrol(all, "Nandini Reddy", year.id(), "PRE_MATRIC_SC", SchemeStatus.APPLIED, 350_000L, null, admin);
            return null;
        });
        return saved == null ? 0 : saved;
    }

    private List<StudentFieldsForm> studentForms() {
        List<StudentFieldsForm> forms = new ArrayList<>();
        int i = 0;
        for (StudentRow s : pupils()) {
            boolean incomplete = INCOMPLETE.contains(s.fullName());
            String category = CATEGORIES[i % CATEGORIES.length];
            forms.add(new StudentFieldsForm(s.id(), incomplete ? null : String.format("%012d", 100_000_000_000L + i),
                    !incomplete, incomplete ? null : "TS26" + String.format("%06d", 4000 + i), category,
                    RELIGIONS[i % RELIGIONS.length], TONGUES[i % TONGUES.length], i % 9 == 0,
                    i % 23 == 0 ? "LOW_VISION" : null, i % 11 == 0, i % 13 == 0 ? "36011200451" : null));
            i++;
        }
        return forms;
    }

    private IntakeView rteIntake(YearInfo year, Actor office, Actor principal) {
        ClassView entry = academicsService.classes().stream().filter(c -> c.name().equals(RTE_CLASS)).findFirst()
                .orElseThrow(() -> new IllegalStateException("No " + RTE_CLASS));
        IntakeView intake = rte.create(new IntakeForm(year.id(), entry.id(), 40, 10, "TS", null, null, null, null,
                null, year.startsOn().minusMonths(2)), office);
        int phone = 3001;
        for (String[] f : RTE_FAMILIES) {
            Gender gender = f[2].equals("F") ? Gender.FEMALE : Gender.MALE;
            UUID applicationId = admissions.create(new ApplicationRequest(ApplicationStage.APPLICATION, f[0], f[1],
                    LocalDate.of(year.startsOn().getYear() - 6, 1 + phone % 12, 1 + phone % 27), gender, null,
                    entry.id(), year.id(), ApplicationSource.WALK_IN, null, null, "RTE 25% application.",
                    List.of(new GuardianInput(f[3], gender == Gender.FEMALE ? GuardianRelation.MOTHER
                            : GuardianRelation.FATHER, "98760" + String.format("%05d", phone++), null, true))),
                    office).id();
            UUID rteId = rte.addApplication(intake.id(), applicationId, office).id();
            boolean eligible = !f[0].equals("Charan");
            rte.screen(rteId, new ScreeningForm(f[4], eligible ? 9_000_000L : 15_000_000L, true,
                    new BigDecimal(f[5]), Map.of("BIRTH_CERT", true, "ADDRESS_PROOF", true, "INCOME_CERT", eligible),
                    eligible ? Eligibility.ELIGIBLE : Eligibility.INELIGIBLE,
                    eligible ? null : "Declared income is above the limit."), office);
        }
        rte.runLottery(intake.id(), new LotteryForm("Smt. Lakshmi Iyer, Principal (in the presence of the SMC)",
                null, 20260401L), principal);
        return intake;
    }

    private void enrol(List<StudentRow> all, String name, UUID yearId, String scheme, SchemeStatus status,
            Long amountPaise, String reference, Actor actor) {
        all.stream().filter(s -> s.fullName().equals(name)).findFirst().ifPresent(s -> schemes.add(
                new SchemeForm(s.id(), yearId, scheme, status, amountPaise, reference,
                        LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")).minusDays(20), null), actor));
    }

    private List<StudentRow> pupils() {
        List<StudentRow> all = new ArrayList<>();
        for (int page = 0;; page++) {
            List<StudentRow> items = students.list(new StudentQuery(null, null, null, StudentStatus.ACTIVE, null,
                    page, StudentService.MAX_PAGE_SIZE)).items();
            all.addAll(items);
            if (items.size() < StudentService.MAX_PAGE_SIZE) {
                return all;
            }
        }
    }

    private <T> T step(UUID tenantId, String what, Supplier<T> work) {
        try {
            return TenantContext.runAs(tenantId, () -> tx.execute(status -> work.get()));
        } catch (RuntimeException e) {
            log.info("Skipped demo government reporting step '{}' ({})", what, e.getMessage());
            return null;
        }
    }
}
