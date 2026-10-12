package com.akshara.onboarding;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.accounts.AccountsEvents;
import com.akshara.accounts.AccountsForms.ApproveForm;
import com.akshara.accounts.AccountsForms.ApproverInput;
import com.akshara.accounts.AccountsForms.ApproversForm;
import com.akshara.accounts.AccountsForms.BankAccountForm;
import com.akshara.accounts.AccountsForms.BudgetForm;
import com.akshara.accounts.AccountsForms.CostCentreForm;
import com.akshara.accounts.AccountsForms.ExpenseForm;
import com.akshara.accounts.AccountsForms.IncomeForm;
import com.akshara.accounts.AccountsForms.OpeningBalanceInput;
import com.akshara.accounts.AccountsForms.OpeningBalancesForm;
import com.akshara.accounts.AccountsForms.PaidForm;
import com.akshara.accounts.AccountsForms.PettyCashForm;
import com.akshara.accounts.AccountsForms.RecurringForm;
import com.akshara.accounts.AccountsForms.SettingsForm;
import com.akshara.accounts.AccountsForms.VendorForm;
import com.akshara.accounts.AccountsSettingsService;
import com.akshara.accounts.AccountsViews.ExpenseDetail;
import com.akshara.accounts.AccountsViews.HeadView;
import com.akshara.accounts.BankService;
import com.akshara.accounts.BudgetMode;
import com.akshara.accounts.BudgetService;
import com.akshara.accounts.BudgetSpread;
import com.akshara.accounts.ChartService;
import com.akshara.accounts.ExpenseService;
import com.akshara.accounts.FinancialYear;
import com.akshara.accounts.IncomeKind;
import com.akshara.accounts.IncomeService;
import com.akshara.accounts.PayMode;
import com.akshara.accounts.PettyCashService;
import com.akshara.accounts.PettyKind;
import com.akshara.accounts.VendorService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.TenantContext;

/**
 * The demo school's management accounts: the standard chart with the fee heads mapped, cost centres, vendors, two
 * bank accounts with this year's opening balances, this year's budget, about forty expenses spread over the last six
 * months (most paid, a few waiting for approval, one passed on to the admin, one rejected), a recurring rent, petty
 * cash, donations and a grant, and the fee income summary rebuilt from the demo receipts (the payroll cost arrived
 * with last month's approved run).
 */
@Component
class DemoAccountsData {

    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private record Bill(String head, int monthsAgo, int day, long rupees, boolean gst, String vendor, String what) {
    }

