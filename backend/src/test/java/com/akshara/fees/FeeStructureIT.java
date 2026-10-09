package com.akshara.fees;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.akshara.support.FeeFixtures;
import com.akshara.support.FeeFixtures.Heads;
import com.akshara.support.IntegrationTest;
import com.akshara.support.SchoolFixtures;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.School;
import com.akshara.support.TestApi.Session;

/** Fee heads and structures, publishing them into students' dues, and concessions. */
class FeeStructureIT extends IntegrationTest {

    private School school;
    private Session admin;
    private SchoolFixtures fixtures;
    private FeeFixtures fees;
    private String yearId;
    private String class5;
    private String class5A;
    private String asha;
    private String bala;
    private Heads heads;

    @BeforeEach
    void schoolWithStudents() throws Exception {
        school = api.signup();
        admin = api.login(school);
        fixtures = new SchoolFixtures(api);
        fees = new FeeFixtures(api, mvc);
        yearId = fixtures.currentYear(admin);
        class5 = fixtures.schoolClass(admin, "Class 5");
        class5A = fixtures.section(admin, class5, "A", 40);
        String class5B = fixtures.section(admin, class5, "B", 40);
        asha = fixtures.student(admin, class5A, "F-1", "Asha", "Rao", "Lata Rao", "9876501001");
        bala = fixtures.student(admin, class5B, "F-2", "Bala", "Iyer", "Uma Iyer", "9876501002");
        heads = fees.defaultHeads(admin);
    }

