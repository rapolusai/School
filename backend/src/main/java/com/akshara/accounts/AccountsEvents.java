package com.akshara.accounts;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.accounts.AccountsViews.FeeDay;
import com.akshara.accounts.AccountsViews.FeeReconciliation;
import com.akshara.accounts.AccountsViews.PayrollCostView;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.fees.FeeInsights;
import com.akshara.fees.FeeInsights.DayTotal;
import com.akshara.fees.FeePaymentReceived;
import com.akshara.fees.FeeReportService;
import com.akshara.fees.FeeViews.ModeTotal;
import com.akshara.fees.PaymentMode;
import com.akshara.payroll.PayrollApproved;
import com.akshara.shared.TenantContext;

/**
 * What arrives from the other modules: fee income per day from {@link FeePaymentReceived} (inside the receipt's
 * transaction, so a rolled-back payment leaves nothing) and payroll cost per month from {@link PayrollApproved}.
 * The reconciliation screen compares the daily fee summary with the fees module's own totals, read through its
 * public services, and flags the days that differ (a cancelled receipt, for example); a rebuild copies the fees
 * module's figures over.
 */
@Service
public class AccountsEvents {

    private static final Logger log = LoggerFactory.getLogger(AccountsEvents.class);

    private final FeeIncomeDayRepository feeDays;
    private final PayrollCostRepository payrollCosts;
    private final FeeInsights feeInsights;
    private final FeeReportService feeReports;
    private final AuditService audit;
    private final Recorder recorder;

    AccountsEvents(FeeIncomeDayRepository feeDays, PayrollCostRepository payrollCosts, FeeInsights feeInsights,
            FeeReportService feeReports, AuditService audit, Recorder recorder) {
        this.feeDays = feeDays;
        this.payrollCosts = payrollCosts;
        this.feeInsights = feeInsights;
        this.feeReports = feeReports;
        this.audit = audit;
        this.recorder = recorder;
    }

    /*
     * The listeners are not transactional themselves: the school is selected first (the event names it), and only
     * then does the recorder's transaction start, so the database session carries the tenant. Inside the publisher's
     * transaction (a fee receipt, a payroll approval) the recorder joins it.
     */

    @EventListener
    public void paymentReceived(FeePaymentReceived event) {
        TenantContext.runAs(event.tenantId(), () -> {
            recorder.feePayment(event);
            return null;
        });
    }

    @EventListener
    public void payrollApproved(PayrollApproved event) {
        TenantContext.runAs(event.tenantId(), () -> {
            recorder.payrollApproved(event);
            return null;
        });
    }

    /** The transactional half of the listeners. */
    @Service
    @Transactional
    static class Recorder {

        private final FeeIncomeDayRepository feeDays;
        private final PayrollCostRepository payrollCosts;

        Recorder(FeeIncomeDayRepository feeDays, PayrollCostRepository payrollCosts) {
            this.feeDays = feeDays;
            this.payrollCosts = payrollCosts;
        }

        public void feePayment(FeePaymentReceived event) {
            LocalDate day = event.receivedAt().atZone(FinancialYear.INDIA).toLocalDate();
            FeeIncomeDay d = feeDays.findByDay(day).orElseGet(() -> feeDays.save(new FeeIncomeDay(day)));
            d.add(event.amountPaise(), event.mode() == PaymentMode.CASH);
            feeDays.flush();
        }

        public void payrollApproved(PayrollApproved event) {
            LocalDate month = event.month().atDay(1);
            if (payrollCosts.findByMonth(month).isPresent()) {
                log.warn("Payroll cost for {} already recorded; ignoring a second approval event", month);
                return;
            }
            payrollCosts.save(new PayrollCost(month, event.runId(), event.staffCount(), event.grossPaise(),
                    event.deductionsPaise(), event.netPaise(), event.employerContributionsPaise(),
                    event.approvedAt()));
            payrollCosts.flush();
        }
    }

    @Transactional(readOnly = true)
    public List<PayrollCostView> payrollCosts(FinancialYear year) {
        TenantContext.require();
        return payrollCosts.findBetween(year.start(), year.end()).stream().map(AccountsEvents::view).toList();
    }

    static PayrollCostView view(PayrollCost p) {
        return new PayrollCostView(p.getMonth().toString().substring(0, 7), p.getRunId(), p.getStaffCount(),
                p.getGrossPaise(), p.getEmployerPaise(), p.costPaise(), p.getNetPaise(), p.getApprovedAt());
    }

    /** Day by day: what this module counted against what the fees module reports, with the difference. */
    @Transactional(readOnly = true)
    public FeeReconciliation feeReconciliation(LocalDate from, LocalDate to) {
        TenantContext.require();
        AccountsAccess.period(from, to);
        Map<LocalDate, FeeIncomeDay> mine = feeDays.findBetween(from, to).stream()
                .collect(Collectors.toMap(FeeIncomeDay::getDay, Function.identity()));
        List<FeeDay> days = new ArrayList<>();
        long accounts = 0;
        long fees = 0;
        int differing = 0;
        for (DayTotal t : feeInsights.collectedPerDay(from, to)) {
            FeeIncomeDay d = mine.get(t.date());
            long a = d == null ? 0 : d.getAmountPaise();
            int c = d == null ? 0 : d.getReceiptCount();
            if (a == 0 && t.amountPaise() == 0 && c == 0 && t.receiptCount() == 0) {
                continue;
            }
            long diff = a - t.amountPaise();
            if (diff != 0 || c != t.receiptCount()) {
                differing++;
            }
            days.add(new FeeDay(t.date(), a, c, t.amountPaise(), t.receiptCount(), diff));
            accounts += a;
            fees += t.amountPaise();
        }
        return new FeeReconciliation(from, to, days, accounts, fees, accounts - fees, differing);
    }

    /** Replaces the daily summary of the period with the fees module's own figures. */
    @Transactional
    public FeeReconciliation rebuildFeeIncome(LocalDate from, LocalDate to, Actor actor) {
        TenantContext.require();
        AccountsAccess.period(from, to);
        feeDays.deleteBetween(from, to);
        feeDays.flush();
        int days = 0;
        for (DayTotal t : feeInsights.collectedPerDay(from, to)) {
            if (t.receiptCount() == 0) {
                continue;
            }
            long cash = feeReports.collection(t.date(), t.date()).byMode().stream()
                    .filter(m -> m.mode() == PaymentMode.CASH).mapToLong(ModeTotal::amountPaise).sum();
            FeeIncomeDay d = new FeeIncomeDay(t.date());
            d.set(t.amountPaise(), cash, (int) t.receiptCount());
            feeDays.save(d);
            days++;
        }
        feeDays.flush();
        audit.record(actor, "fee_income.rebuilt", "fee_income_day", null, Map.of("from", from.toString(), "to",
                to.toString(), "days", days));
        return feeReconciliation(from, to);
    }

    static UUID none() {
        return null;
    }
}
