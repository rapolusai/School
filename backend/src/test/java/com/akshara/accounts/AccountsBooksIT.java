package com.akshara.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.akshara.support.AccountsFixtures;
import com.akshara.support.FeeFixtures;
import com.akshara.support.FeeFixtures.Heads;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;

/**
 * Money in and the books: other income with receipts (and 80G numbers), fee income arriving from the fees module's
 * event and reconciled against it, payroll cost from the payroll module's event, bank and cash books, the cash
 * position, bank reconciliation with a statement import, vendors with masked details, setup conflicts, the reports
 * with their CSVs and the dashboard's finance card.
 */
class AccountsBooksIT extends AccountsIntegrationTest {

    @Autowired
    ApplicationEventPublisher events;

    static final String FY = AccountsFixtures.financialYear();
    static final LocalDate TODAY = AccountsFixtures.today();
    static final String RANGE = "from=" + TODAY.minusDays(30) + "&to=" + TODAY;

    @Test
    void otherIncomeGetsNumberedReceiptsAndAnEightyGNumberWhenRegistered() throws Exception {
        AccountsSchool s = new AccountsSchool();
        String bank = s.fixtures.bankAccount(s.accountant, "SBI current");
        api.put("/api/accounts/settings", s.admin.accessToken(), """
                {"budgetMode":"WARN","donation80gRegistered":true,"donation80gNumber":"AAATA1234FF20214",
                 "pettyCashFloatPaise":0}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.donation80gRegistered").value(true));
        // A donation needs the donor's PAN for the 80G receipt; a bank receipt needs its account.
        api.post("/api/accounts/income", s.accountant.accessToken(), """
                {"kind":"DONATION","headId":"%s","receivedOn":"%s","amountPaise":500000,"payerName":"Ramesh",
                 "mode":"BANK","bankAccountId":"%s"}""".formatted(s.donations, TODAY, bank))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.payerPan").exists());
        api.post("/api/accounts/income", s.accountant.accessToken(), """
                {"kind":"DONATION","headId":"%s","receivedOn":"%s","amountPaise":500000,"payerName":"Ramesh",
                 "payerPan":"ABCPG1234H","mode":"UPI"}""".formatted(s.donations, TODAY))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.bankAccountId").exists());
        api.post("/api/accounts/income", s.accountant.accessToken(), """
                {"kind":"DONATION","headId":"%s","receivedOn":"%s","amountPaise":500000,"payerName":"Ramesh",
                 "payerPan":"ABCPG1234H","mode":"BANK","bankAccountId":"%s"}"""
                .formatted(s.electricity, TODAY, bank))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.headId").exists());
        String donation = s.fixtures.income(s.accountant, s.donations, "DONATION", 500000, "BANK", bank);
        api.get("/api/accounts/income/" + donation, s.principal.accessToken())
                .andExpect(jsonPath("$.receiptNo").value("INC/" + FY + "/000001"))
                .andExpect(jsonPath("$.eightyGNumber").value("80G/" + FY + "/000001"))
                .andExpect(jsonPath("$.eightyGRegistration").value("AAATA1234FF20214"))
                .andExpect(jsonPath("$.payerPanMasked").value("XXXXXX234H"))
                .andExpect(jsonPath("$.amountInWords").value("Rupees Five Thousand Only"))
                .andExpect(jsonPath("$.bankAccountName").value("SBI current"))
                .andExpect(jsonPath("$.school.name").exists())
                .andExpect(jsonPath("$.status").value("ISSUED"));
        String rent = s.fixtures.income(s.accountant, s.rentals, "RENTAL", 120000, "CASH", null);
        api.get("/api/accounts/income/" + rent, s.accountant.accessToken())
                .andExpect(jsonPath("$.receiptNo").value("INC/" + FY + "/000002"))
                .andExpect(jsonPath("$.eightyGNumber").doesNotExist());
        // Cancelled with a reason, never edited; a second cancellation is a conflict.
        api.post("/api/accounts/income/" + rent + "/cancel", s.accountant.accessToken(), """
                {"reason":"Wrong tenant"}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("Wrong tenant"));
        api.post("/api/accounts/income/" + rent + "/cancel", s.accountant.accessToken(), """
                {"reason":"Again"}""").andExpect(status().isConflict());
        api.get("/api/accounts/income?" + RANGE, s.principal.accessToken()).andExpect(jsonPath("$", hasSize(2)));
        api.get("/api/accounts/income/" + rent, new AccountsSchool().admin.accessToken())
                .andExpect(status().isNotFound());
        api.post("/api/accounts/income/" + rent + "/cancel", s.principal.accessToken(), """
                {"reason":"x"}""").andExpect(status().isForbidden());
        assertThat(audit(s.admin, "income_receipt.issued")).hasSize(2);
        assertThat(audit(s.admin, "income_receipt.cancelled")).hasSize(1);
    }

