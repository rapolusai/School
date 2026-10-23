package com.akshara.govreport;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.govreport.GovReportForms.CodeForm;
import com.akshara.govreport.GovReportForms.NewCodeForm;
import com.akshara.govreport.GovReportTypes.CodeListType;
import com.akshara.govreport.GovReportViews.CodeView;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * The code lists every export and rule reads: UDISE+ social categories, religions, mother tongues, CWSN types,
 * school category and management codes, the RTE income limit per state, scheme names and the RTE document
 * checklist. Seeded per school on first use from our best reading of the public specifications, every row marked
 * "verify against the current portal template" until the school confirms or corrects it. A change is a data edit,
 * never a release.
 */
@Service
@Transactional
public class CodeListService {

    /** The day the seeded codes take effect: the start of the first UDISE+ cycle this product supports. */
    static final LocalDate SEED_FROM = LocalDate.of(2024, 4, 1);

    private record Seed(CodeListType list, String code, String label, String value, String state) {
    }

    /** value: the numeric code the portal uses (where known) or, for income limits, the limit in paise. */
    static final List<Seed> SEEDS = List.of(
            new Seed(CodeListType.SOCIAL_CATEGORY, "GEN", "General", "1", null),
            new Seed(CodeListType.SOCIAL_CATEGORY, "SC", "Scheduled Caste", "2", null),
            new Seed(CodeListType.SOCIAL_CATEGORY, "ST", "Scheduled Tribe", "3", null),
            new Seed(CodeListType.SOCIAL_CATEGORY, "OBC", "Other Backward Class", "4", null),
            new Seed(CodeListType.RELIGION, "HINDU", "Hindu", "1", null),
            new Seed(CodeListType.RELIGION, "MUSLIM", "Muslim", "2", null),
            new Seed(CodeListType.RELIGION, "CHRISTIAN", "Christian", "3", null),
            new Seed(CodeListType.RELIGION, "SIKH", "Sikh", "4", null),
            new Seed(CodeListType.RELIGION, "BUDDHIST", "Buddhist", "5", null),
            new Seed(CodeListType.RELIGION, "PARSI", "Parsi", "6", null),
            new Seed(CodeListType.RELIGION, "JAIN", "Jain", "7", null),
            new Seed(CodeListType.RELIGION, "OTHER", "Other", "8", null),
            new Seed(CodeListType.MOTHER_TONGUE, "HINDI", "Hindi", null, null),
            new Seed(CodeListType.MOTHER_TONGUE, "ENGLISH", "English", null, null),
            new Seed(CodeListType.MOTHER_TONGUE, "TELUGU", "Telugu", null, null),
            new Seed(CodeListType.MOTHER_TONGUE, "TAMIL", "Tamil", null, null),
            new Seed(CodeListType.MOTHER_TONGUE, "KANNADA", "Kannada", null, null),
            new Seed(CodeListType.MOTHER_TONGUE, "MALAYALAM", "Malayalam", null, null),
            new Seed(CodeListType.MOTHER_TONGUE, "MARATHI", "Marathi", null, null),
            new Seed(CodeListType.MOTHER_TONGUE, "GUJARATI", "Gujarati", null, null),
            new Seed(CodeListType.MOTHER_TONGUE, "BENGALI", "Bengali", null, null),
            new Seed(CodeListType.MOTHER_TONGUE, "PUNJABI", "Punjabi", null, null),
            new Seed(CodeListType.MOTHER_TONGUE, "URDU", "Urdu", null, null),
            new Seed(CodeListType.MOTHER_TONGUE, "ODIA", "Odia", null, null),
            new Seed(CodeListType.MOTHER_TONGUE, "ASSAMESE", "Assamese", null, null),
            new Seed(CodeListType.MOTHER_TONGUE, "OTHER", "Other", null, null),
            new Seed(CodeListType.CWSN_TYPE, "BLINDNESS", "Blindness", "1", null),
            new Seed(CodeListType.CWSN_TYPE, "LOW_VISION", "Low vision", "2", null),
            new Seed(CodeListType.CWSN_TYPE, "HEARING", "Hearing impairment", "3", null),
            new Seed(CodeListType.CWSN_TYPE, "SPEECH", "Speech and language disability", "4", null),
            new Seed(CodeListType.CWSN_TYPE, "LOCOMOTOR", "Locomotor disability", "5", null),
            new Seed(CodeListType.CWSN_TYPE, "INTELLECTUAL", "Intellectual disability", "6", null),
            new Seed(CodeListType.CWSN_TYPE, "LEARNING", "Specific learning disability", "7", null),
            new Seed(CodeListType.CWSN_TYPE, "AUTISM", "Autism spectrum disorder", "8", null),
            new Seed(CodeListType.CWSN_TYPE, "CEREBRAL_PALSY", "Cerebral palsy", "9", null),
            new Seed(CodeListType.CWSN_TYPE, "MULTIPLE", "Multiple disabilities", "10", null),
            new Seed(CodeListType.CWSN_TYPE, "OTHER", "Other", "11", null),
            new Seed(CodeListType.SCHOOL_CATEGORY, "CAT1", "Primary only (1 to 5)", "1", null),
            new Seed(CodeListType.SCHOOL_CATEGORY, "CAT2", "Primary with upper primary (1 to 8)", "2", null),
            new Seed(CodeListType.SCHOOL_CATEGORY, "CAT3", "Primary to higher secondary (1 to 12)", "3", null),
            new Seed(CodeListType.SCHOOL_CATEGORY, "CAT4", "Upper primary only (6 to 8)", "4", null),
            new Seed(CodeListType.SCHOOL_CATEGORY, "CAT5", "Upper primary to higher secondary (6 to 12)", "5", null),
            new Seed(CodeListType.SCHOOL_CATEGORY, "CAT6", "Primary to secondary (1 to 10)", "6", null),
            new Seed(CodeListType.SCHOOL_CATEGORY, "CAT7", "Upper primary with secondary (6 to 10)", "7", null),
            new Seed(CodeListType.SCHOOL_CATEGORY, "CAT8", "Secondary only (9 to 10)", "8", null),
            new Seed(CodeListType.SCHOOL_CATEGORY, "CAT10", "Secondary with higher secondary (9 to 12)", "10", null),
            new Seed(CodeListType.SCHOOL_CATEGORY, "CAT11", "Higher secondary only (11 to 12)", "11", null),
            new Seed(CodeListType.MANAGEMENT, "GOVT", "Department of Education", "1", null),
            new Seed(CodeListType.MANAGEMENT, "LOCAL_BODY", "Local body", "3", null),
            new Seed(CodeListType.MANAGEMENT, "PVT_AIDED", "Private aided", "4", null),
            new Seed(CodeListType.MANAGEMENT, "PVT_UNAIDED", "Private unaided (recognised)", "5", null),
            new Seed(CodeListType.MANAGEMENT, "CENTRAL", "Central government", "7", null),
            new Seed(CodeListType.MANAGEMENT, "OTHER", "Other", "6", null),
            new Seed(CodeListType.RTE_INCOME_LIMIT, "DL", "Delhi", "10000000", "DL"),
            new Seed(CodeListType.RTE_INCOME_LIMIT, "KA", "Karnataka", "35000000", "KA"),
            new Seed(CodeListType.RTE_INCOME_LIMIT, "MH", "Maharashtra", "10000000", "MH"),
            new Seed(CodeListType.RTE_INCOME_LIMIT, "GJ", "Gujarat", "15000000", "GJ"),
            new Seed(CodeListType.RTE_INCOME_LIMIT, "RJ", "Rajasthan", "25000000", "RJ"),
            new Seed(CodeListType.RTE_INCOME_LIMIT, "UP", "Uttar Pradesh", "10000000", "UP"),
            new Seed(CodeListType.RTE_INCOME_LIMIT, "TN", "Tamil Nadu", "20000000", "TN"),
            new Seed(CodeListType.RTE_INCOME_LIMIT, "HR", "Haryana", "18000000", "HR"),
            new Seed(CodeListType.RTE_INCOME_LIMIT, "TS", "Telangana", "10000000", "TS"),
            new Seed(CodeListType.RTE_INCOME_LIMIT, "AP", "Andhra Pradesh", "10000000", "AP"),
            new Seed(CodeListType.RTE_INCOME_LIMIT, "MP", "Madhya Pradesh", "10000000", "MP"),
            new Seed(CodeListType.RTE_INCOME_LIMIT, "KL", "Kerala", "10000000", "KL"),
            new Seed(CodeListType.SCHEME, "PRE_MATRIC_SC", "Pre-matric scholarship (SC)", null, null),
            new Seed(CodeListType.SCHEME, "PRE_MATRIC_ST", "Pre-matric scholarship (ST)", null, null),
            new Seed(CodeListType.SCHEME, "PRE_MATRIC_OBC", "Pre-matric scholarship (OBC/EBC/DNT)", null, null),
            new Seed(CodeListType.SCHEME, "PRE_MATRIC_MINORITY", "Pre-matric scholarship (minorities)", null, null),
            new Seed(CodeListType.SCHEME, "NMMS", "National Means-cum-Merit Scholarship", null, null),
            new Seed(CodeListType.SCHEME, "PM_POSHAN", "PM POSHAN (mid-day meal)", null, null),
            new Seed(CodeListType.SCHEME, "FREE_TEXTBOOKS", "Free textbooks", null, null),
            new Seed(CodeListType.SCHEME, "FREE_UNIFORM", "Free uniform", null, null),
            new Seed(CodeListType.SCHEME, "STATE_SCHOLARSHIP", "State scholarship", null, null),
            new Seed(CodeListType.SCHEME, "OTHER", "Other scheme", null, null),
            new Seed(CodeListType.RTE_DOCUMENT, "BIRTH_CERT", "Birth certificate", null, null),
            new Seed(CodeListType.RTE_DOCUMENT, "ADDRESS_PROOF", "Proof of address", null, null),
            new Seed(CodeListType.RTE_DOCUMENT, "INCOME_CERT", "Income certificate", null, null),
            new Seed(CodeListType.RTE_DOCUMENT, "CASTE_CERT", "Caste certificate (if claimed)", null, null),
            new Seed(CodeListType.RTE_DOCUMENT, "BPL_CARD", "BPL or ration card (if claimed)", null, null),
            new Seed(CodeListType.RTE_DOCUMENT, "DISABILITY_CERT", "Disability certificate (if claimed)", null, null),
            new Seed(CodeListType.RTE_DOCUMENT, "PHOTO", "Passport-size photograph", null, null));

