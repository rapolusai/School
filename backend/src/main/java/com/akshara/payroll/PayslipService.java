package com.akshara.payroll;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.fees.AmountInWords;
import com.akshara.payroll.PayrollSettings.Values;
import com.akshara.payroll.PayrollViews.LineView;
import com.akshara.payroll.PayrollViews.MyPayslipRow;
import com.akshara.payroll.PayrollViews.PayslipPerson;
import com.akshara.payroll.PayrollViews.PayslipView;
import com.akshara.payroll.PayrollViews.SchoolView;
import com.akshara.platform.SchoolProfile;
import com.akshara.platform.TenantDirectory;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;
import com.akshara.staff.StaffPayrollInputs;

/**
 * Payslips as printed: one, every payslip of a run (to print them all), and the signed-in staff member's own. Staff see
 * only their own payslips of approved or paid months; anyone else's, and drafts, are 404 to them. The PAN is masked
 * and only the last four digits of the bank account are shown.
 */
@Service
@Transactional(readOnly = true)
public class PayslipService {

    private final PayslipRepository payslips;
    private final PayslipLineRepository lines;
    private final PayrollRunRepository runs;
    private final PayrollSettingsService settings;
    private final TenantDirectory tenants;
    private final StaffPayrollInputs staff;

    PayslipService(PayslipRepository payslips, PayslipLineRepository lines, PayrollRunRepository runs,
            PayrollSettingsService settings, TenantDirectory tenants, StaffPayrollInputs staff) {
        this.payslips = payslips;
        this.lines = lines;
        this.runs = runs;
        this.settings = settings;
        this.tenants = tenants;
        this.staff = staff;
    }

    /** Any payslip of the school, draft or not (payroll.read). */
    public PayslipView payslip(UUID id) {
        TenantContext.require();
        Payslip slip = payslips.findById(id).orElseThrow(() -> ApiException.notFound("Payslip"));
        PayrollRun run = runs.findById(slip.getRunId()).orElseThrow(() -> ApiException.notFound("Payslip"));
        return views(run, List.of(slip)).getFirst();
    }

    /** Every payslip of a run, by name, to print them all at once. */
    public List<PayslipView> ofRun(UUID runId) {
        TenantContext.require();
        PayrollRun run = runs.findById(runId).orElseThrow(() -> ApiException.notFound("Payroll run"));
        return views(run, payslips.findByRun(runId));
    }

    /**
     * The signed-in person's payslips of approved and paid months, newest first. Parents and students have none
     * (403): payslips are for the school's staff.
     */
    public List<MyPayslipRow> mine(UUID userId) {
        TenantContext.require();
        requireStaff(userId);
        Map<UUID, PayrollRun> byId = runs.findAll().stream()
                .collect(Collectors.toMap(PayrollRun::getId, r -> r));
        return payslips.findLockedByUser(userId).stream().map(s -> {
            PayrollRun r = byId.get(s.getRunId());
            return new MyPayslipRow(s.getId(), r.yearMonth().toString(), r.getStatus(), r.getPaidOn(),
                    s.getGrossPaise(), s.getDeductionsPaise(), s.getNetPaise());
        }).toList();
    }

    /** One of the signed-in person's own payslips of an approved or paid month; any other is 404. */
    public PayslipView mine(UUID userId, UUID payslipId) {
        TenantContext.require();
        requireStaff(userId);
        Payslip slip = payslips.findById(payslipId).filter(s -> s.getUserId().equals(userId))
                .orElseThrow(() -> ApiException.notFound("Payslip"));
        PayrollRun run = runs.findById(slip.getRunId()).filter(r -> r.getStatus().locked())
                .orElseThrow(() -> ApiException.notFound("Payslip"));
        return views(run, List.of(slip)).getFirst();
    }

    private void requireStaff(UUID userId) {
        if (staff.person(userId).isEmpty()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Staff only", "Payslips are for the school's staff.");
        }
    }

    SchoolView school() {
        SchoolProfile p = tenants.profile(TenantContext.require()).orElseThrow(() -> ApiException.notFound("School"));
        Values v = settings.values();
        return new SchoolView(p.name(), p.address(), p.city(), p.phone(), p.contactEmail(), v.pfEstablishmentCode(),
                v.esiEmployerCode(), v.tan());
    }

    private List<PayslipView> views(PayrollRun run, List<Payslip> slips) {
        if (slips.isEmpty()) {
            return List.of();
        }
        SchoolView school = school();
        Collection<UUID> ids = slips.stream().map(Payslip::getId).toList();
        Map<UUID, List<PayslipLine>> byPayslip = lines.findByPayslips(ids).stream()
                .collect(Collectors.groupingBy(PayslipLine::getPayslipId));
        List<PayslipView> views = new ArrayList<>();
        for (Payslip s : slips) {
            List<PayslipLine> mine = byPayslip.getOrDefault(s.getId(), List.of());
            views.add(new PayslipView(s.getId(), run.getId(), run.yearMonth().toString(), run.getStatus(),
                    run.getPaidOn(), school,
                    new PayslipPerson(s.getUserId(), s.getEmployeeName(), s.getEmployeeCode(), s.getDesignation(),
                            s.getDepartmentName(), s.getDateOfJoining(), s.getDateOfLeaving(), s.getPanMasked(),
                            s.getBankLast4(), s.getBankIfsc(), s.getUan(), s.getEsiNumber()),
                    run.getProrationBasis(), s.getBasisDays(), s.getDaysOnRoll(), s.getLopDays(), s.getPayableDays(),
                    lines(mine, LineKind.EARNING), lines(mine, LineKind.DEDUCTION), lines(mine, LineKind.EMPLOYER),
                    s.getGrossPaise(), s.getDeductionsPaise(), s.getNetPaise(), s.getEmployerPaise(),
                    AmountInWords.rupees(s.getNetPaise()), s.getTdsProjectedIncomePaise(),
                    s.getTdsAnnualTaxPaise()));
        }
        return views;
    }

    private static List<LineView> lines(List<PayslipLine> all, LineKind kind) {
        return all.stream().filter(l -> l.getKind() == kind)
                .map(l -> new LineView(l.getCode(), l.getName(), l.getFullPaise(), l.getAmountPaise())).toList();
    }
}
