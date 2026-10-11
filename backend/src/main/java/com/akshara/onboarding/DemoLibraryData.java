package com.akshara.onboarding;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.akshara.audit.AuditService.Actor;
import com.akshara.library.CatalogueService;
import com.akshara.library.CirculationService;
import com.akshara.library.CopySource;
import com.akshara.library.FineService;
import com.akshara.library.HoldService;
import com.akshara.library.LibraryForms.CollectForm;
import com.akshara.library.LibraryForms.HoldForm;
import com.akshara.library.LibraryForms.IssueForm;
import com.akshara.library.LibraryForms.ReturnForm;
import com.akshara.library.LibraryForms.TitleForm;
import com.akshara.library.LibraryPaymentMode;
import com.akshara.library.LibraryViews.FineView;
import com.akshara.library.LibraryViews.ReturnResult;
import com.akshara.library.LibraryViews.TitleDetail;
import com.akshara.library.LoanMath;
import com.akshara.library.MemberType;
import com.akshara.shared.TenantContext;
import com.akshara.staff.DepartmentService;
import com.akshara.staff.EmploymentType;
import com.akshara.staff.StaffForms.CreateStaff;
import com.akshara.staff.StaffService;
import com.akshara.students.StudentService;
import com.akshara.students.StudentService.StudentQuery;
import com.akshara.students.StudentService.StudentRow;
import com.akshara.students.StudentStatus;

/**
 * The demo school's library: a librarian (Kavitha Rao, {@code librarian@demo.akshara.test}), {@value #TITLE_COUNT}
 * titles with one to three copies each (ISBNs are made up, with valid check digits), and a few loans: Arjun Sharma
 * (Class 5 A) has a book that is overdue, two classmates have books due back soon, the demo teacher has one, Kunal
 * Mehta returned one late and paid the fine, and Sneha Iyengar waits for the book Arjun has. Dates follow today.
 */
@Component
class DemoLibraryData {

    private static final Logger log = LoggerFactory.getLogger(DemoLibraryData.class);

    static final String LIBRARIAN_EMAIL = "librarian" + DemoDataSeeder.DEMO_DOMAIN;
    static final String LIBRARIAN_NAME = "Kavitha Rao";
    static final int TITLE_COUNT = 60;
    /** The book Arjun has, overdue; Sneha Iyengar waits for it. */
    static final String OVERDUE_TITLE = "The Jungle Book";