    @Test
    void defaultHeadsAreAddedOnceAndHeadsCanBeEdited() throws Exception {
        api.get("/api/fees/heads", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$[0].name").value("Tuition fee"))
                .andExpect(jsonPath("$[1].oneTime").value(true));
        api.post("/api/fees/heads/defaults", admin.accessToken(), null).andExpect(jsonPath("$.length()").value(6));

        api.post("/api/fees/heads", admin.accessToken(), """
                {"name":"tuition FEE","kind":"TUITION"}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.name").exists());
        String sports = TestApi.read(api.post("/api/fees/heads", admin.accessToken(), """
                {"name":"Sports fee","kind":"OTHER"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.oneTime").value(false))
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.displayOrder").value(7)), "$.id");
        api.put("/api/fees/heads/" + sports, admin.accessToken(), """
                {"name":"Sports and games","kind":"OTHER","oneTime":false,"active":false}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Sports and games"))
                .andExpect(jsonPath("$.active").value(false));
        api.post("/api/fees/heads", admin.accessToken(), """
                {"name":"","kind":"OTHER"}""").andExpect(status().isBadRequest());
        api.get("/api/audit-events?limit=20", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("fee_head.created", "fee_head.updated")));
    }

    @Test
    void publishingCreatesDuesForEveryActiveStudentAndLaterJoinersGetThemToo() throws Exception {
        List<LocalDate> due = FeeFixtures.quarters();
        String id = TestApi.read(api.post("/api/fees/structures", admin.accessToken(),
                FeeFixtures.structureJson(yearId, class5, heads, 40_000_00, 6_000_00, due))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.structure.status").value("DRAFT"))
                .andExpect(jsonPath("$.structure.className").value("Class 5"))
                .andExpect(jsonPath("$.structure.totalPaise").value(46_000_00))
                .andExpect(jsonPath("$.structure.instalments.length()").value(4))
                .andExpect(jsonPath("$.structure.instalments[0].label").value("Quarter 1"))
                .andExpect(jsonPath("$.structure.instalments[0].amountPaise").value(11_500_00))
                .andExpect(jsonPath("$.dues.studentsCreated").value(0)), "$.structure.id");
        // A draft creates no dues.
        api.get("/api/fees/students/" + asha, admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instalments.length()").value(0));

        api.post("/api/fees/structures/" + id + "/publish", admin.accessToken(), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.structure.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.dues.studentsCreated").value(2))
                .andExpect(jsonPath("$.structure.studentsWithDues").value(2));

        api.get("/api/fees/students/" + asha, admin.accessToken())
                .andExpect(jsonPath("$.student.fullName").value("Asha Rao"))
                .andExpect(jsonPath("$.instalments.length()").value(4))
                .andExpect(jsonPath("$.instalments[0].dueDate").value(due.get(0).toString()))
                .andExpect(jsonPath("$.instalments[0].status").value("OVERDUE"))
                .andExpect(jsonPath("$.instalments[0].daysOverdue").value(60))
                .andExpect(jsonPath("$.instalments[0].heads[*].headName", contains("Tuition fee", "Annual charges")))
                .andExpect(jsonPath("$.instalments[1].status").value("OVERDUE"))
                .andExpect(jsonPath("$.instalments[2].status").value("UPCOMING"))
                .andExpect(jsonPath("$.instalments[3].status").value("UPCOMING"))
                .andExpect(jsonPath("$.totals.netPaise").value(46_000_00))
                .andExpect(jsonPath("$.totals.overduePaise").value(23_000_00))
                .andExpect(jsonPath("$.totals.balancePaise").value(46_000_00));

        // A student who joins the class later gets their dues the first time their fees are read.
        String chitra = fixtures.student(admin, class5A, "F-3", "Chitra", "Das", "Ritu Das", "9876501003");
        api.get("/api/fees/students/" + chitra, admin.accessToken())
                .andExpect(jsonPath("$.instalments.length()").value(4))
                .andExpect(jsonPath("$.totals.netPaise").value(46_000_00));
        String dev = fixtures.student(admin, class5A, "F-4", "Dev", "Shah", "Mina Shah", "9876501004");
        // ... or when the structure is published again.
        api.post("/api/fees/structures/" + id + "/publish", admin.accessToken(), null)
                .andExpect(jsonPath("$.dues.studentsCreated").value(1));
        api.get("/api/fees/structures?yearId=" + yearId, admin.accessToken())
                .andExpect(jsonPath("$[0].status").value("PUBLISHED"))
                .andExpect(jsonPath("$[0].studentsWithDues").value(4))
                .andExpect(jsonPath("$[0].totalPaise").value(46_000_00));
        api.get("/api/fees/students/" + dev, admin.accessToken()).andExpect(jsonPath("$.instalments.length()")
                .value(4));
        // A class can have only one structure a year.
        api.post("/api/fees/structures", admin.accessToken(),
                FeeFixtures.structureJson(yearId, class5, heads, 1_00, 0, due))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.classId").exists());
        api.get("/api/audit-events?limit=50", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("fee_structure.saved", "fee_structure.published")));
    }

    @Test
    void editingAPublishedStructureRepricesOnlyWhatIsUnpaid() throws Exception {
        List<LocalDate> due = FeeFixtures.quarters();
        String id = fees.publishedStructure(admin, yearId, class5, heads);
        // Asha pays Q1 (₹11,500) and ₹2,500 of Q2's tuition.
        fees.cash(admin, asha, 14_000_00);

        // Tuition comes down to ₹32,000 (₹8,000 a quarter). Asha's Q1 tuition stays at ₹10,000, as paid.
        api.put("/api/fees/structures/" + id, admin.accessToken(),
                FeeFixtures.structureJson(yearId, class5, heads, 32_000_00, 6_000_00, due))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dues.studentsUpdated").value(2))
                .andExpect(jsonPath("$.dues.studentsCreated").value(0))
                .andExpect(jsonPath("$.dues.paidCellsKept").value(1))
                .andExpect(jsonPath("$.structure.totalPaise").value(38_000_00));
        api.get("/api/fees/students/" + asha, admin.accessToken())
                .andExpect(jsonPath("$.totals.paidPaise").value(14_000_00))
                .andExpect(jsonPath("$.instalments[0].status").value("PAID"))
                .andExpect(jsonPath("$.instalments[0].heads[0].grossPaise").value(10_000_00))
                .andExpect(jsonPath("$.instalments[0].heads[0].paidPaise").value(10_000_00))
                .andExpect(jsonPath("$.instalments[1].heads[0].grossPaise").value(8_000_00))
                .andExpect(jsonPath("$.instalments[1].heads[0].paidPaise").value(2_500_00))
                .andExpect(jsonPath("$.instalments[1].balancePaise").value(7_000_00));
        api.get("/api/fees/students/" + bala, admin.accessToken())
                .andExpect(jsonPath("$.totals.netPaise").value(38_000_00))
                .andExpect(jsonPath("$.instalments[0].netPaise").value(9_500_00));

        // An instalment with payments cannot be removed; one without can, and its dues go with it.
        api.put("/api/fees/structures/" + id, admin.accessToken(),
                FeeFixtures.structureJson(yearId, class5, heads, 32_000_00, 6_000_00, due.subList(0, 1)))
                .andExpect(status().isConflict());
        api.put("/api/fees/structures/" + id, admin.accessToken(),
                FeeFixtures.structureJson(yearId, class5, heads, 30_000_00, 6_000_00, due.subList(0, 3)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.structure.instalments.length()").value(3));
        api.get("/api/fees/students/" + bala, admin.accessToken())
                .andExpect(jsonPath("$.instalments.length()").value(3))
                .andExpect(jsonPath("$.totals.netPaise").value(36_000_00));
        api.get("/api/fees/students/" + asha, admin.accessToken())
                .andExpect(jsonPath("$.totals.paidPaise").value(14_000_00));
        // A structure keeps its class.
        String class6 = fixtures.schoolClass(admin, "Class 6");
        fixtures.section(admin, class6, "A", 40);
        api.put("/api/fees/structures/" + id, admin.accessToken(),
                FeeFixtures.structureJson(yearId, class6, heads, 30_000_00, 6_000_00, due))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.classId").exists());
    }

    @Test
    void sharesMustAddUpExactlyAndTheLastInstalmentTakesTheRounding() throws Exception {
        LocalDate d = LocalDate.of(2026, 6, 10);
        // ₹10,000.01 in three: the last instalment absorbs the extra paise.
        api.post("/api/fees/structures", admin.accessToken(), """
                {"academicYearId":"%s","classId":"%s","heads":[{"headId":"%s","amountPaise":1000001},
                 {"headId":"%s","amountPaise":200000}],
                 "instalments":[{"dueDate":"%s"},{"dueDate":"%s"},{"dueDate":"%s"}]}"""
                .formatted(yearId, class5, heads.tuition(), heads.admission(), d, d.plusMonths(3), d.plusMonths(6)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.structure.instalments[*].label", contains("Instalment 1", "Instalment 2",
                        "Instalment 3")))
                .andExpect(jsonPath("$.structure.instalments[0].shares[0].amountPaise").value(333333))
                .andExpect(jsonPath("$.structure.instalments[2].shares[0].amountPaise").value(333335))
                // Admission is one-time, so it is all in the first instalment.
                .andExpect(jsonPath("$.structure.instalments[0].amountPaise").value(533333))
                .andExpect(jsonPath("$.structure.instalments[1].shares.length()").value(1));

        String class6 = fixtures.schoolClass(admin, "Class 6");
        fixtures.section(admin, class6, "A", 40);
        String base = """
                {"academicYearId":"%s","classId":"%s","heads":[{"headId":"%s","amountPaise":1000000}],
                 "instalments":%s}""";
        // Shares that do not add up to the head's amount.
        api.post("/api/fees/structures", admin.accessToken(), base.formatted(yearId, class6, heads.tuition(), """
                [{"dueDate":"2026-06-10","shares":[{"headId":"%s","amountPaise":500000}]},
                 {"dueDate":"2026-09-10","shares":[{"headId":"%s","amountPaise":400000}]}]"""
                .formatted(heads.tuition(), heads.tuition())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.instalments").value(
                        "Tuition fee: the instalments add up to ₹9,000, not ₹10,000."));
        // Shares for some instalments but not others.
        api.post("/api/fees/structures", admin.accessToken(), base.formatted(yearId, class6, heads.tuition(), """
                [{"dueDate":"2026-06-10","shares":[{"headId":"%s","amountPaise":1000000}]},
                 {"dueDate":"2026-09-10"}]""".formatted(heads.tuition())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.instalments").exists());
        // A share of a head that is not in the structure.
        api.post("/api/fees/structures", admin.accessToken(), base.formatted(yearId, class6, heads.tuition(), """
                [{"dueDate":"2026-06-10","shares":[{"headId":"%s","amountPaise":1000000},
                  {"headId":"%s","amountPaise":1}]}]""".formatted(heads.tuition(), heads.exam())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['instalments[0].shares[1].headId']").exists());
        // Due dates must go forward.
        api.post("/api/fees/structures", admin.accessToken(), base.formatted(yearId, class6, heads.tuition(), """
                [{"dueDate":"2026-09-10"},{"dueDate":"2026-06-10"}]"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['instalments[1].dueDate']").exists());
        // At most twelve instalments.
        StringBuilder thirteen = new StringBuilder("[");
        for (int i = 0; i < 13; i++) {
            thirteen.append(i == 0 ? "" : ",").append("{\"dueDate\":\"").append(d.plusMonths(i)).append("\"}");
        }
        api.post("/api/fees/structures", admin.accessToken(), base.formatted(yearId, class6, heads.tuition(),
                thirteen.append("]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.instalments").exists());
        // Nothing to charge.
        api.post("/api/fees/structures", admin.accessToken(), """
                {"academicYearId":"%s","classId":"%s","heads":[{"headId":"%s","amountPaise":0}],
                 "instalments":[{"dueDate":"2026-06-10"}]}""".formatted(yearId, class6, heads.tuition()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.heads").exists());

        // Another school's head, class or year is a 400 on that field.
        School other = api.signup();
        Session otherAdmin = api.login(other);
        Heads otherHeads = fees.defaultHeads(otherAdmin);
        String otherYear = fixtures.currentYear(otherAdmin);
        String otherClass = fixtures.schoolClass(otherAdmin, "Class 6");
        fixtures.section(otherAdmin, otherClass, "A", 40);
        api.post("/api/fees/structures", admin.accessToken(), base.formatted(yearId, class6, otherHeads.tuition(),
                "[{\"dueDate\":\"2026-06-10\"}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['heads[0].headId']").exists());
        api.post("/api/fees/structures", admin.accessToken(), base.formatted(yearId, otherClass, heads.tuition(),
                "[{\"dueDate\":\"2026-06-10\"}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.classId").exists());
        api.post("/api/fees/structures", admin.accessToken(), base.formatted(otherYear, class6, heads.tuition(),
                "[{\"dueDate\":\"2026-06-10\"}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.academicYearId").exists());
    }

    @Test
    void concessionsLowerUnpaidDuesAndNeverTouchWhatIsPaid() throws Exception {
        fees.publishedStructure(admin, yearId, class5, heads);
        String chitra = fixtures.student(admin, class5A, "F-3", "Chitra", "Das", "Ritu Das", "9876501003");
        fees.cash(admin, chitra, 11_500_00);

        // RTE: 100% of tuition, whatever the form says.
        String rte = TestApi.read(api.post("/api/fees/concessions", admin.accessToken(), """
                {"studentId":"%s","type":"RTE","mode":"FIXED","fixedPaise":100,"headIds":["%s"],
                 "reason":"RTE quota admission"}""".formatted(asha, heads.annual()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("RTE"))
                .andExpect(jsonPath("$.mode").value("PERCENT"))
                .andExpect(jsonPath("$.percent").value(100.0))
                .andExpect(jsonPath("$.heads[*].name", contains("Tuition fee")))
                .andExpect(jsonPath("$.approvedByName").value("Asha Admin"))
                .andExpect(jsonPath("$.studentConcessionPaise").value(40_000_00)), "$.id");
        api.get("/api/fees/students/" + asha, admin.accessToken())
                .andExpect(jsonPath("$.totals.concessionPaise").value(40_000_00))
                .andExpect(jsonPath("$.totals.netPaise").value(6_000_00))
                .andExpect(jsonPath("$.instalments[0].netPaise").value(1_500_00))
                .andExpect(jsonPath("$.concessions[0].type").value("RTE"));
        api.post("/api/fees/concessions", admin.accessToken(), """
                {"studentId":"%s","type":"RTE","reason":"Again"}""".formatted(asha))
                .andExpect(status().isConflict());

        // Sibling 10% of tuition plus a fixed ₹2,000 off annual charges, spread over the quarters.
        String sibling = TestApi.read(api.post("/api/fees/concessions", admin.accessToken(), """
                {"studentId":"%s","type":"SIBLING","mode":"PERCENT","percent":10,"headIds":["%s"],
                 "reason":"Younger sibling"}""".formatted(bala, heads.tuition()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.percent").value(10.0)), "$.id");
        api.post("/api/fees/concessions", admin.accessToken(), """
                {"studentId":"%s","type":"STAFF_WARD","mode":"FIXED","fixedPaise":200000,"headIds":["%s"],
                 "reason":"Child of a teacher"}""".formatted(bala, heads.annual()))
                .andExpect(status().isCreated());
        api.get("/api/fees/students/" + bala, admin.accessToken())
                .andExpect(jsonPath("$.totals.concessionPaise").value(6_000_00))
                .andExpect(jsonPath("$.instalments[0].concessionPaise").value(1_500_00))
                .andExpect(jsonPath("$.instalments[0].netPaise").value(10_000_00));
        api.post("/api/fees/concessions/" + sibling + "/revoke", admin.accessToken(), """
                {"reason":"Sibling left the school"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.revokeReason").value("Sibling left the school"));
        api.post("/api/fees/concessions/" + sibling + "/revoke", admin.accessToken(), """
                {"reason":"Again"}""").andExpect(status().isConflict());
        api.get("/api/fees/students/" + bala, admin.accessToken())
                .andExpect(jsonPath("$.totals.concessionPaise").value(2_000_00));

        // Chitra already paid Q1; an RTE place lowers what is still owed but never what was paid.
        api.post("/api/fees/concessions", admin.accessToken(), """
                {"studentId":"%s","type":"RTE","reason":"RTE quota admission"}""".formatted(chitra))
                .andExpect(status().isCreated());
        api.get("/api/fees/students/" + chitra, admin.accessToken())
                .andExpect(jsonPath("$.totals.paidPaise").value(11_500_00))
                .andExpect(jsonPath("$.instalments[0].heads[0].paidPaise").value(10_000_00))
                .andExpect(jsonPath("$.instalments[0].status").value("PAID"))
                .andExpect(jsonPath("$.instalments[1].netPaise").value(1_500_00));

        // Validation.
        api.post("/api/fees/concessions", admin.accessToken(), """
                {"studentId":"%s","type":"MERIT","mode":"PERCENT","headIds":["%s"],"reason":"Topper"}"""
                .formatted(bala, heads.tuition()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.percent").exists());
        api.post("/api/fees/concessions", admin.accessToken(), """
                {"studentId":"%s","type":"MERIT","mode":"PERCENT","percent":12.345,"headIds":["%s"],
                 "reason":"Topper"}""".formatted(bala, heads.tuition()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.percent").exists());
        api.post("/api/fees/concessions", admin.accessToken(), """
                {"studentId":"%s","type":"MERIT","mode":"PERCENT","percent":5,"headIds":[],"reason":"Topper"}"""
                .formatted(bala))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.headIds").exists());
        api.post("/api/fees/concessions", admin.accessToken(), """
                {"studentId":"%s","type":"MERIT","mode":"PERCENT","percent":5,"headIds":["%s"],"reason":" "}"""
                .formatted(bala, heads.tuition()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reason").exists());

        api.get("/api/fees/concessions?yearId=" + yearId, admin.accessToken())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$[*].studentName", hasItem("Asha Rao")));
        api.get("/api/fees/concessions?studentId=" + bala, admin.accessToken())
                .andExpect(jsonPath("$.length()").value(2));
        api.get("/api/audit-events?limit=50", admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("concession.granted", "concession.revoked")));
        api.get("/api/audit-events?limit=50", admin.accessToken())
                .andExpect(jsonPath("$[?(@.action == 'concession.granted')].entityId", hasItem(rte)));
    }

    @Test
    void theLateFeeRuleIsValidatedAndSaved() throws Exception {
        api.get("/api/fees/late-fee-rule", admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("NONE"));
        api.put("/api/fees/late-fee-rule", admin.accessToken(), """
                {"mode":"FLAT","graceDays":5}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.flatPaise").exists());
        api.put("/api/fees/late-fee-rule", admin.accessToken(), """
                {"mode":"PER_DAY","graceDays":7,"perDayPaise":1000,"capPaise":500}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.capPaise").exists());
        api.put("/api/fees/late-fee-rule", admin.accessToken(), """
                {"mode":"PER_DAY","graceDays":7,"perDayPaise":1000,"capPaise":50000,"flatPaise":99}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("PER_DAY"))
                .andExpect(jsonPath("$.flatPaise").value(0))
                .andExpect(jsonPath("$.capPaise").value(50000));
        api.get("/api/fees/late-fee-rule", admin.accessToken())
                .andExpect(jsonPath("$.graceDays").value(7))
                .andExpect(jsonPath("$.perDayPaise").value(1000));
        api.get("/api/audit-events?limit=5", admin.accessToken())
                .andExpect(jsonPath("$[0].action").value("late_fee_rule.updated"));
    }
}
