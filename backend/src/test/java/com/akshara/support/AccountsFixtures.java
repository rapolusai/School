package com.akshara.support;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.web.servlet.ResultActions;

import com.akshara.payroll.PayrollApproved;
import com.akshara.support.TestApi.Session;
import com.jayway.jsonpath.JsonPath;

/** Builds accounts records through the API, as a school's accountant would, for the integration tests. */
public final class AccountsFixtures {

    public static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    private final TestApi api;

    public AccountsFixtures(TestApi api) {
        this.api = api;
    }

    public static LocalDate today() {
        return LocalDate.now(INDIA);
    }

    /** The current Indian financial year as "2026-27". */
    public static String financialYear() {
        LocalDate t = today();
        int start = t.getMonthValue() >= 4 ? t.getYear() : t.getYear() - 1;
        return start + "-" + String.format("%02d", (start + 1) % 100);
    }

    /** Adds the standard chart and returns the id of the head with this system code. */
    public String standardHead(Session session, String systemCode) throws Exception {
        ResultActions result = api.post("/api/accounts/heads/standard", session.accessToken(), null)
                .andExpect(status().isOk());
        return first(result, "$[?(@.systemCode == '" + systemCode + "')].id");
    }

    public String costCentre(Session session, String code, String name) throws Exception {
        return TestApi.read(api.post("/api/accounts/cost-centres", session.accessToken(), """
                {"code":"%s","name":"%s","active":true}""".formatted(code, name)).andExpect(status().isCreated()),
                "$.id");
    }

    /** A GST-registered contractor under 194C at 2%. */
    public String vendor(Session session, String name) throws Exception {
        return TestApi.read(api.post("/api/accounts/vendors", session.accessToken(), """
                {"name":"%s","gstin":"36AABCU9603R1ZX","pan":"AABCU9603R","phone":"9876543210",
                 "bankAccountNumber":"000000123456","bankIfsc":"AKSH0000001","tdsSection":"194C",
                 "tdsRatePercent":2,"active":true}""".formatted(name)).andExpect(status().isCreated()), "$.id");
    }

    public String bankAccount(Session session, String name) throws Exception {
        return TestApi.read(api.post("/api/accounts/bank-accounts", session.accessToken(), """
                {"name":"%s","bankName":"State Bank of India","accountNumber":"000012345678","ifsc":"SBIN0001234",
                 "active":true}""".formatted(name)).andExpect(status().isCreated()), "$.id");
    }

    public void openingBalances(Session session, String bankAccountId, long cashPaise, long bankPaise)
            throws Exception {
        api.put("/api/accounts/opening-balances/" + financialYear(), session.accessToken(), """
                {"balances":[{"bankAccountId":null,"amountPaise":%d},{"bankAccountId":"%s","amountPaise":%d}]}"""
                .formatted(cashPaise, bankAccountId, bankPaise)).andExpect(status().isOk());
    }

    public void approvers(Session session, String json) throws Exception {
        api.put("/api/accounts/settings/approvers", session.accessToken(), json).andExpect(status().isOk());
    }

    /** An expense of today, submitted when asked; returns its id. */
    public String expense(Session session, String headId, String vendorId, long amountPaise, long gstPaise,
            boolean submit, String description) throws Exception {
        String vendor = vendorId == null ? "null" : "\"" + vendorId + "\"";
        return TestApi.read(api.post("/api/accounts/expenses?submit=" + submit, session.accessToken(), """
                {"headId":"%s","vendorId":%s,"expenseDate":"%s","amountPaise":%d,"gstPaise":%d,"deductTds":false,
                 "description":"%s","billNumber":"B-1"}"""
                .formatted(headId, vendor, today(), amountPaise, gstPaise, description))
                .andExpect(status().isCreated()), "$.id");
    }

    public ResultActions approve(Session session, String expenseId) throws Exception {
        return api.post("/api/accounts/expenses/" + expenseId + "/approve", session.accessToken(), """
                {"note":"OK"}""");
    }

    public ResultActions paid(Session session, String expenseId, String mode, String bankAccountId,
            String reference) throws Exception {
        String bank = bankAccountId == null ? "null" : "\"" + bankAccountId + "\"";
        String ref = reference == null ? "null" : "\"" + reference + "\"";
        return api.post("/api/accounts/expenses/" + expenseId + "/paid", session.accessToken(), """
                {"paidOn":"%s","mode":"%s","bankAccountId":%s,"reference":%s}"""
                .formatted(today(), mode, bank, ref));
    }

    public void pettyCash(Session session, String kind, long amountPaise, String headId, String description)
            throws Exception {
        String head = headId == null ? "null" : "\"" + headId + "\"";
        api.post("/api/accounts/petty-cash", session.accessToken(), """
                {"entryDate":"%s","kind":"%s","amountPaise":%d,"headId":%s,"description":"%s"}"""
                .formatted(today(), kind, amountPaise, head, description)).andExpect(status().isCreated());
    }

