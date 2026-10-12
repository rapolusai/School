package com.akshara.accounts;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.akshara.accounts.AccountsForms.VendorForm;
import com.akshara.accounts.AccountsViews.VendorLedger;
import com.akshara.accounts.AccountsViews.VendorLedgerRow;
import com.akshara.accounts.AccountsViews.VendorView;
import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.shared.ApiException;
import com.akshara.shared.TenantContext;

/**
 * Vendors: name, GSTIN (format checked), PAN, phone and bank details (all three masked in every response), the TDS
 * section and rate applied to their bills, and a ledger of bills and payments.
 */
@Service
@Transactional
public class VendorService {

    /** Default rates per section, as data the accountant verifies. */
    static final Map<String, BigDecimal> DEFAULT_TDS = Map.of(
            "194C", new BigDecimal("2.00"),
            "194J", new BigDecimal("10.00"),
            "194H", new BigDecimal("2.00"),
            "194I", new BigDecimal("10.00"),
            "194Q", new BigDecimal("0.10"));

    private final VendorRepository vendors;
    private final ExpenseRepository expenses;
    private final AuditService audit;

    VendorService(VendorRepository vendors, ExpenseRepository expenses, AuditService audit) {
        this.vendors = vendors;
        this.expenses = expenses;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<VendorView> list() {
        TenantContext.require();
        return vendors.findAllOrdered().stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public VendorView get(UUID id) {
        TenantContext.require();
        return view(vendors.findById(id).orElseThrow(() -> ApiException.notFound("Vendor")));
    }

    public VendorView create(VendorForm form, Actor actor) {
        TenantContext.require();
        Vendor.Fields f = fields(form, new UUID(0, 0));
        Vendor v = vendors.saveAndFlush(new Vendor(f));
        audit.record(actor, "vendor.created", "vendor", v.getId(), details(v));
        return view(v);
    }

    public VendorView update(UUID id, VendorForm form, Actor actor) {
        TenantContext.require();
        Vendor v = vendors.findById(id).orElseThrow(() -> ApiException.notFound("Vendor"));
        v.update(fields(form, id));
        vendors.saveAndFlush(v);
        audit.record(actor, "vendor.updated", "vendor", id, details(v));
        return view(v);
    }

    public void delete(UUID id, Actor actor) {
        TenantContext.require();
        Vendor v = vendors.findById(id).orElseThrow(() -> ApiException.notFound("Vendor"));
        if (expenses.vendorUsed(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "In use",
                    "This vendor has bills. Mark it inactive instead.");
        }
        vendors.delete(v);
        vendors.flush();
        audit.record(actor, "vendor.deleted", "vendor", id, Map.of("name", v.getName()));
    }

    /** The vendor's bills in the period (every status but DRAFT and REJECTED), with what was paid and kept back. */
    @Transactional(readOnly = true)
    public VendorLedger ledger(UUID id, LocalDate from, LocalDate to) {
        TenantContext.require();
        Vendor v = vendors.findById(id).orElseThrow(() -> ApiException.notFound("Vendor"));
        AccountsAccess.period(from, to);
        List<VendorLedgerRow> rows = expenses.findByVendor(id).stream()
                .filter(e -> e.getStatus() != ExpenseStatus.DRAFT && e.getStatus() != ExpenseStatus.REJECTED)
                .filter(e -> !e.getExpenseDate().isBefore(from) && !e.getExpenseDate().isAfter(to))
                .map(e -> new VendorLedgerRow(e.getId(), e.getNumber(), e.getExpenseDate(), e.getDescription(),
                        e.getStatus(), e.getTotalPaise(), e.getGstPaise(), e.getTdsPaise(),
                        e.getStatus() == ExpenseStatus.PAID ? e.getPaidPaise() : 0, e.getPaidOn(),
                        e.getPaymentMode()))
                .toList();
        long billed = rows.stream().mapToLong(VendorLedgerRow::billPaise).sum();
        long gst = rows.stream().mapToLong(VendorLedgerRow::gstPaise).sum();
        long tds = rows.stream().filter(r -> r.status() == ExpenseStatus.PAID).mapToLong(VendorLedgerRow::tdsPaise)
                .sum();
        long paid = rows.stream().mapToLong(VendorLedgerRow::paidPaise).sum();
        return new VendorLedger(view(v), from, to, rows, billed, gst, tds, paid, billed - paid - tds);
    }

    /** The vendor, which must exist and be active (400 on the field otherwise); null for none. */
    Vendor active(UUID id, String field) {
        if (id == null) {
            return null;
        }
        Vendor v = vendors.findById(id).orElse(null);
        if (v == null || !v.isActive()) {
            throw ApiException.badRequest("Pick an active vendor.", field);
        }
        return v;
    }

    Map<UUID, Vendor> byId() {
        return vendors.findAll().stream().collect(Collectors.toMap(Vendor::getId, Function.identity()));
    }

    VendorView view(Vendor v) {
        return new VendorView(v.getId(), v.getName(), v.getGstin(), AccountsViews.maskPan(v.getPan()),
                AccountsViews.maskPhone(v.getPhone()), v.getEmail(), v.getAddress(),
                AccountsViews.maskAccount(v.getBankAccountNumber()), v.getBankIfsc(), v.getTdsSection(),
                v.getTdsRatePercent(), v.isActive(), expenses.vendorUsed(v.getId()));
    }

    private Vendor.Fields fields(VendorForm form, UUID exceptId) {
        String name = form.name().strip();
        if (vendors.nameTaken(name, exceptId)) {
            throw ApiException.conflict("A vendor with this name already exists.", "name");
        }
        String account = AccountsForms.clean(form.bankAccountNumber());
        String ifsc = AccountsForms.upper(form.bankIfsc());
        if ((account == null) != (ifsc == null)) {
            throw ApiException.badRequest("Give the account number and the IFSC together.",
                    account == null ? "bankAccountNumber" : "bankIfsc");
        }
        String section = AccountsForms.upper(form.tdsSection());
        BigDecimal rate = form.tdsRatePercent();
        if (section != null && rate == null) {
            rate = DEFAULT_TDS.get(section);
        }
        if (section == null && rate != null) {
            throw ApiException.badRequest("Pick the TDS section the rate belongs to.", "tdsSection");
        }
        if (rate != null && rate.scale() > 2) {
            throw ApiException.badRequest("Give the rate with at most two decimals.", "tdsRatePercent");
        }
        String email = AccountsForms.clean(form.email());
        if (email != null && !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw ApiException.badRequest("Enter a valid email address.", "email");
        }
        return new Vendor.Fields(name, AccountsForms.upper(form.gstin()), AccountsForms.upper(form.pan()),
                AccountsForms.clean(form.phone()), email, AccountsForms.clean(form.address()), account, ifsc,
                section, rate == null ? null : rate.setScale(2), form.active());
    }

    private static Map<String, Object> details(Vendor v) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("name", v.getName());
        if (v.getGstin() != null) {
            d.put("gstin", v.getGstin());
        }
        if (v.getTdsSection() != null) {
            d.put("tdsSection", v.getTdsSection());
            d.put("tdsRatePercent", v.getTdsRatePercent().toPlainString());
        }
        d.put("active", v.isActive());
        return d;
    }
}
