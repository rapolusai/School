package com.akshara.accounts;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/** One of the school's bank accounts. The account number is shown masked. */
@Entity
@Table(schema = "accounts", name = "bank_account")
class BankAccount extends AssignedIdEntity {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String bankName;

    @Column(nullable = false)
    private String accountNumber;

    @Column(nullable = false)
    private String ifsc;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected BankAccount() {
    }

    BankAccount(String name, String bankName, String accountNumber, String ifsc) {
        this.id = Ids.newId();
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
        update(name, bankName, accountNumber, ifsc, true);
    }

    void update(String name, String bankName, String accountNumber, String ifsc, boolean active) {
        this.name = name;
        this.bankName = bankName;
        this.accountNumber = accountNumber;
        this.ifsc = ifsc;
        this.active = active;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    String getName() {
        return name;
    }

    String getBankName() {
        return bankName;
    }

    String getAccountNumber() {
        return accountNumber;
    }

    String getIfsc() {
        return ifsc;
    }

    boolean isActive() {
        return active;
    }
}
