package com.akshara.exams;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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

import jakarta.persistence.EntityManager;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.academics.AcademicsService;
import com.akshara.academics.AcademicsService.ClassView;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.exams.ExamsForms.BandForm;
import com.akshara.exams.ExamsForms.ClassSchemeForm;
import com.akshara.exams.ExamsForms.SchemeForm;
import com.akshara.exams.ExamsForms.SettingsForm;
import com.akshara.exams.ExamsViews.Band;
import com.akshara.exams.ExamsViews.ClassSchemeRow;
import com.akshara.exams.ExamsViews.ClassSchemes;
import com.akshara.exams.ExamsViews.Scheme;
import com.akshara.exams.ExamsViews.SchemeRef;
import com.akshara.exams.ExamsViews.Settings;
import com.akshara.exams.ExamsViews.YearRef;
import com.akshara.platform.Board;
import com.akshara.platform.SchoolProfile;
import com.akshara.platform.TenantDirectory;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * Grading schemes and their bands, the scheme each class uses per academic year (by default the one for the school's
 * board), and the school's exam settings. Every change is audited. The built-in schemes are added to every school by
 * the V13 migration and at sign-up through {@link #addBuiltInSchemes()}.
 */
@Service
@Transactional
public class GradingService {

    static final String CBSE_1_8 = "CBSE_1_8";
    static final String CBSE_9_10 = "CBSE_9_10";
    static final String CBSE_CO = "CBSE_CO";
    static final String ICSE = "ICSE";
    static final String STATE = "STATE";

    static final BigDecimal DEFAULT_PASS = BigDecimal.valueOf(33);

    private final GradingSchemeRepository schemes;
    private final GradeBandRepository bands;
    private final ExamSettingsRepository settings;
    private final ClassSchemeRepository classSchemes;
    private final AcademicsDirectory academics;
    private final AcademicsService academicsService;
    private final TenantDirectory tenants;
    private final AuditService audit;
    private final EntityManager entityManager;

    GradingService(GradingSchemeRepository schemes, GradeBandRepository bands, ExamSettingsRepository settings,
            ClassSchemeRepository classSchemes, AcademicsDirectory academics, AcademicsService academicsService,
            TenantDirectory tenants, AuditService audit, EntityManager entityManager) {
        this.schemes = schemes;
        this.bands = bands;
        this.settings = settings;
        this.classSchemes = classSchemes;
        this.academics = academics;
        this.academicsService = academicsService;
        this.tenants = tenants;
        this.audit = audit;
        this.entityManager = entityManager;
    }

    /**
     * Adds the built-in schemes the school does not have yet (CBSE classes 1 to 8 and 9 and 10, CBSE co-scholastic,
     * ICSE and State board), with the same definitions as the V13 migration. Called inside the new school's
     * transaction at sign-up; returns how many were added.
     */
    public int addBuiltInSchemes() {
        UUID tenantId = TenantContext.require();
        entityManager.flush();
        Object added = entityManager.createNativeQuery("select exams.seed_default_schemes(?1)")
                .setParameter(1, tenantId)
                .getSingleResult();
        return ((Number) added).intValue();
    }

    /** {@link #addBuiltInSchemes()} for a school that removed some, audited. Returns every scheme. */
    public List<Scheme> restoreBuiltInSchemes(Actor actor) {
        int added = addBuiltInSchemes();
        if (added > 0) {
            audit.record(actor, "grading_scheme.defaults_added", "grading_scheme", null, Map.of("added", added));
        }
        return list();
    }

    // ------------------------------------------------------------------ schemes

    @Transactional(readOnly = true)
    public List<Scheme> list() {
        TenantContext.require();
        List<GradingScheme> all = schemes.findAllOrdered();
        Map<UUID, List<Band>> bandsByScheme = bandsOf(all.stream().map(GradingScheme::getId).toList());
        return all.stream().map(s -> view(s, bandsByScheme.getOrDefault(s.getId(), List.of()))).toList();
    }

    @Transactional(readOnly = true)
    public Scheme scheme(UUID id) {
        TenantContext.require();
        GradingScheme s = schemes.findById(id).orElseThrow(() -> ApiException.notFound("Grading scheme"));
        return view(s, bandsOf(List.of(id)).getOrDefault(id, List.of()));
    }

    public Scheme create(SchemeForm form, Actor actor) {
        TenantContext.require();
        String name = form.name().trim();
        List<BandForm> checked = checkScheme(null, name, form);
        GradingScheme scheme = schemes.saveAndFlush(new GradingScheme(name, form.kind(), clean(form.description()),
                form.passPercent(), form.overallPassPercent()));
        saveBands(scheme.getId(), checked);
        audit.record(actor, "grading_scheme.created", "grading_scheme", scheme.getId(), describe(scheme, checked));
        return scheme(scheme.getId());
    }

    public Scheme update(UUID id, SchemeForm form, Actor actor) {
        TenantContext.require();
        GradingScheme scheme = schemes.findById(id).orElseThrow(() -> ApiException.notFound("Grading scheme"));
        if (form.kind() != scheme.getKind()) {
            throw ApiException.badRequest("A scheme's kind cannot change. Add a new scheme instead.", "kind");
        }
        String name = form.name().trim();
        List<BandForm> checked = checkScheme(id, name, form);
        scheme.update(name, clean(form.description()), form.passPercent(), form.overallPassPercent());
        scheme.bandsChanged();
        schemes.saveAndFlush(scheme);
        bands.deleteBySchemeId(id);
        bands.flush();
        saveBands(id, checked);
        audit.record(actor, "grading_scheme.updated", "grading_scheme", id, describe(scheme, checked));
        return scheme(id);
    }

    public void delete(UUID id, Actor actor) {
        TenantContext.require();
        GradingScheme scheme = schemes.findById(id).orElseThrow(() -> ApiException.notFound("Grading scheme"));
        long classes = classSchemes.countBySchemeId(id);
        if (classes > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "In use", scheme.getName() + " is used by " + classes
                    + (classes == 1 ? " class" : " classes") + ". Give them another scheme first.");
        }
        if (settings.countByCoScholasticSchemeId(id) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "In use", scheme.getName()
                    + " is the co-scholastic scheme in the exam settings. Choose another one first.");
        }
        bands.deleteBySchemeId(id);
        schemes.delete(scheme);
        schemes.flush();
        audit.record(actor, "grading_scheme.deleted", "grading_scheme", id, Map.of("name", scheme.getName()));
    }

    private List<BandForm> checkScheme(UUID id, String name, SchemeForm form) {
        if (schemes.nameTaken(name, id == null ? new UUID(0, 0) : id)) {
            throw ApiException.conflict("There is already a grading scheme called " + name + ".", "name");
        }
        List<BandForm> sorted = form.bands().stream()
                .map(b -> new BandForm(b.minPercent().stripTrailingZeros(), b.grade().trim(), b.gradePoint(),
                        clean(b.remark())))
                .sorted(Comparator.comparing(BandForm::minPercent).reversed())
                .toList();
        Set<String> grades = new HashSet<>();
        Set<BigDecimal> mins = new HashSet<>();
        for (BandForm b : sorted) {
            if (b.grade().isEmpty()) {
                throw ApiException.badRequest("Give every band a grade.", "bands");
            }
            if (!grades.add(b.grade().toUpperCase(Locale.ROOT))) {
                throw ApiException.badRequest("The grade " + b.grade() + " appears twice.", "bands");
            }
            if (!mins.add(b.minPercent())) {
                throw ApiException.badRequest("Two bands start at " + b.minPercent().toPlainString() + "%.",
                        "bands");
            }
        }
        if (sorted.getLast().minPercent().signum() != 0) {
            throw ApiException.badRequest("The lowest band must start at 0%, so every percentage gets a grade.",
                    "bands");
        }
        return sorted;
    }

    private void saveBands(UUID schemeId, List<BandForm> sorted) {
        int position = 1;
        for (BandForm b : sorted) {
            bands.save(new GradeBand(schemeId, position++, b.minPercent(), b.grade(), b.gradePoint(), b.remark()));
        }
        bands.flush();
    }

    private Map<String, Object> describe(GradingScheme s, List<BandForm> list) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", s.getName());
        details.put("kind", s.getKind().name());
        details.put("passPercent", s.getPassPercent().toPlainString());
        details.put("overallPassPercent", s.getOverallPassPercent().toPlainString());
        details.put("bands", list.stream().map(b -> b.grade() + "≥" + b.minPercent().toPlainString()).toList());
        return details;
    }

    private Scheme view(GradingScheme s, List<Band> list) {
        return new Scheme(s.getId(), s.getCode(), s.getName(), s.getKind(), s.getDescription(), s.getPassPercent(),
                s.getOverallPassPercent(), s.getCode() != null, list,
                classSchemes.countBySchemeId(s.getId()) + settings.countByCoScholasticSchemeId(s.getId()),
                s.getUpdatedAt());
    }

    /** Bands of each scheme, highest first. */
    Map<UUID, List<Band>> bandsOf(List<UUID> schemeIds) {
        if (schemeIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<Band>> result = new HashMap<>();
        for (GradeBand b : bands.findBySchemeIds(schemeIds)) {
            result.computeIfAbsent(b.getSchemeId(), k -> new ArrayList<>())
                    .add(new Band(b.getMinPercent(), b.getGrade(), b.getGradePoint(), b.getRemark()));
        }
        return result;
    }

    // ------------------------------------------------------------------ settings

    @Transactional(readOnly = true)
    public Settings settings() {
        TenantContext.require();
        Optional<ExamSettings> s = settings.findFirstBy();
        GradingScheme co = coScholasticScheme(s.map(ExamSettings::getCoScholasticSchemeId).orElse(null));
        return new Settings(s.map(ExamSettings::isRanksEnabled).orElse(false),
                s.map(ExamSettings::getHpcUpToLevel).orElse(ExamSettings.DEFAULT_HPC_UP_TO),
                s.map(ExamSettings::getCoScholasticSchemeId).orElse(null), co == null ? null : co.getName());
    }

    public Settings updateSettings(SettingsForm form, Actor actor) {
        TenantContext.require();
        if (form.coScholasticSchemeId() != null) {
            GradingScheme co = schemes.findById(form.coScholasticSchemeId()).orElseThrow(() ->
                    ApiException.badRequest("Pick a co-scholastic scheme from the list.", "coScholasticSchemeId"));
            if (co.getKind() != SchemeKind.CO_SCHOLASTIC) {
                throw ApiException.badRequest("Pick a co-scholastic scheme from the list.", "coScholasticSchemeId");
            }
        }
        ExamSettings s = settings.findFirstBy().orElse(null);
        if (s == null) {
            settings.saveAndFlush(new ExamSettings(form.ranksEnabled(), form.hpcUpToLevel(),
                    form.coScholasticSchemeId()));
        } else {
            s.update(form.ranksEnabled(), form.hpcUpToLevel(), form.coScholasticSchemeId());
            settings.saveAndFlush(s);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("ranksEnabled", form.ranksEnabled());
        details.put("hpcUpToLevel", form.hpcUpToLevel());
        details.put("coScholasticSchemeId", form.coScholasticSchemeId());
        audit.record(actor, "exam_settings.updated", "exam_settings", null, details);
        return settings();
    }

    boolean ranksEnabled() {
        return settings.findFirstBy().map(ExamSettings::isRanksEnabled).orElse(false);
    }

    /** Whether a class gets the holistic progress card: its level is known and at most the configured one. */
    boolean hpcFor(String className) {
        Integer level = ClassLevel.of(className);
        int upTo = settings.findFirstBy().map(ExamSettings::getHpcUpToLevel).orElse(ExamSettings.DEFAULT_HPC_UP_TO);
        return level != null && level <= upTo;
    }

    /** The co-scholastic scheme: the one in the settings, else the built-in one, else any; null when none. */
    GradingScheme coScholasticScheme(UUID chosen) {
        if (chosen != null) {
            Optional<GradingScheme> s = schemes.findById(chosen);
            if (s.isPresent()) {
                return s.get();
            }
        }
        return schemes.findByCode(CBSE_CO).or(() -> schemes.findAllOrdered().stream()
                .filter(s -> s.getKind() == SchemeKind.CO_SCHOLASTIC).findFirst()).orElse(null);
    }

    /** The grades a co-scholastic area may get, highest first (A, B, C by default). */
    List<String> coScholasticGrades() {
        GradingScheme co = coScholasticScheme(settings.findFirstBy().map(ExamSettings::getCoScholasticSchemeId)
                .orElse(null));
        if (co == null) {
            return List.of("A", "B", "C");
        }
        return bandsOf(List.of(co.getId())).getOrDefault(co.getId(), List.of()).stream().map(Band::grade).toList();
    }

    // ------------------------------------------------------------------ class schemes

    @Transactional(readOnly = true)
    public ClassSchemes classSchemes(UUID yearId) {
        TenantContext.require();
        YearInfo year = year(yearId);
        Board board = board();
        Map<UUID, GradingScheme> byId = schemes.findAll().stream()
                .collect(Collectors.toMap(GradingScheme::getId, Function.identity()));
        Map<UUID, UUID> chosen = classSchemes.findByAcademicYearId(year.id()).stream()
                .collect(Collectors.toMap(ClassScheme::getClassId, ClassScheme::getSchemeId));
        List<ClassSchemeRow> rows = new ArrayList<>();
        for (ClassView c : academicsService.classes()) {
            UUID schemeId = chosen.get(c.id());
            GradingScheme scheme = schemeId != null ? byId.get(schemeId) : defaultScheme(board, c.name());
            rows.add(new ClassSchemeRow(c.id(), c.name(), ClassLevel.of(c.name()),
                    scheme == null ? null : scheme.getId(), scheme == null ? null : scheme.getName(),
                    schemeId == null, hpcFor(c.name())));
        }
        return new ClassSchemes(YearRef.of(year), academics.years().stream().map(YearRef::of).toList(),
                board == null ? null : board.name(), rows);
    }

    public ClassSchemes assign(UUID yearId, ClassSchemeForm form, Actor actor) {
        TenantContext.require();
        YearInfo year = year(yearId);
        Set<UUID> classIds = new LinkedHashSet<>(form.classIds());
        Map<UUID, String> classNames = classNames();
        if (!classNames.keySet().containsAll(classIds)) {
            throw ApiException.badRequest("Pick classes from this school's list.", "classIds");
        }
        GradingScheme scheme = null;
        if (form.schemeId() != null) {
            scheme = schemes.findById(form.schemeId()).orElseThrow(() ->
                    ApiException.badRequest("Pick a grading scheme from the list.", "schemeId"));
            if (scheme.getKind() != SchemeKind.SCHOLASTIC) {
                throw ApiException.badRequest("A class needs a scholastic scheme (marks to grades).", "schemeId");
            }
        }
        Map<UUID, ClassScheme> existing = classSchemes.findByAcademicYearId(year.id()).stream()
                .collect(Collectors.toMap(ClassScheme::getClassId, Function.identity()));
        for (UUID classId : classIds) {
            ClassScheme row = existing.get(classId);
            if (scheme == null) {
                if (row != null) {
                    classSchemes.delete(row);
                }
            } else if (row == null) {
                classSchemes.save(new ClassScheme(year.id(), classId, scheme.getId()));
            } else {
                row.change(scheme.getId());
            }
        }
        classSchemes.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("year", year.name());
        details.put("classes", classIds.stream().map(classNames::get).toList());
        details.put("scheme", scheme == null ? "default" : scheme.getName());
        audit.record(actor, "class_scheme.updated", "class_scheme", year.id(), details);
        return classSchemes(year.id());
    }

    /** The scheme the class uses in the year: its own, else the board's default; null when the school has none. */
    GradingScheme schemeFor(UUID classId, String className, UUID yearId) {
        Optional<GradingScheme> own = classSchemes.findByAcademicYearIdAndClassId(yearId, classId)
                .flatMap(c -> schemes.findById(c.getSchemeId()));
        return own.orElseGet(() -> defaultScheme(board(), className));
    }

    /** The scheme with its bands, as used to compute results. Without any scheme: no grades, 33% to pass. */
    Graded graded(UUID classId, String className, UUID yearId) {
        GradingScheme scheme = schemeFor(classId, className, yearId);
        if (scheme == null) {
            return new Graded(null, new GradeMath.Scale(List.of(), DEFAULT_PASS, DEFAULT_PASS));
        }
        List<Band> list = bandsOf(List.of(scheme.getId())).getOrDefault(scheme.getId(), List.of());
        GradeMath.Scale scale = new GradeMath.Scale(list.stream()
                .map(b -> new GradeMath.Band(b.minPercent(), b.grade(), b.gradePoint(), b.remark())).toList(),
                scheme.getPassPercent(), scheme.getOverallPassPercent());
        return new Graded(new SchemeRef(scheme.getId(), scheme.getName(), scheme.getPassPercent(),
                scheme.getOverallPassPercent(), scale.usesGradePoints(), list), scale);
    }

    /** A class's scheme as shown ({@code ref}, null without one) and as computed with ({@code scale}). */
    record Graded(SchemeRef ref, GradeMath.Scale scale) {
    }

    /**
     * The default scheme for the school's board: CBSE classes 9 and up use the nine-point scale, other CBSE classes
     * (pre-primary included) the classes 1 to 8 scale; ICSE schools the ICSE scheme; State, IB and Cambridge schools
     * the State board scale. When that scheme was removed, the first scholastic scheme by name.
     */
    GradingScheme defaultScheme(Board board, String className) {
        String code = switch (board == null ? Board.STATE : board) {
            case CBSE -> {
                Integer level = ClassLevel.of(className);
                yield level != null && level >= 9 ? CBSE_9_10 : CBSE_1_8;
            }
            case ICSE -> ICSE;
            case STATE, IB, CAMBRIDGE -> STATE;
        };
        return schemes.findByCode(code).or(() -> schemes.findAllOrdered().stream()
                .filter(s -> s.getKind() == SchemeKind.SCHOLASTIC).findFirst()).orElse(null);
    }

    private Board board() {
        return tenants.profile(TenantContext.require()).map(SchoolProfile::board).orElse(null);
    }

    private Map<UUID, String> classNames() {
        Map<UUID, String> names = new LinkedHashMap<>();
        academicsService.classes().forEach(c -> names.put(c.id(), c.name()));
        return names;
    }

    private YearInfo year(UUID yearId) {
        if (yearId == null) {
            return academics.currentYear().orElseThrow(() -> ApiException.badRequest(
                    "Set up the current academic year in School setup first.", "yearId"));
        }
        return academics.year(yearId).orElseThrow(() -> ApiException.notFound("Academic year"));
    }

    static String clean(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
