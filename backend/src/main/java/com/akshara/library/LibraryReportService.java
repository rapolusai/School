package com.akshara.library;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.library.LibraryCsv.Rupees;
import com.akshara.library.LibraryViews.FineView;
import com.akshara.library.LibraryViews.IssuedReport;
import com.akshara.library.LibraryViews.LibrarySummary;
import com.akshara.library.LibraryViews.LoanView;
import com.akshara.library.LibraryViews.Member;
import com.akshara.library.LibraryViews.MemberHistory;
import com.akshara.library.LibraryViews.MissingCopy;
import com.akshara.library.LibraryViews.OverdueReport;
import com.akshara.library.LibraryViews.PopularReport;
import com.akshara.library.LibraryViews.PopularRow;
import com.akshara.library.LibraryViews.StockCheckDetail;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * The library's reports for the reports hub, each as JSON for the screen and as a CSV download (UTF-8 with a byte
 * order mark, which Excel opens): books overdue, books issued on a day, the most borrowed titles, a member's history,
 * and the copies a stock check did not see.
 */
@Service
@Transactional(readOnly = true)
public class LibraryReportService {

    /** The longest range the most-borrowed report accepts. */
    public static final int MAX_RANGE_DAYS = 400;
    static final int POPULAR_LIMIT = 50;

    private final LoanRepository loans;
    private final FineRepository fines;
    private final CopyRepository copies;
    private final ReservationRepository reservations;
    private final LibraryMembers members;
    private final LibraryMapper mapper;
    private final StockCheckService stockChecks;

    LibraryReportService(LoanRepository loans, FineRepository fines, CopyRepository copies,
            ReservationRepository reservations, LibraryMembers members, LibraryMapper mapper,
            StockCheckService stockChecks) {
        this.loans = loans;
        this.fines = fines;
        this.copies = copies;
        this.reservations = reservations;
        this.members = members;
        this.mapper = mapper;
        this.stockChecks = stockChecks;
    }

    /** Books past their due date today, oldest first; optionally students or staff only, or one class's students. */
    public OverdueReport overdue(MemberType memberType, UUID classId, LocalDate today) {
        TenantContext.require();
        Set<UUID> inClass = classId == null ? null : members.studentIdsInClass(classId);
        List<Loan> found = loans.overdue(today).stream()
                .filter(l -> memberType == null || l.getMemberType() == memberType)
                .filter(l -> inClass == null || (l.getStudentId() != null && inClass.contains(l.getStudentId())))
                .toList();
        List<LoanView> rows = mapper.loans(found, today);
        return new OverdueReport(today, rows, rows.stream().mapToLong(LoanView::fineSoFarPaise).sum());
    }

    public LibraryCsv overdueCsv(MemberType memberType, UUID classId, LocalDate today) {
        OverdueReport report = overdue(memberType, classId, today);
        LibraryCsv csv = new LibraryCsv("Accession no", "Title", "Member", "Member type", "Admission / employee no",
                "Class / designation", "Issued on", "Due on", "Days overdue", "Fine so far (Rs)");
        for (LoanView l : report.rows()) {
            csv.row(l.accessionNo(), l.title(), name(l.member()), l.member().type().name(), l.member().code(),
                    l.member().detail(), l.issuedOn(), l.dueOn(), l.daysOverdue(), new Rupees(l.fineSoFarPaise()));
        }
        return csv;
    }

    /** Books lent on a day (today by default), in the order they were lent. */
    public IssuedReport issued(LocalDate date, LocalDate today) {
        TenantContext.require();
        LocalDate day = date == null ? today : date;
        return new IssuedReport(day, mapper.loans(loans.issuedOn(day), today));
    }

    public LibraryCsv issuedCsv(LocalDate date, LocalDate today) {
        IssuedReport report = issued(date, today);
        LibraryCsv csv = new LibraryCsv("Accession no", "Title", "Member", "Member type", "Admission / employee no",
                "Class / designation", "Due on", "Status", "Issued by");
        for (LoanView l : report.rows()) {
            csv.row(l.accessionNo(), l.title(), name(l.member()), l.member().type().name(), l.member().code(),
                    l.member().detail(), l.dueOn(), l.status().name(), l.issuedByName());
        }
        return csv;
    }

