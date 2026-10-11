package com.akshara.payroll;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.payroll.PayrollForms.PayProfileForm;
import com.akshara.payroll.PayrollForms.StructureForm;
import com.akshara.payroll.PayrollForms.StructureLineInput;
import com.akshara.payroll.PayrollSettings.Values;
import com.akshara.payroll.PayrollViews.PayProfileFull;
import com.akshara.payroll.PayrollViews.PayProfileView;
import com.akshara.payroll.PayrollViews.PayStaffDetail;
import com.akshara.payroll.PayrollViews.PayStaffList;
import com.akshara.payroll.PayrollViews.PayStaffRow;
import com.akshara.payroll.PayrollViews.PayslipRef;
import com.akshara.payroll.PayrollViews.StructureLineView;
import com.akshara.payroll.PayrollViews.StructureView;
import com.akshara.payroll.PayslipCalculator.Input;
import com.akshara.payroll.PayslipCalculator.Result;
import com.akshara.payroll.PayslipCalculator.YearToDate;
import com.akshara.payroll.StructureBook.Loaded;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.staff.StaffPayrollInputs;
import com.akshara.staff.StaffPayrollInputs.PayrollPerson;

/**
 * Payroll's view of the staff: who has a salary structure and pay details, each person's structures (revisions keep
 * the history) and their pay details. Account numbers and PAN are masked; payroll managers can see them in full, and
 * every such view is audited.
 */
@Service
@Transactional
public class PayStaffService {

    private final StaffPayrollInputs staff;
    private final StructureBook book;
    private final SalaryStructureRepository structures;
    private final SalaryStructureLineRepository structureLines;
    private final SalaryComponentRepository components;
    private final StaffPayProfileRepository profiles;
    private final PayrollRunRepository runs;
    private final PayslipRepository payslips;
    private final PayrollSettingsService settings;
    private final StatutoryTables statutory;
    private final AuditService audit;