    /** title | authors | publisher | year | language | subject | call number | copies | price in rupees. */
    static final List<String> TITLES = List.of(
            "The Jungle Book|Rudyard Kipling|Puffin India|2015|English|Fiction|823 KIP|1|250",
            "Panchatantra Stories|Vishnu Sharma (retold)|Rupa Publications|2018|English|Fiction|891.2 VIS|3|180",
            "Malgudi Days|R. K. Narayan|Indian Thought Publications|2016|English|Fiction|823 NAR|2|299",
            "Swami and Friends|R. K. Narayan|Indian Thought Publications|2014|English|Fiction|823 NAR|2|250",
            "The Room on the Roof|Ruskin Bond|Penguin India|2017|English|Fiction|823 BON|2|225",
            "The Blue Umbrella|Ruskin Bond|Rupa Publications|2012|English|Fiction|823 BON|3|150",
            "The Adventures of Tom Sawyer|Mark Twain|Fingerprint Publishing|2019|English|Fiction|813 TWA|1|199",
            "Treasure Island|Robert Louis Stevenson|Fingerprint Publishing|2018|English|Fiction|823 STE|1|175",
            "Alice's Adventures in Wonderland|Lewis Carroll|Puffin India|2016|English|Fiction|823 CAR|2|199",
            "Heidi|Johanna Spyri|Fingerprint Publishing|2020|English|Fiction|833 SPY|1|175",
            "Little Women|Louisa May Alcott|Fingerprint Publishing|2019|English|Fiction|813 ALC|1|250",
            "Gulliver's Travels|Jonathan Swift|Fingerprint Publishing|2017|English|Fiction|823 SWI|1|199",
            "Black Beauty|Anna Sewell|Puffin India|2015|English|Fiction|823 SEW|2|175",
            "The Secret Garden|Frances Hodgson Burnett|Fingerprint Publishing|2018|English|Fiction|823 BUR|2|199",
            "Tales from Tagore|Rabindranath Tagore|Rupa Publications|2011|English|Fiction|891.44 TAG|2|195",
            "Akbar and Birbal|Anant Pai (retold)|Amar Chitra Katha|2019|English|Fiction|398.2 PAI|3|150",
            "Tenali Raman Stories|Anant Pai (retold)|Amar Chitra Katha|2019|English|Fiction|398.2 PAI|2|150",
            "Godan|Premchand|Rajkamal Prakashan|2016|Hindi|Hindi Literature|891.43 PRE|2|299",
            "Idgah aur Anya Kahaniyan|Premchand|Rajkamal Prakashan|2018|Hindi|Hindi Literature|891.43 PRE|3|150",
            "Panch Parmeshwar|Premchand|Diamond Books|2017|Hindi|Hindi Literature|891.43 PRE|1|120",
            "Madhushala|Harivansh Rai Bachchan|Rajpal and Sons|2015|Hindi|Poetry|891.43 BAC|1|175",
            "Rashmirathi|Ramdhari Singh Dinkar|Lokbharti Prakashan|2016|Hindi|Poetry|891.43 DIN|1|150",
            "Chandrakanta|Devaki Nandan Khatri|Rajkamal Prakashan|2014|Hindi|Hindi Literature|891.43 KHA|1|250",
            "Gitanjali|Rabindranath Tagore|Rupa Publications|2012|English|Poetry|891.44 TAG|1|150",
            "A Child's Garden of Verses|Robert Louis Stevenson|Puffin India|2014|English|Poetry|821 STE|1|175",
            "Wings of Fire|A. P. J. Abdul Kalam, Arun Tiwari|Universities Press|2015|English|Biography|920 KAL|3|350",
            "The Story of My Experiments with Truth|M. K. Gandhi|Navajivan Publishing House|2018|English|Biography"
                    + "|920 GAN|2|150",
            "Ignited Minds|A. P. J. Abdul Kalam|Penguin India|2014|English|Biography|303.4 KAL|1|250",
            "Swami Vivekananda: A Biography|Swami Nikhilananda|Advaita Ashrama|2016|English|Biography|920 NIK|1|120",
            "Sachin Tendulkar: Playing It My Way|Sachin Tendulkar, Boria Majumdar|Hachette India|2014|English"
                    + "|Biography|920 TEN|1|599",
            "The Discovery of India|Jawaharlal Nehru|Penguin India|2008|English|History|954 NEH|1|499",
            "India After Gandhi|Ramachandra Guha|Picador India|2017|English|History|954.04 GUH|1|699",
            "Our Pasts I (Class 6 History)|NCERT|NCERT|2023|English|History|954 NCE|3|65",
            "The Story of the Indian Freedom Struggle|Bipan Chandra|Penguin India|2016|English|History"
                    + "|954.03 CHA|1|450",
            "A Brief History of Time|Stephen Hawking|Bantam|2011|English|Science|523.1 HAW|1|399",
            "The Magic School Bus Inside the Human Body|Joanna Cole|Scholastic India|2017|English|Science"
                    + "|612 COL|2|299",
            "Science Experiments for Young Minds|Dr. Rekha Menon|Navneet Publications|2021|English|Science"
                    + "|507.8 MEN|2|225",
            "Looking Around (Class 5 EVS)|NCERT|NCERT|2023|English|Science|372.3 NCE|3|60",
            "Science (Class 8)|NCERT|NCERT|2023|English|Science|500 NCE|3|75",
            "The Way Things Work Now|David Macaulay|DK|2016|English|Science|600 MAC|1|1299",
            "Birds of the Indian Subcontinent|Richard Grimmett, Carol Inskipp, Tim Inskipp|Oxford University Press"
                    + "|2016|English|Science|598 GRI|1|899",
            "Math-Magic (Class 5)|NCERT|NCERT|2023|English|Mathematics|372.7 NCE|3|55",
            "Mathematics (Class 8)|NCERT|NCERT|2023|English|Mathematics|510 NCE|3|70",
            "Vedic Mathematics|Bharati Krishna Tirthaji|Motilal Banarsidass|2015|English|Mathematics|510 TIR|1|295",
            "The Man Who Knew Infinity|Robert Kanigel|Penguin India|2016|English|Mathematics|510.92 KAN|1|499",
            "Mathematical Puzzles for Children|Shakuntala Devi|Orient Paperbacks|2017|English|Mathematics"
                    + "|793.74 DEV|2|175",
            "Oxford Advanced Learner's Dictionary|A. S. Hornby|Oxford University Press|2020|English|Reference"
                    + "|423 HOR|2|895",
            "Oxford Hindi-English Dictionary|R. S. McGregor|Oxford University Press|2018|Hindi|Reference"
                    + "|491.43 MCG|1|995",
            "Children's Illustrated Atlas|DK|DK|2019|English|Reference|912 DK|2|799",
            "Manorama Year Book 2026|Malayala Manorama|Malayala Manorama|2026|English|Reference|030 MAN|1|350",
            "Wren and Martin High School English Grammar|P. C. Wren, H. Martin|S. Chand|2017|English|Reference"
                    + "|425 WRE|3|425",
            "The Hungry Caterpillar|Eric Carle|Puffin India|2016|English|Picture Books|E CAR|2|299",
            "Grandma's Bag of Stories|Sudha Murty|Puffin India|2015|English|Fiction|823 MUR|3|199",
            "How I Taught My Grandmother to Read|Sudha Murty|Puffin India|2014|English|Fiction|823 MUR|2|199",
            "The Magic Drum and Other Favourite Stories|Sudha Murty|Puffin India|2016|English|Fiction|823 MUR|2|199",
            "Ruskin Bond's Book of Nature|Ruskin Bond|Rupa Publications|2015|English|Science|508 BON|1|295",
            "Harry Potter and the Philosopher's Stone|J. K. Rowling|Bloomsbury India|2014|English|Fiction"
                    + "|823 ROW|2|399",
            "Charlie and the Chocolate Factory|Roald Dahl|Puffin India|2016|English|Fiction|823 DAH|2|299",
            "Matilda|Roald Dahl|Puffin India|2016|English|Fiction|823 DAH|2|299",
            "Yoga for Children|Swati Chitra|Om Books|2018|English|Health|613.7 CHI|1|250");

