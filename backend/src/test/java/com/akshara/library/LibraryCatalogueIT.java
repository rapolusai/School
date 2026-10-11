package com.akshara.library;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import com.akshara.support.IntegrationTest;
import com.akshara.support.TestApi;

/** The catalogue: titles, copies with accession numbers, the offline ISBN check, search, labels and the import. */
class LibraryCatalogueIT extends IntegrationTest {

    @Test
    void addsTitlesWithCopiesNumberedInSequence() throws Exception {
        LibrarySchool s = new LibrarySchool(api);
        // An ISBN-10 is kept as its ISBN-13 too.
        String wings = TestApi.read(api.post("/api/library/titles", s.librarian.accessToken(), """
                {"isbn":"81-7371-146-1","title":"  Wings of Fire ","subtitle":"An Autobiography",
                 "authors":"A. P. J. Abdul Kalam","publisher":"Universities Press","publicationYear":1999,
                 "language":"English","subject":"Biography","callNumber":"920 KAL","shelfLocation":"Rack B2",
                 "copies":2,"pricePaise":35000,"source":"DONATION","acquiredOn":"2026-06-02"}""")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Wings of Fire"))
                .andExpect(jsonPath("$.isbn13").value("9788173711466"))
                .andExpect(jsonPath("$.isbn10").value("8173711461"))
                .andExpect(jsonPath("$.copiesTotal").value(2))
                .andExpect(jsonPath("$.available").value(2))
                .andExpect(jsonPath("$.copies[*].accessionNo", contains("LIB00001", "LIB00002")))
                .andExpect(jsonPath("$.copies[0].source").value("DONATION"))
                .andExpect(jsonPath("$.copies[0].pricePaise").value(35000)), "$.id");
        // A school prefix of its own; the running number carries on.
        api.put("/api/library/settings", s.librarian.accessToken(), """
                {"accessionPrefix":"KV","holdDays":2,"remindersEnabled":false}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextAccessionNo").value("KV00003"));
        api.post("/api/library/titles/" + wings + "/copies", s.librarian.accessToken(), """
                {"count":1,"pricePaise":30000}""")
                .andExpect(status().isCreated())
                // Copies are listed by accession number.
                .andExpect(jsonPath("$.copies[*].accessionNo", contains("KV00003", "LIB00001", "LIB00002")))
                .andExpect(jsonPath("$.copies[0].source").value("PURCHASE"));
        // A title without copies (a catalogue entry for later) and the default of one copy.
        api.post("/api/library/titles", s.librarian.accessToken(), """
                {"title":"Gitanjali","copies":0}""").andExpect(jsonPath("$.copiesTotal").value(0));
        api.post("/api/library/titles", s.librarian.accessToken(), """
                {"title":"Godan","authors":"Premchand"}""")
                .andExpect(jsonPath("$.copies[*].accessionNo", contains("KV00004")));
        api.get("/api/library/settings", s.librarian.accessToken())
                .andExpect(jsonPath("$.accessionPrefix").value("KV"))
                .andExpect(jsonPath("$.holdDays").value(2))
                .andExpect(jsonPath("$.nextAccessionNo").value("KV00005"));
        api.get("/api/library/titles", s.teacher.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.stats.titles").value(3))
                .andExpect(jsonPath("$.stats.copies").value(4))
                .andExpect(jsonPath("$.items[*].title", contains("Gitanjali", "Godan", "Wings of Fire")));
        api.get("/api/library/subjects", s.teacher.accessToken()).andExpect(jsonPath("$", contains("Biography")));
    }

    @Test
    void checksIsbnsOfflineAndRefusesDuplicates() throws Exception {
        LibrarySchool s = new LibrarySchool(api);
        api.post("/api/library/titles", s.librarian.accessToken(), """
                {"isbn":"978-81-7371-146-7","title":"Wings of Fire"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.isbn", containsString("does not add up")));
        api.post("/api/library/titles", s.librarian.accessToken(), """
                {"isbn":"12345","title":"Wings of Fire"}""")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.isbn", containsString("10 or 13 digits")));
        api.post("/api/library/titles", s.librarian.accessToken(), """
                {"isbn":"9788173711466","title":""}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.title").exists());
        api.post("/api/library/titles", s.librarian.accessToken(), """
                {"title":"Too many","copies":101}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.copies").exists());
        String wings = TestApi.read(api.post("/api/library/titles", s.librarian.accessToken(), """
                {"isbn":"9788173711466","title":"Wings of Fire","authors":"A. P. J. Abdul Kalam",
                 "subject":"Biography","callNumber":"920 KAL"}""").andExpect(status().isCreated()), "$.id");
        // The same book typed as its ISBN-10 is a duplicate.
        api.post("/api/library/titles", s.librarian.accessToken(), """
                {"isbn":"8173711461","title":"Wings of Fire (again)"}""")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.isbn", containsString("Add copies to it instead")));
        // The lookup never goes online: it checks the number and finds it in the school's own catalogue.
        api.get("/api/library/isbn?value=81-7371-146-1", s.librarian.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.isbn13").value("9788173711466"))
                .andExpect(jsonPath("$.existing.id").value(wings))
                .andExpect(jsonPath("$.suggestion.source").value("CATALOGUE"))
                .andExpect(jsonPath("$.suggestion.callNumber").value("920 KAL"));
        api.get("/api/library/isbn?value=9780306406157", s.librarian.accessToken())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.isbn10").value("0306406152"))
                .andExpect(jsonPath("$.existing").value(nullValue()))
                .andExpect(jsonPath("$.suggestion").value(nullValue()));
        api.get("/api/library/isbn?value=9780306406158", s.librarian.accessToken())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.problem", containsString("does not add up")));
        // Changing another title to the same ISBN is a conflict too.
        String other = s.title("Ignited Minds", 1);
        api.put("/api/library/titles/" + other, s.librarian.accessToken(), """
                {"isbn":"8173711461","title":"Ignited Minds"}""").andExpect(status().isConflict());
        api.put("/api/library/titles/" + wings, s.librarian.accessToken(), """
                {"isbn":"8173711461","title":"Wings of Fire","edition":"2nd"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.edition").value("2nd"))
                .andExpect(jsonPath("$.authors").value(nullValue()));
    }

    @Test
    void searchesByTitleAuthorIsbnAccessionNumberAndSubject() throws Exception {
        LibrarySchool s = new LibrarySchool(api);
        api.post("/api/library/titles", s.librarian.accessToken(), """
                {"isbn":"9788173711466","title":"Wings of Fire","authors":"A. P. J. Abdul Kalam",
                 "subject":"Biography","copies":1}""").andExpect(status().isCreated());
        api.post("/api/library/titles", s.librarian.accessToken(), """
                {"title":"Malgudi Days","authors":"R. K. Narayan","subject":"Fiction","copies":2}""")
                .andExpect(status().isCreated());
        api.post("/api/library/titles", s.librarian.accessToken(), """
                {"title":"Swami and Friends","authors":"R. K. Narayan","subject":"Fiction","copies":1}""")
                .andExpect(status().isCreated());
        api.get("/api/library/titles?q=malgudi", s.teacher.accessToken())
                .andExpect(jsonPath("$.items[*].title", contains("Malgudi Days")))
                .andExpect(jsonPath("$.items[0].copies").value(2))
                .andExpect(jsonPath("$.items[0].available").value(2));
        api.get("/api/library/titles?q=narayan", s.teacher.accessToken())
                .andExpect(jsonPath("$.total").value(2));
        api.get("/api/library/titles?q=978-81-7371-146-6", s.teacher.accessToken())
                .andExpect(jsonPath("$.items[*].title", contains("Wings of Fire")));
        api.get("/api/library/titles?q=8173711461", s.teacher.accessToken())
                .andExpect(jsonPath("$.items[*].title", contains("Wings of Fire")));
        api.get("/api/library/titles?q=lib00003", s.teacher.accessToken())
                .andExpect(jsonPath("$.items[*].title", contains("Malgudi Days")));
        api.get("/api/library/titles?subject=Fiction", s.teacher.accessToken())
                .andExpect(jsonPath("$.items[*].title", contains("Malgudi Days", "Swami and Friends")));
        api.get("/api/library/titles?q=50%25_", s.teacher.accessToken()).andExpect(jsonPath("$.total").value(0));
        api.get("/api/library/titles?size=101", s.teacher.accessToken()).andExpect(status().isBadRequest());
    }

    @Test
    void changesCopiesAndDeletesTitlesNeverLent() throws Exception {
        LibrarySchool s = new LibrarySchool(api);
        String title = s.title("Treasure Island", 2);
        String copy = TestApi.read(api.get("/api/library/titles/" + title, s.librarian.accessToken()),
                "$.copies[1].id");
        api.put("/api/library/copies/" + copy, s.librarian.accessToken(), """
                {"pricePaise":19900,"source":"DONATION","status":"DAMAGED","statusNote":"Cover torn"}""")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DAMAGED"))
                .andExpect(jsonPath("$.statusNote").value("Cover torn"));
        api.put("/api/library/copies/" + copy, s.librarian.accessToken(), """
                {"source":"PURCHASE","status":"ISSUED"}""").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.status").exists());
        api.put("/api/library/copies/" + copy, s.librarian.accessToken(), """
                {"source":"PURCHASE","status":"WITHDRAWN","statusNote":"Too old"}""")
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));
        api.get("/api/library/titles/" + title, s.teacher.accessToken())
                .andExpect(jsonPath("$.available").value(1))
                .andExpect(jsonPath("$.queue.length()").value(0));
        // A copy on loan changes only at the desk.
        String first = s.accession(title, 0);
        s.issue(first, "STUDENT", s.asha, null);
        String firstId = TestApi.read(api.get("/api/library/titles/" + title, s.librarian.accessToken()),
                "$.copies[0].id");
        api.put("/api/library/copies/" + firstId, s.librarian.accessToken(), """
                {"source":"PURCHASE","status":"LOST"}""").andExpect(status().isConflict());
        api.delete("/api/library/titles/" + title, s.librarian.accessToken()).andExpect(status().isConflict());
        String mistake = s.title("Typo", 3);
        api.delete("/api/library/titles/" + mistake, s.librarian.accessToken()).andExpect(status().isNoContent());
        api.get("/api/library/titles/" + mistake, s.librarian.accessToken()).andExpect(status().isNotFound());
        api.get("/api/library/copies/lookup?accessionNo=" + first.toLowerCase(), s.librarian.accessToken())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.copy.status").value("ISSUED"))
                .andExpect(jsonPath("$.title.title").value("Treasure Island"))
                .andExpect(jsonPath("$.loan.member.name").value("Asha Rao"));
        api.get("/api/library/copies/lookup?accessionNo=NOPE1", s.librarian.accessToken())
                .andExpect(status().isNotFound());
    }

    @Test
    void listsCopiesForLabelSheets() throws Exception {
        LibrarySchool s = new LibrarySchool(api);
        String malgudi = s.title("Malgudi Days", 3);
        s.title("Godan", 2);
        api.get("/api/library/labels?titleId=" + malgudi, s.librarian.accessToken())
                .andExpect(jsonPath("$[*].accessionNo", contains("LIB00001", "LIB00002", "LIB00003")))
                .andExpect(jsonPath("$[0].title").value("Malgudi Days"));
        api.get("/api/library/labels?from=LIB00003&to=LIB00004", s.librarian.accessToken())
                .andExpect(jsonPath("$[*].accessionNo", contains("LIB00003", "LIB00004")));
        api.get("/api/library/labels?q=godan", s.librarian.accessToken())
                .andExpect(jsonPath("$.length()").value(2));
        api.get("/api/library/labels", s.teacher.accessToken()).andExpect(status().isForbidden());
    }

    @Test
    void importsACsvWithAPreviewAndRowErrors() throws Exception {
        LibrarySchool s = new LibrarySchool(api);
        s.title("Existing Book", 1); // LIB00001
        String bad = """
                title,isbn,authors,copies,price,source,accession_no,acquired_on,shelf
                Wings of Fire,978-81-7371-146-7,Kalam,1,350,,,,
                ,,Nobody,1,,,,,
                Malgudi Days,,R. K. Narayan,two,abc,gift shop,,2099-01-01,
                Old Register Book,,,3,,,OLD/1,,
                Dup,,,1,,,LIB00001,,
                """;
        api.post("/api/library/titles/import", s.librarian.accessToken(), json(bad))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.totalRows").value(5))
                .andExpect(jsonPath("$.invalidRows").value(5))
                .andExpect(jsonPath("$.ignoredColumns", contains("shelf")))
                .andExpect(jsonPath("$.errors[?(@.row == 2)].column", contains("isbn")))
                .andExpect(jsonPath("$.errors[?(@.row == 3)].column", contains("title")))
                .andExpect(jsonPath("$.errors[?(@.row == 4)].column", contains("copies", "price", "source",
                        "acquired_on")))
                .andExpect(jsonPath("$.errors[?(@.row == 5)].column", contains("copies")))
                .andExpect(jsonPath("$.errors[?(@.row == 6)].message", contains(
                        "Accession number LIB00001 is already used.")));
        // A real run with bad rows writes nothing.
        api.post("/api/library/titles/import?dryRun=false", s.librarian.accessToken(), json(bad))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.result.committed").value(false));
        api.get("/api/library/titles", s.librarian.accessToken()).andExpect(jsonPath("$.total").value(1));

        String good = """
                Title,ISBN,Authors,Subject,Call number,Copies,Price,Source,Accession no,Acquired on
                Wings of Fire,81-7371-146-1,A. P. J. Abdul Kalam,Biography,920 KAL,2,"1,250.50",Purchase,,2026-06-02
                Wings of Fire,9788173711466,A. P. J. Abdul Kalam,Biography,920 KAL,,,,OLD/7,
                "Malgudi Days, Vol. 1",,R. K. Narayan,Fiction,823 NAR,1,₹ 299,donated,,02-06-2026
                Existing Book,,Test Author,,,1,,,,
                """;
        api.post("/api/library/titles/import", s.librarian.accessToken(), json(good))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invalidRows").value(0))
                .andExpect(jsonPath("$.titlesCreated").value(2))
                .andExpect(jsonPath("$.copiesCreated").value(5))
                .andExpect(jsonPath("$.preview[*].existingTitle", contains(false, true, false, true)))
                .andExpect(jsonPath("$.preview[1].accessionNo").value("OLD/7"));
        api.post("/api/library/titles/import?dryRun=false", s.librarian.accessToken(), json(good))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.committed").value(true))
                .andExpect(jsonPath("$.titlesCreated").value(2))
                .andExpect(jsonPath("$.copiesCreated").value(5));
        api.get("/api/library/titles?q=wings", s.librarian.accessToken())
                .andExpect(jsonPath("$.items[0].copies").value(3));
        String wings = TestApi.read(api.get("/api/library/titles?q=wings", s.librarian.accessToken()),
                "$.items[0].id");
        api.get("/api/library/titles/" + wings, s.librarian.accessToken())
                .andExpect(jsonPath("$.copies[*].accessionNo", contains("LIB00002", "LIB00003", "OLD/7")))
                .andExpect(jsonPath("$.copies[0].pricePaise").value(125050))
                .andExpect(jsonPath("$.isbn10").value("8173711461"));
        api.get("/api/library/titles?q=malgudi", s.librarian.accessToken())
                .andExpect(jsonPath("$.items[0].title").value("Malgudi Days, Vol. 1"));
        api.get("/api/library/titles?q=existing", s.librarian.accessToken())
                .andExpect(jsonPath("$.items[0].copies").value(2));
        api.get("/api/audit-events", s.admin.accessToken())
                .andExpect(jsonPath("$[*].action", hasItem("library_titles.imported")));
        api.post("/api/library/titles/import", s.librarian.accessToken(), json("isbn,authors\n1,2\n"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.csv", containsString("missing title")));
        api.post("/api/library/titles/import", s.teacher.accessToken(), json(good)).andExpect(status().isForbidden());
    }

    private static String json(String csv) {
        return "{\"csv\":\"" + csv.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"}";
    }
}
