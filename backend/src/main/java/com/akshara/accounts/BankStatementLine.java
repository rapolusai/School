package com.akshara.accounts;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** A line of an imported bank statement, matched (or not) to a paid expense or an income receipt. */
@Entity
@Table(schema = "accounts", name = "bank_statement_line")
class BankStatementLine extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID bankAccountId;

    @Column(nullable = false, updatable = false)
    private LocalDate statementDate;

    @Column(nullable = false, updatable = false)
    private LocalDate lineDate;

    @Column(nullable = false, updatable = false)
    private String description;

    @Column(updatable = false)
    private String reference;

    @Column(nullable = false, updatable = false)
    private long debitPaise;

    @Column(nullable = false, updatable = false)
    private long creditPaise;

    private UUID matchedExpenseId;

    private UUID matchedIncomeId;

    @Column(updatable = false)
    private String importedByName;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected BankStatementLine() {
    }

    BankStatementLine(UUID bankAccountId, LocalDate statementDate, LocalDate lineDate, String description,
            String reference, long debitPaise, long creditPaise, UUID matchedExpenseId, UUID matchedIncomeId,
            String importedByName) {
        this.id = Ids.newId();
        this.bankAccountId = bankAccountId;
        this.statementDate = statementDate;
        this.lineDate = lineDate;
        this.description = description;
        this.reference = reference;
        this.debitPaise = debitPaise;
        this.creditPaise = creditPaise;
        this.matchedExpenseId = matchedExpenseId;
        this.matchedIncomeId = matchedIncomeId;
        this.importedByName = importedByName;
        this.createdAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getBankAccountId() {
        return bankAccountId;
    }

    LocalDate getStatementDate() {
        return statementDate;
    }

    LocalDate getLineDate() {
        return lineDate;
    }

    String getDescription() {
        return description;
    }

    String getReference() {
        return reference;
    }

    long getDebitPaise() {
        return debitPaise;
    }

    long getCreditPaise() {
        return creditPaise;
    }

    UUID getMatchedExpenseId() {
        return matchedExpenseId;
    }

    UUID getMatchedIncomeId() {
        return matchedIncomeId;
    }

    String getImportedByName() {
        return importedByName;
    }
}
