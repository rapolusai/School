package com.akshara.library;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.library.BookMetadataLookup.BookMetadata;
import com.akshara.library.LibraryForms.CopiesForm;
import com.akshara.library.LibraryForms.CopyForm;
import com.akshara.library.LibraryForms.TitleForm;
import com.akshara.library.LibraryViews.CatalogueStats;
import com.akshara.library.LibraryViews.CopyLoan;
import com.akshara.library.LibraryViews.CopyLookup;
import com.akshara.library.LibraryViews.CopyView;
import com.akshara.library.LibraryViews.HeldFor;
import com.akshara.library.LibraryViews.IsbnLookup;
import com.akshara.library.LibraryViews.LabelCopy;
import com.akshara.library.LibraryViews.LoanView;
import com.akshara.library.LibraryViews.Member;
import com.akshara.library.LibraryViews.QueueEntry;
import com.akshara.library.LibraryViews.Suggestion;
import com.akshara.library.LibraryViews.TitleDetail;
import com.akshara.library.LibraryViews.TitlePage;
import com.akshara.library.LibraryViews.TitleRef;
import com.akshara.library.LibraryViews.TitleRow;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * The catalogue: titles and their copies, search, the offline ISBN check and copies for labels. Every member of staff
 * may search it (library.read); adding and changing books is for library.manage. Who has a copy and who is waiting
 * for a title is shown only to library.manage.
 */
@Service
@Transactional
public class CatalogueService {

    public static final int MAX_PAGE_SIZE = 100;
    /** The most copies one label sheet request returns. */
    public static final int MAX_LABEL_COPIES = 500;

    private final TitleRepository titles;
    private final CopyRepository copies;
    private final LoanRepository loans;
    private final ReservationRepository reservations;
    private final LibrarySetupService setup;
    private final HoldService holds;
    private final LibraryMembers members;
    private final LibraryMapper mapper;
    private final BookMetadataLookup lookup;
    private final AuditService audit;
    private final EntityManager entityManager;

    CatalogueService(TitleRepository titles, CopyRepository copies, LoanRepository loans,
            ReservationRepository reservations, LibrarySetupService setup, HoldService holds, LibraryMembers members,
            LibraryMapper mapper, BookMetadataLookup lookup, AuditService audit, EntityManager entityManager) {
        this.titles = titles;
        this.copies = copies;
        this.loans = loans;
        this.reservations = reservations;
        this.setup = setup;
        this.holds = holds;
        this.members = members;
        this.mapper = mapper;
        this.lookup = lookup;
        this.audit = audit;
        this.entityManager = entityManager;
    }

    // ------------------------------------------------------------------ search and detail

