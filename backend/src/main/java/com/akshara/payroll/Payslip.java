package com.akshara.payroll;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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

/**
 * One staff member's pay in a run: who they were (kept as at the run, with PAN masked and only the last four digits
 * of the account), the days, every statutory figure the exports need, and the totals. Its lines are
 * {@link PayslipLine}s. Recalculated while the run is a draft; frozen once it is approved.
 */
@Entity
@Table(schema = "payroll", name = "payslip")
class Payslip extends AssignedIdEntity {

    /** Who the payslip is for, as shown on it. */
    record Person(String name, String employeeCode, String designation, UUID departmentId, String departmentName,
            LocalDate dateOfJoining, LocalDate dateOfLeaving, String panMasked, String bankLast4, String bankIfsc,
            String uan, String esiNumber) {
    }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, updatable = false)
    private UUID runId;

    @Column(nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false)
    private String employeeName;

    private String employeeCode;

    private String designation;

    private UUID departmentId;

    private String departmentName;

    private LocalDate dateOfJoining;

    private LocalDate dateOfLeaving;

    @Column(nullable = false)
    private LocalDate structureFrom;

    private String panMasked;

    @Column(name = "bank_last4")
    private String bankLast4;

    private String bankIfsc;

    private String uan;

    private String esiNumber;

    @Column(nullable = false)
    private BigDecimal basisDays;

    @Column(nullable = false)
    private BigDecimal daysOnRoll;

    @Column(nullable = false)
    private BigDecimal lopLeaveDays;

    @Column(nullable = false)
    private BigDecimal lopAdjustmentDays;

    @Column(nullable = false)
    private BigDecimal lopDays;

    @Column(nullable = false)
    private BigDecimal payableDays;

    @Column(nullable = false)
    private long grossPaise;

    @Column(nullable = false)
    private long deductionsPaise;

    @Column(nullable = false)
    private long netPaise;

    @Column(nullable = false)
    private long employerPaise;

    @Column(nullable = false)
    private long taxablePaise;

    @Column(nullable = false)
    private boolean pfMember;

    @Column(nullable = false)
    private long pfWagesPaise;

    @Column(nullable = false)
    private long epfWagesPaise;

    @Column(nullable = false)
    private long epsWagesPaise;

    @Column(nullable = false)
    private long edliWagesPaise;

    @Column(nullable = false)
    private long pfEmployeePaise;

    @Column(nullable = false)
    private long epsPaise;

    @Column(nullable = false)
    private long epfEmployerPaise;

    @Column(nullable = false)
    private long edliPaise;

    @Column(nullable = false)
    private long pfAdminPaise;

    @Column(nullable = false)
    private boolean esiCovered;

    @Column(nullable = false)
    private long esiWagesPaise;

    @Column(nullable = false)
    private long esiEmployeePaise;

    @Column(nullable = false)
    private long esiEmployerPaise;

    @Column(nullable = false)
    private long ptPaise;

    @Column(nullable = false)
    private long tdsCalculatedPaise;

    @Column(nullable = false)
    private long tdsPaise;

    private Long tdsOverridePaise;

    private String tdsOverrideReason;

    @Column(nullable = false)
    private long tdsProjectedIncomePaise;

    @Column(nullable = false)
    private long tdsAnnualTaxPaise;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Payslip() {
    }

    Payslip(UUID runId, UUID userId) {
        this.id = Ids.newId();
        this.runId = runId;
        this.userId = userId;
        this.createdAt = Instant.now();
        this.updatedAt = createdAt;
    }

    /** Copies who the person is and every calculated figure. The TDS override is kept as it was. */
    void apply(Person p, PayslipCalculator.Result r) {
        this.employeeName = p.name();
        this.employeeCode = p.employeeCode();
        this.designation = p.designation();
        this.departmentId = p.departmentId();
        this.departmentName = p.departmentName();
        this.dateOfJoining = p.dateOfJoining();
        this.dateOfLeaving = p.dateOfLeaving();
        identifiers(p);
        this.structureFrom = r.structureFrom();
        this.basisDays = r.basisDays();
        this.daysOnRoll = r.daysOnRoll();
        this.lopLeaveDays = r.lopLeaveDays();
        this.lopAdjustmentDays = r.lopAdjustmentDays();
        this.lopDays = r.lopDays();
        this.payableDays = r.payableDays();
        this.grossPaise = r.gross();
        this.deductionsPaise = r.deductions();
        this.netPaise = r.net();
        this.employerPaise = r.employer();
        this.taxablePaise = r.taxable();
        this.pfMember = r.pfMember();
        this.pfWagesPaise = r.pf().pfWages();
        this.epfWagesPaise = r.pf().epfWages();
        this.epsWagesPaise = r.pf().epsWages();
        this.edliWagesPaise = r.pf().edliWages();
        this.pfEmployeePaise = r.pf().employee();
        this.epsPaise = r.pf().eps();
        this.epfEmployerPaise = r.pf().epfEmployer();
        this.edliPaise = r.pf().edli();
        this.pfAdminPaise = r.pf().admin();
        this.esiCovered = r.esi().covered();
        this.esiWagesPaise = r.esi().wages();
        this.esiEmployeePaise = r.esi().employee();
        this.esiEmployerPaise = r.esi().employer();
        this.ptPaise = r.pt();
        this.tdsCalculatedPaise = r.tdsCalculated();
        this.tdsPaise = r.tds();
        this.tdsProjectedIncomePaise = r.tdsWorking().income();
        this.tdsAnnualTaxPaise = r.tdsWorking().total();
    }

    /** The masked PAN, last four digits, IFSC, UAN and ESI number as they are now (refreshed on approval). */
    void identifiers(Person p) {
        this.panMasked = p.panMasked();
        this.bankLast4 = p.bankLast4();
        this.bankIfsc = p.bankIfsc();
        this.uan = p.uan();
        this.esiNumber = p.esiNumber();
    }

    void overrideTds(Long paise, String reason) {
        this.tdsOverridePaise = paise;
        this.tdsOverrideReason = reason;
    }

    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return id;
    }

    UUID getRunId() {
        return runId;
    }

    UUID getUserId() {
        return userId;
    }

    String getEmployeeName() {
        return employeeName;
    }

    String getEmployeeCode() {
        return employeeCode;
    }

    String getDesignation() {
        return designation;
    }

    UUID getDepartmentId() {
        return departmentId;
    }

    String getDepartmentName() {
        return departmentName;
    }

    LocalDate getDateOfJoining() {
        return dateOfJoining;
    }

    LocalDate getDateOfLeaving() {
        return dateOfLeaving;
    }

    LocalDate getStructureFrom() {
        return structureFrom;
    }

    String getPanMasked() {
        return panMasked;
    }

    String getBankLast4() {
        return bankLast4;
    }

    String getBankIfsc() {
        return bankIfsc;
    }

    String getUan() {
        return uan;
    }

    String getEsiNumber() {
        return esiNumber;
    }

    BigDecimal getBasisDays() {
        return basisDays;
    }

    BigDecimal getDaysOnRoll() {
        return daysOnRoll;
    }

    BigDecimal getLopLeaveDays() {
        return lopLeaveDays;
    }

    BigDecimal getLopAdjustmentDays() {
        return lopAdjustmentDays;
    }

    BigDecimal getLopDays() {
        return lopDays;
    }

    BigDecimal getPayableDays() {
        return payableDays;
    }

    long getGrossPaise() {
        return grossPaise;
    }

    long getDeductionsPaise() {
        return deductionsPaise;
    }

    long getNetPaise() {
        return netPaise;
    }

    long getEmployerPaise() {
        return employerPaise;
    }

    long getTaxablePaise() {
        return taxablePaise;
    }

    boolean isPfMember() {
        return pfMember;
    }

    long getPfWagesPaise() {
        return pfWagesPaise;
    }

    long getEpfWagesPaise() {
        return epfWagesPaise;
    }

    long getEpsWagesPaise() {
        return epsWagesPaise;
    }

    long getEdliWagesPaise() {
        return edliWagesPaise;
    }

    long getPfEmployeePaise() {
        return pfEmployeePaise;
    }

    long getEpsPaise() {
        return epsPaise;
    }

    long getEpfEmployerPaise() {
        return epfEmployerPaise;
    }

    long getEdliPaise() {
        return edliPaise;
    }

    long getPfAdminPaise() {
        return pfAdminPaise;
    }

    boolean isEsiCovered() {
        return esiCovered;
    }

    long getEsiWagesPaise() {
        return esiWagesPaise;
    }

    long getEsiEmployeePaise() {
        return esiEmployeePaise;
    }

    long getEsiEmployerPaise() {
        return esiEmployerPaise;
    }

    long getPtPaise() {
        return ptPaise;
    }

    long getTdsCalculatedPaise() {
        return tdsCalculatedPaise;
    }

    long getTdsPaise() {
        return tdsPaise;
    }

    Long getTdsOverridePaise() {
        return tdsOverridePaise;
    }

    String getTdsOverrideReason() {
        return tdsOverrideReason;
    }

    long getTdsProjectedIncomePaise() {
        return tdsProjectedIncomePaise;
    }

    long getTdsAnnualTaxPaise() {
        return tdsAnnualTaxPaise;
    }
}
