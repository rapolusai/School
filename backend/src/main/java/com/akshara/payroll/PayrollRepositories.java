package com.akshara.payroll;

/*
 * The payroll module's Spring Data repositories, one interface per entity. Kept in one file because each is a few
 * queries; all are package-private.
 */

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface StatutoryRateRepository extends JpaRepository<StatutoryRate, UUID> {

    @Query("select r from StatutoryRate r order by r.effectiveFrom desc")
    List<StatutoryRate> findAllNewestFirst();
}

interface TaxRuleRepository extends JpaRepository<TaxRule, UUID> {

    @Query("select r from TaxRule r order by r.startsOn desc")
    List<TaxRule> findAllNewestFirst();
}

interface TaxSlabRepository extends JpaRepository<TaxSlab, UUID> {

    @Query("select s from TaxSlab s where s.taxRuleId = ?1 order by s.fromPaise")
    List<TaxSlab> findByRule(UUID taxRuleId);
}

interface PtSlabDefaultRepository extends JpaRepository<PtSlabDefault, UUID> {

    @Query("select s from PtSlabDefault s where s.stateCode = ?1 order by s.effectiveFrom desc, s.appliesTo, "
            + "s.fromPaise, s.months nulls first")
    List<PtSlabDefault> findByState(String stateCode);
}

interface PtSlabRepository extends JpaRepository<PtSlab, UUID> {

    @Query("select s from PtSlab s where s.stateCode = ?1 order by s.effectiveFrom desc, s.appliesTo, s.fromPaise, "
            + "s.months nulls first")
    List<PtSlab> findByState(String stateCode);

    @Query("select distinct s.stateCode from PtSlab s")
    List<String> customStates();

    @Modifying
    @Query("delete from PtSlab s where s.stateCode = ?1")
    int deleteByState(String stateCode);
}

interface PayrollSettingsRepository extends JpaRepository<PayrollSettings, UUID> {

    /** The school's settings row (row-level security and the tenant filter leave at most one). */
    @Query("select s from PayrollSettings s")
    List<PayrollSettings> findForSchool();
}

interface SalaryComponentRepository extends JpaRepository<SalaryComponent, UUID> {

    @Query("select c from SalaryComponent c order by c.kind, c.displayOrder, lower(c.name)")
    List<SalaryComponent> findAllOrdered();

    @Query("select count(c) > 0 from SalaryComponent c where c.code = ?1")
    boolean codeTaken(String code);

    @Query("select count(c) > 0 from SalaryComponent c where lower(c.name) = lower(?1) and c.id <> ?2")
    boolean nameTaken(String name, UUID exceptId);
}

interface SalaryStructureRepository extends JpaRepository<SalaryStructure, UUID> {

    /** Oldest first. */
    @Query("select s from SalaryStructure s where s.userId = ?1 order by s.effectiveFrom")
    List<SalaryStructure> findByUser(UUID userId);

    @Query("select s from SalaryStructure s order by s.userId, s.effectiveFrom")
    List<SalaryStructure> findAllOrdered();

    @Query("select count(s) > 0 from SalaryStructure s where s.userId = ?1 and s.effectiveFrom = ?2")
    boolean exists(UUID userId, LocalDate effectiveFrom);
}

interface SalaryStructureLineRepository extends JpaRepository<SalaryStructureLine, UUID> {

    @Query("select l from SalaryStructureLine l where l.structureId in ?1")
    List<SalaryStructureLine> findByStructures(Collection<UUID> structureIds);

    @Query("select count(l) > 0 from SalaryStructureLine l where l.componentId = ?1")
    boolean componentUsed(UUID componentId);

    @Modifying
    @Query("delete from SalaryStructureLine l where l.structureId = ?1")
    int deleteByStructure(UUID structureId);
}

interface StaffPayProfileRepository extends JpaRepository<StaffPayProfile, UUID> {

    Optional<StaffPayProfile> findByUserId(UUID userId);
}

interface PayrollRunRepository extends JpaRepository<PayrollRun, UUID> {

    @Query("select r from PayrollRun r order by r.month desc")
    List<PayrollRun> findAllNewestFirst();

    Optional<PayrollRun> findByMonth(LocalDate month);

    /** Reads and locks the run, so two people never change or approve it at the same time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PayrollRun r where r.id = ?1")
    Optional<PayrollRun> findForUpdate(UUID id);

    /** The latest month that is approved or paid, if any. */
    @Query("select max(r.month) from PayrollRun r where r.status <> com.akshara.payroll.RunStatus.DRAFT")
    Optional<LocalDate> latestLockedMonth();

    @Query("select r from PayrollRun r where r.status <> com.akshara.payroll.RunStatus.DRAFT "
            + "and r.month >= ?1 and r.month <= ?2 order by r.month")
    List<PayrollRun> findLockedBetween(LocalDate from, LocalDate to);
}

interface PayslipRepository extends JpaRepository<Payslip, UUID> {

    @Query("select p from Payslip p where p.runId = ?1 order by lower(p.employeeName), p.employeeCode")
    List<Payslip> findByRun(UUID runId);

    @Query("select p from Payslip p where p.runId = ?1 and p.userId = ?2")
    Optional<Payslip> findByRunAndUser(UUID runId, UUID userId);

    @Query("select p from Payslip p where p.runId in ?1")
    List<Payslip> findByRuns(Collection<UUID> runIds);

    List<Payslip> findByUserId(UUID userId);

    /** A person's payslips in approved or paid runs, newest month first. */
    @Query("select p from Payslip p, PayrollRun r where r.id = p.runId and p.userId = ?1 "
            + "and r.status <> com.akshara.payroll.RunStatus.DRAFT order by r.month desc")
    List<Payslip> findLockedByUser(UUID userId);

    /** Payslips in approved or paid runs of months from {@code from} to {@code to}, for TDS and year to date. */
    @Query("select p from Payslip p, PayrollRun r where r.id = p.runId and r.status <> "
            + "com.akshara.payroll.RunStatus.DRAFT and r.month >= ?1 and r.month <= ?2")
    List<Payslip> findLockedBetween(LocalDate from, LocalDate to);

    /** Whether a person was paid from a structure in an approved or paid run. */
    @Query("select count(p) > 0 from Payslip p, PayrollRun r where r.id = p.runId and p.userId = ?1 "
            + "and p.structureFrom = ?2 and r.status <> com.akshara.payroll.RunStatus.DRAFT")
    boolean lockedWithStructure(UUID userId, LocalDate structureFrom);
}

interface PayslipLineRepository extends JpaRepository<PayslipLine, UUID> {

    @Query("select l from PayslipLine l where l.payslipId in ?1 order by l.displayOrder")
    List<PayslipLine> findByPayslips(Collection<UUID> payslipIds);

    @Query("select l from PayslipLine l where l.payslipId = ?1 order by l.displayOrder")
    List<PayslipLine> findByPayslip(UUID payslipId);

    @Modifying
    @Query("delete from PayslipLine l where l.payslipId = ?1")
    int deleteByPayslip(UUID payslipId);

    @Modifying
    @Query("delete from PayslipLine l where l.payslipId in (select p.id from Payslip p where p.runId = ?1)")
    int deleteByRun(UUID runId);
}

interface PayrollAdjustmentRepository extends JpaRepository<PayrollAdjustment, UUID> {

    @Query("select a from PayrollAdjustment a where a.runId = ?1 order by a.createdAt")
    List<PayrollAdjustment> findByRun(UUID runId);

    @Modifying
    @Query("delete from PayrollAdjustment a where a.runId = ?1 and a.userId = ?2")
    int deleteByRunAndUser(UUID runId, UUID userId);
}
