package com.akshara.govreport;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.academics.AcademicsService;
import com.akshara.academics.AcademicsService.ClassView;
import com.akshara.admissions.AdmissionsService;
import com.akshara.admissions.AdmissionsService.ApplicationDetail;
import com.akshara.admissions.AdmissionsService.ApplicationQuery;
import com.akshara.admissions.AdmissionsService.ApplicationRow;
import com.akshara.admissions.AdmissionsService.GuardianView;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.govreport.GovReportForms.IntakeForm;
import com.akshara.govreport.GovReportForms.LotteryForm;
import com.akshara.govreport.GovReportForms.ScreeningForm;
import com.akshara.govreport.GovReportTypes.CodeListType;
import com.akshara.govreport.GovReportTypes.Eligibility;
import com.akshara.govreport.GovReportTypes.IntakeStatus;
import com.akshara.govreport.GovReportViews.AllotmentView;
import com.akshara.govreport.GovReportViews.ApplicationPick;
import com.akshara.govreport.GovReportViews.IntakeDetail;
import com.akshara.govreport.GovReportViews.IntakeView;
import com.akshara.govreport.GovReportViews.RteApplicationView;
import com.akshara.govreport.GovReportViews.RunDetail;
import com.akshara.govreport.GovReportViews.RunSummary;
import com.akshara.platform.TenantDirectory;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The RTE 25% admissions: an intake per academic year and entry class, applications marked RTE (admissions
 * applications read through the admissions module's public views), eligibility screening, and the lottery with a
 * recorded seed, timestamp and witness. A run is immutable; a rerun is a new run with a reason.
 */
@Service
@Transactional
public class RteService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final RteIntakeRepository intakes;
    private final RteApplicationRepository applications;
    private final RteLotteryRunRepository runs;
    private final RteAllotmentRepository allotments;
    private final AdmissionsService admissions;
    private final AcademicsDirectory academics;
    private final AcademicsService academicsService;
    private final CodeListService codes;
    private final TenantDirectory tenants;
    private final AuditService audit;
    private final JsonMapper json;

    RteService(RteIntakeRepository intakes, RteApplicationRepository applications, RteLotteryRunRepository runs,
            RteAllotmentRepository allotments, AdmissionsService admissions, AcademicsDirectory academics,
            AcademicsService academicsService, CodeListService codes, TenantDirectory tenants, AuditService audit,
            JsonMapper json) {
        this.intakes = intakes;
        this.applications = applications;
        this.runs = runs;
        this.allotments = allotments;
        this.admissions = admissions;
        this.academics = academics;
        this.academicsService = academicsService;
        this.codes = codes;
        this.tenants = tenants;
        this.audit = audit;
        this.json = json;
    }

    // ------------------------------------------------------------------ intakes

    @Transactional
    public List<IntakeView> intakes(UUID yearId) {
        TenantContext.require();
        List<RteIntake> list = yearId == null ? intakes.findAllByOrderByCreatedAtDesc()
                : intakes.findByAcademicYearIdOrderByCreatedAtDesc(yearId);
        return list.stream().map(this::view).toList();
    }

    public IntakeView create(IntakeForm form, Actor actor) {
        TenantContext.require();
        YearInfo year = academics.year(form.academicYearId())
                .orElseThrow(() -> ApiException.badRequest("Choose an academic year.", "academicYearId"));
        ClassView entry = classView(form.entryClassId());
        RteIntake intake = new RteIntake(year.id(), entry.id(), actor);
        apply(intake, form);
        try {
            intakes.saveAndFlush(intake);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("This class already has an RTE intake for " + year.name() + ".",
                    "entryClassId");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("academicYear", year.name());
        details.put("entryClass", entry.name());
        details.put("totalSeats", intake.getTotalSeats());
        details.put("rteSeats", intake.getRteSeats());
        details.put("incomeLimitPaise", intake.getIncomeLimitPaise());
        audit.record(actor, "rte_intake.created", "rte_intake", intake.getId(), details);
        return view(intake);
    }

    public IntakeView update(UUID id, IntakeForm form, Actor actor) {
        RteIntake intake = find(id);
        if (intake.getStatus() != IntakeStatus.OPEN) {
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "Lottery done",
                    "The intake cannot change after its lottery has run.");
        }
        apply(intake, form);
        intakes.saveAndFlush(intake);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("totalSeats", intake.getTotalSeats());
        details.put("rteSeats", intake.getRteSeats());
        details.put("incomeLimitPaise", intake.getIncomeLimitPaise());
        audit.record(actor, "rte_intake.updated", "rte_intake", intake.getId(), details);
        return view(intake);
    }

    public IntakeView close(UUID id, Actor actor) {
        RteIntake intake = find(id);
        intake.close();
        intakes.saveAndFlush(intake);
        audit.record(actor, "rte_intake.closed", "rte_intake", intake.getId(), Map.of());
        return view(intake);
    }

    private void apply(RteIntake intake, IntakeForm form) {
        int seats = form.totalSeats();
        int rteSeats = form.rteSeats() != null ? form.rteSeats() : (int) Math.ceil(seats * 0.25);
        if (rteSeats > seats) {
            throw ApiException.badRequest("RTE seats cannot be more than the seats.", "rteSeats");
        }
        String state = GovReportAuth.blankToNull(form.stateCode());
        state = state == null ? null : state.toUpperCase(java.util.Locale.ROOT);
        Long limit = form.incomeLimitPaise();
        if (limit == null) {
            limit = codes.incomeLimit(state, GovReportAuth.today()).orElseThrow(() -> ApiException.badRequest(
                    "Give the income limit, or choose a state that has one in the code list.", "incomeLimitPaise"));
        }
        BigDecimal b1 = form.distanceBand1Km() != null ? form.distanceBand1Km() : BigDecimal.ONE;
        BigDecimal b2 = form.distanceBand2Km() != null ? form.distanceBand2Km() : BigDecimal.valueOf(3);
        BigDecimal b3 = form.distanceBand3Km() != null ? form.distanceBand3Km() : BigDecimal.valueOf(6);
        if (b2.compareTo(b1) < 0 || b3.compareTo(b2) < 0) {
            throw ApiException.badRequest("The distance bands must grow: first, second, third.", "distanceBand2Km");
        }
        List<String> documents = form.documents() != null ? form.documents().stream().map(String::trim)
                .map(s -> s.toUpperCase(java.util.Locale.ROOT)).distinct().toList()
                : new ArrayList<>(codes.inForce(CodeListType.RTE_DOCUMENT, GovReportAuth.today()).keySet());
        intake.update(seats, rteSeats, state, limit, b1, b2, b3, json.writeValueAsString(documents),
                form.applicationsCloseOn());
    }

    private ClassView classView(UUID classId) {
        return academicsService.classes().stream().filter(c -> c.id().equals(classId)).findFirst()
                .orElseThrow(() -> ApiException.badRequest("Choose an entry class.", "entryClassId"));
    }

    RteIntake find(UUID id) {
        TenantContext.require();
        return intakes.findById(id).orElseThrow(() -> ApiException.notFound("RTE intake"));
    }

    @Transactional
    public IntakeDetail detail(UUID id) {
        RteIntake intake = find(id);
        List<RteApplication> apps = applications.findByIntakeIdOrderByCreatedAtAsc(intake.getId());
        List<RteApplicationView> views = apps.stream().map(a -> view(intake, a)).toList();
        List<RunSummary> runViews = runs.findByIntakeIdOrderByRunNoDesc(intake.getId()).stream()
                .map(r -> runSummary(r, intake)).toList();
        return new IntakeDetail(view(intake), views, runViews);
    }

    /** The open admissions applications for the intake's year and class, with whether each is already in it. */
    @Transactional
    public List<ApplicationPick> candidates(UUID id) {
        RteIntake intake = find(id);
        java.util.Set<UUID> inIntake = applications.findByIntakeIdOrderByCreatedAtAsc(intake.getId()).stream()
                .map(RteApplication::getApplicationId).collect(Collectors.toSet());
        List<ApplicationPick> picks = new ArrayList<>();
        for (int page = 0;; page++) {
            List<ApplicationRow> rows = admissions.list(new ApplicationQuery(intake.getAcademicYearId(),
                    intake.getEntryClassId(), null, null, null, page, AdmissionsService.MAX_PAGE_SIZE)).items();
            rows.stream().filter(r -> r.stage().isOpen() || inIntake.contains(r.id()))
                    .forEach(r -> picks.add(new ApplicationPick(r.id(), r.childName(), r.className(),
                            r.stage().name(), inIntake.contains(r.id()))));
            if (rows.size() < AdmissionsService.MAX_PAGE_SIZE) {
                return picks;
            }
        }
    }

    // ------------------------------------------------------------------ applications

    public RteApplicationView addApplication(UUID intakeId, UUID applicationId, Actor actor) {
        RteIntake intake = find(intakeId);
        requireOpen(intake);
        ApplicationDetail detail;
        try {
            detail = admissions.detail(applicationId);
        } catch (ApiException e) {
            throw ApiException.badRequest("That application is not in this school.", "applicationId");
        }
        if (!detail.academicYearId().equals(intake.getAcademicYearId())
                || !detail.classId().equals(intake.getEntryClassId())) {
            throw ApiException.badRequest("The application is for another year or class.", "applicationId");
        }
        if (applications.findByIntakeIdAndApplicationId(intake.getId(), applicationId).isPresent()) {
            throw ApiException.conflict("That application is already in the intake.", "applicationId");
        }
        RteApplication a = new RteApplication(intake.getId(), applicationId);
        applications.saveAndFlush(a);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("intakeId", intake.getId().toString());
        details.put("applicationId", applicationId.toString());
        audit.record(actor, "rte_application.added", "rte_application", a.getId(), details);
        return view(intake, a);
    }

    public RteApplicationView screen(UUID id, ScreeningForm form, Actor actor) {
        RteApplication a = findApplication(id);
        RteIntake intake = find(a.getIntakeId());
        requireOpen(intake);
        if (form.eligibility() == Eligibility.INELIGIBLE && GovReportAuth.blankToNull(form.ineligibleReason())
                == null) {
            throw ApiException.badRequest("Say why the application is not eligible.", "ineligibleReason");
        }
        if (form.eligibility() == Eligibility.ELIGIBLE) {
            if (!form.incomeDeclared()) {
                throw ApiException.badRequest("An eligible application needs the income declaration.",
                        "incomeDeclared");
            }
            if (form.annualIncomePaise() != null && form.annualIncomePaise() > intake.getIncomeLimitPaise()) {
                throw ApiException.badRequest("The declared income is above the intake's limit.",
                        "annualIncomePaise");
            }
        }
        String category = GovReportAuth.blankToNull(form.categoryCode());
        Map<String, Boolean> documents = form.documents() == null ? Map.of() : form.documents();
        a.screen(category == null ? null : category.toUpperCase(java.util.Locale.ROOT), form.annualIncomePaise(),
                form.incomeDeclared(), form.distanceKm(), json.writeValueAsString(documents), form.eligibility(),
                GovReportAuth.blankToNull(form.ineligibleReason()), actor);
        applications.saveAndFlush(a);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("eligibility", form.eligibility().name());
        details.put("distanceBand", intake.distanceBand(a.getDistanceKm()));
        details.put("incomeDeclared", form.incomeDeclared());
        audit.record(actor, "rte_application.screened", "rte_application", a.getId(), details);
        return view(intake, a);
    }

    public RteApplicationView withdraw(UUID id, Actor actor) {
        RteApplication a = findApplication(id);
        RteIntake intake = find(a.getIntakeId());
        requireOpen(intake);
        a.withdraw();
        applications.saveAndFlush(a);
        audit.record(actor, "rte_application.withdrawn", "rte_application", a.getId(), Map.of());
        return view(intake, a);
    }

    private RteApplication findApplication(UUID id) {
        TenantContext.require();
        return applications.findById(id).orElseThrow(() -> ApiException.notFound("RTE application"));
    }

    private static void requireOpen(RteIntake intake) {
        if (intake.getStatus() != IntakeStatus.OPEN) {
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "Lottery done",
                    "Applications cannot change after the lottery has run. Rerun the lottery with a reason instead.");
        }
    }

    // ------------------------------------------------------------------ the lottery

    /**
     * Runs the lottery over the eligible, not withdrawn applications. The first run needs no reason; any later run
     * does. The seed is random unless given (for a reproducible rerun). Everything is audited.
     */
    public RunDetail runLottery(UUID intakeId, LotteryForm form, Actor actor) {
        RteIntake intake = intakes.lockById(intakeId).orElseThrow(() -> ApiException.notFound("RTE intake"));
        if (intake.getStatus() == IntakeStatus.CLOSED) {
            throw new ApiException(org.springframework.http.HttpStatus.CONFLICT, "Intake closed",
                    "The intake is closed.");
        }
        int runNo = runs.countByIntakeId(intake.getId()) + 1;
        String reason = GovReportAuth.blankToNull(form.reason());
        if (runNo > 1 && reason == null) {
            throw ApiException.badRequest("Give the reason for running the lottery again.", "reason");
        }
        List<RteApplication> eligible = applications.findByIntakeIdOrderByCreatedAtAsc(intake.getId()).stream()
                .filter(a -> a.getEligibility() == Eligibility.ELIGIBLE && !a.isWithdrawn()).toList();
        if (eligible.isEmpty()) {
            throw ApiException.badRequest("Screen at least one eligible application first.", "witnessName");
        }
        long seed = form.seed() != null ? form.seed() : RANDOM.nextLong();
        Instant now = Instant.now();
        RteLotteryRun run = new RteLotteryRun(intake.getId(), runNo, seed, intake.getRteSeats(), eligible.size(),
                form.witnessName().trim(), reason, actor, now);
        runs.saveAndFlush(run);
        List<Lottery.Candidate> candidates = eligible.stream()
                .map(a -> new Lottery.Candidate(a.getId(), intake.distanceBand(a.getDistanceKm()))).toList();
        List<RteAllotment> lines = Lottery.draw(candidates, intake.getRteSeats(), seed).stream()
                .map(d -> new RteAllotment(run.getId(), d.id(), d.position(), d.outcome(), d.waitlistNo(),
                        d.distanceBand(), now))
                .toList();
        allotments.saveAll(lines);
        allotments.flush();
        intake.lotteryDone(run.getId());
        intakes.saveAndFlush(intake);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("intakeId", intake.getId().toString());
        details.put("runNo", runNo);
        details.put("seed", seed);
        details.put("seats", intake.getRteSeats());
        details.put("applicants", eligible.size());
        details.put("witness", run.getWitnessName());
        details.put("reason", reason);
        audit.record(actor, "rte_lottery.run", "rte_lottery_run", run.getId(), details);
        return runDetail(run, intake);
    }

    @Transactional
    public RunDetail run(UUID runId) {
        TenantContext.require();
        RteLotteryRun run = runs.findById(runId).orElseThrow(() -> ApiException.notFound("Lottery run"));
        return runDetail(run, find(run.getIntakeId()));
    }

    private RunDetail runDetail(RteLotteryRun run, RteIntake intake) {
        Map<UUID, RteApplication> apps = applications.findByIntakeIdOrderByCreatedAtAsc(intake.getId()).stream()
                .collect(Collectors.toMap(RteApplication::getId, Function.identity()));
        List<AllotmentView> lines = allotments.findByRunIdOrderByPositionAsc(run.getId()).stream()
                .map(a -> new AllotmentView(a.getPosition(), a.getOutcome(), a.getWaitlistNo(), a.getDistanceBand(),
                        view(intake, apps.get(a.getRteApplicationId()))))
                .toList();
        String school = tenants.profile(TenantContext.require()).map(p -> p.name()).orElse(null);
        return new RunDetail(runSummary(run, intake), view(intake), school, lines);
    }

    // ------------------------------------------------------------------ views

    IntakeView view(RteIntake i) {
        String yearName = academics.year(i.getAcademicYearId()).map(YearInfo::name).orElse(null);
        String className = academicsService.classes().stream().filter(c -> c.id().equals(i.getEntryClassId()))
                .map(ClassView::name).findFirst().orElse(null);
        List<RteApplication> apps = applications.findByIntakeIdOrderByCreatedAtAsc(i.getId());
        long applicants = apps.stream().filter(a -> !a.isWithdrawn()).count();
        long eligible = apps.stream().filter(a -> !a.isWithdrawn() && a.getEligibility() == Eligibility.ELIGIBLE)
                .count();
        List<String> documents = json.readValue(i.getDocuments(), new TypeReference<List<String>>() { });
        return new IntakeView(i.getId(), i.getAcademicYearId(), yearName, i.getEntryClassId(), className,
                i.getTotalSeats(), i.getRteSeats(), i.getStateCode(), i.getIncomeLimitPaise(),
                i.getDistanceBand1Km(), i.getDistanceBand2Km(), i.getDistanceBand3Km(), documents,
                i.getApplicationsCloseOn(), i.getStatus(), i.getCurrentRunId(), applicants, eligible,
                runs.countByIntakeId(i.getId()), i.getCreatedByName(), i.getCreatedAt());
    }

    RteApplicationView view(RteIntake intake, RteApplication a) {
        ApplicationDetail d;
        try {
            d = admissions.detail(a.getApplicationId());
        } catch (ApiException e) {
            d = null;
        }
        GuardianView g = d == null ? null : d.guardians().stream().filter(GuardianView::primary).findFirst()
                .orElse(d.guardians().isEmpty() ? null : d.guardians().getFirst());
        Map<String, Boolean> documents = json.readValue(a.getDocuments(), new TypeReference<Map<String, Boolean>>() {
        });
        return new RteApplicationView(a.getId(), a.getApplicationId(), d == null ? "—" : d.childName(),
                d == null ? null : d.dateOfBirth(), d == null ? null : d.gender(), g == null ? null : g.name(),
                g == null ? null : maskPhone(g.phone()), d == null ? null : d.stage().name(), a.getCategoryCode(),
                a.getAnnualIncomePaise(), a.isIncomeDeclared(), a.getDistanceKm(),
                intake.distanceBand(a.getDistanceKm()), documents, a.getEligibility(), a.getIneligibleReason(),
                a.getScreenedByName(), a.getScreenedAt(), a.isWithdrawn(), a.getCreatedAt());
    }

    private RunSummary runSummary(RteLotteryRun r, RteIntake intake) {
        return new RunSummary(r.getId(), r.getRunNo(), r.getSeed(), r.getSeats(), r.getApplicants(),
                r.getWitnessName(), r.getReason(), r.getRanByName(), r.getRanAt(),
                r.getId().equals(intake.getCurrentRunId()));
    }

    /** "9876543210" becomes "XXXXXX3210": lists never show a family's full number. */
    static String maskPhone(String phone) {
        if (phone == null) {
            return null;
        }
        String digits = phone.replaceAll("\\D", "");
        if (digits.length() <= 4) {
            return "X".repeat(digits.length());
        }
        return "X".repeat(digits.length() - 4) + digits.substring(digits.length() - 4);
    }

    /** The intakes of a year with their allotted and waitlisted counts (the reports). */
    @Transactional
    public long[] outcomeCounts(UUID intakeId) {
        RteIntake intake = find(intakeId);
        if (intake.getCurrentRunId() == null) {
            return new long[] {0, 0};
        }
        List<RteAllotment> lines = allotments.findByRunIdOrderByPositionAsc(intake.getCurrentRunId());
        long allotted = lines.stream().filter(a -> a.getOutcome() == GovReportTypes.AllotmentOutcome.ALLOTTED)
                .count();
        return new long[] {allotted, lines.size() - allotted};
    }

    static LocalDate today() {
        return GovReportAuth.today();
    }
}
