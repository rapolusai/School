package com.akshara.fees;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import com.akshara.support.FeeFixtures;
import com.akshara.support.FeeFixtures.Heads;
import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/** Counter payments, receipts and their numbers, cancellations, late fees, reports and exports. */
@RecordApplicationEvents
class FeePaymentsIT extends IntegrationTest {

    @Autowired
    ApplicationEvents events;

    private School school;
    private Session admin;
    private Session accounts;
    private SchoolFixtures fixtures;
    private FeeFixtures fees;
    private String yearId;
    private String class5;
    private String asha;
    private String bala;
    private String financialYear;

    @BeforeEach
    void schoolWithFees() throws Exception {
        school = api.signup();
        admin = api.login(school);
        api.createUser(admin, "Meena Reddy", "meena@" + school.code() + ".akshara.test", List.of("ACCOUNTANT"));
        accounts = api.login(school.code(), "meena@" + school.code() + ".akshara.test", TestApi.PASSWORD);
        fixtures = new SchoolFixtures(api);
        fees = new FeeFixtures(api, mvc);
        yearId = fixtures.currentYear(admin);
        class5 = fixtures.schoolClass(admin, "Class 5");
        String class5A = fixtures.section(admin, class5, "A", 40);
        asha = fixtures.student(admin, class5A, "P-1", "Asha", "Rao", "Lata Rao", "9876501001");
        bala = fixtures.student(admin, class5A, "P-2", "Bala", "Iyer", "Uma Iyer", "9876501002");
        Heads heads = fees.defaultHeads(accounts);
        fees.publishedStructure(accounts, yearId, class5, heads);
        // ₹100 per overdue instalment once 5 days have passed.
        api.put("/api/fees/late-fee-rule", accounts.accessToken(), """
                {"mode":"FLAT","graceDays":5,"flatPaise":10000}""").andExpect(status().isOk());
        financialYear = FeeMath.financialYear(FeeFixtures.today());
    }

