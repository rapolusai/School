package com.akshara.onboarding;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.audit.AuditService.Actor;
import com.akshara.payroll.PayStaffService;
import com.akshara.payroll.PayrollForms.PaidForm;
import com.akshara.payroll.PayrollForms.PayProfileForm;
import com.akshara.payroll.PayrollForms.SettingsForm;
import com.akshara.payroll.PayrollForms.StructureForm;
import com.akshara.payroll.PayrollForms.StructureLineInput;
import com.akshara.payroll.PayrollRunService;
import com.akshara.payroll.PayrollSettingsService;
import com.akshara.payroll.PayrollViews.ComponentView;
import com.akshara.payroll.PayrollViews.PayStaffRow;
import com.akshara.payroll.PayrollViews.RunDetail;
import com.akshara.payroll.ProrationBasis;
import com.akshara.payroll.SalaryComponentService;
import com.akshara.shared.TenantContext;

/**
 * The demo school's payroll: Telangana professional tax, the standard salary components, a salary structure and pay
 * details for every member of staff, and last month's payroll approved by the School Admin and paid on the 1st. Bank
 * details are clearly fake: account numbers start with 000000, the IFSC's bank code is AKSH (no such bank), PANs start
 * with DEMO, and UANs and ESI numbers are runs of zeros. Salaries follow the designation; the front office executive
 * and the part-time teacher earn under ₹21,000, so ESI applies to them.
 */
@Component
class DemoPayrollData {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    /** Monthly gross in rupees by designation; anyone else gets {@link #DEFAULT_GROSS}. */
    static final Map<String, Long> GROSS = Map.of(
            "Principal", 95_000L,
            "School Administrator", 70_000L,
            "Post Graduate Teacher (PGT)", 52_000L,
            "Trained Graduate Teacher (TGT)", 42_000L,
            "Primary Teacher (PRT)", 32_000L,
            "Accountant", 36_000L,
            "Front Office Executive", 18_500L,
            "Hindi Teacher", 16_000L);
    static final long DEFAULT_GROSS = 38_000L;

    private final PayrollSettingsService settings;
    private final SalaryComponentService components;
    private final PayStaffService staff;
    private final PayrollRunService runs;
    private final TransactionTemplate tx;

    DemoPayrollData(PayrollSettingsService settings, SalaryComponentService components, PayStaffService staff,
            PayrollRunService runs, PlatformTransactionManager transactionManager) {
        this.settings = settings;
        this.components = components;
        this.staff = staff;
        this.runs = runs;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Returns last month as "YYYY-MM" with the number of payslips in its paid run. */
    String seed(UUID tenantId, Map<String, UUID> userIds, UUID adminId) {
        Actor admin = new Actor(adminId, "Priya Nair");
        Actor accountant = new Actor(userIds.get("accounts" + DemoDataSeeder.DEMO_DOMAIN), "Meena Reddy");
        YearMonth month = YearMonth.now(INDIA).minusMonths(1);
        YearMonth yearStart = month.getMonthValue() >= 4 ? YearMonth.of(month.getYear(), 4)
                : YearMonth.of(month.getYear() - 1, 4);
        TenantContext.runAs(tenantId, () -> tx.execute(status -> {
            setUp(accountant, yearStart);
            return null;
        }));
        RunDetail run = TenantContext.runAs(tenantId, () -> tx.execute(status ->
                runs.create(month.toString(), accountant)));
        TenantContext.runAs(tenantId, () -> tx.execute(status -> runs.approve(run.id(), admin)));
        LocalDate paidOn = month.plusMonths(1).atDay(1);
        TenantContext.runAs(tenantId, () -> tx.execute(status -> runs.markPaid(run.id(),
                new PaidForm(paidOn, "DEMO-NEFT-" + month), accountant)));
        return month + " (" + run.totals().staffCount() + " payslips)";
    }

    private void setUp(Actor accountant, YearMonth yearStart) {
        settings.save(new SettingsForm(1, ProrationBasis.CALENDAR_DAYS, true, false, true, true, "TG",
                "TSHYD0000000000", "00000000000000000", "PTIN00000000", "HYDA00000A", "Demo Bank",
                "Banjara Hills", "000000900001", "AKSH0000001"), accountant);
        Map<String, UUID> ids = components.addStandard(accountant).stream()
                .collect(Collectors.toMap(ComponentView::code, ComponentView::id));
        int n = 1;
        for (PayStaffRow person : staff.list().staff()) {
            if (!person.active()) {
                continue;
            }
            String serial = String.format("%04d", n);
            staff.saveProfile(person.userId(), new PayProfileForm("00000010" + serial, "AKSH0000001", null,
                    "DEMOP" + serial + "A", "00000000" + serial, "000000" + serial, true, false), accountant);
            long gross = GROSS.getOrDefault(person.designation(), DEFAULT_GROSS);
            YearMonth from = yearStart;
            if (person.dateOfJoining() != null && YearMonth.from(person.dateOfJoining()).isAfter(from)) {
                from = YearMonth.from(person.dateOfJoining());
            }
            staff.createStructure(person.userId(), new StructureForm(from.toString(), "Demo salary",
                    lines(ids, gross)), accountant);
            n++;
        }
    }

    /**
     * Basic 50%, DA 10%, HRA 20%, conveyance ₹1,600 and the rest as special allowance; a ₹2,000 monthly loan
     * recovery for those earning ₹40,000 or more.
     */
    static List<StructureLineInput> lines(Map<String, UUID> ids, long grossRupees) {
        long basic = grossRupees * 50 / 100;
        long da = grossRupees * 10 / 100;
        long hra = grossRupees * 20 / 100;
        long conveyance = 1_600;
        long special = grossRupees - basic - da - hra - conveyance;
        List<StructureLineInput> lines = new ArrayList<>();
        lines.add(new StructureLineInput(ids.get("BASIC"), basic * 100));
        lines.add(new StructureLineInput(ids.get("DA"), da * 100));
        lines.add(new StructureLineInput(ids.get("HRA"), hra * 100));
        lines.add(new StructureLineInput(ids.get("CONVEYANCE"), conveyance * 100));
        if (special > 0) {
            lines.add(new StructureLineInput(ids.get("SPECIAL"), special * 100));
        }
        if (grossRupees >= 40_000) {
            lines.add(new StructureLineInput(ids.get("LOAN"), 2_000_00L));
        }
        return lines;
    }
}