    /**
     * Titles matching the text in their title, subtitle, authors, publisher or subject, by ISBN (10 or 13 digits, or
     * part of one) or by a copy's accession number; optionally one subject. By title.
     */
    @Transactional(readOnly = true)
    public TitlePage search(String text, String subject, int page, int size, LocalDate today) {
        TenantContext.require();
        int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        int pageNo = Math.max(page, 0);
        StringBuilder where = new StringBuilder(" from Title t where 1 = 1");
        Map<String, Object> params = new HashMap<>();
        String q = text == null ? "" : text.trim();
        if (!q.isEmpty()) {
            List<String> or = new ArrayList<>();
            or.add("lower(t.title) like :q escape '\\'");
            or.add("lower(coalesce(t.subtitle, '')) like :q escape '\\'");
            or.add("lower(coalesce(t.authors, '')) like :q escape '\\'");
            or.add("lower(coalesce(t.publisher, '')) like :q escape '\\'");
            or.add("lower(coalesce(t.subject, '')) like :q escape '\\'");
            params.put("q", "%" + escapeLike(q.toLowerCase(Locale.ROOT)) + "%");
            String digits = Isbn.clean(q);
            if (digits.matches("[0-9]{4,12}[0-9X]?")) {
                or.add("t.isbn13 like :digits or t.isbn10 like :digits");
                params.put("digits", "%" + digits + "%");
            }
            Optional<Isbn.Parsed> isbn = Isbn.parse(q);
            if (isbn.isPresent()) {
                or.add("t.isbn13 = :isbn13");
                params.put("isbn13", isbn.get().isbn13());
            }
            if (q.matches(LibraryForms.ACCESSION_PATTERN)) {
                or.add("exists (select c.id from Copy c where c.titleId = t.id and c.accessionNo = :accession)");
                params.put("accession", q.toUpperCase(Locale.ROOT));
            }
            where.append(" and (").append(String.join(" or ", or)).append(")");
        }
        if (subject != null && !subject.isBlank()) {
            where.append(" and lower(t.subject) = :subject");
            params.put("subject", subject.trim().toLowerCase(Locale.ROOT));
        }
        TypedQuery<Long> count = entityManager.createQuery("select count(t)" + where, Long.class);
        TypedQuery<Title> rows = entityManager.createQuery("select t" + where + " order by lower(t.title), t.id",
                Title.class);
        params.forEach((k, v) -> {
            count.setParameter(k, v);
            rows.setParameter(k, v);
        });
        long total = count.getSingleResult();
        List<Title> found = rows.setFirstResult(pageNo * pageSize).setMaxResults(pageSize).getResultList();
        Map<UUID, Map<CopyStatus, Integer>> counts = counts(found.stream().map(Title::getId).toList());
        List<TitleRow> items = found.stream().map(t -> {
            Map<CopyStatus, Integer> c = counts.getOrDefault(t.getId(), Map.of());
            int all = c.values().stream().mapToInt(Integer::intValue).sum() - c.getOrDefault(CopyStatus.WITHDRAWN, 0);
            return new TitleRow(t.getId(), t.getIsbn13(), t.getTitle(), t.getSubtitle(), t.getAuthors(),
                    t.getSubject(), t.getCallNumber(), t.getShelfLocation(), all,
                    c.getOrDefault(CopyStatus.AVAILABLE, 0), c.getOrDefault(CopyStatus.ISSUED, 0));
        }).toList();
        return new TitlePage(items, pageNo, pageSize, total, stats(today));
    }

    /** The subjects in use, for the catalogue filter. */
    @Transactional(readOnly = true)
    public List<String> subjects() {
        TenantContext.require();
        return entityManager.createQuery("select distinct t.subject from Title t where t.subject is not null "
                + "order by t.subject", String.class).getResultList();
    }

    @Transactional(readOnly = true)
    public CatalogueStats stats(LocalDate today) {
        TenantContext.require();
        long titleCount = titles.count();
        Map<CopyStatus, Long> byStatus = new EnumMap<>(CopyStatus.class);
        for (Object[] row : copies.countByStatus()) {
            byStatus.put((CopyStatus) row[0], ((Number) row[1]).longValue());
        }
        long copyCount = byStatus.entrySet().stream().filter(e -> e.getKey() != CopyStatus.WITHDRAWN)
                .mapToLong(Map.Entry::getValue).sum();
        return new CatalogueStats(titleCount, copyCount, byStatus.getOrDefault(CopyStatus.ISSUED, 0L),
                loans.countOverdue(today));
    }

    /** A title and its copies; with library.manage ({@code manage}), who has each copy and the hold queue. */
    @Transactional(readOnly = true)
    public TitleDetail detail(UUID id, boolean manage, LocalDate today) {
        TenantContext.require();
        Title t = titles.findById(id).orElseThrow(() -> ApiException.notFound("Title"));
        return detail(t, manage, today);
    }