    private final CodeListRepository codes;
    private final AuditService audit;

    @PersistenceContext
    private EntityManager em;

    CodeListService(CodeListRepository codes, AuditService audit) {
        this.codes = codes;
        this.audit = audit;
    }

    /** Seeds the defaults the first time a school opens the module. Idempotent. */
    void ensureDefaults() {
        UUID tenant = TenantContext.require();
        if (codes.count() > 0) {
            return;
        }
        // Two requests of a new school can arrive together (the profile form loads two lists):
        // serialise the seeding per school so the second finds the codes the first wrote.
        em.createNativeQuery("select pg_advisory_xact_lock(hashtext(:k))")
                .setParameter("k", "govreport-codes:" + tenant).getSingleResult();
        if (codes.count() > 0) {
            return;
        }
        List<CodeListEntry> seeded = new ArrayList<>();
        int order = 0;
        CodeListType last = null;
        for (Seed s : SEEDS) {
            order = s.list() == last ? order + 1 : 0;
            last = s.list();
            seeded.add(new CodeListEntry(s.list(), s.code(), s.label(), s.value(), s.state(), SEED_FROM, null, true,
                    order));
        }
        codes.saveAll(seeded);
        codes.flush();
    }

    /** Every code of every list, in list and display order. */
    public List<CodeView> all() {
        ensureDefaults();
        LocalDate today = GovReportAuth.today();
        return codes.findAllByOrderByListAscDisplayOrderAscCodeAscEffectiveFromAsc().stream()
                .map(c -> view(c, today)).toList();
    }