    @Test
    void feeIncomeArrivesWithEachReceiptAndReconcilesAgainstTheFeesModule() throws Exception {
        AccountsSchool s = new AccountsSchool();
        SchoolFixtures school = new SchoolFixtures(api);
        String yearId = school.currentYear(s.admin);
        String classId = school.schoolClass(s.admin, "Class 1");
        String section = school.section(s.admin, classId, "A", 30);
        String student = school.student(s.admin, section, "A-1", "Asha", null, "Lata Rao", "9876501001");
        FeeFixtures fees = new FeeFixtures(api, mvc);
        Heads heads = fees.defaultHeads(s.admin);
        fees.publishedStructure(s.admin, yearId, classId, heads);
        // The fee heads created after the chart are mapped by adding the standard heads again.
        api.post("/api/accounts/heads/standard", s.accountant.accessToken(), null).andExpect(status().isOk());
        // Two cash receipts today, one of them cancelled afterwards.
        fees.cash(s.admin, student, 1_000_00);
        String second = fees.cash(s.admin, student, 500_00);
        String reconcile = "/api/accounts/fee-reconciliation?from=" + TODAY.minusDays(7) + "&to=" + TODAY;
        api.get(reconcile, s.accountant.accessToken())
                .andExpect(jsonPath("$.days", hasSize(1)))
                .andExpect(jsonPath("$.days[0].accountsPaise").value(150000))
                .andExpect(jsonPath("$.days[0].accountsCount").value(2))
                .andExpect(jsonPath("$.days[0].feesPaise").value(150000))
                .andExpect(jsonPath("$.daysWithDifferences").value(0));
        api.post("/api/fees/receipts/" + second + "/cancel", s.admin.accessToken(), """
                {"reason":"Duplicate"}""").andExpect(status().isOk());
        // The fees module now says 1,000; accounts still counts 1,500: the day is flagged.
        api.get(reconcile, s.principal.accessToken())
                .andExpect(jsonPath("$.days[0].feesPaise").value(100000))
                .andExpect(jsonPath("$.days[0].differencePaise").value(50000))
                .andExpect(jsonPath("$.daysWithDifferences").value(1))
                .andExpect(jsonPath("$.differencePaise").value(50000));
        api.post("/api/accounts/fee-reconciliation/rebuild?from=" + TODAY.minusDays(7) + "&to=" + TODAY,
                s.principal.accessToken(), null).andExpect(status().isForbidden());
        api.post("/api/accounts/fee-reconciliation/rebuild?from=" + TODAY.minusDays(7) + "&to=" + TODAY,
                s.accountant.accessToken(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.days[0].accountsPaise").value(100000))
                .andExpect(jsonPath("$.daysWithDifferences").value(0));
        // The cash book shows the day's fee collections in cash; the fee head is in the standard chart.
        api.get("/api/accounts/books?" + RANGE, s.accountant.accessToken())
                .andExpect(jsonPath("$.rows[?(@.kind == 'FEES')].receiptPaise").value(100000))
                .andExpect(jsonPath("$.closingPaise").value(100000));
        api.get("/api/accounts/heads", s.accountant.accessToken())
                .andExpect(jsonPath("$[?(@.systemCode == 'FEES')]").exists())
                .andExpect(jsonPath("$[?(@.feeHeadId == '%s')].parentName".formatted(heads.tuition()))
                        .value("Fee income"));
        api.get("/api/accounts/reports/income-vs-expense", s.principal.accessToken())
                .andExpect(jsonPath("$.totals.feeIncomePaise").value(100000));
        assertThat(audit(s.admin, "fee_income.rebuilt")).hasSize(1);
        // Too long a period is refused on the field.
        api.get("/api/accounts/fee-reconciliation?from=2020-01-01&to=2026-01-01", s.accountant.accessToken())
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.to").exists());
    }

    @Test
    void payrollCostArrivesOncePerMonthFromTheApprovalEvent() throws Exception {
        AccountsSchool s = new AccountsSchool();
        YearMonth month = YearMonth.from(TODAY);
        AccountsFixtures.payrollApproved(events, s.school.tenantId(), month, 6_000_000, 720_000,
                UUID.fromString(s.adminId));
        AccountsFixtures.payrollApproved(events, s.school.tenantId(), month, 1, 1, UUID.fromString(s.adminId));
        api.get("/api/accounts/payroll-costs", s.principal.accessToken())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].month").value(month.toString()))
                .andExpect(jsonPath("$[0].grossPaise").value(6000000))
                .andExpect(jsonPath("$[0].employerPaise").value(720000))
                .andExpect(jsonPath("$[0].costPaise").value(6720000));
        api.get("/api/accounts/payroll-costs?financialYear=2019-20", s.principal.accessToken())
                .andExpect(jsonPath("$", hasSize(0)));
        // The salaries head shows it as the actual against its budget, and the report counts it.
        s.fixtures.budget(s.accountant, s.salaries, 100_000_000);
        api.get("/api/accounts/budgets/" + FY, s.principal.accessToken())
                .andExpect(jsonPath("$.rows[?(@.headId == '%s')].actualPaise".formatted(s.salaries))
                        .value(6720000));
        api.get("/api/accounts/reports/income-vs-expense", s.principal.accessToken())
                .andExpect(jsonPath("$.totals.payrollPaise").value(6720000));
        api.get("/api/dashboard", s.principal.accessToken())
                .andExpect(jsonPath("$.accounts.payrollPaise").value(6720000));
        // Another school sees none of it.
        api.get("/api/accounts/payroll-costs", new AccountsSchool().admin.accessToken())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void bankBooksCashPositionAndStatementReconciliation() throws Exception {
        AccountsSchool s = new AccountsSchool();
        String vendor = s.fixtures.vendor(s.accountant, "Sharma Electricals");
        String sbi = s.fixtures.bankAccount(s.accountant, "SBI current");
        api.post("/api/accounts/bank-accounts", s.accountant.accessToken(), """
                {"name":"SBI current","bankName":"SBI","accountNumber":"000012345678","ifsc":"SBIN0001234",
                 "active":true}""").andExpect(status().isConflict()).andExpect(jsonPath("$.errors.name").exists());
        api.post("/api/accounts/bank-accounts", s.accountant.accessToken(), """
                {"name":"Bad","bankName":"SBI","accountNumber":"12","ifsc":"nope","active":true}""")
                .andExpect(status().isBadRequest());
        api.get("/api/accounts/bank-accounts", s.principal.accessToken())
                .andExpect(jsonPath("$[0].accountNumberMasked").value("XXXXXXXX5678"));
        s.fixtures.openingBalances(s.accountant, sbi, 10_000_00, 1_000_000_00);
        api.get("/api/accounts/opening-balances/" + FY, s.accountant.accessToken())
                .andExpect(jsonPath("$.cashPaise").value(1000000))
                .andExpect(jsonPath("$.accounts[0].amountPaise").value(100000000));
        // A bank payment, a cash payment, a cash donation and a petty cash top-up.
        String power = s.fixtures.expense(s.accountant, s.electricity, vendor, 10_000_00, 1_800_00, true, "Power");
        s.fixtures.approve(s.admin, power).andExpect(status().isOk());
        s.fixtures.paid(s.accountant, power, "BANK", sbi, "UTR777").andExpect(status().isOk());
        String nails = s.fixtures.expense(s.accountant, s.repairs, null, 300_00, 0, true, "Nails");
        s.fixtures.approve(s.admin, nails).andExpect(status().isOk());
        s.fixtures.paid(s.accountant, nails, "CASH", null, null).andExpect(status().isOk());
        s.fixtures.income(s.accountant, s.donations, "DONATION", 2_000_00, "CASH", null);
        s.fixtures.pettyCash(s.accountant, "TOP_UP", 1_000_00, null, "Float");
        api.get("/api/accounts/books?" + RANGE, s.accountant.accessToken())
                .andExpect(jsonPath("$.name").value("Cash"))
                .andExpect(jsonPath("$.openingPaise").value(1000000))
                .andExpect(jsonPath("$.rows", hasSize(3)))
                .andExpect(jsonPath("$.receiptsPaise").value(200000))
                .andExpect(jsonPath("$.paymentsPaise").value(130000))
                .andExpect(jsonPath("$.closingPaise").value(1070000));
        api.get("/api/accounts/books?bankAccountId=" + sbi + "&" + RANGE, s.accountant.accessToken())
                .andExpect(jsonPath("$.name").value("SBI current"))
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].paymentPaise").value(1180000))
                .andExpect(jsonPath("$.rows[0].cleared").value(false))
                .andExpect(jsonPath("$.closingPaise").value(98820000));
        api.get("/api/accounts/cash-position", s.principal.accessToken())
                .andExpect(jsonPath("$.cashPaise").value(1070000))
                .andExpect(jsonPath("$.pettyCashPaise").value(100000))
                .andExpect(jsonPath("$.bankPaise").value(98820000))
                .andExpect(jsonPath("$.totalPaise").value(99990000));
        api.get("/api/dashboard", s.admin.accessToken())
                .andExpect(jsonPath("$.accounts.cashInHandPaise").value(1170000))
                .andExpect(jsonPath("$.accounts.bankPaise").value(98820000))
                .andExpect(jsonPath("$.accounts.expensePaise").value(1210000))
                .andExpect(jsonPath("$.accounts.otherIncomePaise").value(200000));
        api.get("/api/dashboard", s.teacher.accessToken()).andExpect(jsonPath("$.accounts").doesNotExist());

        // Reconciliation: the payment is uncleared, so the bank should show the book balance plus it.
        api.get("/api/accounts/bank-accounts/" + sbi + "/reconciliation", s.accountant.accessToken())
                .andExpect(jsonPath("$.bookBalancePaise").value(98820000))
                .andExpect(jsonPath("$.unclearedPayments", hasSize(1)))
                .andExpect(jsonPath("$.expectedStatementBalancePaise").value(100000000));
        // A statement CSV is previewed: the UTR matches the payment, a stray line matches nothing.
        String csv = "﻿Date,Narration,Ref No,Withdrawal,Deposit\r\n"
                + TODAY + ",NEFT SHARMA ELECTRICALS UTR777,UTR777,\"11,800.00\",\r\n"
                + TODAY + ",BANK CHARGES,,59.00,\r\n";
        String preview = body(mvc.perform(MockMvcRequestBuilders.multipart("/api/accounts/bank-accounts/" + sbi
                + "/statement/preview").file(new MockMultipartFile("file", "stmt.csv", "text/csv",
                        csv.getBytes(StandardCharsets.UTF_8)))
                .header("Authorization", "Bearer " + s.accountant.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines", hasSize(2)))
                .andExpect(jsonPath("$.lines[0].debitPaise").value(1180000))
                .andExpect(jsonPath("$.lines[0].match.quality").value("MATCHED"))
                .andExpect(jsonPath("$.lines[0].match.expenseId").value(power))
                .andExpect(jsonPath("$.lines[1].match").doesNotExist())
                .andExpect(jsonPath("$.matched").value(1))
                .andExpect(jsonPath("$.unmatched").value(1)));
        assertThat(preview).contains("Sharma Electricals");
        mvc.perform(MockMvcRequestBuilders.multipart("/api/accounts/bank-accounts/" + sbi + "/statement/preview")
                .file(new MockMultipartFile("file", "stmt.csv", "text/csv", "nothing,here\r\n1,2\r\n".getBytes()))
                .header("Authorization", "Bearer " + s.accountant.accessToken()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.file").exists());
        // Imported as previewed: the payment is cleared, and importing the same lines again skips them.
        String lines = """
                {"statementDate":"%1$s","lines":[{"date":"%1$s","description":"NEFT SHARMA ELECTRICALS UTR777",
                 "reference":"UTR777","debitPaise":1180000,"creditPaise":0,"expenseId":"%2$s"},
                 {"date":"%1$s","description":"BANK CHARGES","debitPaise":5900,"creditPaise":0}]}"""
                .formatted(TODAY, power);
        api.post("/api/accounts/bank-accounts/" + sbi + "/statement/import", s.accountant.accessToken(), lines)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(2))
                .andExpect(jsonPath("$.cleared").value(1));
        api.post("/api/accounts/bank-accounts/" + sbi + "/statement/import", s.accountant.accessToken(), lines)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(0))
                .andExpect(jsonPath("$.skipped").value(2));
        api.get("/api/accounts/bank-accounts/" + sbi + "/reconciliation", s.accountant.accessToken())
                .andExpect(jsonPath("$.unclearedPayments", hasSize(0)))
                .andExpect(jsonPath("$.recentLines", hasSize(2)))
                .andExpect(jsonPath("$.expectedStatementBalancePaise").value(98820000));
        api.get("/api/accounts/expenses/" + power, s.accountant.accessToken())
                .andExpect(jsonPath("$.clearedOn").value(TODAY.toString()));
        // Clearing an item of another account or school is a 400 on the field; a foreign account is 404.
        AccountsSchool other = new AccountsSchool();
        String otherBank = other.fixtures.bankAccount(other.accountant, "HDFC");
        api.post("/api/accounts/bank-accounts/" + otherBank + "/clear", other.accountant.accessToken(), """
                {"statementDate":"%s","expenseIds":["%s"]}""".formatted(TODAY, power))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.expenseIds").exists());
        api.get("/api/accounts/bank-accounts/" + sbi + "/reconciliation", other.accountant.accessToken())
                .andExpect(status().isNotFound());
        api.delete("/api/accounts/bank-accounts/" + sbi, s.accountant.accessToken()).andExpect(status().isConflict());
        assertThat(audit(s.admin, "bank_statement.imported")).hasSize(2);
        assertThat(audit(s.admin, "opening_balances.saved")).hasSize(1);
    }

    @Test
    void vendorsAreValidatedAndMaskedAndTheChartIsManaged() throws Exception {
        AccountsSchool s = new AccountsSchool();
        api.post("/api/accounts/vendors", s.accountant.accessToken(), """
                {"name":"Bad GST","gstin":"36AABCU9603R1Z","active":true}""")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.gstin").exists());
        api.post("/api/accounts/vendors", s.accountant.accessToken(), """
                {"name":"Half bank","bankAccountNumber":"000000123456","active":true}""")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.bankIfsc").exists());
        String vendor = s.fixtures.vendor(s.accountant, "Sharma Electricals");
        api.get("/api/accounts/vendors/" + vendor, s.principal.accessToken())
                .andExpect(jsonPath("$.gstin").value("36AABCU9603R1ZX"))
                .andExpect(jsonPath("$.panMasked").value("XXXXXX603R"))
                .andExpect(jsonPath("$.phoneMasked").value("XXXXXX3210"))
                .andExpect(jsonPath("$.bankAccountMasked").value("XXXXXXXX3456"))
                .andExpect(jsonPath("$.tdsSection").value("194C"))
                .andExpect(jsonPath("$.tdsRatePercent").value(2.0))
                .andExpect(jsonPath("$.pan").doesNotExist())
                .andExpect(jsonPath("$.phone").doesNotExist());
        api.post("/api/accounts/vendors", s.accountant.accessToken(), """
                {"name":"sharma electricals","active":true}""").andExpect(status().isConflict());
        // Consultant under 194J at 10%, editable later.
        String consultant = TestApi.read(api.post("/api/accounts/vendors", s.accountant.accessToken(), """
                {"name":"Dr Rao","pan":"ABCPR1234K","tdsSection":"194J","tdsRatePercent":10,"active":true}""")
                .andExpect(status().isCreated()), "$.id");
        api.put("/api/accounts/vendors/" + consultant, s.accountant.accessToken(), """
                {"name":"Dr Rao","pan":"ABCPR1234K","tdsSection":"194J","tdsRatePercent":7.5,"active":true}""")
                .andExpect(status().isOk()).andExpect(jsonPath("$.tdsRatePercent").value(7.5));
        // A vendor with expenses cannot be deleted; a foreign vendor is 404.
        String id = s.fixtures.expense(s.accountant, s.electricity, vendor, 1_000_00, 0, false, "Bulb");
        api.delete("/api/accounts/vendors/" + vendor, s.accountant.accessToken()).andExpect(status().isConflict());
        api.delete("/api/accounts/vendors/" + consultant, s.accountant.accessToken())
                .andExpect(status().isNoContent());
        api.get("/api/accounts/vendors/" + vendor, new AccountsSchool().admin.accessToken())
                .andExpect(status().isNotFound());
        // Heads: codes and names are unique, a sub-head sits one level deep, a used head cannot be deactivated.
        api.post("/api/accounts/heads", s.accountant.accessToken(), """
                {"code":"EXP-ELEC","name":"Power","kind":"EXPENSE","gstApplicable":true,"active":true,
                 "displayOrder":1}""").andExpect(status().isConflict()).andExpect(jsonPath("$.errors.code").exists());
        String generator = TestApi.read(api.post("/api/accounts/heads", s.accountant.accessToken(), """
                {"code":"EXP-GEN","name":"Generator diesel","kind":"EXPENSE","parentId":"%s","gstApplicable":true,
                 "active":true,"displayOrder":5}""".formatted(s.electricity)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.parentName").value("Electricity")), "$.id");
        api.post("/api/accounts/heads", s.accountant.accessToken(), """
                {"code":"EXP-GEN2","name":"Deeper","kind":"EXPENSE","parentId":"%s","gstApplicable":true,
                 "active":true,"displayOrder":6}""".formatted(generator)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.parentId").exists());
        api.put("/api/accounts/heads/" + s.electricity, s.accountant.accessToken(), """
                {"code":"EXP-ELEC","name":"Electricity","kind":"INCOME","gstApplicable":true,"active":true,
                 "displayOrder":9}""").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.kind").exists());
        api.put("/api/accounts/heads/" + s.electricity, s.accountant.accessToken(), """
                {"code":"EXP-ELEC","name":"Electricity","kind":"EXPENSE","gstApplicable":true,"active":false,
                 "displayOrder":9}""").andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        // An inactive head cannot take new expenses.
        api.post("/api/accounts/expenses", s.accountant.accessToken(), """
                {"headId":"%s","expenseDate":"%s","amountPaise":100,"deductTds":false,"description":"x"}"""
                .formatted(s.electricity, TODAY)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.headId").exists());
        api.put("/api/accounts/heads/" + s.electricity, s.accountant.accessToken(), """
                {"code":"EXP-ELEC","name":"Electricity","kind":"EXPENSE","gstApplicable":true,"active":true,
                 "displayOrder":9}""").andExpect(status().isOk());
        api.post("/api/accounts/heads/standard", s.accountant.accessToken(), null).andExpect(status().isOk());
        assertThat(details(audit(s.admin, "account_heads.standard_added").getFirst())).containsEntry("added", 0);
        // Cost centres.
        String wing = s.fixtures.costCentre(s.accountant, "PRI", "Primary wing");
        api.post("/api/accounts/cost-centres", s.accountant.accessToken(), """
                {"code":"PRI","name":"Again","active":true}""").andExpect(status().isConflict());
        api.put("/api/accounts/expenses/" + id, s.accountant.accessToken(), """
                {"headId":"%s","costCentreId":"%s","vendorId":"%s","expenseDate":"%s","amountPaise":100000,
                 "deductTds":false,"description":"Bulb"}""".formatted(s.electricity, wing, vendor, TODAY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.costCentreName").value("Primary wing"));
        api.delete("/api/accounts/cost-centres/" + wing, s.accountant.accessToken()).andExpect(status().isConflict());
        // Settings are read by approvers too, with the people who could approve.
        api.get("/api/accounts/settings", s.principal.accessToken())
                .andExpect(jsonPath("$.candidates[?(@.name == 'Lakshmi Iyer')]").exists())
                .andExpect(jsonPath("$.candidates[?(@.name == 'Meena Reddy')]").doesNotExist());
        for (String action : List.of("vendor.created", "vendor.updated", "vendor.deleted", "account_head.created",
                "cost_centre.created", "expense.updated")) {
            assertThat(audit(s.admin, action)).as(action).isNotEmpty();
        }
    }

    @Test
    void reportsAddUpAndDownloadAsCsvWithABom() throws Exception {
        AccountsSchool s = new AccountsSchool();
        String vendor = s.fixtures.vendor(s.accountant, "Sharma Electricals");
        String sbi = s.fixtures.bankAccount(s.accountant, "SBI current");
        String wing = s.fixtures.costCentre(s.accountant, "PRI", "Primary wing");
        String power = TestApi.read(api.post("/api/accounts/expenses?submit=true", s.accountant.accessToken(), """
                {"headId":"%s","costCentreId":"%s","vendorId":"%s","expenseDate":"%s","amountPaise":1000000,
                 "gstPaise":180000,"deductTds":false,"description":"Power"}"""
                .formatted(s.electricity, wing, vendor, TODAY)).andExpect(status().isCreated()), "$.id");
        s.fixtures.approve(s.admin, power).andExpect(status().isOk());
        s.fixtures.paid(s.accountant, power, "BANK", sbi, "UTR1").andExpect(status().isOk());
        String nails = s.fixtures.expense(s.accountant, s.repairs, null, 300_00, 0, true, "Nails");
        s.fixtures.approve(s.admin, nails).andExpect(status().isOk());
        s.fixtures.expense(s.accountant, s.repairs, null, 999_00, 0, true, "Waiting");
        s.fixtures.expense(s.accountant, s.repairs, null, 5_00, 0, false, "Draft");
        s.fixtures.income(s.accountant, s.donations, "DONATION", 2_000_00, "CASH", null);
        s.fixtures.pettyCash(s.accountant, "TOP_UP", 1_000_00, null, "Float");
        s.fixtures.pettyCash(s.accountant, "SPENT", 50_00, s.repairs, "Tea");

        api.get("/api/accounts/reports/income-vs-expense", s.principal.accessToken())
                .andExpect(jsonPath("$.financialYear").value(FY))
                .andExpect(jsonPath("$.months", hasSize(12)))
                .andExpect(jsonPath("$.totals.otherIncomePaise").value(200000))
                .andExpect(jsonPath("$.totals.expensePaise").value(1210000))
                .andExpect(jsonPath("$.totals.pettyCashPaise").value(5000))
                .andExpect(jsonPath("$.totals.netPaise").value(200000 - 1215000));
        api.get("/api/accounts/reports/receipts-and-payments?" + RANGE, s.principal.accessToken())
                .andExpect(jsonPath("$.receiptsPaise").value(200000))
                .andExpect(jsonPath("$.paymentsPaise").value(1180000 + 5000))
                .andExpect(jsonPath("$.closingPaise").value(200000 - 1185000));
        api.get("/api/accounts/reports/head-wise?" + RANGE, s.principal.accessToken())
                .andExpect(jsonPath("$.rows[0].name").value("Electricity"))
                .andExpect(jsonPath("$.rows[0].costCentres[0].name").value("Primary wing"))
                .andExpect(jsonPath("$.rows[1].amountPaise").value(30000))
                .andExpect(jsonPath("$.amountPaise").value(1210000))
                .andExpect(jsonPath("$.count").value(2));
        api.get("/api/accounts/reports/gst-summary?" + RANGE, s.principal.accessToken())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.rows[0].gstin").value("36AABCU9603R1ZX"))
                .andExpect(jsonPath("$.gstPaise").value(180000))
                .andExpect(jsonPath("$.taxablePaise").value(1000000));
        api.get("/api/accounts/reports/day-book?date=" + TODAY, s.principal.accessToken())
                .andExpect(jsonPath("$.receiptsPaise").value(200000))
                .andExpect(jsonPath("$.paymentsPaise").value(1180000 + 100000 + 5000));
        api.get("/api/accounts/reports/vendor-ledger?vendorId=" + vendor + "&" + RANGE, s.principal.accessToken())
                .andExpect(jsonPath("$.billedPaise").value(1180000));
        api.get("/api/accounts/reports/books?bankAccountId=" + sbi + "&" + RANGE, s.principal.accessToken())
                .andExpect(jsonPath("$.paymentsPaise").value(1180000));
        api.get("/api/accounts/reports/budget-vs-actual?financialYear=" + FY, s.principal.accessToken())
                .andExpect(status().isOk());
        api.get("/api/dashboard", s.principal.accessToken())
                .andExpect(jsonPath("$.accounts.awaitingApproval").value(1))
                .andExpect(jsonPath("$.accounts.awaitingApprovalPaise").value(99900))
                .andExpect(jsonPath("$.accounts.incomePaise").value(200000));
        for (String path : List.of("income-vs-expense.csv", "receipts-and-payments.csv?" + RANGE,
                "budget-vs-actual.csv", "head-wise.csv?" + RANGE, "gst-summary.csv?" + RANGE,
                "day-book.csv?date=" + TODAY, "vendor-ledger.csv?vendorId=" + vendor + "&" + RANGE,
                "books.csv?" + RANGE)) {
            byte[] bytes = api.get("/api/accounts/reports/" + path, s.principal.accessToken())
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                    .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith(
                            "attachment")))
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(bytes).as(path).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
            assertThat(new String(bytes, StandardCharsets.UTF_8)).as(path).contains("\r\n");
            api.get("/api/accounts/reports/" + path, s.teacher.accessToken()).andExpect(status().isForbidden());
        }
        assertThat(new String(api.get("/api/accounts/reports/income-vs-expense.csv", s.principal.accessToken())
                .andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
                .contains("TOTAL,0.00,2000.00,2000.00,12100.00,0.00,50.00,12150.00,-10150.00");
    }
}