    private TitleDetail detail(Title t, boolean manage, LocalDate today) {
        List<Copy> list = copies.ofTitle(t.getId());
        Map<UUID, Loan> openLoans = new HashMap<>();
        Map<UUID, Reservation> heldCopies = new HashMap<>();
        List<Reservation> queue = List.of();
        Map<MemberRef, Member> people = Map.of();
        if (manage && !list.isEmpty()) {
            loans.openOfCopies(list.stream().map(Copy::getId).toList()).forEach(l -> openLoans.put(l.getCopyId(), l));
        }
        if (manage) {
            queue = reservations.queueOf(t.getId());
            queue.stream().filter(r -> r.getCopyId() != null && r.getStatus() == ReservationStatus.READY)
                    .forEach(r -> heldCopies.put(r.getCopyId(), r));
            List<MemberRef> refs = new ArrayList<>();
            openLoans.values().forEach(l -> refs.add(l.member()));
            queue.forEach(r -> refs.add(r.member()));
            people = members.describe(refs.stream().distinct().toList());
        }
        Map<MemberRef, Member> who = people;
        List<CopyView> copyViews = list.stream().map(c -> copyView(c, openLoans.get(c.getId()),
                heldCopies.get(c.getId()), who, today)).toList();
        List<QueueEntry> queueViews = new ArrayList<>();
        for (int i = 0; i < queue.size(); i++) {
            Reservation r = queue.get(i);
            queueViews.add(new QueueEntry(r.getId(), i + 1, who.get(r.member()), r.getStatus(), r.getHoldUntil(),
                    r.getCreatedAt()));
        }
        int total = (int) list.stream().filter(c -> c.getStatus() != CopyStatus.WITHDRAWN).count();
        int available = (int) list.stream().filter(c -> c.getStatus() == CopyStatus.AVAILABLE).count();
        return new TitleDetail(t.getId(), t.getIsbn13(), t.getIsbn10(), t.getTitle(), t.getSubtitle(),
                t.getAuthors(), t.getPublisher(), t.getPublicationYear(), t.getEdition(), t.getLanguage(),
                t.getSubject(), t.getCallNumber(), t.getShelfLocation(), total, available,
                loans.countByTitleId(t.getId()), copyViews, queueViews);
    }

    private static CopyView copyView(Copy c, Loan loan, Reservation hold, Map<MemberRef, Member> who,
            LocalDate today) {
        CopyLoan copyLoan = loan == null ? null : new CopyLoan(loan.getId(), who.get(loan.member()),
                loan.getIssuedOn(), loan.getDueOn(), loan.getDueOn().isBefore(today));
        HeldFor heldFor = hold == null ? null : new HeldFor(hold.getId(), who.get(hold.member()),
                hold.getHoldUntil());
        return new CopyView(c.getId(), c.getTitleId(), c.getAccessionNo(), c.getStatus(), c.getStatusNote(),
                c.getPricePaise(), c.getSource(), c.getAcquiredOn(), copyLoan, heldFor);
    }

    // ------------------------------------------------------------------ adding and changing titles

    public TitleDetail create(TitleForm form, Actor actor, LocalDate today) {
        TenantContext.require();
        Title.Details details = clean(form);
        if (details.isbn13() != null) {
            titles.findByIsbn13(details.isbn13()).ifPresent(existing -> {
                throw isbnTaken(existing);
            });
        }
        Title title = titles.saveAndFlush(new Title(details));
        int count = form.copies() == null ? 1 : form.copies();
        List<Copy> added = addCopies(title, count, form.pricePaise(), form.source(), form.acquiredOn());
        Map<String, Object> audited = new LinkedHashMap<>();
        audited.put("title", title.getTitle());
        audited.put("isbn13", title.getIsbn13());
        audited.put("copies", added.size());
        audit.record(actor, "library_title.created", "library_title", title.getId(), audited);
        return detail(title, true, today);
    }

