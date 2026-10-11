package com.akshara.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;

/** The issue and return desk: loans, due dates, fines, renewals, holds, lost and damaged copies, members' accounts. */
class LibraryCirculationIT extends IntegrationTest {

    @Test
    void lendsTakesBackLateAndCollectsTheFine() throws Exception {
        LibrarySchool s = new LibrarySchool(api);
        LocalDate today = LibrarySchool.today();
        LocalDate issuedOn = today.minusDays(30);
        // A school holiday on the day the loan would fall due moves it to the next open day.
        LocalDate plain = issuedOn.plusDays(14);
        LocalDate holiday = plain.getDayOfWeek() == DayOfWeek.SUNDAY ? plain.plusDays(1) : plain;
        api.post("/api/calendar/entries", s.admin.accessToken(), """
                {"kind":"HOLIDAY","title":"Local holiday","startsOn":"%s","audience":"SCHOOL"}""".formatted(holiday))
                .andExpect(status().isCreated());
        Set<LocalDate> holidays = Set.of(holiday);
        LocalDate due = plain;
        while (due.getDayOfWeek() == DayOfWeek.SUNDAY || holidays.contains(due)) {
            due = due.plusDays(1);
        }
        String title = s.title("Malgudi Days", 1);
        String accession = s.accession(title, 0);
        String loan = TestApi.read(api.post("/api/library/loans", s.librarian.accessToken(), """
                {"accessionNo":"%s","memberType":"STUDENT","memberId":"%s","issuedOn":"%s"}"""
                .formatted(accession.toLowerCase(), s.asha, issuedOn))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.dueOn").value(due.toString()))
                .andExpect(jsonPath("$.member.name").value("Asha Rao"))
                .andExpect(jsonPath("$.member.detail").value("Class 5 A"))
                .andExpect(jsonPath("$.overdue").value(true))
                .andExpect(jsonPath("$.issuedByName").value("Libby Librarian")), "$.id");
        int lateDays = openDaysBetween(due, today, holidays);
        long expectedFine = Math.min(lateDays * 100L, 5000L);
        api.get("/api/library/loans?overdue=true", s.librarian.accessToken())
                .andExpect(jsonPath("$.items[*].id", contains(loan)))
                .andExpect(jsonPath("$.items[0].fineSoFarPaise").value(expectedFine));
        api.get("/api/library/members/STUDENT/" + s.asha, s.librarian.accessToken())
                .andExpect(jsonPath("$.loans.length()").value(1))
                .andExpect(jsonPath("$.rule.maxBooks").value(2))
                .andExpect(jsonPath("$.canBorrow").value(true));

        String fine = TestApi.read(api.post("/api/library/returns", s.librarian.accessToken(), """
                {"accessionNo":"%s"}""".formatted(accession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loan.status").value("RETURNED"))
                .andExpect(jsonPath("$.loan.returnedOn").value(today.toString()))
                .andExpect(jsonPath("$.fines[0].kind").value("OVERDUE"))
                .andExpect(jsonPath("$.fines[0].daysLate").value(lateDays))
                .andExpect(jsonPath("$.fines[0].amountPaise").value(expectedFine))
                .andExpect(jsonPath("$.heldFor").doesNotExist()), "$.fines[0].id");
        api.post("/api/library/returns", s.librarian.accessToken(), """
                {"accessionNo":"%s"}""".formatted(accession)).andExpect(status().isConflict());
        api.get("/api/library/members/STUDENT/" + s.asha, s.librarian.accessToken())
                .andExpect(jsonPath("$.finesDuePaise").value(expectedFine))
                .andExpect(jsonPath("$.history[0].status").value("RETURNED"));
        api.get("/api/library/fines?status=DUE", s.librarian.accessToken())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.totalDuePaise").value(expectedFine));

        // A cheque needs its number.
        api.post("/api/library/fines/collect", s.librarian.accessToken(), """
                {"fineIds":["%s"],"mode":"CHEQUE"}""".formatted(fine))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reference").exists());
        String receipt = TestApi.read(api.post("/api/library/fines/collect", s.librarian.accessToken(), """
                {"fineIds":["%s"],"mode":"UPI","reference":"UPI-123"}""".formatted(fine))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receiptNo", startsWith("LIBF/" + LoanMath.financialYear(today) + "/")))
                .andExpect(jsonPath("$.totalPaise").value(expectedFine))
                .andExpect(jsonPath("$.member.name").value("Asha Rao"))
                .andExpect(jsonPath("$.collectedByName").value("Libby Librarian")), "$.receiptNo");
        assertThat(receipt).endsWith("/000001");
        api.get("/api/library/fines/receipt?no=" + receipt, s.librarian.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("UPI"))
                .andExpect(jsonPath("$.schoolName", startsWith("Test School")));
        api.get("/api/library/fines/receipt?no=LIBF/2026-27/999999", s.librarian.accessToken())
                .andExpect(status().isNotFound());
        api.post("/api/library/fines/collect", s.librarian.accessToken(), """
                {"fineIds":["%s"],"mode":"CASH"}""".formatted(fine)).andExpect(status().isConflict());
        api.get("/api/audit-events", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItems("library_loan.issued", "library_loan.returned",
                        "library_fine.collected")));
    }

    @Test
    void appliesTheBorrowingRules() throws Exception {
        LibrarySchool s = new LibrarySchool(api);
        String a = s.title("Book A", 2);
        String b = s.title("Book B", 1);
        String c = s.title("Book C", 1);
        s.issue(s.accession(a, 0), "STUDENT", s.bala, null);
        // The same title twice.
        api.post("/api/library/loans", s.librarian.accessToken(), """
                {"accessionNo":"%s","memberType":"STUDENT","memberId":"%s"}""".formatted(s.accession(a, 1), s.bala))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.accessionNo", containsString("already has a copy")));
        // A copy already out.
        api.post("/api/library/loans", s.librarian.accessToken(), """
                {"accessionNo":"%s","memberType":"STUDENT","memberId":"%s"}""".formatted(s.accession(a, 0), s.asha))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.accessionNo", containsString("already lent")));
        s.issue(s.accession(b, 0), "STUDENT", s.bala, null);
        // Two books is the most a student may have.
        api.post("/api/library/loans", s.librarian.accessToken(), """
                {"accessionNo":"%s","memberType":"STUDENT","memberId":"%s"}""".formatted(s.accession(c, 0), s.bala))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.memberId", containsString("the most allowed")));
        api.get("/api/library/members/STUDENT/" + s.bala, s.librarian.accessToken())
                .andExpect(jsonPath("$.canBorrow").value(false))
                .andExpect(jsonPath("$.blockedReason", containsString("the most allowed")));
        // The rule can be changed.
        api.put("/api/library/rules/STUDENT", s.librarian.accessToken(), """
                {"maxBooks":3,"loanDays":7,"maxRenewals":0,"finePerDayPaise":200,"maxFinePaise":0}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.custom").value(true));
        api.put("/api/library/rules/STUDENT", s.librarian.accessToken(), """
                {"maxBooks":0,"loanDays":500,"maxRenewals":0,"finePerDayPaise":-1,"maxFinePaise":0}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.maxBooks").exists())
                .andExpect(jsonPath("$.errors.loanDays").exists())
                .andExpect(jsonPath("$.errors.finePerDayPaise").exists());
        api.get("/api/library/rules", s.teacher.accessToken())
                .andExpect(jsonPath("$[?(@.memberType == 'STUDENT')].maxBooks", contains(3)))
                .andExpect(jsonPath("$[?(@.memberType == 'STAFF')].custom", contains(false)));
        s.issue(s.accession(c, 0), "STUDENT", s.bala, null);
        // Unknown copies, future and too old dates, and members of no school.
        api.post("/api/library/loans", s.librarian.accessToken(), """
                {"accessionNo":"NOPE9","memberType":"STUDENT","memberId":"%s"}""".formatted(s.asha))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.accessionNo").exists());
        api.post("/api/library/loans", s.librarian.accessToken(), """
                {"accessionNo":"%s","memberType":"STUDENT","memberId":"%s","issuedOn":"%s"}"""
                .formatted(s.accession(a, 1), s.asha, LibrarySchool.today().plusDays(1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.issuedOn").exists());
        api.post("/api/library/loans", s.librarian.accessToken(), """
                {"accessionNo":"%s","memberType":"STUDENT","memberId":"%s","issuedOn":"%s"}"""
                .formatted(s.accession(a, 1), s.asha, LibrarySchool.today().minusDays(61)))
                .andExpect(status().isBadRequest());
        api.post("/api/library/loans", s.librarian.accessToken(), """
                {"accessionNo":"%s","memberType":"STUDENT","memberId":"%s"}""".formatted(s.accession(a, 1),
                s.teacherId)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.memberId").exists());
        api.post("/api/library/loans", s.librarian.accessToken(), """
                {"accessionNo":"%s","memberType":"STAFF","memberId":"%s"}""".formatted(s.accession(a, 1), s.asha))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.memberId").exists());
        api.post("/api/library/loans", s.librarian.accessToken(), """
                {"accessionNo":"bad code!","memberType":"STUDENT","memberId":"%s"}""".formatted(s.asha))
                .andExpect(status().isBadRequest());
        // A student who left cannot borrow.
        api.post("/api/students/" + s.chitra + "/leave", s.admin.accessToken(), """
                {"status":"TRANSFERRED","leftOn":"%s","reason":"Moved"}""".formatted(LibrarySchool.today()))
                .andExpect(status().isOk());
        api.post("/api/library/loans", s.librarian.accessToken(), """
                {"accessionNo":"%s","memberType":"STUDENT","memberId":"%s"}""".formatted(s.accession(a, 1), s.chitra))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.memberId", containsString("has left the school")));
        // Staff borrow under their own rule.
        s.issue(s.accession(a, 1), "STAFF", s.teacherId, null);
        api.get("/api/library/members/STAFF/" + s.teacherId, s.librarian.accessToken())
                .andExpect(jsonPath("$.member.name").value("Tara Teacher"))
                .andExpect(jsonPath("$.rule.maxBooks").value(5))
                .andExpect(jsonPath("$.loans.length()").value(1));
        api.get("/api/library/members?q=tara", s.librarian.accessToken())
                .andExpect(jsonPath("$[*].type", contains("STAFF")));
        api.get("/api/library/members?q=LIB-", s.librarian.accessToken())
                .andExpect(jsonPath("$[*].name", contains("Asha Rao", "Bala Iyer")));
        api.get("/api/library/loans?memberType=STUDENT&memberId=" + s.bala, s.librarian.accessToken())
                .andExpect(jsonPath("$.total").value(3));
    }

    @Test
    void renewsUntilTheLimitAndNotWhileOthersWait() throws Exception {
        LibrarySchool s = new LibrarySchool(api);
        String title = s.title("Wings of Fire", 1);
        LocalDate today = LibrarySchool.today();
        String loan = s.issue(s.accession(title, 0), "STUDENT", s.asha, today.minusDays(3));
        String renewed = TestApi.read(api.post("/api/library/loans/" + loan + "/renew", s.librarian.accessToken(),
                null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.renewals").value(1))
                .andExpect(jsonPath("$.renewalsLeft").value(0)), "$.dueOn");
        assertThat(LocalDate.parse(renewed)).isAfterOrEqualTo(today.plusDays(14));
        api.post("/api/library/loans/" + loan + "/renew", s.librarian.accessToken(), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("the most allowed")));
        // A member waiting for the title stops a renewal.
        api.put("/api/library/rules/STUDENT", s.librarian.accessToken(), """
                {"maxBooks":2,"loanDays":14,"maxRenewals":3,"finePerDayPaise":100,"maxFinePaise":5000}""")
                .andExpect(status().isOk());
        api.post("/api/library/reservations", s.librarian.accessToken(), """
                {"titleId":"%s","memberType":"STUDENT","memberId":"%s"}""".formatted(title, s.bala))
                .andExpect(status().isCreated());
        api.post("/api/library/loans/" + loan + "/renew", s.librarian.accessToken(), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("waiting")));
        // An overdue book is taken back rather than renewed.
        String other = s.title("Ignited Minds", 1);
        String late = s.issue(s.accession(other, 0), "STUDENT", s.chitra, today.minusDays(40));
        api.post("/api/library/loans/" + late + "/renew", s.librarian.accessToken(), null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail", containsString("overdue")));
        api.post("/api/library/loans/" + java.util.UUID.randomUUID() + "/renew", s.librarian.accessToken(), null)
                .andExpect(status().isNotFound());
    }

    @Test
    void keepsReturnedCopiesForTheFirstMemberWaiting() throws Exception {
        LibrarySchool s = new LibrarySchool(api);
        String title = s.title("Harry Potter", 1);
        String accession = s.accession(title, 0);
        // Holds are only for titles with no copy on the shelf.
        api.post("/api/library/reservations", s.librarian.accessToken(), """
                {"titleId":"%s","memberType":"STUDENT","memberId":"%s"}""".formatted(title, s.bala))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.titleId", containsString("on the shelf")));
        s.issue(accession, "STUDENT", s.asha, null);
        api.post("/api/library/reservations", s.librarian.accessToken(), """
                {"titleId":"%s","memberType":"STUDENT","memberId":"%s"}""".formatted(title, s.asha))
                .andExpect(status().isConflict());
        String bala = TestApi.read(api.post("/api/library/reservations", s.librarian.accessToken(), """
                {"titleId":"%s","memberType":"STUDENT","memberId":"%s"}""".formatted(title, s.bala))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.position").value(1)), "$.id");
        api.post("/api/library/reservations", s.librarian.accessToken(), """
                {"titleId":"%s","memberType":"STUDENT","memberId":"%s"}""".formatted(title, s.bala))
                .andExpect(status().isConflict());
        String tara = TestApi.read(api.post("/api/library/reservations", s.librarian.accessToken(), """
                {"titleId":"%s","memberType":"STAFF","memberId":"%s"}""".formatted(title, s.teacherId))
                .andExpect(jsonPath("$.position").value(2)), "$.id");
        // Back at the desk: the copy is kept for Bala, who is told (the next member in the queue).
        api.post("/api/library/returns", s.librarian.accessToken(), """
                {"accessionNo":"%s"}""".formatted(accession))
                .andExpect(jsonPath("$.heldFor.id").value(bala))
                .andExpect(jsonPath("$.heldFor.status").value("READY"))
                .andExpect(jsonPath("$.heldFor.copyAccessionNo").value(accession));
        api.get("/api/library/titles/" + title, s.librarian.accessToken())
                .andExpect(jsonPath("$.copies[0].status").value("RESERVED"))
                .andExpect(jsonPath("$.copies[0].heldFor.member.name").value("Bala Iyer"))
                .andExpect(jsonPath("$.queue[*].status", contains("READY", "WAITING")));
        api.get("/api/audit-events", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItem("library_hold.ready")));
        // Nobody else may take it meanwhile.
        api.post("/api/library/loans", s.librarian.accessToken(), """
                {"accessionNo":"%s","memberType":"STAFF","memberId":"%s"}""".formatted(accession, s.teacherId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.accessionNo", containsString("kept for Bala Iyer")));
        // Bala gives it up: it passes to Tara, who borrows it.
        api.post("/api/library/reservations/" + bala + "/cancel", s.librarian.accessToken(), null)
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        api.post("/api/library/reservations/" + bala + "/cancel", s.librarian.accessToken(), null)
                .andExpect(status().isConflict());
        api.get("/api/library/reservations", s.librarian.accessToken())
                .andExpect(jsonPath("$[*].id", contains(tara)))
                .andExpect(jsonPath("$[0].status").value("READY"));
        s.issue(accession, "STAFF", s.teacherId, null);
        api.get("/api/library/reservations", s.librarian.accessToken()).andExpect(jsonPath("$.length()").value(0));
        api.post("/api/library/reservations/" + java.util.UUID.randomUUID() + "/cancel", s.librarian.accessToken(),
                null).andExpect(status().isNotFound());
        api.post("/api/library/reservations", s.librarian.accessToken(), """
                {"titleId":"%s","memberType":"STUDENT","memberId":"%s"}""".formatted(java.util.UUID.randomUUID(),
                s.bala)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.titleId").exists());
    }

    @Test
    void chargesForLostAndDamagedCopiesAndWaivesWithAReason() throws Exception {
        LibrarySchool s = new LibrarySchool(api);
        String title = s.title("Atlas", 2); // priced at Rs 250
        String lostLoan = s.issue(s.accession(title, 0), "STUDENT", s.asha, null);
        String lostFine = TestApi.read(api.post("/api/library/loans/" + lostLoan + "/lost", s.librarian.accessToken(),
                "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.loan.status").value("LOST"))
                .andExpect(jsonPath("$.fines[0].kind").value("LOST"))
                .andExpect(jsonPath("$.fines[0].amountPaise").value(25000)), "$.fines[0].id");
        api.post("/api/library/loans/" + lostLoan + "/lost", s.librarian.accessToken(), "{}")
                .andExpect(status().isConflict());
        api.get("/api/library/titles/" + title, s.librarian.accessToken())
                .andExpect(jsonPath("$.copies[0].status").value("LOST"));
        s.issue(s.accession(title, 1), "STUDENT", s.bala, null);
        api.post("/api/library/returns", s.librarian.accessToken(), """
                {"accessionNo":"%s","damaged":true,"chargePaise":5000,"note":"Pages torn"}"""
                .formatted(s.accession(title, 1)))
                .andExpect(jsonPath("$.fines[0].kind").value("DAMAGED"))
                .andExpect(jsonPath("$.fines[0].amountPaise").value(5000));
        api.get("/api/library/titles/" + title, s.librarian.accessToken())
                .andExpect(jsonPath("$.copies[1].status").value("DAMAGED"))
                .andExpect(jsonPath("$.copies[1].statusNote").value("Pages torn"))
                .andExpect(jsonPath("$.available").value(0));
        api.post("/api/library/fines/" + lostFine + "/waive", s.librarian.accessToken(), """
                {"reason":""}""").andExpect(status().isBadRequest());
        api.post("/api/library/fines/" + lostFine + "/waive", s.librarian.accessToken(), """
                {"reason":"Found at home and returned"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAIVED"))
                .andExpect(jsonPath("$.waivedByName").value("Libby Librarian"));
        api.post("/api/library/fines/" + lostFine + "/waive", s.librarian.accessToken(), """
                {"reason":"Again"}""").andExpect(status().isConflict());
        api.get("/api/audit-events", s.admin.accessToken())
                .andExpect(jsonPath("$[?(@.action == 'library_fine.waived')].details.reason",
                        contains("Found at home and returned")));
        // Fines of two members are collected separately.
        String damagedFine = TestApi.read(api.get("/api/library/fines?status=DUE", s.librarian.accessToken()),
                "$.items[0].id");
        String otherTitle = s.title("Map Book", 1);
        String late = s.issue(s.accession(otherTitle, 0), "STUDENT", s.asha, LibrarySchool.today().minusDays(40));
        String lateFine = TestApi.read(api.post("/api/library/loans/" + late + "/lost", s.librarian.accessToken(), """
                {"chargePaise":10000}""").andExpect(jsonPath("$.fines.length()").value(2)), "$.fines[0].id");
        api.post("/api/library/fines/collect", s.librarian.accessToken(), """
                {"fineIds":["%s","%s"],"mode":"CASH"}""".formatted(damagedFine, lateFine))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.fineIds", containsString("one member")));
        api.post("/api/library/fines/" + java.util.UUID.randomUUID() + "/waive", s.librarian.accessToken(), """
                {"reason":"x"}""").andExpect(status().isNotFound());
    }

    /** Open days after {@code due} up to and including {@code to}: not Sundays, not the given holidays. */
    private static int openDaysBetween(LocalDate due, LocalDate to, Set<LocalDate> holidays) {
        int days = 0;
        for (LocalDate d = due.plusDays(1); !d.isAfter(to); d = d.plusDays(1)) {
            if (d.getDayOfWeek() != DayOfWeek.SUNDAY && !holidays.contains(d)) {
                days++;
            }
        }
        return days;
    }
}