    PayStaffService(StaffPayrollInputs staff, StructureBook book, SalaryStructureRepository structures,
            SalaryStructureLineRepository structureLines, SalaryComponentRepository components,
            StaffPayProfileRepository profiles, PayrollRunRepository runs, PayslipRepository payslips,
            PayrollSettingsService settings, StatutoryTables statutory, AuditService audit) {
        this.staff = staff;
        this.book = book;
        this.structures = structures;
        this.structureLines = structureLines;
        this.components = components;
        this.profiles = profiles;
        this.runs = runs;
        this.payslips = payslips;
        this.settings = settings;
        this.statutory = statutory;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ list and detail

    /**
     * Everyone on the staff who is active or has a structure, with the structure in force this month (or the next one
     * when it starts later), its gross and CTC, and which pay details are missing.
     */
    @Transactional(readOnly = true)
    public PayStaffList list() {
        TenantContext.require();
        YearMonth month = PayrollSettingsService.thisMonth();
        Map<UUID, List<Loaded>> all = book.all();
        Map<UUID, StaffPayProfile> byUser = profiles.findAll().stream()
                .collect(Collectors.toMap(StaffPayProfile::getUserId, Function.identity()));
        Values values = settings.values();
        StatutoryRate rate = statutory.rateFor(month);
        List<PayStaffRow> rows = new ArrayList<>();
        int needStructure = 0;
        int needBank = 0;
        for (PayrollPerson p : staff.people()) {
            List<Loaded> mine = all.getOrDefault(p.userId(), List.of());
            if (!p.active() && mine.isEmpty()) {
                continue;
            }
            Optional<Loaded> shown = shown(mine, month);
            StaffPayProfile profile = byUser.get(p.userId());
            boolean hasBank = profile != null && profile.getBankAccountNumber() != null;
            if (p.active() && mine.isEmpty()) {
                needStructure++;
            }
            if (p.active() && !mine.isEmpty() && !hasBank) {
                needBank++;
            }
            long gross = shown.map(Loaded::gross).orElse(0L);
            long ctc = shown.map(s -> gross + employerCost(s, profile, values, rate)).orElse(0L);
            rows.add(new PayStaffRow(p.userId(), p.name(), p.employeeCode(), p.designation(), p.departmentId(),
                    p.departmentName(), p.active(), p.profileComplete(), p.dateOfJoining(), p.dateOfLeaving(),
                    shown.map(Loaded::from).orElse(null), gross, ctc, hasBank,
                    profile == null ? null : PayrollViews.last4(profile.getBankAccountNumber()),
                    profile == null ? null : PayrollViews.maskPan(profile.getPan()),
                    profile != null && profile.getPan() != null, profile != null && profile.getUan() != null,
                    profile != null && profile.getEsiNumber() != null));
        }
        return new PayStaffList(month.toString(), rows, needStructure, needBank);
    }

    /** One member of staff: pay details (masked), structures newest first, and payslips of approved months. */
    @Transactional(readOnly = true)
    public PayStaffDetail detail(UUID userId) {
        TenantContext.require();
        PayrollPerson p = person(userId);
        YearMonth month = PayrollSettingsService.thisMonth();
        Optional<StaffPayProfile> profile = profiles.findByUserId(userId);
        Values values = settings.values();
        StatutoryRate rate = statutory.rateFor(month);
        List<Loaded> mine = book.of(userId);
        Optional<Loaded> current = StructureBook.inForce(mine, month.atDay(1));
        Map<UUID, PayrollRun> runById = runs.findAll().stream()
                .collect(Collectors.toMap(PayrollRun::getId, Function.identity()));
        List<Payslip> slips = payslips.findByUserId(userId);
        Set<LocalDate> lockedFroms = new HashSet<>();
        List<PayslipRef> refs = new ArrayList<>();
        for (Payslip s : slips) {
            PayrollRun run = runById.get(s.getRunId());
            if (run == null) {
                continue;
            }
            if (run.getStatus().locked()) {
                lockedFroms.add(s.getStructureFrom());
            }
            refs.add(new PayslipRef(s.getId(), run.yearMonth().toString(), run.getStatus(), s.getGrossPaise(),
                    s.getNetPaise()));
        }
        refs.sort(Comparator.comparing(PayslipRef::month).reversed());
        List<StructureView> views = new ArrayList<>();
        for (Loaded s : mine.reversed()) {
            long employer = employerCost(s, profile.orElse(null), values, rate);
            views.add(new StructureView(s.structure().getId(), YearMonth.from(s.from()).toString(),
                    s.structure().getNote(), s.structure().getCreatedByName(), s.structure().getCreatedAt(),
                    s.lines().stream().map(l -> new StructureLineView(l.componentId(), l.code(), l.name(), l.kind(),
                            l.monthlyPaise())).toList(),
                    s.gross(), s.fixedDeductions(), employer, s.gross() + employer,
                    current.map(c -> c.structure().getId().equals(s.structure().getId())).orElse(false),
                    lockedFroms.contains(s.from())));
        }
        return new PayStaffDetail(p.userId(), p.name(), p.email(), p.employeeCode(), p.designation(),
                p.departmentName(), p.active(), p.profileComplete(), p.dateOfJoining(), p.dateOfLeaving(),
                profileView(profile.orElse(null)), views, refs);
    }

    // ------------------------------------------------------------------ pay details

    /** The pay details in full. Audited every time: the account number and PAN are sensitive. */
    public PayProfileFull full(UUID userId, Actor actor) {
        TenantContext.require();
        person(userId);
        Optional<StaffPayProfile> profile = profiles.findByUserId(userId);
        StaffPayProfile.Values v = profile.map(StaffPayProfile::values).orElse(StaffPayProfile.Values.EMPTY);
        audit.record(actor, "payroll_pay_profile.viewed", "staff_pay_profile",
                profile.map(StaffPayProfile::getId).orElse(null), Map.of("userId", userId.toString()));
        return new PayProfileFull(v.bankAccountNumber(), v.bankIfsc(), v.accountHolderName(), v.pan(), v.uan(),
                v.esiNumber(), v.pfMember(), v.ptWomenSlab());
    }

    public PayProfileView saveProfile(UUID userId, PayProfileForm form, Actor actor) {
        TenantContext.require();
        person(userId);
        String account = PayrollForms.clean(form.bankAccountNumber());
        String ifsc = PayrollForms.upper(form.bankIfsc());
        if ((account == null) != (ifsc == null)) {
            throw ApiException.badRequest("Enter the account number and its IFSC together.",
                    account == null ? "bankAccountNumber" : "bankIfsc");
        }
        StaffPayProfile.Values values = new StaffPayProfile.Values(account, ifsc,
                PayrollForms.clean(form.accountHolderName()), PayrollForms.upper(form.pan()),
                PayrollForms.clean(form.uan()), PayrollForms.clean(form.esiNumber()), form.pfMember(),
                form.ptWomenSlab());
        Optional<StaffPayProfile> existing = profiles.findByUserId(userId);
        StaffPayProfile.Values before = existing.map(StaffPayProfile::values).orElse(StaffPayProfile.Values.EMPTY);
        StaffPayProfile profile = existing.orElse(null);
        if (profile == null) {
            profile = profiles.save(new StaffPayProfile(userId, values));
        } else {
            profile.apply(values);
        }
        profiles.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("userId", userId.toString());
        details.put("changed", changed(before, values));
        audit.record(actor, "payroll_pay_profile.updated", "staff_pay_profile", profile.getId(), details);
        return profileView(profile);
    }

    // ------------------------------------------------------------------ structures

    /**
     * A salary structure from a month on (a revision when the person has one). The month must come after the latest
     * approved payroll month, since an approved month never changes: pay owed for it goes in as arrears.
     */
    public StructureView createStructure(UUID userId, StructureForm form, Actor actor) {
        TenantContext.require();
        person(userId);
        YearMonth month = PayrollAccess.month(form.effectiveFrom(), "effectiveFrom");
        Optional<LocalDate> locked = runs.latestLockedMonth();
        if (locked.isPresent() && !month.atDay(1).isAfter(locked.get())) {
            throw new ApiException(HttpStatus.CONFLICT, "Month already approved",
                    "Payroll for " + YearMonth.from(locked.get()) + " is approved. Start the structure in a later "
                            + "month and pay anything owed as arrears.",
                    Map.of("effectiveFrom", "Pick a month after " + YearMonth.from(locked.get()) + "."));
        }
        if (structures.exists(userId, month.atDay(1))) {
            throw ApiException.conflict("There is already a structure from this month. Delete it first to replace it.",
                    "effectiveFrom");
        }
        Map<UUID, SalaryComponent> byId = components.findAll().stream()
                .collect(Collectors.toMap(SalaryComponent::getId, Function.identity()));
        Set<UUID> seen = new HashSet<>();
        boolean earning = false;
        List<StructureLineInput> lines = form.lines();
        for (int i = 0; i < lines.size(); i++) {
            SalaryComponent c = byId.get(lines.get(i).componentId());
            String field = "lines[" + i + "].componentId";
            if (c == null) {
                throw ApiException.badRequest("Pick one of the school's salary components.", field);
            }
            if (!c.isActive()) {
                throw ApiException.badRequest("This component is switched off.", field);
            }
            if (!seen.add(c.getId())) {
                throw ApiException.badRequest("Each component can be used once.", field);
            }
            earning |= c.getKind() == ComponentKind.EARNING;
        }
        if (!earning) {
            throw ApiException.badRequest("Add at least one earning, such as Basic.", "lines");
        }
        SalaryStructure structure;
        try {
            structure = structures.saveAndFlush(new SalaryStructure(userId, month.atDay(1),
                    PayrollForms.clean(form.note()), actor));
        } catch (DataIntegrityViolationException e) {
            throw ApiException.conflict("There is already a structure from this month.", "effectiveFrom");
        }
        for (StructureLineInput l : lines) {
            structureLines.save(new SalaryStructureLine(structure.getId(), l.componentId(), l.monthlyPaise()));
        }
        structureLines.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("userId", userId.toString());
        details.put("effectiveFrom", month.toString());
        details.put("components", lines.size());
        audit.record(actor, "salary_structure.created", "salary_structure", structure.getId(), details);
        UUID id = structure.getId();
        return detail(userId).structures().stream().filter(s -> s.id().equals(id)).findFirst().orElseThrow();
    }

    /** Deletes a structure no approved run has used (409 otherwise). */
    public void deleteStructure(UUID structureId, Actor actor) {
        TenantContext.require();
        SalaryStructure s = structures.findById(structureId)
                .orElseThrow(() -> ApiException.notFound("Salary structure"));
        if (payslips.lockedWithStructure(s.getUserId(), s.getEffectiveFrom())) {
            throw new ApiException(HttpStatus.CONFLICT, "Structure in use",
                    "An approved month was paid on this structure, so it stays as history. Add a revision instead.");
        }
        structureLines.deleteByStructure(structureId);
        structures.delete(s);
        structures.flush();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("userId", s.getUserId().toString());
        details.put("effectiveFrom", YearMonth.from(s.getEffectiveFrom()).toString());
        audit.record(actor, "salary_structure.deleted", "salary_structure", structureId, details);
    }

    // ------------------------------------------------------------------ helpers

    /** A member of staff of this school (404 for anyone else). */
    PayrollPerson person(UUID userId) {
        return staff.person(userId).orElseThrow(() -> ApiException.notFound("Staff member"));
    }

    private static Optional<Loaded> shown(List<Loaded> mine, YearMonth month) {
        Optional<Loaded> now = StructureBook.inForce(mine, month.atDay(1));
        return now.isPresent() ? now : mine.stream().findFirst();
    }

    /** The employer's PF, EDLI, PF administration and ESI for a full month on the structure, at today's rates. */
    static long employerCost(Loaded s, StaffPayProfile profile, Values values, StatutoryRate rate) {
        BigDecimal days = BigDecimal.valueOf(30);
        Result r = PayslipCalculator.calculate(new Input(PayrollSettingsService.thisMonth(), s.from(), s.lines(),
                days, days, BigDecimal.ZERO, List.of(), values.pfEnabled() && (profile == null || profile.isPfMember()),
                values.pfOnFullWages(), rate.pfRates(), values.esiEnabled(), null, rate.getEsiWageCeilingPaise(),
                rate.getEsiEmployeePercent(), rate.getEsiEmployerPercent(), List.of(), false, null, YearToDate.NONE, 0,
                null));
        return r.employer();
    }

    static PayProfileView profileView(StaffPayProfile p) {
        if (p == null) {
            return new PayProfileView(false, null, null, null, null, null, null, null, true, false, null);
        }
        return new PayProfileView(true, PayrollViews.maskAccount(p.getBankAccountNumber()),
                PayrollViews.last4(p.getBankAccountNumber()), p.getBankIfsc(), p.getAccountHolderName(),
                PayrollViews.maskPan(p.getPan()), p.getUan(), p.getEsiNumber(), p.isPfMember(), p.isPtWomenSlab(),
                p.getUpdatedAt());
    }

    /** Names of the pay details that changed, never their values. */
    private static List<String> changed(StaffPayProfile.Values a, StaffPayProfile.Values b) {
        List<String> names = new ArrayList<>();
        if (!Objects.equals(a.bankAccountNumber(), b.bankAccountNumber())
                || !Objects.equals(a.bankIfsc(), b.bankIfsc())) {
            names.add("bankAccount");
        }
        if (!Objects.equals(a.accountHolderName(), b.accountHolderName())) {
            names.add("accountHolderName");
        }
        if (!Objects.equals(a.pan(), b.pan())) {
            names.add("pan");
        }
        if (!Objects.equals(a.uan(), b.uan())) {
            names.add("uan");
        }
        if (!Objects.equals(a.esiNumber(), b.esiNumber())) {
            names.add("esiNumber");
        }
        if (a.pfMember() != b.pfMember()) {
            names.add("pfMember");
        }
        if (a.ptWomenSlab() != b.ptWomenSlab()) {
            names.add("ptWomenSlab");
        }
        return names;
    }
}