    private final StaffService staff;
    private final DepartmentService departments;
    private final StudentService students;
    private final CatalogueService catalogue;
    private final CirculationService circulation;
    private final FineService fines;
    private final HoldService holds;
    private final TransactionTemplate tx;

    DemoLibraryData(StaffService staff, DepartmentService departments, StudentService students,
            CatalogueService catalogue, CirculationService circulation, FineService fines, HoldService holds,
            PlatformTransactionManager transactionManager) {
        this.staff = staff;
        this.departments = departments;
        this.students = students;
        this.catalogue = catalogue;
        this.circulation = circulation;
        this.fines = fines;
        this.holds = holds;
        this.tx = new TransactionTemplate(transactionManager);
    }

    /**
     * Returns the number of copies created. {@code userIds} maps the demo people's emails to their ids;
     * {@code passwordHash} is the demo password, already hashed, for the librarian.
     */
    int seed(UUID tenantId, Map<String, UUID> userIds, UUID adminId, String passwordHash) {
        Actor admin = new Actor(adminId, "Priya Nair");
        UUID librarianId = TenantContext.runAs(tenantId, () -> tx.execute(status -> librarian(admin,
                passwordHash)));
        Actor librarian = new Actor(librarianId, LIBRARIAN_NAME);
        LocalDate today = LoanMath.today();
        Map<String, TitleDetail> titles = TenantContext.runAs(tenantId, () -> tx.execute(status ->
                catalogue(librarian, today)));
        int copies = titles == null ? 0 : titles.values().stream().mapToInt(TitleDetail::copiesTotal).sum();
        if (titles == null) {
            return copies;
        }
        Map<String, UUID> pupils = TenantContext.runAs(tenantId, () -> tx.execute(status -> pupils()));
        // Each step in its own transaction, so one that cannot be done (a holiday, a student who left) skips only
        // itself.
        step(tenantId, "Arjun's overdue book", () -> issue(titles, OVERDUE_TITLE, MemberType.STUDENT,
                pupils.get("Arjun Sharma"), today.minusDays(30), librarian, today));
        step(tenantId, "Ananya's book", () -> issue(titles, "Grandma's Bag of Stories", MemberType.STUDENT,
                pupils.get("Ananya Patel"), today.minusDays(4), librarian, today));
        step(tenantId, "Rohan's book", () -> issue(titles, "Wings of Fire", MemberType.STUDENT,
                pupils.get("Rohan Das"), today.minusDays(1), librarian, today));
        step(tenantId, "the teacher's book", () -> issue(titles, "The Discovery of India", MemberType.STAFF,
                userIds.get("teacher" + DemoDataSeeder.DEMO_DOMAIN), today.minusDays(8), librarian, today));
        step(tenantId, "Kunal's late return", () -> lateReturn(titles, pupils.get("Kunal Mehta"), librarian,
                today));
        step(tenantId, "Sneha's hold", () -> holds.place(new HoldForm(titles.get(OVERDUE_TITLE).id(),
                MemberType.STUDENT, pupils.get("Sneha Iyengar")), librarian, today));
        return copies;
    }