    public TitleDetail update(UUID id, TitleForm form, Actor actor, LocalDate today) {
        TenantContext.require();
        Title title = titles.findById(id).orElseThrow(() -> ApiException.notFound("Title"));
        Title.Details details = clean(form);
        if (details.isbn13() != null) {
            titles.findByIsbn13(details.isbn13()).filter(other -> !other.getId().equals(id)).ifPresent(other -> {
                throw isbnTaken(other);
            });
        }
        title.update(details);
        titles.saveAndFlush(title);
        Map<String, Object> audited = new LinkedHashMap<>();
        audited.put("title", title.getTitle());
        audited.put("isbn13", title.getIsbn13());
        audit.record(actor, "library_title.updated", "library_title", title.getId(), audited);
        return detail(title, true, today);
    }

    /** Removes a title added by mistake, with its copies. Once anything was lent or held, withdraw copies instead. */
    public void delete(UUID id, Actor actor) {
        TenantContext.require();
        Title title = titles.findById(id).orElseThrow(() -> ApiException.notFound("Title"));
        if (loans.existsByTitleId(id) || reservations.existsByTitleId(id)) {
            throw ApiException.conflict(title.getTitle() + " has been lent or reserved, so it stays in the "
                    + "catalogue. Mark its copies withdrawn instead.", "title");
        }
        List<Copy> list = copies.ofTitle(id);
        copies.deleteAll(list);
        copies.flush();
        titles.delete(title);
        titles.flush();
        audit.record(actor, "library_title.deleted", "library_title", id,
                Map.of("title", title.getTitle(), "copies", list.size()));
    }

    public TitleDetail addCopies(UUID titleId, CopiesForm form, Actor actor, LocalDate today) {
        TenantContext.require();
        Title title = titles.findById(titleId).orElseThrow(() -> ApiException.notFound("Title"));
        List<Copy> added = addCopies(title, form.count(), form.pricePaise(), form.source(), form.acquiredOn());
        for (Copy copy : added) {
            holds.offer(copy, today);
        }
        Map<String, Object> audited = new LinkedHashMap<>();
        audited.put("title", title.getTitle());
        audited.put("count", added.size());
        audited.put("first", added.getFirst().getAccessionNo());
        audited.put("last", added.getLast().getAccessionNo());
        audit.record(actor, "library_copy.added", "library_title", title.getId(), audited);
        return detail(title, true, today);
    }

    /** Copies with the next accession numbers. Used by the import too. */
    List<Copy> addCopies(Title title, int count, Long pricePaise, CopySource source, LocalDate acquiredOn) {
        if (count < 1) {
            return List.of();
        }
        List<Copy> added = new ArrayList<>();
        for (String accessionNo : setup.allocateAccessionNumbers(count)) {
            added.add(new Copy(title.getId(), accessionNo, pricePaise, source == null ? CopySource.PURCHASE : source,
                    acquiredOn));
        }
        copies.saveAllAndFlush(added);
        return added;
    }

    /**
     * Changes a copy's price, source and date, and where it is: back on the shelf (found, repaired), damaged, lost or
     * withdrawn. A copy on loan or kept for a hold changes only at the desk.
     */
    public CopyView updateCopy(UUID copyId, CopyForm form, Actor actor, LocalDate today) {
        TenantContext.require();
        Copy copy = copies.lockById(copyId).orElseThrow(() -> ApiException.notFound("Copy"));
        CopyStatus from = copy.getStatus();
        CopyStatus to = form.status();
        if (to != from) {
            if (from == CopyStatus.ISSUED) {
                throw ApiException.conflict("Copy " + copy.getAccessionNo() + " is on loan. Take it back at the desk "
                        + "or report it lost there.", "status");
            }
            if (from == CopyStatus.RESERVED) {
                throw ApiException.conflict("Copy " + copy.getAccessionNo() + " is kept for a hold. Cancel the hold "
                        + "first.", "status");
            }
            if (to == CopyStatus.ISSUED || to == CopyStatus.RESERVED) {
                throw ApiException.badRequest("Lend copies and keep them for holds at the desk.", "status");
            }
        }
        copy.updateDetails(form.pricePaise(), form.source(), form.acquiredOn());
        String note = blankToNull(form.statusNote());
        copy.setStatus(to, to == CopyStatus.AVAILABLE ? null : note);
        copies.saveAndFlush(copy);
        if (to != from && to == CopyStatus.AVAILABLE) {
            holds.offer(copy, today);
        }
        Map<String, Object> audited = new LinkedHashMap<>();
        audited.put("accessionNo", copy.getAccessionNo());
        audited.put("from", from.name());
        audited.put("to", to.name());
        audit.record(actor, "library_copy.updated", "library_copy", copy.getId(), audited);
        Map<MemberRef, Member> who = new HashMap<>();
        Reservation hold = reservations.readyWithCopy(copy.getId()).orElse(null);
        if (hold != null) {
            who.putAll(members.describe(List.of(hold.member())));
        }
        return copyView(copy, null, hold, who, today);
    }

