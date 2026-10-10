package com.akshara.billing;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface InvoicePaymentRepository extends JpaRepository<InvoicePayment, UUID> {

    List<InvoicePayment> findByInvoiceIdOrderByCreatedAtAsc(UUID invoiceId);
}
