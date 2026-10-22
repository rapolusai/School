package com.akshara.accounts;

import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.accounts.AccountsForms.CancelForm;
import com.akshara.accounts.AccountsForms.IncomeForm;
import com.akshara.accounts.AccountsViews.IncomeReceiptView;
import com.akshara.accounts.AccountsViews.IncomeRow;
import com.akshara.accounts.AccountsViews.SchoolHeader;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.fees.AmountInWords;
import com.akshara.platform.TenantDirectory;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * Income other than fees: donations (with an 80G receipt number when the school is registered), grants, rentals and
 * miscellaneous receipts, each with a gap-free receipt number per financial year (INC/2026-27/000001) and a printable
 * receipt. A receipt is cancelled with a reason, never edited.
 */
@Service
@Transactional
public class IncomeService {

    private final IncomeReceiptRepository receipts;
    private final BankAccountRepository banks;
    private final ChartService chart;
    private final AccountsSettingsService settings;
    private final TenantDirectory tenants;
    private final EntityManager entityManager;
    private final AuditService audit;

    IncomeService(IncomeReceiptRepository receipts, BankAccountRepository banks, ChartService chart,
            AccountsSettingsService settings, TenantDirectory tenants, EntityManager entityManager,
            AuditService audit) {
        this.receipts = receipts;
        this.banks = banks;
        this.chart = chart;
        this.settings = settings;
        this.tenants = tenants;
        this.entityManager = entityManager;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<IncomeRow> list(LocalDate from, LocalDate to) {
        TenantContext.require();
        Map<UUID, Head> heads = chart.byId();
        List<IncomeReceipt> list = from == null || to == null ? receipts.findAllNewestFirst()
                : receipts.findBetween(from, to).reversed();
        if (from != null && to != null) {
            AccountsAccess.period(from, to);
        }
        return list.stream().map(r -> row(r, heads)).toList();
    }

    @Transactional(readOnly = true)
    public IncomeReceiptView get(UUID id) {
        TenantContext.require();
        return view(receipts.findById(id).orElseThrow(() -> ApiException.notFound("Receipt")));
    }

    public IncomeReceiptView record(IncomeForm form, Actor actor) {
        UUID tenantId = TenantContext.require();
        LocalDate on = AccountsAccess.notFuture(form.receivedOn(), "receivedOn");
        Head head = chart.activeHead(form.headId(), HeadKind.INCOME, "headId");
        UUID bankAccountId = bankAccountFor(form.mode(), form.bankAccountId());
        String pan = AccountsForms.upper(form.payerPan());
        String eightyG = null;
        AccountsSettings s = settings.current();
        if (form.kind() == IncomeKind.DONATION && s.isDonation80gRegistered()) {
            if (pan == null) {
                throw ApiException.badRequest("An 80G receipt needs the donor's PAN.", "payerPan");
            }
            eightyG = "80G/" + FinancialYear.of(on).label() + "/" + String.format("%06d", 0);
        }
        FinancialYear year = FinancialYear.of(on);
        int seq = nextSeq(tenantId, "INC", year.label());
        String number = "INC/" + year.label() + "/" + String.format("%06d", seq);
        if (eightyG != null) {
            eightyG = "80G/" + year.label() + "/" + String.format("%06d", seq);
        }
        IncomeReceipt r = receipts.saveAndFlush(new IncomeReceipt(number, year.label(), seq,
                new IncomeReceipt.Fields(form.kind(), head.getId(), on, form.amountPaise(), form.payerName().strip(),
                        pan, AccountsForms.clean(form.payerAddress()), form.mode(), bankAccountId,
                        AccountsForms.clean(form.reference()), eightyG, AccountsForms.clean(form.description())),
                actor));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("receiptNo", number);
        details.put("kind", form.kind().name());
        details.put("head", head.getName());
        details.put("amountPaise", form.amountPaise());
        details.put("payerName", r.getPayerName());
        details.put("mode", form.mode().name());
        audit.record(actor, "income_receipt.issued", "income_receipt", r.getId(), details);
        return view(r);
    }

    public IncomeReceiptView cancel(UUID id, CancelForm form, Actor actor) {
        TenantContext.require();
        IncomeReceipt r = receipts.findById(id).orElseThrow(() -> ApiException.notFound("Receipt"));
        if (!r.issued()) {
            throw new ApiException(HttpStatus.CONFLICT, "Already cancelled",
                    "Receipt " + r.getReceiptNo() + " is already cancelled.");
        }
        r.cancel(actor, form.reason().strip(), Instant.now());
        receipts.flush();
        audit.record(actor, "income_receipt.cancelled", "income_receipt", id, Map.of("receiptNo", r.getReceiptNo(),
                "reason", r.getCancelReason()));
        return view(r);
    }

    /** CASH has no account; any other mode needs an active bank account (400 on the field otherwise). */
    UUID bankAccountFor(PayMode mode, UUID bankAccountId) {
        if (mode.cash()) {
            return null;
        }
        if (bankAccountId == null) {
            throw ApiException.badRequest("Pick the bank account.", "bankAccountId");
        }
        BankAccount b = banks.findById(bankAccountId).orElse(null);
        if (b == null || !b.isActive()) {
            throw ApiException.badRequest("Pick an active bank account.", "bankAccountId");
        }
        return b.getId();
    }

    /** The next number of the kind in the year; the counter row is locked until the transaction ends. */
    int nextSeq(UUID tenantId, String kind, String financialYear) {
        entityManager.createNativeQuery("insert into accounts.number_counter (tenant_id, kind, financial_year, "
                + "last_seq, updated_at) values (?1, ?2, ?3, 0, now()) on conflict (tenant_id, kind, financial_year) "
                + "do nothing")
                .setParameter(1, tenantId).setParameter(2, kind).setParameter(3, financialYear).executeUpdate();
        Object next = entityManager.createNativeQuery("update accounts.number_counter set last_seq = last_seq + 1, "
                + "updated_at = now() where tenant_id = ?1 and kind = ?2 and financial_year = ?3 returning last_seq")
                .setParameter(1, tenantId).setParameter(2, kind).setParameter(3, financialYear).getSingleResult();
        return ((Number) next).intValue();
    }

    IncomeRow row(IncomeReceipt r, Map<UUID, Head> heads) {
        return new IncomeRow(r.getId(), r.getReceiptNo(), r.getKind(), r.getHeadId(),
                ChartService.nameOf(heads, r.getHeadId()), r.getReceivedOn(), r.getAmountPaise(), r.getPayerName(),
                r.getMode(), r.getReference(), r.getStatus(), r.getEightyGNumber(), r.getDescription());
    }

    IncomeReceiptView view(IncomeReceipt r) {
        Map<UUID, Head> heads = chart.byId();
        AccountsSettings s = settings.current();
        String bankName = r.getBankAccountId() == null ? null
                : banks.findById(r.getBankAccountId()).map(BankAccount::getName).orElse(null);
        return new IncomeReceiptView(r.getId(), r.getReceiptNo(), r.getFinancialYear(), r.getKind(), r.getHeadId(),
                ChartService.nameOf(heads, r.getHeadId()), r.getReceivedOn(), r.getAmountPaise(),
                AmountInWords.rupees(r.getAmountPaise()), r.getPayerName(), AccountsViews.maskPan(r.getPayerPan()),
                r.getPayerAddress(), r.getMode(), r.getBankAccountId(), bankName, r.getReference(),
                r.getEightyGNumber(), r.getEightyGNumber() == null ? null : s.getDonation80gNumber(),
                r.getDescription(), r.getStatus(), r.getCancelReason(), r.getCancelledByName(), r.getCancelledAt(),
                r.getReceivedByName(), r.getCreatedAt(), r.getClearedOn(),
                SchoolHeader.of(tenants.profile(TenantContext.require()).orElse(null)));
    }
}