    /** Six months of bills; the amounts are in rupees. */
    static final List<Bill> BILLS = List.of(
            new Bill("ELECTRICITY", 5, 8, 42_300, true, "TSSPDCL", "Electricity bill"),
            new Bill("ELECTRICITY", 4, 8, 47_950, true, "TSSPDCL", "Electricity bill"),
            new Bill("ELECTRICITY", 3, 8, 51_200, true, "TSSPDCL", "Electricity bill"),
            new Bill("ELECTRICITY", 2, 8, 38_400, true, "TSSPDCL", "Electricity bill"),
            new Bill("ELECTRICITY", 1, 8, 36_750, true, "TSSPDCL", "Electricity bill"),
            new Bill("WATER", 5, 12, 6_200, false, "HMWSSB", "Water charges"),
            new Bill("WATER", 3, 12, 6_800, false, "HMWSSB", "Water charges"),
            new Bill("WATER", 1, 12, 6_400, false, "HMWSSB", "Water charges"),
            new Bill("REPAIRS", 5, 15, 18_500, true, "Sharma Electricals", "Fan and wiring repairs, Block B"),
            new Bill("REPAIRS", 4, 20, 64_000, true, "Sri Lakshmi Constructions", "Roof waterproofing"),
            new Bill("REPAIRS", 2, 3, 9_800, true, "Sharma Electricals", "Lab bench repairs"),
            new Bill("REPAIRS", 1, 18, 22_400, true, "Sri Lakshmi Constructions", "Boundary wall plastering"),
            new Bill("STATIONERY", 5, 2, 31_600, true, "Vijaya Stationers", "Notebooks and registers"),
            new Bill("STATIONERY", 4, 2, 12_300, true, "Vijaya Stationers", "Printer paper and toner"),
            new Bill("STATIONERY", 3, 2, 8_900, true, "Vijaya Stationers", "Exam answer booklets"),
            new Bill("STATIONERY", 2, 2, 14_750, true, "Vijaya Stationers", "Chart paper, markers"),
            new Bill("STATIONERY", 1, 2, 10_200, true, "Vijaya Stationers", "Report card printing"),
            new Bill("FUEL", 5, 28, 48_000, false, "Bharat Petroleum, Kukatpally", "Diesel for buses"),
            new Bill("FUEL", 4, 28, 51_500, false, "Bharat Petroleum, Kukatpally", "Diesel for buses"),
            new Bill("FUEL", 3, 28, 46_200, false, "Bharat Petroleum, Kukatpally", "Diesel for buses"),
            new Bill("FUEL", 2, 28, 49_800, false, "Bharat Petroleum, Kukatpally", "Diesel for buses"),
            new Bill("FUEL", 1, 28, 50_300, false, "Bharat Petroleum, Kukatpally", "Diesel for buses"),
            new Bill("EVENTS", 4, 14, 27_500, true, "Kalyan Tent House", "Independence Day stage and sound"),
            new Bill("EVENTS", 2, 25, 18_000, true, "Kalyan Tent House", "Teachers' Day function"),
            new Bill("EVENTS", 1, 10, 42_000, true, "Kalyan Tent House", "Annual sports day pandal"),
            new Bill("TAXES", 5, 20, 24_000, false, "GHMC", "Property tax, first half"),
            new Bill("TAXES", 3, 5, 3_500, false, "Transport department", "Bus fitness certificates"),
            new Bill("OTHER_EXPENSE", 5, 22, 7_200, true, "Hindustan Pest Control", "Quarterly pest control"),
            new Bill("OTHER_EXPENSE", 2, 22, 7_200, true, "Hindustan Pest Control", "Quarterly pest control"),
            new Bill("OTHER_EXPENSE", 4, 9, 15_000, true, "Dr Rao Consulting", "Fire safety audit"),
            new Bill("OTHER_EXPENSE", 3, 17, 5_600, true, "Vijaya Stationers", "First-aid kits"),
            new Bill("OTHER_EXPENSE", 1, 6, 4_400, false, "BSNL", "Landline and broadband"));

    /** This month's bills: drafts and ones waiting for approval. */
    static final List<Bill> PENDING = List.of(
            new Bill("ELECTRICITY", 0, 8, 39_100, true, "TSSPDCL", "Electricity bill"),
            new Bill("REPAIRS", 0, 4, 1_85_000, true, "Sri Lakshmi Constructions", "Science lab renovation"),
            new Bill("STATIONERY", 0, 3, 11_400, true, "Vijaya Stationers", "Half-yearly exam papers"),
            new Bill("EVENTS", 0, 6, 32_000, true, "Kalyan Tent House", "Annual day stage and lights"),
            new Bill("FUEL", 0, 5, 23_600, false, "Bharat Petroleum, Kukatpally", "Diesel for buses"),
            new Bill("REPAIRS", 0, 2, 6_500, true, "Sharma Electricals", "Replace corridor lights"));

    private record Donation(IncomeKind kind, String head, int monthsAgo, int day, long rupees, String payer,
            String pan, PayMode mode, String reference) {
    }

    static final List<Donation> DONATIONS = List.of(
            new Donation(IncomeKind.DONATION, "DONATIONS", 5, 10, 50_000, "Ramesh Gupta", "ABCPG1234H", PayMode.BANK,
                    "NEFT-SBIN-55012"),
            new Donation(IncomeKind.DONATION, "DONATIONS", 3, 21, 25_000, "Sunrise Rotary Club", "AAATS1234K",
                    PayMode.CHEQUE, "004512"),
            new Donation(IncomeKind.GRANT, "GRANTS", 4, 1, 2_00_000, "Samagra Shiksha, Telangana", null,
                    PayMode.BANK, "RTGS-SBIN-90021"),
            new Donation(IncomeKind.RENTAL, "RENTALS", 2, 15, 12_000, "Kukatpally Yoga Circle", null, PayMode.UPI,
                    "UPI-884321"),
            new Donation(IncomeKind.RENTAL, "RENTALS", 1, 15, 12_000, "Kukatpally Yoga Circle", null, PayMode.UPI,
                    "UPI-901877"),
            new Donation(IncomeKind.MISC, "OTHER_INCOME", 1, 27, 3_200, "Old newspapers sale", null, PayMode.CASH,
                    null),
            new Donation(IncomeKind.DONATION, "DONATIONS", 0, 2, 10_000, "Anita Deshmukh", "ABCPD5678M",
                    PayMode.UPI, "UPI-110233"));

