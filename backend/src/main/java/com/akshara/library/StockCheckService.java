package com.akshara.library;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.library.LibraryForms.ScanForm;
import com.akshara.library.LibraryForms.StockCheckForm;
import com.akshara.library.LibraryViews.MissingCopy;
import com.akshara.library.LibraryViews.ScanResult;
import com.akshara.library.LibraryViews.SeenCopy;
import com.akshara.library.LibraryViews.StockCheckDetail;
import com.akshara.library.LibraryViews.StockCheckRow;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * Stock verification: start a check, scan the copies on the shelves (a barcode scanner types the accession number),
 * then list the copies that should be there but were not seen. Copies expected on the shelves are those available,
 * kept for a hold or set aside as damaged; copies on loan, lost or withdrawn are not expected.
 */
@Service
@Transactional
public class StockCheckService {

    static final List<CopyStatus> EXPECTED = List.of(CopyStatus.AVAILABLE, CopyStatus.RESERVED, CopyStatus.DAMAGED);
    private static final int LATEST = 20;

    private final StockCheckRepository checks;
    private final StockCheckItemRepository items;
    private final CopyRepository copies;
    private final TitleRepository titles;
    private final LibraryMapper mapper;
    private final AuditService audit;

    StockCheckService(StockCheckRepository checks, StockCheckItemRepository items, CopyRepository copies,
            TitleRepository titles, LibraryMapper mapper, AuditService audit) {
        this.checks = checks;
        this.items = items;
        this.copies = copies;
        this.titles = titles;
        this.mapper = mapper;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<StockCheckRow> list() {
        TenantContext.require();
        Map<UUID, Long> seen = new HashMap<>();
        for (Object[] row : items.countPerCheck()) {
            seen.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return checks.newestFirst().stream().map(c -> row(c, seen.getOrDefault(c.getId(), 0L))).toList();
    }

    public StockCheckRow start(StockCheckForm form, Actor actor, Instant at) {
        TenantContext.require();
        if (checks.findOpen().isPresent()) {
            throw ApiException.conflict("A stock check is already open. Close it before starting another.", "note");
        }
        StockCheck check = checks.saveAndFlush(new StockCheck(CatalogueService.blankToNull(form.note()), actor, at));
        audit.record(actor, "library_stock_check.started", "library_stock_check", check.getId(),
                Map.of("expected", expected().size()));
        return row(check, 0);
    }

    /** Marks a scanned copy as seen. Scanning it again is harmless. */
    public ScanResult scan(UUID id, ScanForm form, Actor actor, Instant at) {
        TenantContext.require();
        StockCheck check = checks.lockById(id).orElseThrow(() -> ApiException.notFound("Stock check"));
        if (!check.isOpen()) {
            throw ApiException.conflict("This stock check is closed.", "accessionNo");
        }
        String acc = form.accessionNo().trim().toUpperCase(Locale.ROOT);
        Copy copy = copies.findByAccessionNo(acc).orElseThrow(() -> ApiException.badRequest(
                "No copy has the accession number " + acc + ".", "accessionNo"));
        boolean already = items.existsByStockCheckIdAndCopyId(check.getId(), copy.getId());
        if (!already) {
            items.saveAndFlush(new StockCheckItem(check.getId(), copy.getId(), at, actor.id()));
            audit.record(actor, "library_stock_check.copy_seen", "library_stock_check", check.getId(),
                    Map.of("accessionNo", copy.getAccessionNo()));
        }
        String warning = switch (copy.getStatus()) {
            case ISSUED, LOST, WITHDRAWN -> copy.getStatus().name();
            default -> null;
        };
        String title = titles.findById(copy.getTitleId()).map(Title::getTitle).orElse("");
        return new ScanResult(copy.getAccessionNo(), title, copy.getStatus(), already, warning,
                items.countByStockCheckId(check.getId()), expected().size());
    }

    /** Closes the check, keeping how many copies were expected and how many of them were not seen. */
    public StockCheckDetail close(UUID id, Actor actor, Instant at) {
        TenantContext.require();
        StockCheck check = checks.lockById(id).orElseThrow(() -> ApiException.notFound("Stock check"));
        if (!check.isOpen()) {
            throw ApiException.conflict("This stock check is already closed.", "status");
        }
        List<Copy> expected = expected();
        List<MissingCopy> missing = missing(check, expected);
        check.close(expected.size(), missing.size(), actor, at);
        checks.saveAndFlush(check);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("expected", expected.size());
        details.put("missing", missing.size());
        details.put("seen", items.countByStockCheckId(check.getId()));
        audit.record(actor, "library_stock_check.closed", "library_stock_check", check.getId(), details);
        return detail(check, missing);
    }

    /**
     * The check with the copies not seen: those expected on the shelves now (for a closed check too) that were not
     * scanned during it.
     */
    @Transactional(readOnly = true)
    public StockCheckDetail detail(UUID id) {
        TenantContext.require();
        StockCheck check = checks.findById(id).orElseThrow(() -> ApiException.notFound("Stock check"));
        return detail(check, missing(check, expected()));
    }

    private StockCheckDetail detail(StockCheck check, List<MissingCopy> missing) {
        List<StockCheckItem> latest = items.latest(check.getId(), Limit.of(LATEST));
        Map<UUID, Copy> copyById = mapper.copiesOf(latest.stream().map(StockCheckItem::getCopyId).toList());
        Map<UUID, Title> titleById = mapper.titlesOf(copyById.values().stream().map(Copy::getTitleId).toList());
        List<SeenCopy> seen = latest.stream().map(i -> {
            Copy c = copyById.get(i.getCopyId());
            Title t = c == null ? null : titleById.get(c.getTitleId());
            return new SeenCopy(c == null ? null : c.getAccessionNo(), t == null ? null : t.getTitle(),
                    i.getSeenAt());
        }).toList();
        return new StockCheckDetail(row(check, items.countByStockCheckId(check.getId())), missing, seen);
    }

    private List<MissingCopy> missing(StockCheck check, List<Copy> expected) {
        Set<UUID> seen = new HashSet<>(items.copyIdsOf(check.getId()));
        List<Copy> notSeen = expected.stream().filter(c -> !seen.contains(c.getId())).toList();
        Map<UUID, Title> titleById = mapper.titlesOf(notSeen.stream().map(Copy::getTitleId).toList());
        return notSeen.stream().map(c -> {
            Title t = titleById.get(c.getTitleId());
            return new MissingCopy(c.getId(), c.getAccessionNo(), c.getTitleId(), t == null ? null : t.getTitle(),
                    t == null ? null : t.getCallNumber(), t == null ? null : t.getShelfLocation(), c.getStatus());
        }).toList();
    }

    private List<Copy> expected() {
        return copies.withStatus(EXPECTED);
    }

    private StockCheckRow row(StockCheck c, long seen) {
        long expected;
        long missing;
        if (c.isOpen()) {
            List<Copy> now = expected();
            expected = now.size();
            missing = missing(c, now).size();
        } else {
            expected = c.getExpectedCount() == null ? 0 : c.getExpectedCount();
            missing = c.getMissingCount() == null ? 0 : c.getMissingCount();
        }
        return new StockCheckRow(c.getId(), c.getStatus(), c.getNote(), c.getStartedByName(), c.getStartedAt(),
                c.getClosedByName(), c.getClosedAt(), seen, expected, missing);
    }
}
