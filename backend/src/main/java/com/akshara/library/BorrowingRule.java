package com.akshara.library;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.TenantId;

import com.akshara.shared.AssignedIdEntity;
import com.akshara.shared.Ids;

/**
 * How a kind of member borrows: how many books at once, for how many days, how many renewals, and the overdue fine per
 * day (paise) with its cap (0 means no cap). Until the school saves its own, {@link #defaults} apply.
 */
@Entity
@Table(schema = "library", name = "borrowing_rule")
class BorrowingRule extends AssignedIdEntity {

    /** The values a rule holds. */
    record Values(int maxBooks, int loanDays, int maxRenewals, long finePerDayPaise, long maxFinePaise) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private MemberType memberType;

    @Column(nullable = false)
    private int maxBooks;

    @Column(nullable = false)
    private int loanDays;

    @Column(nullable = false)
    private int maxRenewals;

    @Column(nullable = false)
    private long finePerDayPaise;

    @Column(nullable = false)
    private long maxFinePaise;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected BorrowingRule() {
    }

    BorrowingRule(MemberType memberType, Values values) {
        this.id = Ids.newId();
        this.memberType = memberType;
        update(values);
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    /** Students: 2 books for 14 days, one renewal, ₹1 a day up to ₹50. Staff: 5 books for 30 days, two renewals. */
    static Values defaults(MemberType type) {
        return type == MemberType.STUDENT ? new Values(2, 14, 1, 100, 5_000) : new Values(5, 30, 2, 0, 0);
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    void update(Values values) {
        this.maxBooks = values.maxBooks();
        this.loanDays = values.loanDays();
        this.maxRenewals = values.maxRenewals();
        this.finePerDayPaise = values.finePerDayPaise();
        this.maxFinePaise = values.maxFinePaise();
    }

    Values values() {
        return new Values(maxBooks, loanDays, maxRenewals, finePerDayPaise, maxFinePaise);
    }

    @Override
    public UUID getId() {
        return id;
    }

    MemberType getMemberType() {
        return memberType;
    }
}