    @Test
    void aPaymentGoesToTheOldestDuesFirstAndGetsAReceipt() throws Exception {
        api.get("/api/fees/students/" + asha, accounts.accessToken())
                .andExpect(jsonPath("$.instalments[0].lateFeePaise").value(100_00))
                .andExpect(jsonPath("$.instalments[1].lateFeePaise").value(100_00))
                .andExpect(jsonPath("$.instalments[2].lateFeePaise").value(0))
                .andExpect(jsonPath("$.totals.lateFeePaise").value(200_00))
                .andExpect(jsonPath("$.totals.overduePaise").value(23_000_00))
                .andExpect(jsonPath("$.totals.payableNowPaise").value(23_200_00))
                .andExpect(jsonPath("$.lateFeeRule.mode").value("FLAT"));

        // ₹15,000: Q1's late fee, Q1 in full, then Q2's late fee and ₹3,300 of its tuition.
        String receipt = TestApi.read(api.post("/api/fees/students/" + asha + "/payments", accounts.accessToken(), """
                {"amountPaise":1500000,"mode":"CASH","remarks":"Paid by mother"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.receiptNo").value("RCPT/" + financialYear + "/000001"))
                .andExpect(jsonPath("$.amountPaise").value(15_000_00))
                .andExpect(jsonPath("$.lateFeePaise").value(200_00))
                .andExpect(jsonPath("$.amountInWords").value("Rupees Fifteen Thousand Only"))
                .andExpect(jsonPath("$.studentName").value("Asha Rao"))
                .andExpect(jsonPath("$.classLabel").value("Class 5 A"))
                .andExpect(jsonPath("$.mode").value("CASH"))
                .andExpect(jsonPath("$.collectedByName").value("Meena Reddy"))
                .andExpect(jsonPath("$.school.name").value("Test School " + school.code()))
                .andExpect(jsonPath("$.lines[*].kind", contains("LATE_FEE", "DUE", "DUE", "LATE_FEE", "DUE")))
                .andExpect(jsonPath("$.lines[*].headName", contains(null, "Tuition fee", "Annual charges", null,
                        "Tuition fee")))
                .andExpect(jsonPath("$.lines[4].amountPaise").value(3_300_00))
                .andExpect(jsonPath("$.lines[4].instalmentLabel").value("Quarter 2")), "$.id");
        api.get("/api/fees/students/" + asha, accounts.accessToken())
                .andExpect(jsonPath("$.instalments[0].status").value("PAID"))
                .andExpect(jsonPath("$.instalments[0].lateFeePaise").value(0))
                .andExpect(jsonPath("$.instalments[1].status").value("OVERDUE"))
                .andExpect(jsonPath("$.instalments[1].paidPaise").value(3_300_00))
                // Q2's late fee was charged on this receipt, so it is not owed again.
                .andExpect(jsonPath("$.instalments[1].lateFeePaise").value(0))
                .andExpect(jsonPath("$.totals.paidPaise").value(14_800_00))
                .andExpect(jsonPath("$.receipts[0].id").value(receipt));
        assertThat(events.stream(FeePaymentReceived.class)
                .filter(e -> e.receiptId().toString().equals(receipt))
                .map(FeePaymentReceived::amountPaise)).containsExactly(15_000_00L);

        // Mode-specific details are required.
        api.post("/api/fees/students/" + asha + "/payments", accounts.accessToken(), """
                {"amountPaise":100000,"mode":"UPI"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reference").exists());
        api.post("/api/fees/students/" + asha + "/payments", accounts.accessToken(), """
                {"amountPaise":100000,"mode":"CHEQUE","bankName":"HDFC Bank"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.chequeNo").exists());
        api.post("/api/fees/students/" + asha + "/payments", accounts.accessToken(), """
                {"amountPaise":100000,"mode":"ONLINE"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.mode").exists());
        api.post("/api/fees/students/" + asha + "/payments", accounts.accessToken(), """
                {"amountPaise":0,"mode":"CASH"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amountPaise").exists());
        // More than is owed is refused.
        api.post("/api/fees/students/" + asha + "/payments", accounts.accessToken(), """
                {"amountPaise":3200001,"mode":"CASH"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amountPaise").value("This is more than the ₹31,200 due."));

        // A cheque for chosen instalments only: Q3, without touching what is left of Q2.
        String q3 = fees.instalmentIds(accounts, asha).get(2);
        api.post("/api/fees/students/" + asha + "/payments", accounts.accessToken(), """
                {"amountPaise":1150000,"mode":"CHEQUE","chequeNo":"004512","bankName":"HDFC Bank",
                 "instalmentIds":["%s"]}""".formatted(q3))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.receiptNo").value("RCPT/" + financialYear + "/000002"))
                .andExpect(jsonPath("$.chequeNo").value("004512"))
                .andExpect(jsonPath("$.lines.length()").value(2));
        api.post("/api/fees/students/" + asha + "/payments", accounts.accessToken(), """
                {"amountPaise":100,"mode":"CASH","instalmentIds":["%s"]}""".formatted(q3))
                .andExpect(status().isConflict());
        api.post("/api/fees/students/" + asha + "/payments", accounts.accessToken(), """
                {"amountPaise":100,"mode":"CASH","instalmentIds":["%s"]}""".formatted(UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.instalmentIds").exists());
        // UPI with its reference.
        api.post("/api/fees/students/" + asha + "/payments", accounts.accessToken(), """
                {"amountPaise":50000,"mode":"UPI","reference":"612345678901","includeLateFee":false}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reference").value("612345678901"))
                .andExpect(jsonPath("$.amountInWords").value("Rupees Five Hundred Only"));

        api.get("/api/fees/receipts/" + receipt, admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.remarks").value("Paid by mother"));
        api.get("/api/fees/receipts?q=asha", accounts.accessToken())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.totalPaise").value(27_000_00));
        api.get("/api/fees/receipts?mode=CHEQUE", accounts.accessToken())
                .andExpect(jsonPath("$.total").value(1));
        api.get("/api/audit-events?limit=20", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItem("fee_payment.recorded")));
    }

    @Test
    void concurrentPaymentsGetConsecutiveUniqueReceiptNumbers() throws Exception {
        String chitra = fixtures.student(admin, fixtures.section(admin, class5, "B", 40), "P-3", "Chitra", "Das",
                "Ritu Das", "9876501003");
        List<String> students = List.of(asha, bala, chitra, asha, bala, chitra, asha, bala);
        // Create everyone's dues first, so the payments only race for receipt numbers and balances.
        for (String s : List.of(asha, bala, chitra)) {
            api.get("/api/fees/students/" + s, accounts.accessToken()).andExpect(status().isOk());
        }
        ExecutorService pool = Executors.newFixedThreadPool(students.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        try {
            for (String s : students) {
                Callable<String> pay = () -> {
                    start.await();
                    return TestApi.read(api.post("/api/fees/students/" + s + "/payments", accounts.accessToken(), """
                            {"amountPaise":100000,"mode":"CASH","includeLateFee":false}""")
                            .andExpect(status().isCreated()), "$.receiptNo");
                };
                results.add(pool.submit(pay));
            }
            start.countDown();
            List<String> numbers = new ArrayList<>();
            for (Future<String> f : results) {
                numbers.add(f.get());
            }
            List<String> expected = new ArrayList<>();
            for (int i = 1; i <= students.size(); i++) {
                expected.add("RCPT/" + financialYear + "/" + String.format("%06d", i));
            }
            assertThat(numbers).containsExactlyInAnyOrderElementsOf(expected);
        } finally {
            pool.shutdownNow();
        }
        api.get("/api/fees/students/" + asha, accounts.accessToken())
                .andExpect(jsonPath("$.totals.paidPaise").value(3_000_00));
        // Another school numbers its receipts from 1 too.
        School other = api.signup();
        Session otherAdmin = api.login(other);
        String otherYear = fixtures.currentYear(otherAdmin);
        String otherClass = fixtures.schoolClass(otherAdmin, "Class 1");
        String otherStudent = fixtures.student(otherAdmin, fixtures.section(otherAdmin, otherClass, "A", 40), "O-1",
                "Om", "Rao", "Lata Rao", "9876501009");
        fees.publishedStructure(otherAdmin, otherYear, otherClass, fees.defaultHeads(otherAdmin));
        api.post("/api/fees/students/" + otherStudent + "/payments", otherAdmin.accessToken(), """
                {"amountPaise":100000,"mode":"CASH"}""")
                .andExpect(jsonPath("$.receiptNo").value("RCPT/" + financialYear + "/000001"));
    }

    @Test
    void cancellingAReceiptReversesItsAllocationsAndKeepsItsNumber() throws Exception {
        String first = fees.cash(accounts, asha, 11_600_00);
        api.post("/api/fees/receipts/" + first + "/cancel", accounts.accessToken(), """
                {"reason":""}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reason").exists());
        api.post("/api/fees/receipts/" + first + "/cancel", accounts.accessToken(), """
                {"reason":"Cheque returned unpaid"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelReason").value("Cheque returned unpaid"))
                .andExpect(jsonPath("$.cancelledByName").value("Meena Reddy"))
                // The printed lines stay as issued.
                .andExpect(jsonPath("$.lines.length()").value(3));
        api.post("/api/fees/receipts/" + first + "/cancel", accounts.accessToken(), """
                {"reason":"Again"}""").andExpect(status().isConflict());

        // Q1 and its late fee are owed again.
        api.get("/api/fees/students/" + asha, accounts.accessToken())
                .andExpect(jsonPath("$.instalments[0].paidPaise").value(0))
                .andExpect(jsonPath("$.instalments[0].status").value("OVERDUE"))
                .andExpect(jsonPath("$.instalments[0].lateFeePaise").value(100_00))
                .andExpect(jsonPath("$.totals.paidPaise").value(0))
                .andExpect(jsonPath("$.receipts[0].status").value("CANCELLED"));
        try (Connection owner = ownerConnection();
                PreparedStatement s = owner.prepareStatement("""
                        select entry, count(*), sum(amount_paise) from fees.payment_allocation
                        where receipt_id = ? group by entry order by entry""")) {
            s.setObject(1, UUID.fromString(first));
            try (ResultSet rs = s.executeQuery()) {
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("ALLOCATION");
                assertThat(rs.getLong(2)).isEqualTo(3);
                assertThat(rs.getLong(3)).isEqualTo(11_600_00);
                rs.next();
                assertThat(rs.getString(1)).isEqualTo("REVERSAL");
                assertThat(rs.getLong(2)).isEqualTo(3);
                assertThat(rs.getLong(3)).isEqualTo(-11_600_00);
            }
        }
        // The runtime role can never delete a receipt or change what it says.
        try (Connection app = appConnection()) {
            app.createStatement().execute("select set_config('app.tenant_id', '" + school.tenantId() + "', false)");
            assertThat(catchSql(app, "delete from fees.receipt where id = '" + first + "'"))
                    .contains("permission denied");
            assertThat(catchSql(app, "update fees.receipt set amount_paise = 1 where id = '" + first + "'"))
                    .contains("permission denied");
            assertThat(catchSql(app, "delete from fees.payment_allocation")).contains("permission denied");
        }

        // The number is not reused.
        fees.cash(accounts, asha, 1_000_00);
        api.get("/api/fees/receipts", accounts.accessToken())
                .andExpect(jsonPath("$.items[0].receiptNo").value("RCPT/" + financialYear + "/000002"))
                .andExpect(jsonPath("$.items[1].status").value("CANCELLED"))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.totalPaise").value(1_000_00));
        LocalDate today = FeeFixtures.today();
        api.get("/api/fees/reports/collection?from=" + today + "&to=" + today, accounts.accessToken())
                .andExpect(jsonPath("$.totalPaise").value(1_000_00))
                .andExpect(jsonPath("$.receiptCount").value(1))
                .andExpect(jsonPath("$.cancelledCount").value(1));
        // Tally sees the receipt and its cancellation as two lines each way, so the day adds up to what was kept.
        api.get("/api/fees/reports/tally.csv?from=" + today + "&to=" + today, accounts.accessToken())
                .andExpect(content().string(containsString(",RCPT/" + financialYear + "/000001,Tuition fee,10000.00,")))
                .andExpect(content().string(containsString(",RCPT/" + financialYear + "/000001,Tuition fee,-10000.00,")))
                .andExpect(content().string(containsString("Cancelled receipt RCPT/" + financialYear + "/000001")));
        api.get("/api/audit-events?limit=20", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItem("receipt.cancelled")));
    }

    @Test
    void aLateFeeCanBeWaivedWithAReason() throws Exception {
        List<String> instalments = fees.instalmentIds(accounts, asha);
        api.post("/api/fees/students/" + asha + "/late-fee-waivers", accounts.accessToken(), """
                {"instalmentId":"%s","reason":""}""".formatted(instalments.get(0)))
                .andExpect(status().isBadRequest());
        api.post("/api/fees/students/" + asha + "/late-fee-waivers", accounts.accessToken(), """
                {"instalmentId":"%s","reason":"Family emergency"}""".formatted(UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.instalmentId").exists());
        api.post("/api/fees/students/" + asha + "/late-fee-waivers", accounts.accessToken(), """
                {"instalmentId":"%s","reason":"Family emergency"}""".formatted(instalments.get(0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instalments[0].lateFeeWaived").value(true))
                .andExpect(jsonPath("$.instalments[0].lateFeePaise").value(0))
                .andExpect(jsonPath("$.totals.lateFeePaise").value(100_00));
        api.post("/api/fees/students/" + asha + "/late-fee-waivers", accounts.accessToken(), """
                {"instalmentId":"%s","reason":"Again"}""".formatted(instalments.get(0)))
                .andExpect(status().isConflict());
        // Paying Q1 now takes no late fee.
        api.post("/api/fees/students/" + asha + "/payments", accounts.accessToken(), """
                {"amountPaise":1150000,"mode":"CASH","instalmentIds":["%s"]}""".formatted(instalments.get(0)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lateFeePaise").value(0));
        api.get("/api/audit-events?limit=20", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItem("late_fee.waived")));
    }

    @Test
    void reportsRemindersAndExports() throws Exception {
        fees.cash(accounts, bala, 23_200_00);
        LocalDate today = FeeFixtures.today();

        api.get("/api/fees/reports/overview", accounts.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.academicYearName").value("2026-27"))
                .andExpect(jsonPath("$.today.amountPaise").value(23_200_00))
                .andExpect(jsonPath("$.today.receiptCount").value(1))
                .andExpect(jsonPath("$.outstandingPaise").value(46_000_00 + 23_000_00))
                .andExpect(jsonPath("$.overduePaise").value(23_000_00))
                .andExpect(jsonPath("$.overdueStudents").value(1))
                .andExpect(jsonPath("$.recentReceipts.length()").value(1));
        api.get("/api/fees/reports/collection?from=" + today.minusDays(7) + "&to=" + today, accounts.accessToken())
                .andExpect(jsonPath("$.byMode[0].mode").value("CASH"))
                .andExpect(jsonPath("$.byMode[0].amountPaise").value(23_200_00))
                .andExpect(jsonPath("$.byHead[*].headName", contains("Tuition fee", "Annual charges")))
                .andExpect(jsonPath("$.byHead[0].amountPaise").value(20_000_00))
                .andExpect(jsonPath("$.lateFeePaise").value(200_00));
        api.get("/api/fees/reports/collection?from=" + today + "&to=" + today.minusDays(1), accounts.accessToken())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.to").exists());
        api.get("/api/fees/reports/outstanding", accounts.accessToken())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].className").value("Class 5"))
                .andExpect(jsonPath("$.rows[0].sectionName").value("A"))
                .andExpect(jsonPath("$.rows[0].students").value(2))
                .andExpect(jsonPath("$.rows[0].paidPaise").value(23_000_00))
                .andExpect(jsonPath("$.total.balancePaise").value(69_000_00))
                .andExpect(jsonPath("$.total.overduePaise").value(23_000_00));
        api.get("/api/fees/reports/overdue", accounts.accessToken())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].fullName").value("Asha Rao"))
                .andExpect(jsonPath("$.rows[0].guardianPhone").value("98•••••001"))
                .andExpect(jsonPath("$.rows[0].overduePaise").value(23_000_00))
                .andExpect(jsonPath("$.rows[0].lateFeePaise").value(200_00))
                .andExpect(jsonPath("$.rows[0].daysOverdue").value(60))
                .andExpect(jsonPath("$.rows[0].instalments", contains("Quarter 1", "Quarter 2")))
                .andExpect(jsonPath("$.rows[0].lastReminderAt").doesNotExist());
        api.get("/api/fees/reports/overdue?minDays=61", accounts.accessToken())
                .andExpect(jsonPath("$.rows.length()").value(0));

        // Reminders: one per overdue student; others are skipped; students of other schools are refused.
        api.post("/api/fees/reminders", accounts.accessToken(), """
                {"studentIds":["%s","%s"]}""".formatted(asha, bala))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(1))
                .andExpect(jsonPath("$.skipped").value(1));
        assertThat(events.stream(FeeReminderRequested.class).filter(e -> e.studentId().toString().equals(asha)))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.overduePaise()).isEqualTo(23_000_00);
                    assertThat(e.daysOverdue()).isEqualTo(60);
                    assertThat(e.tenantId()).isEqualTo(school.tenantId());
                });
        api.get("/api/fees/reports/overdue", accounts.accessToken())
                .andExpect(jsonPath("$.rows[0].lastReminderAt").exists());
        api.post("/api/fees/reminders", accounts.accessToken(), """
                {"studentIds":["%s"]}""".formatted(UUID.randomUUID()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.studentIds").exists());

        api.get("/api/fees/receipts/export.csv?from=" + today + "&to=" + today, accounts.accessToken())
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", startsWith("attachment;")))
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(startsWith("Receipt no,Date,Student,")))
                .andExpect(content().string(containsString("RCPT/" + financialYear + "/000001," + today
                        + ",Bala Iyer,P-2,Class 5 A,CASH,,,,23200.00,200.00,COUNTER,Meena Reddy,ISSUED")));
        String tally = api.get("/api/fees/reports/tally.csv?from=" + today + "&to=" + today, accounts.accessToken())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String day = String.format("%02d-%02d-%d", today.getDayOfMonth(), today.getMonthValue(), today.getYear());
        assertThat(tally.split("\r\n")).containsExactly(
                "Date,Receipt No,Ledger,Amount,Mode,Narration",
                day + ",RCPT/" + financialYear + "/000001,Late fee,200.00,CASH,"
                        + "\"Fee from Bala Iyer (P-2), Class 5 A, Quarter 1, Quarter 2\"",
                day + ",RCPT/" + financialYear + "/000001,Tuition fee,20000.00,CASH,"
                        + "\"Fee from Bala Iyer (P-2), Class 5 A, Quarter 1, Quarter 2\"",
                day + ",RCPT/" + financialYear + "/000001,Annual charges,3000.00,CASH,"
                        + "\"Fee from Bala Iyer (P-2), Class 5 A, Quarter 1, Quarter 2\"");
        api.get("/api/audit-events?limit=20", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("fee_reminders.requested", "fee_payment.recorded")));
    }

    private static String catchSql(Connection connection, String sql) {
        try {
            connection.createStatement().execute(sql);
            return "no error";
        } catch (java.sql.SQLException e) {
            return e.getMessage();
        }
    }
}