    private UUID librarian(Actor admin, String passwordHash) {
        UUID administration = departments.list().stream().filter(d -> d.name().equals("Administration"))
                .map(d -> d.id()).findFirst().orElse(null);
        return staff.create(new CreateStaff(LIBRARIAN_NAME, LIBRARIAN_EMAIL, null, List.of("LIBRARIAN"), "AKS-203",
                "Librarian", administration, EmploymentType.PERMANENT, LocalDate.of(2020, 6, 1), "9848010203",
                "M.Lib.I.Sc.", null, null), passwordHash, admin).userId();
    }

    private Map<String, TitleDetail> catalogue(Actor librarian, LocalDate today) {
        Map<String, TitleDetail> byTitle = new HashMap<>();
        for (int i = 0; i < TITLES.size(); i++) {
            String[] p = TITLES.get(i).split("\\|");
            String shelf = "Rack " + (char) ('A' + Math.abs(p[5].hashCode()) % 6) + (1 + i % 4);
            TitleForm form = new TitleForm(isbn(i), p[0], null, p[1], p[2], Integer.valueOf(p[3]), null, p[4], p[5],
                    p[6], shelf, Integer.valueOf(p[7]), Long.parseLong(p[8]) * 100,
                    i % 7 == 3 ? CopySource.DONATION : CopySource.PURCHASE, LocalDate.of(2025, 6, 2));
            byTitle.put(p[0], catalogue.create(form, librarian, today));
        }
        return byTitle;
    }

    private Map<String, UUID> pupils() {
        Map<String, UUID> ids = new HashMap<>();
        for (String name : List.of("Arjun Sharma", "Ananya Patel", "Rohan Das", "Kunal Mehta", "Sneha Iyengar")) {
            List<StudentRow> found = students.list(new StudentQuery(null, null, null, StudentStatus.ACTIVE, name, 0,
                    5)).items();
            found.stream().filter(r -> r.fullName().equals(name)).findFirst().ifPresent(r -> ids.put(name, r.id()));
        }
        return ids;
    }

    private Object issue(Map<String, TitleDetail> titles, String title, MemberType type, UUID memberId,
            LocalDate issuedOn, Actor librarian, LocalDate today) {
        if (memberId == null) {
            return null;
        }
        return circulation.issue(new IssueForm(titles.get(title).copies().getFirst().accessionNo(), type, memberId,
                issuedOn), librarian, today);
    }

    /** Kunal borrowed a book 40 days ago, brought it back late 12 days ago and paid the fine at the desk. */
    private Object lateReturn(Map<String, TitleDetail> titles, UUID kunal, Actor librarian, LocalDate today) {
        if (kunal == null) {
            return null;
        }
        String accessionNo = titles.get("Matilda").copies().getFirst().accessionNo();
        circulation.issue(new IssueForm(accessionNo, MemberType.STUDENT, kunal, today.minusDays(40)), librarian,
                today.minusDays(12));
        ReturnResult back = circulation.giveBack(new ReturnForm(accessionNo, false, null, null), librarian,
                today.minusDays(12));
        List<UUID> owed = back.fines().stream().map(FineView::id).toList();
        if (!owed.isEmpty()) {
            fines.collect(new CollectForm(owed, LibraryPaymentMode.CASH, null), librarian,
                    Instant.now().minusSeconds(12L * 24 * 3600), today.minusDays(12));
        }
        return back;
    }

    private void step(UUID tenantId, String what, Supplier<Object> work) {
        try {
            TenantContext.runAs(tenantId, () -> tx.execute(status -> work.get()));
        } catch (RuntimeException e) {
            log.info("Skipped demo library step '{}' ({})", what, e.getMessage());
        }
    }

    /** A made-up ISBN-13 for the i-th demo title: 978-93-90000-xx-c, with its check digit. */
    static String isbn(int index) {
        String first12 = "97893" + String.format("%07d", 9_000_000 + index * 37);
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (first12.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return first12 + (10 - sum % 10) % 10;
    }
}
