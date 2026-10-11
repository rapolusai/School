package com.akshara.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;
import com.akshara.support.TestApi.Session;

/**
 * Who may do what in the library, another school's records, the family view, reports and their CSV files, stock
 * verification and overdue reminders.
 */
class LibraryAccessIT extends IntegrationTest {

    @Autowired
    LibraryReminderRunner reminderRunner;

    @Test
    void staffSearchTheCatalogueAndOnlyLibraryManagersChangeIt() throws Exception {
        LibrarySchool s = new LibrarySchool(api);
        String title = s.title("Malgudi Days", 1);
        String loan = s.issue(s.accession(title, 0), "STUDENT", s.asha, null);
        // A teacher searches and opens titles, but sees nobody's loans.
        api.get("/api/library/titles", s.teacher.accessToken()).andExpect(status().isOk());
        api.get("/api/library/titles/" + title, s.teacher.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.copies[0].status").value("ISSUED"))
                .andExpect(jsonPath("$.copies[0].loan").doesNotExist());
        api.get("/api/library/titles/" + title, s.librarian.accessToken())
                .andExpect(jsonPath("$.copies[0].loan.member.name").value("Asha Rao"));
        String token = s.teacher.accessToken();
        // Bodies are valid, so the answer is about the permission.
        api.post("/api/library/titles", token, "{\"title\":\"X\"}").andExpect(status().isForbidden());
        api.put("/api/library/titles/" + title, token, "{\"title\":\"X\"}").andExpect(status().isForbidden());
        api.delete("/api/library/titles/" + title, token).andExpect(status().isForbidden());
        api.post("/api/library/titles/" + title + "/copies", token, "{\"count\":1}").andExpect(status().isForbidden());
        api.get("/api/library/isbn?value=8173711461", token).andExpect(status().isForbidden());
        api.get("/api/library/members?q=asha", token).andExpect(status().isForbidden());
        api.get("/api/library/members/STUDENT/" + s.asha, token).andExpect(status().isForbidden());
        api.post("/api/library/loans", token, """
                {"accessionNo":"LIB00001","memberType":"STUDENT","memberId":"%s"}""".formatted(s.bala))
                .andExpect(status().isForbidden());
        api.get("/api/library/loans", token).andExpect(status().isForbidden());
        api.post("/api/library/loans/" + loan + "/renew", token, null).andExpect(status().isForbidden());
        api.post("/api/library/returns", token, "{\"accessionNo\":\"LIB00001\"}").andExpect(status().isForbidden());
        api.get("/api/library/reservations", token).andExpect(status().isForbidden());
        api.get("/api/library/fines", token).andExpect(status().isForbidden());
        api.get("/api/library/settings", token).andExpect(status().isForbidden());
        api.put("/api/library/rules/STUDENT", token, """
                {"maxBooks":2,"loanDays":14,"maxRenewals":1,"finePerDayPaise":100,"maxFinePaise":5000}""")
                .andExpect(status().isForbidden());
        api.get("/api/library/rules", token).andExpect(status().isOk());
        api.get("/api/library/stock-checks", token).andExpect(status().isForbidden());
        api.get("/api/library/reports/overdue", token).andExpect(status().isForbidden());
        api.get("/api/library/summary", token).andExpect(status().isForbidden());
        // Parents have their own view only.
        api.get("/api/library/titles", s.parent.accessToken()).andExpect(status().isForbidden());
        api.get("/api/library/rules", s.parent.accessToken()).andExpect(status().isForbidden());
        // The school admin manages the library too.
        api.get("/api/library/summary", s.admin.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issued").value(1));
        mvcUnauthenticated("/api/library/titles");
    }

    @Test
    void anotherSchoolsRecordsAreNotFound() throws Exception {
        LibrarySchool s = new LibrarySchool(api);
        String title = s.title("Ours", 2);
        String copy = TestApi.read(api.get("/api/library/titles/" + title, s.librarian.accessToken()),
                "$.copies[0].id");
        String accession = s.accession(title, 0);
        String loan = s.issue(accession, "STUDENT", s.asha, LibrarySchool.today().minusDays(40));
        String fine = TestApi.read(api.post("/api/library/returns", s.librarian.accessToken(), """
                {"accessionNo":"%s"}""".formatted(accession)), "$.fines[0].id");
        s.issue(accession, "STUDENT", s.chitra, null);
        s.issue(s.accession(title, 1), "STUDENT", s.bala, null);
        String hold = TestApi.read(api.post("/api/library/reservations", s.librarian.accessToken(), """
                {"titleId":"%s","memberType":"STUDENT","memberId":"%s"}""".formatted(title, s.asha))
                .andExpect(status().isCreated()), "$.id");
        String check = TestApi.read(api.post("/api/library/stock-checks", s.librarian.accessToken(), "{}"), "$.id");

        LibrarySchool other = new LibrarySchool(api);
        String token = other.librarian.accessToken();
        api.get("/api/library/titles/" + title, token).andExpect(status().isNotFound());
        api.put("/api/library/titles/" + title, token, "{\"title\":\"Mine\"}").andExpect(status().isNotFound());
        api.delete("/api/library/titles/" + title, token).andExpect(status().isNotFound());
        api.post("/api/library/titles/" + title + "/copies", token, "{\"count\":1}")
                .andExpect(status().isNotFound());
        api.put("/api/library/copies/" + copy, token, "{\"source\":\"PURCHASE\",\"status\":\"LOST\"}")
                .andExpect(status().isNotFound());
        api.get("/api/library/copies/lookup?accessionNo=" + accession, token).andExpect(status().isNotFound());
        api.post("/api/library/loans/" + loan + "/renew", token, null).andExpect(status().isNotFound());
        api.post("/api/library/loans/" + loan + "/lost", token, "{}").andExpect(status().isNotFound());
        api.post("/api/library/fines/" + fine + "/waive", token, "{\"reason\":\"Mine\"}")
                .andExpect(status().isNotFound());
        api.post("/api/library/reservations/" + hold + "/cancel", token, null).andExpect(status().isNotFound());
        api.get("/api/library/stock-checks/" + check, token).andExpect(status().isNotFound());
        api.get("/api/library/members/STUDENT/" + s.asha, token).andExpect(status().isNotFound());
        api.get("/api/library/reports/member-history?memberType=STUDENT&memberId=" + s.asha, token)
                .andExpect(status().isNotFound());
        // Ids of another school in a body are a 400 on the field.
        api.post("/api/library/fines/collect", token, """
                {"fineIds":["%s"],"mode":"CASH"}""".formatted(fine))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.fineIds").exists());
        api.post("/api/library/reservations", token, """
                {"titleId":"%s","memberType":"STUDENT","memberId":"%s"}""".formatted(title, other.asha))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.titleId").exists());
        String theirs = other.title("Theirs", 1);
        api.post("/api/library/loans", token, """
                {"accessionNo":"%s","memberType":"STUDENT","memberId":"%s"}"""
                .formatted(other.accession(theirs, 0), s.asha))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.memberId").exists());
        // Accession numbers are per school: the other school's first copy is LIB00001 too, and lists stay apart.
        assertThat(other.accession(theirs, 0)).isEqualTo("LIB00001");
        api.post("/api/library/returns", token, """
                {"accessionNo":"%s"}""".formatted("LIB00002")).andExpect(status().isBadRequest());
        api.get("/api/library/titles", token).andExpect(jsonPath("$.total").value(1));
        api.get("/api/library/fines", token).andExpect(jsonPath("$.total").value(0));
        api.get("/api/library/loans", token).andExpect(jsonPath("$.total").value(0));
        api.get("/api/library/reservations", token).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void familiesSeeTheirOwnChildrensBooks() throws Exception {
        LibrarySchool s = new LibrarySchool(api);
        String title = s.title("Grandma's Bag of Stories", 2);
        LocalDate today = LibrarySchool.today();
        s.issue(s.accession(title, 0), "STUDENT", s.asha, today.minusDays(30));
        s.issue(s.accession(title, 1), "STUDENT", s.bala, null);
        String other = s.title("Panchatantra", 1);
        s.issue(s.accession(other, 0), "STAFF", s.teacherId, null);
        api.get("/api/me/library", s.parent.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(1))
                .andExpect(jsonPath("$.members[0].name").value("Asha Rao"))
                .andExpect(jsonPath("$.members[0].className").value("Class 5 A"))
                .andExpect(jsonPath("$.members[0].loans[0].title").value("Grandma's Bag of Stories"))
                .andExpect(jsonPath("$.members[0].loans[0].overdue").value(true))
                .andExpect(jsonPath("$.members[0].loans[0].fineSoFarPaise").value(greaterThan(0)));
        // Bala's mother has no sign-in; Bala himself signs in and sees only his own book.
        String domain = "@" + s.school.code() + ".akshara.test";
        api.post("/api/students/" + s.bala + "/sign-in", s.admin.accessToken(), """
                {"mode":"CREATE","email":"%s","password":"%s"}""".formatted("bala" + domain, TestApi.PASSWORD))
                .andExpect(status().isOk());
        Session bala = api.login(s.school.code(), "bala" + domain, TestApi.PASSWORD);
        api.get("/api/me/library", bala.accessToken())
                .andExpect(jsonPath("$.members[*].name", contains("Bala Iyer")))
                .andExpect(jsonPath("$.members[0].loans.length()").value(1))
                .andExpect(jsonPath("$.members[0].loans[0].overdue").value(false));
        // A member of staff sees their own.
        api.get("/api/me/library", s.teacher.accessToken())
                .andExpect(jsonPath("$.members[0].type").value("STAFF"))
                .andExpect(jsonPath("$.members[0].loans[0].title").value("Panchatantra"));
        api.get("/api/me/library", s.librarian.accessToken())
                .andExpect(jsonPath("$.members[0].loans.length()").value(0));
    }

    @Test
    void reportsComeAsJsonAndAsCsvFiles() throws Exception {
        LibrarySchool s = new LibrarySchool(api);
        LocalDate today = LibrarySchool.today();
        String popular = s.title("Popular, \"quoted\" title", 2);
        String quiet = s.title("=SUM(A1)", 1);
        s.issue(s.accession(popular, 0), "STUDENT", s.asha, today.minusDays(30));
        s.issue(s.accession(popular, 1), "STUDENT", s.bala, null);
        s.issue(s.accession(quiet, 0), "STAFF", s.teacherId, null);
        String token = s.librarian.accessToken();
        api.get("/api/library/reports/overdue", token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].member.name").value("Asha Rao"));
        api.get("/api/library/reports/overdue?memberType=STAFF", token).andExpect(jsonPath("$.rows.length()").value(0));
        api.get("/api/library/reports/overdue?classId=" + s.classId, token)
                .andExpect(jsonPath("$.rows.length()").value(1));
        api.get("/api/library/reports/issued", token).andExpect(jsonPath("$.rows.length()").value(2));
        api.get("/api/library/reports/issued?date=" + today.minusDays(30), token)
                .andExpect(jsonPath("$.rows[*].member.name", contains("Asha Rao")));
        // The last 30 days by default: Asha's loan is older.
        api.get("/api/library/reports/popular", token)
                .andExpect(jsonPath("$.rows.length()").value(2))
                .andExpect(jsonPath("$.rows[*].loans", contains(1, 1)));
        api.get("/api/library/reports/popular?from=" + today.minusDays(60), token)
                .andExpect(jsonPath("$.rows[0].title").value("Popular, \"quoted\" title"))
                .andExpect(jsonPath("$.rows[0].loans").value(2))
                .andExpect(jsonPath("$.rows[0].copies").value(2));
        api.get("/api/library/reports/popular?from=" + today + "&to=" + today.minusDays(1), token)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.to").exists());
        api.get("/api/library/reports/popular?from=" + today.minusDays(500) + "&to=" + today, token)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.from").exists());
        api.get("/api/library/reports/member-history?memberType=STUDENT&memberId=" + s.asha, token)
                .andExpect(jsonPath("$.member.name").value("Asha Rao"))
                .andExpect(jsonPath("$.loans.length()").value(1));
        api.get("/api/library/reports/member-history?memberType=STUDENT&memberId=" + UUID.randomUUID(), token)
                .andExpect(status().isNotFound());

        // CSV files open in Excel: UTF-8 with a byte order mark, formulas defused, quotes doubled. The web asks
        // for Excel or JSON; the file comes back as CSV all the same.
        byte[] overdue = mvc.perform(MockMvcRequestBuilders.get("/api/library/reports/overdue.csv")
                        .header("Authorization", "Bearer " + token)
                        .header("Accept", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet, "
                                + "application/json"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andExpect(header().string("Content-Disposition", containsString("library-overdue-" + today)))
                .andReturn().getResponse().getContentAsByteArray();
        String text = new String(overdue, StandardCharsets.UTF_8);
        assertThat(text).startsWith("﻿Accession no,Title,Member");
        assertThat(text).contains("\"Popular, \"\"quoted\"\" title\"").contains("Asha Rao").contains("LIB-1");
        String issued = csv("/api/library/reports/issued.csv", token);
        assertThat(issued).contains("'=SUM(A1)").contains("Tara Teacher");
        assertThat(csv("/api/library/reports/popular.csv?from=" + today.minusDays(60), token))
                .contains("1,\"Popular, \"\"quoted\"\" title\",Test Author,2,2");
        assertThat(csv("/api/library/reports/member-history.csv?memberType=STUDENT&memberId=" + s.asha, token))
                .contains("ISSUED");
        api.get("/api/library/reports/overdue.csv", s.teacher.accessToken()).andExpect(status().isForbidden());
    }

    @Test
    void stockChecksListCopiesNotSeen() throws Exception {
        LibrarySchool s = new LibrarySchool(api);
        String title = s.title("Shelf Book", 3);
        String lent = s.title("Lent Book", 1);
        s.issue(s.accession(lent, 0), "STUDENT", s.asha, null);
        String token = s.librarian.accessToken();
        String check = TestApi.read(api.post("/api/library/stock-checks", token, """
                {"note":"Term 1 check"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.expected").value(3))
                .andExpect(jsonPath("$.missing").value(3)), "$.id");
        api.post("/api/library/stock-checks", token, "{}").andExpect(status().isConflict());
        api.post("/api/library/stock-checks/" + check + "/scans", token, """
                {"accessionNo":"%s"}""".formatted(s.accession(title, 0).toLowerCase()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadySeen").value(false))
                .andExpect(jsonPath("$.seen").value(1));
        api.post("/api/library/stock-checks/" + check + "/scans", token, """
                {"accessionNo":"%s"}""".formatted(s.accession(title, 0)))
                .andExpect(jsonPath("$.alreadySeen").value(true))
                .andExpect(jsonPath("$.seen").value(1));
        // A copy on loan found on the shelf is flagged.
        api.post("/api/library/stock-checks/" + check + "/scans", token, """
                {"accessionNo":"%s"}""".formatted(s.accession(lent, 0)))
                .andExpect(jsonPath("$.warning").value("ISSUED"));
        api.post("/api/library/stock-checks/" + check + "/scans", token, """
                {"accessionNo":"NOPE1"}""").andExpect(status().isBadRequest());
        api.get("/api/library/stock-checks/" + check, token)
                .andExpect(jsonPath("$.missing[*].accessionNo", contains(s.accession(title, 1),
                        s.accession(title, 2))))
                .andExpect(jsonPath("$.latest.length()").value(2));
        String missing = csv("/api/library/stock-checks/" + check + "/missing.csv", token);
        assertThat(missing).contains(s.accession(title, 1)).contains("Shelf Book");
        api.post("/api/library/stock-checks/" + check + "/close", token, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.check.status").value("CLOSED"))
                .andExpect(jsonPath("$.check.expected").value(3))
                .andExpect(jsonPath("$.check.missing").value(2))
                .andExpect(jsonPath("$.check.closedByName").value("Libby Librarian"));
        api.post("/api/library/stock-checks/" + check + "/close", token, null).andExpect(status().isConflict());
        api.post("/api/library/stock-checks/" + check + "/scans", token, """
                {"accessionNo":"%s"}""".formatted(s.accession(title, 1))).andExpect(status().isConflict());
        api.get("/api/library/stock-checks", token)
                .andExpect(jsonPath("$[0].seen").value(2))
                .andExpect(jsonPath("$[0].missing").value(2));
        api.get("/api/audit-events", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItem("library_stock_check.closed")));
    }

    @Test
    void overdueRemindersReachParentsOnlyWhenSwitchedOnAndOncePerWeek() throws Exception {
        LibrarySchool s = new LibrarySchool(api);
        LocalDate today = LibrarySchool.today();
        String title = s.title("Late Book", 1);
        s.issue(s.accession(title, 0), "STUDENT", s.asha, today.minusDays(30));
        String waited = s.title("Waited Book", 1);
        s.issue(s.accession(waited, 0), "STUDENT", s.bala, null);
        int before = messages(s);
        reminderRunner.remindOverdue(today, Instant.now());
        assertThat(messages(s)).isEqualTo(before);

        api.put("/api/library/settings", s.librarian.accessToken(), """
                {"accessionPrefix":"LIB","holdDays":3,"remindersEnabled":true}""")
                .andExpect(jsonPath("$.remindersEnabled").value(true));
        assertThat(reminderRunner.remindOverdue(today, Instant.now())).isPositive();
        assertThat(messages(s)).isEqualTo(before + 1);
        api.get("/api/messages", s.admin.accessToken())
                .andExpect(jsonPath("$.items[0].templateKey").value("library.overdue"))
                .andExpect(jsonPath("$.items[0].relatedType").value("library_loan"))
                .andExpect(jsonPath("$.items[0].relatedLabel").value("Late Book"))
                .andExpect(jsonPath("$.items[0].recipientName").value("Lata Rao"));
        // Once a week per book.
        reminderRunner.remindOverdue(today, Instant.now());
        assertThat(messages(s)).isEqualTo(before + 1);
        // A reserved copy coming back tells the waiting student's parent.
        api.post("/api/library/reservations", s.librarian.accessToken(), """
                {"titleId":"%s","memberType":"STUDENT","memberId":"%s"}""".formatted(waited, s.asha))
                .andExpect(status().isCreated());
        api.post("/api/library/returns", s.librarian.accessToken(), """
                {"accessionNo":"%s"}""".formatted(s.accession(waited, 0))).andExpect(status().isOk());
        assertThat(messages(s)).isEqualTo(before + 2);
        api.get("/api/messages", s.admin.accessToken())
                .andExpect(jsonPath("$.items[*].templateKey", hasItem("library.hold_ready")));
        api.put("/api/library/settings", s.teacher.accessToken(), """
                {"accessionPrefix":"LIB","holdDays":3,"remindersEnabled":false}""").andExpect(status().isForbidden());
        api.put("/api/library/settings", s.librarian.accessToken(), """
                {"accessionPrefix":"lower","holdDays":30,"remindersEnabled":false}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.accessionPrefix").exists())
                .andExpect(jsonPath("$.errors.holdDays").exists());
    }

    private int messages(LibrarySchool s) throws Exception {
        return Integer.parseInt(TestApi.read(api.get("/api/messages", s.admin.accessToken()), "$.total"));
    }

    private String csv(String path, String token) throws Exception {
        byte[] body = mvc.perform(MockMvcRequestBuilders.get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andReturn().getResponse().getContentAsByteArray();
        return new String(body, StandardCharsets.UTF_8);
    }

    private void mvcUnauthenticated(String path) throws Exception {
        mvc.perform(MockMvcRequestBuilders.get(path)).andExpect(status().isUnauthorized());
    }
}
