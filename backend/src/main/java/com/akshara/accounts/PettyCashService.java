package com.akshara.accounts;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.accounts.AccountsForms.PettyCashForm;
import com.akshara.accounts.AccountsViews.PettyCashBook;
import com.akshara.accounts.AccountsViews.PettyRow;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * The petty cash book: a float (settings), top-ups from the main cash book, small purchases by head, and the running
 * balance. Entries are never edited; a mistake is corrected with another entry.
 */
@Service
@Transactional
public class PettyCashService {

    private final PettyCashEntryRepository entries;
    private final ChartService chart;
    private final AccountsSettingsService settings;
    private final AuditService audit;

    PettyCashService(PettyCashEntryRepository entries, ChartService chart, AccountsSettingsService settings,
            AuditService audit) {
        this.entries = entries;
        this.chart = chart;
        this.settings = settings;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PettyCashBook book(LocalDate from, LocalDate to) {
        TenantContext.require();
        AccountsAccess.period(from, to);
        long floatPaise = settings.current().getPettyCashFloatPaise();
        long opening = entries.balanceBefore(from);
        long balance = opening;
        long topUps = 0;
        long spent = 0;
        Map<UUID, Head> heads = chart.byId();
        List<PettyRow> rows = new ArrayList<>();
        for (PettyCashEntry e : entries.findBetween(from, to)) {
            if (e.getKind() == PettyKind.TOP_UP) {
                balance += e.getAmountPaise();
                topUps += e.getAmountPaise();
            } else {
                balance -= e.getAmountPaise();
                spent += e.getAmountPaise();
            }
            rows.add(new PettyRow(e.getId(), e.getEntryDate(), e.getKind(), e.getAmountPaise(), e.getHeadId(),
                    ChartService.nameOf(heads, e.getHeadId()), e.getDescription(), e.getRecordedByName(), balance));
        }
        long today = balanceToday();
        return new PettyCashBook(floatPaise, today, Math.max(0, floatPaise - today), from, to, opening, rows,
                topUps, spent);
    }

    /** The balance in the box now: every entry up to today. */
    long balanceToday() {
        return entries.balanceBefore(FinancialYear.today().plusDays(1));
    }

    public PettyCashBook record(PettyCashForm form, Actor actor) {
        TenantContext.require();
        LocalDate date = AccountsAccess.notFuture(form.entryDate(), "entryDate");
        UUID headId = null;
        Head head = null;
        if (form.kind() == PettyKind.SPENT) {
            if (form.headId() == null) {
                throw ApiException.badRequest("Pick the expense head.", "headId");
            }
            head = chart.activeHead(form.headId(), HeadKind.EXPENSE, "headId");
            headId = head.getId();
            long balance = entries.balanceBefore(date.plusDays(1));
            if (form.amountPaise() > balance) {
                throw new ApiException(HttpStatus.CONFLICT, "Not enough petty cash",
                        "Petty cash holds less than this on that day. Record a top-up first.");
            }
        } else if (form.headId() != null) {
            throw ApiException.badRequest("A top-up has no head.", "headId");
        }
        PettyCashEntry e = entries.saveAndFlush(new PettyCashEntry(date, form.kind(), form.amountPaise(), headId,
                form.description().strip(), actor));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("kind", form.kind().name());
        details.put("date", date.toString());
        details.put("amountPaise", form.amountPaise());
        if (head != null) {
            details.put("head", head.getName());
        }
        details.put("description", e.getDescription());
        audit.record(actor, "petty_cash.recorded", "petty_cash_entry", e.getId(), details);
        LocalDate from = date.withDayOfMonth(1);
        LocalDate today = FinancialYear.today();
        return book(from.isAfter(today) ? today.withDayOfMonth(1) : from, today);
    }
}
