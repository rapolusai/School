package com.akshara.accounts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.akshara.support.AccountsFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.Session;

/**
 * Expenses from draft to paid: who may do what, the approval chain with limits and escalation, rejection with a
 * reason, budgets that warn or block, bills attached, the lock once paid, recurring drafts and petty cash.
 */
class ExpenseFlowIT extends AccountsIntegrationTest {

    static final byte[] BILL = "Bill no 42, Rs 10,000".getBytes();

    @Test
    void permissionsPerRole() throws Exception {
        AccountsSchool s = new AccountsSchool();
        api.get("/api/accounts/expenses", s.teacher.accessToken()).andExpect(status().isForbidden());
        api.get("/api/accounts/heads", s.teacher.accessToken()).andExpect(status().isForbidden());
        api.get("/api/accounts/reports/income-vs-expense", s.teacher.accessToken())
                .andExpect(status().isForbidden());
        // The principal reads and approves but does not manage.
        api.get("/api/accounts/expenses", s.principal.accessToken()).andExpect(status().isOk());
        api.get("/api/accounts/expenses/queue", s.principal.accessToken()).andExpect(status().isOk());
        api.post("/api/accounts/vendors", s.principal.accessToken(), """
                {"name":"X","active":true}""").andExpect(status().isForbidden());
        api.post("/api/accounts/expenses", s.principal.accessToken(), """
                {"headId":"%s","expenseDate":"%s","amountPaise":100,"deductTds":false,"description":"x"}"""
                .formatted(s.electricity, AccountsFixtures.today())).andExpect(status().isForbidden());
        // The accountant manages but does not approve.
        api.get("/api/accounts/expenses/queue", s.accountant.accessToken()).andExpect(status().isForbidden());
        String id = s.fixtures.expense(s.accountant, s.electricity, null, 1_000_00, 0, true, "Bulb");
        s.fixtures.approve(s.accountant, id).andExpect(status().isForbidden());
        api.post("/api/accounts/expenses/" + id + "/reject", s.accountant.accessToken(), """
                {"reason":"no"}""").andExpect(status().isForbidden());
        // Another school sees nothing of it.
        AccountsSchool other = new AccountsSchool();
        api.get("/api/accounts/expenses/" + id, other.admin.accessToken()).andExpect(status().isNotFound());
        api.get("/api/accounts/expenses", other.admin.accessToken()).andExpect(jsonPath("$.expenses", hasSize(0)));
        s.fixtures.approve(other.admin, id).andExpect(status().isNotFound());
        // And its ids are foreign in a body: 400 on the field, not 404.
        api.post("/api/accounts/expenses", other.admin.accessToken(), """
                {"headId":"%s","expenseDate":"%s","amountPaise":100,"deductTds":false,"description":"x"}"""
                .formatted(s.electricity, AccountsFixtures.today())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.headId").exists());
    }

    @Test
    void draftSubmitApproveWithinTheLimitAndPay() throws Exception {
        AccountsSchool s = new AccountsSchool();
        s.limits(50_000_00);
        String vendor = s.fixtures.vendor(s.accountant, "Sharma Electricals");
        String bank = s.fixtures.bankAccount(s.accountant, "SBI current");
        // A draft with GST and TDS from the vendor (194C at 2% of the amount before GST).
        ResultActions created = api.post("/api/accounts/expenses", s.accountant.accessToken(), """
                {"headId":"%s","vendorId":"%s","expenseDate":"%s","amountPaise":1000000,"gstPaise":180000,
                 "deductTds":true,"description":"September power bill","billNumber":"TS-9"}"""
                .formatted(s.electricity, vendor, AccountsFixtures.today()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.number").doesNotExist())
                .andExpect(jsonPath("$.totalPaise").value(1180000))
                .andExpect(jsonPath("$.tdsSection").value("194C"))
                .andExpect(jsonPath("$.tdsPaise").value(20000))
                .andExpect(jsonPath("$.paidPaise").value(1160000))
                .andExpect(jsonPath("$.vendorName").value("Sharma Electricals"))
                .andExpect(jsonPath("$.canEdit").value(true));
        String id = TestApi.read(created, "$.id");
        // Nothing to approve or pay yet.
        s.fixtures.approve(s.principal, id).andExpect(status().isConflict());
        s.fixtures.paid(s.accountant, id, "CASH", null, null).andExpect(status().isConflict());
        // A bill photo goes on the draft; the principal can read it, the teacher cannot.
        String file = TestApi.read(attach(s.accountant, id, new MockMultipartFile("file", "bill.txt", "text/plain",
                BILL)).andExpect(status().isOk()).andExpect(jsonPath("$.files[0].name").value("bill.txt")),
                "$.files[0].id");
        api.get("/api/files/" + file, s.principal.accessToken()).andExpect(status().isOk());
        api.get("/api/files/" + file, s.teacher.accessToken()).andExpect(status().isNotFound());

        api.post("/api/accounts/expenses/" + id + "/submit", s.accountant.accessToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.number").value("EXP/" + AccountsFixtures.financialYear() + "/000001"));
        // Submitted: no more edits until rejected.
        api.put("/api/accounts/expenses/" + id, s.accountant.accessToken(), """
                {"headId":"%s","expenseDate":"%s","amountPaise":5,"deductTds":false,"description":"x"}"""
                .formatted(s.electricity, AccountsFixtures.today())).andExpect(status().isConflict());
        api.get("/api/accounts/expenses/queue", s.principal.accessToken())
                .andExpect(jsonPath("$.expenses", hasSize(1)))
                .andExpect(jsonPath("$.myLimitPaise").value(5000000))
                .andExpect(jsonPath("$.actionable", hasItem(id)));
        api.get("/api/accounts/expenses/" + id, s.principal.accessToken())
                .andExpect(jsonPath("$.myDecision").value("APPROVE"));
        s.fixtures.approve(s.principal, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedByName").value("Lakshmi Iyer"))
                .andExpect(jsonPath("$.approvals[0].action").value("APPROVED"))
                .andExpect(jsonPath("$.myDecision").doesNotExist());
        // A cheque needs its number; a bank payment needs an account; then it is paid and locked.
        s.fixtures.paid(s.accountant, id, "CHEQUE", bank, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reference").exists());
        s.fixtures.paid(s.accountant, id, "BANK", null, "UTR1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.bankAccountId").exists());
        s.fixtures.paid(s.accountant, id, "BANK", bank, "UTR1").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.bankAccountName").value("SBI current"))
                .andExpect(jsonPath("$.paidPaise").value(1160000))
                .andExpect(jsonPath("$.canEdit").value(false));
        s.fixtures.paid(s.accountant, id, "BANK", bank, "UTR1").andExpect(status().isConflict());
        api.put("/api/accounts/expenses/" + id, s.accountant.accessToken(), """
                {"headId":"%s","expenseDate":"%s","amountPaise":5,"deductTds":false,"description":"x"}"""
                .formatted(s.electricity, AccountsFixtures.today())).andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Expense paid"));
        api.delete("/api/accounts/expenses/" + id, s.accountant.accessToken()).andExpect(status().isConflict());
        attach(s.accountant, id, new MockMultipartFile("file", "late.txt", "text/plain", BILL))
                .andExpect(status().isConflict());
        api.delete("/api/accounts/expenses/" + id + "/attachments/" + file, s.accountant.accessToken())
                .andExpect(status().isConflict());
        // The vendor's ledger shows the bill, the TDS kept back and nothing outstanding.
        api.get("/api/accounts/vendors/" + vendor + "/ledger?from=" + AccountsFixtures.today().minusDays(1)
                + "&to=" + AccountsFixtures.today(), s.accountant.accessToken())
                .andExpect(jsonPath("$.rows", hasSize(1)))
                .andExpect(jsonPath("$.billedPaise").value(1180000))
                .andExpect(jsonPath("$.tdsPaise").value(20000))
                .andExpect(jsonPath("$.paidPaise").value(1160000))
                .andExpect(jsonPath("$.outstandingPaise").value(0));
        // Everything was audited.
        for (String action : List.of("expense.created", "expense.file_added", "expense.submitted",
                "expense.approved", "expense.paid")) {
            assertThat(audit(s.admin, action)).as(action).isNotEmpty();
        }
        assertThat(details(audit(s.admin, "expense.paid").getFirst())).containsEntry("mode", "BANK")
                .containsEntry("reference", "UTR1");
    }

    @Test
    void aboveTheLimitPassesOnToTheNextApproverAndRejectionNeedsAReason() throws Exception {
        AccountsSchool s = new AccountsSchool();
        s.limits(50_000_00);
        String big = s.fixtures.expense(s.accountant, s.repairs, null, 80_000_00, 0, true, "Roof repair");
        api.get("/api/accounts/expenses/queue", s.principal.accessToken())
                .andExpect(jsonPath("$.limited").value(true))
                .andExpect(jsonPath("$.actionable", hasItem(big)));
        // The principal cannot approve 80,000 but passes it on to the admin.
        api.get("/api/accounts/expenses/" + big, s.principal.accessToken())
                .andExpect(jsonPath("$.myDecision").value("ESCALATE"));
        s.fixtures.approve(s.principal, big).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.escalatedAbovePaise").value(5000000))
                .andExpect(jsonPath("$.approvals[0].action").value("ESCALATED"))
                .andExpect(jsonPath("$.myDecision").value("NOT_YOURS"));
        // Once passed on, the principal may neither approve nor reject it, and it leaves their actionable list.
        s.fixtures.approve(s.principal, big).andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Passed on"));
        api.post("/api/accounts/expenses/" + big + "/reject", s.principal.accessToken(), """
                {"reason":"Too much"}""").andExpect(status().isConflict());
        api.get("/api/accounts/expenses/queue", s.principal.accessToken())
                .andExpect(jsonPath("$.expenses", hasSize(1)))
                .andExpect(jsonPath("$.actionable", hasSize(0)));
        api.get("/api/accounts/expenses/queue", s.admin.accessToken())
                .andExpect(jsonPath("$.limited").value(false))
                .andExpect(jsonPath("$.actionable", hasItem(big)));
        s.fixtures.approve(s.admin, big).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvals", hasSize(2)));
        assertThat(audit(s.admin, "expense.escalated")).hasSize(1);

        // When nobody has a larger limit (the admin's is now smaller) the approver is told so (409).
        s.fixtures.approvers(s.admin, """
                {"approvers":[{"userId":"%s","limitPaise":5000000},{"userId":"%s","limitPaise":4000000}]}"""
                .formatted(s.principalId, s.adminId));
        String bigger = s.fixtures.expense(s.accountant, s.repairs, null, 90_000_00, 0, true, "Paint");
        s.fixtures.approve(s.principal, bigger).andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Above your limit"));
        // Rejection: a reason is required, the requester edits and resubmits with a new number kept.
        api.post("/api/accounts/expenses/" + bigger + "/reject", s.principal.accessToken(), """
                {"reason":" "}""").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.reason").exists());
        api.post("/api/accounts/expenses/" + bigger + "/reject", s.principal.accessToken(), """
                {"reason":"Get two quotes"}""").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value("Get two quotes"))
                .andExpect(jsonPath("$.canEdit").value(true));
        String number = TestApi.read(api.get("/api/accounts/expenses/" + bigger, s.accountant.accessToken()),
                "$.number");
        api.put("/api/accounts/expenses/" + bigger, s.accountant.accessToken(), """
                {"headId":"%s","expenseDate":"%s","amountPaise":4000000,"deductTds":false,
                 "description":"Paint, cheaper quote"}""".formatted(s.repairs, AccountsFixtures.today()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
        api.post("/api/accounts/expenses/" + bigger + "/submit", s.accountant.accessToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number").value(number))
                .andExpect(jsonPath("$.rejectReason").doesNotExist());
        s.fixtures.approve(s.principal, bigger).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        // An approver who cannot approve expenses is refused on the field.
        api.put("/api/accounts/settings/approvers", s.admin.accessToken(), """
                {"approvers":[{"userId":"%s","limitPaise":100}]}""".formatted(s.accountantId))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors['approvers[0].userId']").exists());
    }

    @Test
    void budgetsWarnAtNinetyPercentAndBlockWhenConfigured() throws Exception {
        AccountsSchool s = new AccountsSchool();
        s.fixtures.budget(s.accountant, s.electricity, 100_000_00);
        api.get("/api/accounts/budgets/" + AccountsFixtures.financialYear(), s.principal.accessToken())
                .andExpect(jsonPath("$.rows[?(@.headId == '%s')].annualPaise".formatted(s.electricity))
                        .value(10000000))
                .andExpect(jsonPath("$.rows[?(@.headId == '%s')].months[0]".formatted(s.electricity))
                        .value(833333))
                .andExpect(jsonPath("$.rows[?(@.headId == '%s')].months[11]".formatted(s.electricity))
                        .value(833334));
        String first = s.fixtures.expense(s.accountant, s.electricity, null, 85_000_00, 0, true, "Q1 power");
        s.fixtures.approve(s.admin, first).andExpect(status().isOk())
                .andExpect(jsonPath("$.budgetWarning").value(false))
                .andExpect(jsonPath("$.budget.status").value("OK"));
        // The next one takes the head to 95%: submitted with a warning, approved with a warning.
        String second = s.fixtures.expense(s.accountant, s.electricity, null, 10_000_00, 0, true, "Q2 power");
        api.get("/api/accounts/expenses/" + second, s.principal.accessToken())
                .andExpect(jsonPath("$.budgetWarning").value(true))
                .andExpect(jsonPath("$.budget.status").value("NEAR"))
                .andExpect(jsonPath("$.budget.percentAfter").value(95));
        s.fixtures.approve(s.admin, second).andExpect(status().isOk())
                .andExpect(jsonPath("$.budgetWarning").value(true));
        api.get("/api/accounts/reports/budget-vs-actual", s.principal.accessToken())
                .andExpect(jsonPath("$.rows[?(@.headId == '%s')].status".formatted(s.electricity)).value("NEAR"))
                .andExpect(jsonPath("$.nearCount").value(1));
        // In BLOCK mode an expense that would go over cannot be approved; in WARN mode it can.
        String third = s.fixtures.expense(s.accountant, s.electricity, null, 10_000_00, 0, true, "Q3 power");
        api.put("/api/accounts/settings", s.admin.accessToken(), """
                {"budgetMode":"BLOCK","donation80gRegistered":false,"pettyCashFloatPaise":500000}""")
                .andExpect(status().isOk());
        s.fixtures.approve(s.admin, third).andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Over budget"));
        api.put("/api/accounts/settings", s.admin.accessToken(), """
                {"budgetMode":"WARN","donation80gRegistered":false,"pettyCashFloatPaise":500000}""")
                .andExpect(status().isOk());
        s.fixtures.approve(s.admin, third).andExpect(status().isOk())
                .andExpect(jsonPath("$.budget.status").value("OVER"));
        api.get("/api/accounts/reports/budget-vs-actual", s.principal.accessToken())
                .andExpect(jsonPath("$.overCount").value(1));
        // Entered months must add up to the year.
        api.put("/api/accounts/budgets/" + AccountsFixtures.financialYear(), s.accountant.accessToken(), """
                {"headId":"%s","annualPaise":1200,"spread":"ENTERED","months":[100,100,100,100,100,100,100,100,100,
                 100,100,99]}""".formatted(s.repairs)).andExpect(status().isBadRequest());
        api.get("/api/accounts/budgets/2020-22", s.accountant.accessToken()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.financialYear").exists());
    }

    @Test
    void recurringExpensesBecomeDraftsOncePerMonth() throws Exception {
        AccountsSchool s = new AccountsSchool();
        String vendor = s.fixtures.vendor(s.accountant, "Landlord");
        String rent = s.fixtures.recurring(s.accountant, s.repairs, vendor, 25_000_00);
        String month = YearMonth.from(AccountsFixtures.today()).toString();
        api.post("/api/accounts/recurring/generate", s.accountant.accessToken(), """
                {"month":"%s"}""".formatted(month)).andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.expenses[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.expenses[0].totalPaise").value(2500000));
        api.post("/api/accounts/recurring/generate", s.accountant.accessToken(), """
                {"month":"%s"}""".formatted(month)).andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(0)).andExpect(jsonPath("$.skipped").value(1));
        api.post("/api/accounts/recurring/generate", s.accountant.accessToken(), """
                {"month":"%s"}""".formatted(YearMonth.from(AccountsFixtures.today()).plusMonths(1)))
                .andExpect(status().isBadRequest());
        api.get("/api/accounts/recurring", s.accountant.accessToken())
                .andExpect(jsonPath("$[0].lastGeneratedMonth").exists());
        api.get("/api/accounts/expenses?status=DRAFT", s.accountant.accessToken())
                .andExpect(jsonPath("$.expenses", hasSize(1)))
                .andExpect(jsonPath("$.draftCount").value(1));
        api.delete("/api/accounts/recurring/" + rent, s.accountant.accessToken()).andExpect(status().isNoContent());
        api.get("/api/accounts/recurring", s.accountant.accessToken()).andExpect(jsonPath("$", hasSize(0)));
        api.delete("/api/accounts/recurring/" + rent, s.accountant.accessToken()).andExpect(status().isNotFound());
        assertThat(audit(s.admin, "recurring_expenses.generated")).hasSize(2);
    }

    @Test
    void pettyCashKeepsARunningBalanceAgainstTheFloat() throws Exception {
        AccountsSchool s = new AccountsSchool();
        api.put("/api/accounts/settings", s.admin.accessToken(), """
                {"budgetMode":"WARN","donation80gRegistered":false,"pettyCashFloatPaise":500000}""")
                .andExpect(status().isOk());
        String today = AccountsFixtures.today().toString();
        String range = "?from=" + AccountsFixtures.today().minusDays(30) + "&to=" + today;
        api.get("/api/accounts/petty-cash" + range, s.accountant.accessToken())
                .andExpect(jsonPath("$.floatPaise").value(500000))
                .andExpect(jsonPath("$.balancePaise").value(0))
                .andExpect(jsonPath("$.topUpNeededPaise").value(500000));
        // Spending before any top-up is refused.
        api.post("/api/accounts/petty-cash", s.accountant.accessToken(), """
                {"entryDate":"%s","kind":"SPENT","amountPaise":1000,"headId":"%s","description":"Tea"}"""
                .formatted(today, s.electricity)).andExpect(status().isConflict());
        s.fixtures.pettyCash(s.accountant, "TOP_UP", 500000, null, "Float");
        api.post("/api/accounts/petty-cash", s.accountant.accessToken(), """
                {"entryDate":"%s","kind":"SPENT","amountPaise":1000,"description":"Tea"}""".formatted(today))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.headId").exists());
        s.fixtures.pettyCash(s.accountant, "SPENT", 1000, s.electricity, "Tea");
        s.fixtures.pettyCash(s.accountant, "SPENT", 4000, s.repairs, "Nails");
        api.get("/api/accounts/petty-cash" + range, s.principal.accessToken())
                .andExpect(jsonPath("$.entries", hasSize(3)))
                .andExpect(jsonPath("$.entries[2].balanceAfterPaise").value(495000))
                .andExpect(jsonPath("$.balancePaise").value(495000))
                .andExpect(jsonPath("$.spentPaise").value(5000))
                .andExpect(jsonPath("$.topUpNeededPaise").value(5000));
        api.post("/api/accounts/petty-cash", s.principal.accessToken(), """
                {"entryDate":"%s","kind":"SPENT","amountPaise":1000,"headId":"%s","description":"Tea"}"""
                .formatted(today, s.electricity)).andExpect(status().isForbidden());
        assertThat(audit(s.admin, "petty_cash.recorded")).hasSize(3);
    }

    private ResultActions attach(Session session, String expenseId, MockMultipartFile file) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.multipart("/api/accounts/expenses/" + expenseId + "/attachments")
                .file(file).header("Authorization", "Bearer " + session.accessToken()));
    }

    static Map<String, Object> firstDetails(List<Map<String, Object>> events) {
        return details(events.getFirst());
    }
}