    // ------------------------------------------------------------------ ISBN, labels, desk lookup

    /**
     * Checks an ISBN without going online: whether it is valid, its ISBN-13 and ISBN-10, whether the school already
     * has it, and details to start a new title from (the school's own title, or the outside lookup, which is offline
     * and finds nothing).
     */
    @Transactional(readOnly = true)
    public IsbnLookup isbn(String value) {
        TenantContext.require();
        String input = value == null ? "" : value.trim();
        Optional<Isbn.Parsed> parsed = Isbn.parse(input);
        if (parsed.isEmpty()) {
            return new IsbnLookup(input, false, Isbn.problem(input), null, null, null, null);
        }
        Isbn.Parsed isbn = parsed.get();
        Optional<Title> existing = titles.findByIsbn13(isbn.isbn13());
        if (existing.isPresent()) {
            Title t = existing.get();
            return new IsbnLookup(input, true, null, isbn.isbn13(), isbn.isbn10(),
                    new TitleRef(t.getId(), t.getTitle(), t.getAuthors(), (int) copies.countByTitleId(t.getId())),
                    new Suggestion("CATALOGUE", t.getTitle(), t.getSubtitle(), t.getAuthors(), t.getPublisher(),
                            t.getPublicationYear(), t.getEdition(), t.getLanguage(), t.getSubject(),
                            t.getCallNumber()));
        }
        Suggestion online = lookup.find(isbn.isbn13()).map(CatalogueService::suggestion).orElse(null);
        return new IsbnLookup(input, true, null, isbn.isbn13(), isbn.isbn10(), null, online);
    }

    private static Suggestion suggestion(BookMetadata m) {
        return new Suggestion("ONLINE", m.title(), m.subtitle(), m.authors(), m.publisher(), m.publicationYear(),
                null, m.language(), null, null);
    }

    /**
     * Copies for barcode and spine labels: of one title, in an accession number range (both included, compared as
     * text, so give numbers of the same length), or matching a title text. At most 500, by accession number.
     */
    @Transactional(readOnly = true)
    public List<LabelCopy> labelCopies(UUID titleId, String fromAccession, String toAccession, String text) {
        TenantContext.require();
        StringBuilder jpql = new StringBuilder("select c, t from Copy c join Title t on t.id = c.titleId "
                + "where c.status <> com.akshara.library.CopyStatus.WITHDRAWN");
        Map<String, Object> params = new HashMap<>();
        if (titleId != null) {
            jpql.append(" and c.titleId = :title");
            params.put("title", titleId);
        }
        if (fromAccession != null && !fromAccession.isBlank()) {
            jpql.append(" and c.accessionNo >= :from");
            params.put("from", fromAccession.trim().toUpperCase(Locale.ROOT));
        }
        if (toAccession != null && !toAccession.isBlank()) {
            jpql.append(" and c.accessionNo <= :to");
            params.put("to", toAccession.trim().toUpperCase(Locale.ROOT));
        }
        if (text != null && !text.isBlank()) {
            jpql.append(" and (lower(t.title) like :q escape '\\' or lower(coalesce(t.authors, '')) like :q escape '\\'"
                    + " or c.accessionNo = :exact)");
            params.put("q", "%" + escapeLike(text.trim().toLowerCase(Locale.ROOT)) + "%");
            params.put("exact", text.trim().toUpperCase(Locale.ROOT));
        }
        jpql.append(" order by c.accessionNo");
        TypedQuery<Object[]> query = entityManager.createQuery(jpql.toString(), Object[].class);
        params.forEach(query::setParameter);
        return query.setMaxResults(MAX_LABEL_COPIES).getResultList().stream().map(r -> {
            Copy c = (Copy) r[0];
            Title t = (Title) r[1];
            return new LabelCopy(c.getId(), c.getAccessionNo(), t.getId(), t.getTitle(), t.getAuthors(),
                    t.getCallNumber(), c.getStatus());
        }).toList();
    }

