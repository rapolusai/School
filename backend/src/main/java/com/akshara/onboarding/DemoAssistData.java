package com.akshara.onboarding;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.SectionInfo;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.academics.AcademicsService;
import com.akshara.academics.AcademicsService.ClassView;
import com.akshara.academics.AcademicsService.SubjectRef;
import com.akshara.assist.AssistForms.DraftRemarksForm;
import com.akshara.assist.AssistForms.PaperDraftForm;
import com.akshara.assist.AssistForms.SettingsForm;
import com.akshara.assist.AssistForms.WeightsForm;
import com.akshara.assist.AssistSettingsService;
import com.akshara.assist.AssistViews.Blueprint;
import com.akshara.assist.AssistViews.BlueprintSection;
import com.akshara.assist.AssistViews.RemarkDraftView;
import com.akshara.assist.PaperService;
import com.akshara.assist.RemarkService;
import com.akshara.assist.RemarkTone;
import com.akshara.assist.RiskService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.exams.ExamSetupService;
import com.akshara.exams.ExamsViews.TermView;
import com.akshara.shared.JwtService;
import com.akshara.shared.Permissions;
import com.akshara.shared.TenantContext;

/**
 * The demo school's assistance: switched on (simulated provider), the watch list computed from the demo attendance,
 * marks, fees and homework, three accepted remark drafts for Class 5 A in Term 1 (saved to their report cards) and one
 * saved draft Mathematics paper from the Class 5 question bank. Runs after the exams and lessons demo data.
 */
@Component
class DemoAssistData {

    private static final Logger log = LoggerFactory.getLogger(DemoAssistData.class);
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final List<String> TEACHER = List.of(Permissions.ASSIST_USE, Permissions.EXAMS_READ,
            Permissions.EXAMS_PUBLISH, Permissions.LESSONS_READ);

    private final AssistSettingsService settings;
    private final RemarkService remarks;
    private final RiskService risk;
    private final PaperService papers;
    private final AcademicsDirectory academics;
    private final AcademicsService academicsService;
    private final ExamSetupService examSetup;
    private final TransactionTemplate tx;

    DemoAssistData(AssistSettingsService settings, RemarkService remarks, RiskService risk, PaperService papers,
            AcademicsDirectory academics, AcademicsService academicsService, ExamSetupService examSetup,
            PlatformTransactionManager transactionManager) {
        this.settings = settings;
        this.remarks = remarks;
        this.risk = risk;
        this.papers = papers;
        this.academics = academics;
        this.academicsService = academicsService;
        this.examSetup = examSetup;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Returns the number of students on the watch list. {@code userIds} maps the demo people's emails to ids. */
    int seed(UUID tenantId, Map<String, UUID> userIds, UUID adminId) {
        UUID teacherId = userIds.get("teacher" + DemoDataSeeder.DEMO_DOMAIN);
        if (teacherId == null || adminId == null) {
            return 0;
        }
        Actor admin = new Actor(adminId, "School Admin");
        Actor ravi = new Actor(teacherId, "Ravi Kumar");
        Integer listed = TenantContext.runAs(tenantId, () -> tx.execute(status -> {
            settings.save(new SettingsForm(true, true, true, true, true, 500, "en",
                    new WeightsForm(25, 15, 25, 10, 25, 30)), admin);
            int count = risk.compute(admin, LocalDate.now(INDIA), Instant.now()).listed();
            Optional<YearInfo> year = academics.currentYear();
            Optional<ClassView> five = academicsService.classes().stream().filter(c -> c.name().equals("Class 5"))
                    .findFirst();
            if (year.isEmpty() || five.isEmpty()) {
                return count;
            }
            Optional<SectionInfo> fiveA = academics.sections().stream()
                    .filter(s -> s.classId().equals(five.get().id()) && s.name().equals("A")).findFirst();
            Optional<TermView> termOne = examSetup.structure(year.get().id()).terms().stream()
                    .filter(t -> t.name().equals("Term 1")).findFirst();
            if (fiveA.isPresent() && termOne.isPresent()) {
                as(ravi, () -> {
                    List<RemarkDraftView> drafted = remarks.draft(new DraftRemarksForm(termOne.get().id(),
                            fiveA.get().id(), RemarkTone.ENCOURAGING, null, null), ravi);
                    drafted.stream().limit(3).forEach(d -> remarks.accept(d.id(), d.draftText(), ravi));
                    return null;
                });
            }
            return count;
        }));
        // The paper in its own transaction: an empty question bank (outside the school year) only skips it.
        try {
            TenantContext.runAs(tenantId, () -> tx.execute(status -> {
                academicsService.classes().stream().filter(c -> c.name().equals("Class 5")).findFirst()
                        .ifPresent(five -> five.subjects().stream().filter(s -> s.name().equals("Mathematics"))
                                .map(SubjectRef::id).findFirst()
                                .ifPresent(maths -> as(ravi, () -> papers.draftAndSave(new PaperDraftForm(five.id(),
                                        maths, "Class 5 Mathematics practice paper", 60, new Blueprint(List.of(
                                                new BlueprintSection("Section A", 10, BigDecimal.ONE),
                                                new BlueprintSection("Section B", 5, BigDecimal.ONE)), 40, 40, 20,
                                                List.of(), List.of())), ravi))));
                return null;
            }));
        } catch (RuntimeException e) {
            log.warn("No demo draft paper: {}", e.getMessage());
        }
        return listed == null ? 0 : listed;
    }

    /** Runs the work signed in as the person, with a teacher's permissions here. */
    private static <T> T as(Actor actor, Supplier<T> work) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("demo-data").header("alg", "none").subject(actor.id().toString())
                .claim(JwtService.CLAIM_NAME, actor.name()).issuedAt(now).expiresAt(now.plusSeconds(600)).build();
        context.setAuthentication(new JwtAuthenticationToken(jwt, TEACHER.stream()
                .map(SimpleGrantedAuthority::new).toList()));
        SecurityContextHolder.setContext(context);
        try {
            return work.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