    static final Map<String, Long> BUDGET_RUPEES = Map.ofEntries(
            Map.entry("SALARIES", 1_20_00_000L), Map.entry("ELECTRICITY", 5_40_000L), Map.entry("WATER", 80_000L),
            Map.entry("REPAIRS", 4_00_000L), Map.entry("STATIONERY", 1_60_000L), Map.entry("FUEL", 6_00_000L),
            Map.entry("EVENTS", 1_50_000L), Map.entry("RENT", 0L), Map.entry("TAXES", 60_000L),
            Map.entry("PETTY_CASH", 60_000L), Map.entry("OTHER_EXPENSE", 1_20_000L),
            Map.entry("FEES", 2_40_00_000L), Map.entry("DONATIONS", 2_00_000L), Map.entry("GRANTS", 5_00_000L),
            Map.entry("RENTALS", 1_44_000L), Map.entry("OTHER_INCOME", 30_000L));

    private final AccountsSettingsService settings;
    private final ChartService chart;
    private final VendorService vendors;
    private final BankService bank;
    private final BudgetService budgets;
    private final ExpenseService expenses;
    private final PettyCashService pettyCash;
    private final IncomeService income;
    private final AccountsEvents events;
    private final TransactionTemplate tx;

    DemoAccountsData(AccountsSettingsService settings, ChartService chart, VendorService vendors, BankService bank,
            BudgetService budgets, ExpenseService expenses, PettyCashService pettyCash, IncomeService income,
            AccountsEvents events, PlatformTransactionManager transactionManager) {
        this.settings = settings;
        this.chart = chart;
        this.vendors = vendors;
        this.bank = bank;
        this.budgets = budgets;
        this.expenses = expenses;
        this.pettyCash = pettyCash;
        this.income = income;
        this.events = events;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /** Returns the number of expenses recorded. */
    int seed(UUID tenantId, Map<String, UUID> userIds, UUID adminId) {
        Actor admin = new Actor(adminId, "Priya Nair");
        Actor accountant = new Actor(userIds.get("accounts" + DemoDataSeeder.DEMO_DOMAIN), "Meena Reddy");
        UUID principalId = userIds.get("principal" + DemoDataSeeder.DEMO_DOMAIN);
        Actor principal = new Actor(principalId, "Lakshmi Iyer");
        return TenantContext.runAs(tenantId, () -> tx.execute(status -> seed(admin, accountant, principal)));
    }

    private int seed(Actor admin, Actor accountant, Actor principal) {
        LocalDate today = LocalDate.now(INDIA);
        FinancialYear year = FinancialYear.of(today);
        settings.save(new SettingsForm(BudgetMode.WARN, true, "AAATA1234FF20214", 10_000_00L), admin);
        settings.saveApprovers(new ApproversForm(List.of(new ApproverInput(principal.id(), 1_00_000_00L),
                new ApproverInput(admin.id(), null))), admin);
        Map<String, UUID> heads = new java.util.HashMap<>();
        for (HeadView h : chart.addStandardHeads(accountant)) {
            if (h.systemCode() != null) {
                heads.put(h.systemCode(), h.id());
            }
        }
        UUID primary = chart.createCostCentre(new CostCentreForm("PRI", "Primary wing", true), accountant).id();
        UUID secondary = chart.createCostCentre(new CostCentreForm("SEC", "Secondary wing", true), accountant).id();
        UUID transport = chart.createCostCentre(new CostCentreForm("TRN", "Transport", true), accountant).id();
        chart.createCostCentre(new CostCentreForm("ADM", "Administration", true), accountant);

        Map<String, UUID> vendorIds = new java.util.HashMap<>();
        vendorIds.put("TSSPDCL", vendor(accountant, "TSSPDCL", "36AAACT4738N1ZV", null, null, null, null));
        vendorIds.put("HMWSSB", vendor(accountant, "HMWSSB", null, null, null, null, null));
        vendorIds.put("Sharma Electricals", vendor(accountant, "Sharma Electricals", "36AABCU9603R1ZX",
                "AABCU9603R", "9876543210", "194C", "1"));
        vendorIds.put("Sri Lakshmi Constructions", vendor(accountant, "Sri Lakshmi Constructions",
                "36AAECS1234F1Z5", "AAECS1234F", "9848012345", "194C", "2"));
        vendorIds.put("Vijaya Stationers", vendor(accountant, "Vijaya Stationers", "36AAIFV2345G1ZK", "AAIFV2345G",
                "9701234567", null, null));
        vendorIds.put("Bharat Petroleum, Kukatpally", vendor(accountant, "Bharat Petroleum, Kukatpally",
                "36AAACB2902M1ZQ", null, null, null, null));
        vendorIds.put("Kalyan Tent House", vendor(accountant, "Kalyan Tent House", null, "AKDPK4321L",
                "9949012345", "194C", "1"));
        vendorIds.put("Hindustan Pest Control", vendor(accountant, "Hindustan Pest Control", "36AADCH1111A1Z2",
                "AADCH1111A", "9123456780", "194C", "2"));
        vendorIds.put("Dr Rao Consulting", vendor(accountant, "Dr Rao Consulting", null, "ABCPR1234K",
                "9000011122", "194J", "10"));

        UUID sbi = bank.create(new BankAccountForm("SBI current account", "State Bank of India", "000031245678901",
                "SBIN0002743", true), accountant).id();
        UUID hdfc = bank.create(new BankAccountForm("HDFC fee collection", "HDFC Bank", "50100223344556",
                "HDFC0001234", true), accountant).id();
        bank.saveOpeningBalances(year, new OpeningBalancesForm(List.of(
                new OpeningBalanceInput(null, 18_450_00L), new OpeningBalanceInput(sbi, 7_82_500_00L),
                new OpeningBalanceInput(hdfc, 12_40_000_00L))), accountant);

        BUDGET_RUPEES.forEach((code, rupees) -> {
            if (heads.containsKey(code) && rupees > 0) {
                budgets.save(year, new BudgetForm(heads.get(code), rupees * 100, BudgetSpread.EVEN, null),
                        accountant);
            }
        });

        // Paid bills of the last six months (dates never in the future).
        int count = 0;
        for (Bill b : BILLS) {
            LocalDate date = dateOf(today, b.monthsAgo(), b.day());
            if (date.isAfter(today) || date.isBefore(year.start())) {
                continue;
            }
            UUID centre = b.head().equals("FUEL") ? transport : b.monthsAgo() % 2 == 0 ? primary : secondary;
            ExpenseDetail e = create(accountant, heads, vendorIds, b, date, centre, true);
            expenses.approve(e.id(), new ApproveForm(null), e.totalPaise() > 1_00_000_00L ? admin : principal);
            boolean byBank = b.rupees() > 10_000;
            LocalDate paidOn = date.plusDays(byBank ? 6 : 1);
            if (paidOn.isAfter(today)) {
                paidOn = today;
            }
            expenses.markPaid(e.id(), new PaidForm(paidOn, byBank ? PayMode.BANK : PayMode.CASH, byBank ? sbi : null,
                    byBank ? "NEFT-" + paidOn.getMonthValue() + String.format("%03d", count + 1) : null),
                    accountant);
            count++;
        }
        // This month: one draft, bills waiting for approval, one passed on to the admin, one rejected.
        List<ExpenseDetail> pending = new ArrayList<>();
        for (int i = 0; i < PENDING.size(); i++) {
            Bill b = PENDING.get(i);
            LocalDate date = dateOf(today, 0, b.day());
            if (date.isAfter(today)) {
                date = today;
            }
            pending.add(create(accountant, heads, vendorIds, b, date, i % 2 == 0 ? primary : secondary, i > 0));
            count++;
        }
        // The lab renovation is above the principal's limit: she passes it on; the corridor lights are rejected.
        expenses.approve(pending.get(1).id(), new ApproveForm("Above my limit, for the admin"), principal);
        expenses.reject(pending.get(5).id(), new com.akshara.accounts.AccountsForms.RejectForm(
                "Please get a second quotation."), principal);

        // Recurring: office rent for the annexe, generated for this month as a draft.
        expenses.createRecurring(new RecurringForm("Annexe rent", heads.get("RENT"), null, null, 25_000_00L, 0L, 5,
                "Rent for the annexe building", true), accountant);
        expenses.generate(new com.akshara.accounts.AccountsForms.GenerateForm(YearMonth.from(today).toString()),
                accountant);
        count++;

        // Petty cash: a float topped up each month, small spends against it.
        for (int monthsAgo = 5; monthsAgo >= 0; monthsAgo--) {
            LocalDate first = dateOf(today, monthsAgo, 1);
            if (first.isAfter(today) || first.isBefore(year.start())) {
                continue;
            }
            pettyCash.record(new PettyCashForm(first, PettyKind.TOP_UP, monthsAgo == 5 ? 10_000_00L : 6_500_00L,
                    null, "Petty cash top-up"), accountant);
            petty(accountant, heads, today, monthsAgo, 4, "STATIONERY", 420_00, "Marker pens, staples");
            petty(accountant, heads, today, monthsAgo, 9, "REPAIRS", 850_00, "Plumber, tap washer");
            petty(accountant, heads, today, monthsAgo, 16, "OTHER_EXPENSE", 1_150_00, "Tea and snacks, staff meeting");
            petty(accountant, heads, today, monthsAgo, 22, "FUEL", 600_00, "Auto fare, bank visits");
            petty(accountant, heads, today, monthsAgo, 26, "OTHER_EXPENSE", 2_300_00, "Courier and postage");
        }

        // Donations, a grant, rentals and a small sale.
        for (Donation d : DONATIONS) {
            LocalDate date = dateOf(today, d.monthsAgo(), d.day());
            if (date.isAfter(today) || date.isBefore(year.start())) {
                continue;
            }
            UUID account = d.mode() == PayMode.CASH ? null : d.kind() == IncomeKind.GRANT ? sbi : hdfc;
            income.record(new IncomeForm(d.kind(), heads.get(d.head()), date, d.rupees() * 100, d.payer(), d.pan(),
                    "Kukatpally, Hyderabad", d.mode(), account, d.reference(), null), accountant);
        }

        // The fee income summary, consistent with the demo receipts (the bounced cheque was cancelled).
        events.rebuildFeeIncome(year.start(), today, accountant);
        return count;
    }

    private ExpenseDetail create(Actor accountant, Map<String, UUID> heads, Map<String, UUID> vendorIds, Bill b,
            LocalDate date, UUID centre, boolean submit) {
        long amount = b.rupees() * 100;
        long gst = b.gst() ? amount * 18 / 100 : 0;
        UUID vendorId = vendorIds.get(b.vendor());
        boolean tds = vendorId != null && b.gst() && (b.vendor().contains("Constructions")
                || b.vendor().contains("Sharma") || b.vendor().contains("Kalyan") || b.vendor().contains("Rao")
                || b.vendor().contains("Pest"));
        return expenses.create(new ExpenseForm(heads.get(b.head()), centre, vendorId, vendorId == null
                ? b.vendor() : null, date, amount, gst, tds, b.what(), "INV-" + date.getMonthValue() + "-"
                + b.day()), submit, accountant);
    }

    private void petty(Actor accountant, Map<String, UUID> heads, LocalDate today, int monthsAgo, int day,
            String head, long paise, String what) {
        LocalDate date = dateOf(today, monthsAgo, day);
        if (!date.isAfter(today)) {
            pettyCash.record(new PettyCashForm(date, PettyKind.SPENT, paise, heads.get(head), what), accountant);
        }
    }

    private UUID vendor(Actor accountant, String name, String gstin, String pan, String phone, String section,
            String rate) {
        return vendors.create(new VendorForm(name, gstin, pan, phone, null, "Hyderabad", null, null, section,
                rate == null ? null : new BigDecimal(rate), true), accountant).id();
    }

    private static LocalDate dateOf(LocalDate today, int monthsAgo, int day) {
        YearMonth m = YearMonth.from(today).minusMonths(monthsAgo);
        return m.atDay(Math.min(day, m.lengthOfMonth()));
    }
}