    /** The titles lent most often between two days (both included; the last 30 days by default), top 50. */
    public PopularReport popular(LocalDate from, LocalDate to, LocalDate today) {
        TenantContext.require();
        LocalDate end = to == null ? today : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (end.isBefore(start)) {
            throw ApiException.badRequest("The end date must be on or after the start date.", "to");
        }
        if (ChronoUnit.DAYS.between(start, end) >= MAX_RANGE_DAYS) {
            throw ApiException.badRequest("Pick at most " + MAX_RANGE_DAYS + " days.", "from");
        }
        List<Object[]> counts = loans.mostBorrowed(start, end, Limit.of(POPULAR_LIMIT));
        Map<UUID, Title> titleById = mapper.titlesOf(counts.stream().map(r -> (UUID) r[0]).toList());
        List<PopularRow> rows = new ArrayList<>();
        for (Object[] r : counts) {
            UUID titleId = (UUID) r[0];
            Title t = titleById.get(titleId);
            rows.add(new PopularRow(titleId, t == null ? null : t.getTitle(), t == null ? null : t.getAuthors(),
                    ((Number) r[1]).longValue(), (int) copies.countByTitleId(titleId)));
        }
        return new PopularReport(start, end, rows);
    }

    public LibraryCsv popularCsv(LocalDate from, LocalDate to, LocalDate today) {
        PopularReport report = popular(from, to, today);
        LibraryCsv csv = new LibraryCsv("Rank", "Title", "Authors", "Loans", "Copies");
        int rank = 0;
        for (PopularRow r : report.rows()) {
            csv.row(++rank, r.title(), r.authors(), r.loans(), r.copies());
        }
        return csv;
    }

    /** Every loan of a member, newest first, with their fines. 404 when they are not a member of this school. */
    public MemberHistory memberHistory(MemberRef ref, LocalDate today) {
        TenantContext.require();
        Member member = members.require(ref);
        List<Loan> all = ref.type() == MemberType.STUDENT ? loans.historyOfStudent(ref.id())
                : loans.historyOfStaff(ref.id());
        List<Fine> owed = ref.type() == MemberType.STUDENT ? fines.ofStudent(ref.id()) : fines.ofStaff(ref.id());
        List<FineView> fineViews = mapper.fines(owed);
        long due = owed.stream().filter(f -> f.getStatus() == FineStatus.DUE).mapToLong(Fine::getAmountPaise).sum();
        long paid = owed.stream().filter(f -> f.getStatus() == FineStatus.PAID).mapToLong(Fine::getAmountPaise).sum();
        return new MemberHistory(member, mapper.loans(all, today), fineViews, due, paid);
    }

    public LibraryCsv memberHistoryCsv(MemberRef ref, LocalDate today) {
        MemberHistory history = memberHistory(ref, today);
        LibraryCsv csv = new LibraryCsv("Accession no", "Title", "Issued on", "Due on", "Returned on", "Status",
                "Renewals");
        for (LoanView l : history.loans()) {
            csv.row(l.accessionNo(), l.title(), l.issuedOn(), l.dueOn(), l.returnedOn(), l.status().name(),
                    l.renewals());
        }
        csv.row();
        csv.row("Fine", "Title", "Kind", "Amount (Rs)", "Status", "Receipt no");
        for (FineView f : history.fines()) {
            csv.row(f.createdAt() == null ? null : f.createdAt().atZone(LoanMath.INDIA).toLocalDate(), f.title(),
                    f.kind().name(), new Rupees(f.amountPaise()), f.status().name(), f.receiptNo());
        }
        return csv;
    }

    /** The copies a stock check did not see, as a CSV. */
    public LibraryCsv missingCsv(UUID stockCheckId) {
        StockCheckDetail detail = stockChecks.detail(stockCheckId);
        LibraryCsv csv = new LibraryCsv("Accession no", "Title", "Call number", "Shelf", "Status");
        for (MissingCopy m : detail.missing()) {
            csv.row(m.accessionNo(), m.title(), m.callNumber(), m.shelfLocation(), m.status().name());
        }
        return csv;
    }

    /** The librarian's dashboard figures. */
    public LibrarySummary summary(LocalDate today) {
        TenantContext.require();
        long ready = reservations.allActive().stream().filter(r -> r.getStatus() == ReservationStatus.READY)
                .count();
        return new LibrarySummary(loans.countOpen(), loans.countOverdue(today), loans.countDueOn(today), ready,
                fines.totalDue());
    }

    private static String name(Member m) {
        return m == null ? null : m.name();
    }
}
