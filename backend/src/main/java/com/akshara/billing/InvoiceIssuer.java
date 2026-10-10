package com.akshara.billing;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.persistence.EntityManager;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.akshara.audit.AuditService;
import com.akshara.audit.AuditService.Actor;
import com.akshara.platform.TenantView;
import com.akshara.shared.TenantContext;

/**
 * Issues GST invoices for a school's subscription, with Akshara's gap-free invoice numbers: one series per financial
 * year (April to March, India) across all schools, because Akshara is the one seller. Runs inside the caller's
 * transaction, as the school, so the invoice row is checked by row-level security and the audit entry lands in the
 * school's trail.
 */
@Component
class InvoiceIssuer {

    private final InvoiceRepository invoices;
    private final BillingProperties properties;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final EntityManager entityManager;

    InvoiceIssuer(InvoiceRepository invoices, BillingProperties properties, AuditService audit,
            ApplicationEventPublisher events, EntityManager entityManager) {
        this.invoices = invoices;
        this.properties = properties;
        this.audit = audit;
        this.events = events;
        this.entityManager = entityManager;
    }

    /**
     * The invoice for the subscription's current period at the school's plan, cycle and billed students, dated today.
     * Due {@code paymentTermsDays} after today, or on the period's first day if that is later. The caller has locked the
     * subscription and checked the school's billing state and the plan's student limit, in its transaction.
     */
    Invoice issue(Subscription subscription, TenantView school, Actor by) {
        TenantContext.require();
        LocalDate today = BillingDay.today();
        String financialYear = GstMath.financialYear(today);
        long unit = PlanCatalog.of(school.plan()).unitPricePaise(subscription.getBillingCycle());
        long taxable = Math.multiplyExact(unit, subscription.getBilledStudents());
        BillingProperties.Seller seller = properties.seller();
        GstMath.Tax tax = GstMath.tax(taxable, seller.stateCode(), subscription.getStateCode());
        LocalDate due = today.plusDays(properties.paymentTermsDays());
        if (subscription.getPeriodStart().isAfter(due)) {
            due = subscription.getPeriodStart();
        }
        String buyerName = subscription.getLegalName() != null ? subscription.getLegalName() : school.name();
        int seq = nextSeq(financialYear);
        Invoice invoice = invoices.saveAndFlush(new Invoice(new Invoice.Issue(
                GstMath.invoiceNo(properties.invoicePrefix(), financialYear, seq), financialYear, seq, today, due,
                school.plan(), subscription.getBillingCycle(), subscription.getPeriodStart(),
                subscription.getPeriodEnd(), subscription.getBilledStudents(), unit, tax, properties.sacCode(), seller,
                buyerName, subscription.getBillingAddress(), subscription.getStateCode(), subscription.getGstin(),
                by.name())));

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("invoiceNo", invoice.getInvoiceNo());
        details.put("plan", school.plan().name());
        details.put("billingCycle", subscription.getBillingCycle().name());
        details.put("billedStudents", subscription.getBilledStudents());
        details.put("periodStart", subscription.getPeriodStart().toString());
        details.put("periodEnd", subscription.getPeriodEnd().toString());
        details.put("totalPaise", invoice.getTotalPaise());
        audit.record(by, "invoice.issued", "invoice", invoice.getId(), details);
        events.publishEvent(new SubscriptionInvoiceIssued(school.id(), invoice.getId(), invoice.getInvoiceNo(),
                invoice.getTotalPaise(), invoice.getDueDate()));
        return invoice;
    }

    /**
     * The next number of the financial year. The counter row is locked FOR UPDATE until the transaction ends, so
     * concurrent invoices get consecutive numbers, and an invoice that rolls back gives its number back.
     */
    private int nextSeq(String financialYear) {
        entityManager.createNativeQuery("insert into billing.invoice_counter (financial_year, last_seq, updated_at) "
                + "values (?1, 0, now()) on conflict (financial_year) do nothing")
                .setParameter(1, financialYear).executeUpdate();
        Number last = (Number) entityManager.createNativeQuery("select last_seq from billing.invoice_counter "
                + "where financial_year = ?1 for update")
                .setParameter(1, financialYear).getSingleResult();
        int next = last.intValue() + 1;
        entityManager.createNativeQuery("update billing.invoice_counter set last_seq = ?2, updated_at = now() "
                + "where financial_year = ?1")
                .setParameter(1, financialYear).setParameter(2, next).executeUpdate();
        return next;
    }
}