    public List<CodeView> list(CodeListType list) {
        ensureDefaults();
        LocalDate today = GovReportAuth.today();
        return codes.findByListOrderByDisplayOrderAscCodeAscEffectiveFromAsc(list).stream()
                .map(c -> view(c, today)).toList();
    }

    /** The codes of a list in force on the day, by code (the latest effective row wins). */
    Map<String, CodeView> inForce(CodeListType list, LocalDate day) {
        ensureDefaults();
        Map<String, CodeView> result = new LinkedHashMap<>();
        for (CodeListEntry c : codes.findByListOrderByDisplayOrderAscCodeAscEffectiveFromAsc(list)) {
            if (c.inForce(day)) {
                result.put(c.getCode(), view(c, day));
            }
        }
        return result;
    }

    /** The label of a code in force, or the code itself when it is unknown. */
    String label(Map<String, CodeView> inForce, String code) {
        if (code == null) {
            return null;
        }
        CodeView v = inForce.get(code);
        return v == null ? code : v.label();
    }

    /** The portal's numeric value of a code in force (its {@code value}), or the code when none is known. */
    String portalValue(Map<String, CodeView> inForce, String code) {
        if (code == null) {
            return null;
        }
        CodeView v = inForce.get(code);
        return v == null || v.value() == null ? code : v.value();
    }