    /** A donation received today; returns the receipt id. */
    public String income(Session session, String headId, String kind, long amountPaise, String mode,
            String bankAccountId) throws Exception {
        String bank = bankAccountId == null ? "null" : "\"" + bankAccountId + "\"";
        return TestApi.read(api.post("/api/accounts/income", session.accessToken(), """
                {"kind":"%s","headId":"%s","receivedOn":"%s","amountPaise":%d,"payerName":"Ramesh Gupta",
                 "payerPan":"ABCPG1234H","mode":"%s","bankAccountId":%s,"reference":"UTR123"}"""
                .formatted(kind, headId, today(), amountPaise, mode, bank)).andExpect(status().isCreated()),
                "$.id");
    }

    public void budget(Session session, String headId, long annualPaise) throws Exception {
        api.put("/api/accounts/budgets/" + financialYear(), session.accessToken(), """
                {"headId":"%s","annualPaise":%d,"spread":"EVEN"}""".formatted(headId, annualPaise))
                .andExpect(status().isOk());
    }

    public String recurring(Session session, String headId, String vendorId, long amountPaise) throws Exception {
        String vendor = vendorId == null ? "null" : "\"" + vendorId + "\"";
        return TestApi.read(api.post("/api/accounts/recurring", session.accessToken(), """
                {"name":"Office rent","headId":"%s","vendorId":%s,"amountPaise":%d,"gstPaise":0,"dayOfMonth":5,
                 "description":"Monthly rent","active":true}""".formatted(headId, vendor, amountPaise))
                .andExpect(status().isCreated()), "$.id");
    }

    /** Imports one statement line that clears the paid expense. */
    public void statementLine(Session session, String bankAccountId, String expenseId, long debitPaise,
            String reference) throws Exception {
        api.post("/api/accounts/bank-accounts/" + bankAccountId + "/statement/import", session.accessToken(), """
                {"statementDate":"%s","lines":[{"date":"%s","description":"NEFT %s","reference":"%s",
                 "debitPaise":%d,"creditPaise":0,"expenseId":"%s"}]}"""
                .formatted(today(), today(), reference, reference, debitPaise, expenseId))
                .andExpect(status().isOk());
    }

    /** Publishes the payroll module's event for a month, as approving a run would. */
    public static void payrollApproved(ApplicationEventPublisher publisher, UUID tenantId, YearMonth month,
            long grossPaise, long employerPaise, UUID approvedBy) {
        publisher.publishEvent(new PayrollApproved(tenantId, UUID.randomUUID(), month, 3, grossPaise,
                grossPaise / 10, grossPaise - grossPaise / 10, employerPaise, 0, 0, 0, 0, 0, 0, 0, 0, approvedBy,
                Instant.now()));
    }

    /**
     * One row in every accounts table: settings, an approver (the session's own user), the standard heads, a cost
     * centre, a vendor, a bank account with opening balances, a recurring expense, a submitted expense approved and
     * paid by bank (number counter, approval, statement line), petty cash, a donation receipt and a budget.
     * Fee income and payroll cost rows come from the other modules' events.
     */
    public void everything(Session session, String userId) throws Exception {
        api.put("/api/accounts/settings", session.accessToken(), """
                {"budgetMode":"WARN","donation80gRegistered":true,"donation80gNumber":"AAATA1234FF20214",
                 "pettyCashFloatPaise":500000}""").andExpect(status().isOk());
        approvers(session, """
                {"approvers":[{"userId":"%s","limitPaise":10000000}]}""".formatted(userId));
        String electricity = standardHead(session, "ELECTRICITY");
        String donations = first(api.get("/api/accounts/heads", session.accessToken()),
                "$[?(@.systemCode == 'DONATIONS')].id");
        costCentre(session, "PRI", "Primary wing");
        String vendor = vendor(session, "Sharma Electricals");
        String bank = bankAccount(session, "SBI current");
        openingBalances(session, bank, 100000, 50000000);
        recurring(session, electricity, vendor, 1200000);
        String expense = expense(session, electricity, vendor, 1000000, 180000, true, "September power bill");
        approve(session, expense).andExpect(status().isOk());
        paid(session, expense, "BANK", bank, "UTR999").andExpect(status().isOk());
        statementLine(session, bank, expense, 1180000, "UTR999");
        pettyCash(session, "TOP_UP", 200000, null, "Float");
        pettyCash(session, "SPENT", 15000, electricity, "Fuse wire");
        income(session, donations, "DONATION", 500000, "BANK", bank);
        budget(session, electricity, 12000000);
    }

    public static String first(ResultActions result, String path) throws Exception {
        Object value = JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
        if (value instanceof List<?> list) {
            return list.isEmpty() ? null : String.valueOf(list.getFirst());
        }
        return String.valueOf(value);
    }
}