    /** What the desk shows for a scanned accession number: the copy, its title and its loan. 404 when unknown. */
    @Transactional(readOnly = true)
    public CopyLookup lookup(String accessionNo, LocalDate today) {
        TenantContext.require();
        String acc = accessionNo == null ? "" : accessionNo.trim().toUpperCase(Locale.ROOT);
        Copy copy = copies.findByAccessionNo(acc).orElseThrow(() -> ApiException.notFound("Copy " + acc));
        Title title = titles.findById(copy.getTitleId()).orElseThrow();
        Loan loan = loans.openOfCopy(copy.getId()).orElse(null);
        Reservation hold = reservations.readyWithCopy(copy.getId()).orElse(null);
        List<MemberRef> refs = new ArrayList<>();
        if (loan != null) {
            refs.add(loan.member());
        }
        if (hold != null) {
            refs.add(hold.member());
        }
        Map<MemberRef, Member> who = members.describe(refs.stream().distinct().toList());
        LoanView loanView = loan == null ? null : mapper.loan(loan, today);
        return new CopyLookup(copyView(copy, loan, hold, who, today),
                new TitleRef(title.getId(), title.getTitle(), title.getAuthors(),
                        (int) copies.countByTitleId(title.getId())), loanView);
    }

    // ------------------------------------------------------------------ helpers

    /** The form's fields trimmed (empty ones as null), with the ISBN checked and kept as ISBN-13 and ISBN-10. */
    static Title.Details clean(TitleForm form) {
        String isbn13 = null;
        String isbn10 = null;
        String rawIsbn = blankToNull(form.isbn());
        if (rawIsbn != null) {
            Isbn.Parsed parsed = Isbn.parse(rawIsbn)
                    .orElseThrow(() -> ApiException.badRequest(Isbn.problem(rawIsbn), "isbn"));
            isbn13 = parsed.isbn13();
            isbn10 = parsed.isbn10();
        }
        return new Title.Details(isbn13, isbn10, form.title().trim(), blankToNull(form.subtitle()),
                blankToNull(form.authors()), blankToNull(form.publisher()), form.publicationYear(),
                blankToNull(form.edition()), blankToNull(form.language()), blankToNull(form.subject()),
                blankToNull(form.callNumber()), blankToNull(form.shelfLocation()));
    }

    private static ApiException isbnTaken(Title existing) {
        return ApiException.conflict("This ISBN is already in the catalogue as \"" + existing.getTitle()
                + "\". Add copies to it instead.", "isbn");
    }

    /** Copies per title and status. */
    private Map<UUID, Map<CopyStatus, Integer>> counts(List<UUID> titleIds) {
        Map<UUID, Map<CopyStatus, Integer>> counts = new HashMap<>();
        if (titleIds.isEmpty()) {
            return counts;
        }
        for (Object[] row : copies.countsByTitle(titleIds)) {
            counts.computeIfAbsent((UUID) row[0], k -> new EnumMap<>(CopyStatus.class))
                    .put((CopyStatus) row[1], ((Number) row[2]).intValue());
        }
        return counts;
    }

    static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