    /** The RTE income limit of a state in force on the day, in paise. */
    Optional<Long> incomeLimit(String stateCode, LocalDate day) {
        if (stateCode == null) {
            return Optional.empty();
        }
        return inForce(CodeListType.RTE_INCOME_LIMIT, day).values().stream()
                .filter(c -> stateCode.equalsIgnoreCase(c.stateCode()) || stateCode.equalsIgnoreCase(c.code()))
                .map(CodeView::value)
                .filter(v -> v != null && v.matches("[0-9]+"))
                .map(Long::valueOf)
                .findFirst();
    }

    public CodeView add(NewCodeForm form, Actor actor) {
        ensureDefaults();
        CodeListEntry entry = new CodeListEntry(form.list(), form.code(), form.label().trim(),
                GovReportAuth.blankToNull(form.value()), upper(form.stateCode()), form.effectiveFrom(),
                form.effectiveTo(), true, form.displayOrder());
        checkDates(form.effectiveFrom(), form.effectiveTo());
        try {
            codes.saveAndFlush(entry);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("That code already exists in the list from that date.", "code");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("list", form.list().name());
        details.put("code", form.code());
        details.put("label", entry.getLabel());
        audit.record(actor, "govreport_code.added", "govreport_code", entry.getId(), details);
        return view(entry, GovReportAuth.today());
    }

    public CodeView update(UUID id, CodeForm form, Actor actor) {
        ensureDefaults();
        CodeListEntry entry = codes.findById(id).orElseThrow(() -> ApiException.notFound("Code"));
        checkDates(form.effectiveFrom(), form.effectiveTo());
        entry.update(form.label().trim(), GovReportAuth.blankToNull(form.value()), upper(form.stateCode()),
                form.effectiveFrom(), form.effectiveTo(), form.verify(), form.active(), form.displayOrder());
        try {
            codes.saveAndFlush(entry);
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("That code already exists in the list from that date.", "effectiveFrom");
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("list", entry.getList().name());
        details.put("code", entry.getCode());
        details.put("label", entry.getLabel());
        details.put("value", entry.getValue());
        details.put("verify", entry.isVerify());
        details.put("active", entry.isActive());
        audit.record(actor, "govreport_code.updated", "govreport_code", entry.getId(), details);
        return view(entry, GovReportAuth.today());
    }

    private static void checkDates(LocalDate from, LocalDate to) {
        if (to != null && to.isBefore(from)) {
            throw ApiException.badRequest("The end date cannot be before the start date.", "effectiveTo");
        }
    }

    private static String upper(String state) {
        String s = GovReportAuth.blankToNull(state);
        return s == null ? null : s.toUpperCase(java.util.Locale.ROOT);
    }

    static CodeView view(CodeListEntry c, LocalDate day) {
        return new CodeView(c.getId(), c.getList(), c.getCode(), c.getLabel(), c.getValue(), c.getStateCode(),
                c.getEffectiveFrom(), c.getEffectiveTo(), c.isVerify(), c.isActive(), c.getDisplayOrder(),
                c.inForce(day));
    }

    static <T> Map<String, T> byCode(List<CodeView> views, Function<CodeView, T> value) {
        return views.stream().collect(Collectors.toMap(CodeView::code, value, (a, b) -> b, LinkedHashMap::new));
    }
}
