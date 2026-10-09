package com.akshara.onboarding;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.akshara.academics.AcademicsDirectory;
import com.akshara.academics.AcademicsDirectory.YearInfo;
import com.akshara.audit.AuditService.Actor;
import com.akshara.fees.ConcessionMode;
import com.akshara.fees.ConcessionType;
import com.akshara.fees.FeeDuesService;
import com.akshara.fees.FeeForms.ConcessionForm;
import com.akshara.fees.FeeForms.HeadAmount;
import com.akshara.fees.FeeForms.InstalmentForm;
import com.akshara.fees.FeeForms.LateFeeRuleForm;
import com.akshara.fees.FeeForms.PaymentForm;
import com.akshara.fees.FeeForms.StructureForm;
import com.akshara.fees.FeeHeadKind;
import com.akshara.fees.FeeMath;
import com.akshara.fees.FeePaymentService;
import com.akshara.fees.FeeSetupService;
import com.akshara.fees.FeeViews.HeadView;
import com.akshara.fees.FeeViews.InstalmentDue;
import com.akshara.fees.FeeViews.ReceiptView;
import com.akshara.fees.FeeViews.StructureResult;
import com.akshara.fees.FeeViews.StructureSummary;
import com.akshara.fees.LateFeeMode;
import com.akshara.fees.PaymentMode;
import com.akshara.students.StudentService;
import com.akshara.students.StudentService.StudentPage;
import com.akshara.students.StudentService.StudentQuery;
import com.akshara.students.StudentService.StudentRow;
import com.akshara.students.StudentStatus;

/**
 * The demo school's fees for 2026-27: tuition by class plus annual and exam charges in four quarterly instalments
 * (10 Jun, 10 Sep, 10 Dec, 10 Feb), a late fee of ₹10 a day after 7 days (at most ₹500), a sibling concession for Diya
 * Sharma and RTE places for two students. Most students paid Q1 in June and Q2 in September by mixed modes; about one
 * in six still owe Q2 (Arjun Sharma among them, so the demo parent has something to pay). Receipts are recorded in
 * date order, so their numbers run in date order too. Runs inside the demo school's seeding, as the accountant.
 */
@Component
class DemoFeesData {

    private static final Logger log = LoggerFactory.getLogger(DemoFeesData.class);

    static final LocalDate[] DUE = {LocalDate.of(2026, 6, 10), LocalDate.of(2026, 9, 10), LocalDate.of(2026, 12, 10),
        LocalDate.of(2027, 2, 10)};

    static final String DIYA = "Diya Sharma";
    static final String ARJUN = "Arjun Sharma";
    static final List<String> RTE_STUDENTS = List.of("Vihaan Naidu", "Arnav Das");

    private static final List<PaymentMode> MODES = List.of(PaymentMode.CASH, PaymentMode.UPI, PaymentMode.CASH,
            PaymentMode.CHEQUE, PaymentMode.UPI, PaymentMode.CARD, PaymentMode.BANK_TRANSFER, PaymentMode.CASH);
    private static final List<String> BANKS = List.of("State Bank of India", "HDFC Bank", "ICICI Bank",
            "Canara Bank");

    private final FeeSetupService setup;
    private final FeeDuesService dues;
    private final FeePaymentService payments;
    private final StudentService students;
    private final AcademicsDirectory academics;

    DemoFeesData(FeeSetupService setup, FeeDuesService dues, FeePaymentService payments, StudentService students,
            AcademicsDirectory academics) {
        this.setup = setup;
        this.dues = dues;
        this.payments = payments;
        this.students = students;
        this.academics = academics;
    }

    /** A payment to record, in date order. */
    private record Planned(LocalDate date, LocalTime time, int index, UUID studentId, UUID instalmentId, long amount,
            PaymentMode mode, String chequeNo, String bankName, String reference) {
    }

    /** Sets up fees and records the payments. Returns the number of receipts issued. */
    int seed(Actor accountant) {
        YearInfo year = academics.currentYear().orElseThrow();
        Map<FeeHeadKind, UUID> heads = setup.addDefaultHeads(accountant).stream()
                .collect(Collectors.toMap(HeadView::kind, HeadView::id, (a, b) -> a));
        UUID tuition = heads.get(FeeHeadKind.TUITION);
        UUID annual = heads.get(FeeHeadKind.ANNUAL);
        UUID exam = heads.get(FeeHeadKind.EXAM);
        LateFeeRuleForm lateFee = new LateFeeRuleForm(LateFeeMode.PER_DAY, 7, null, rupees(10), rupees(500));
        setup.updateLateFeeRule(lateFee, accountant);
        FeeMath.LateFeeRule rule = new FeeMath.LateFeeRule(LateFeeMode.PER_DAY, 7, 0, rupees(10), rupees(500));

        int structures = 0;
        for (StructureSummary c : setup.structures(year.id())) {
            int level = DemoSchoolData.level(c.className());
            long tuitionFee = rupees(24_000 + 3_000L * (level + 1));
            long annualFee = rupees(level <= 0 ? 5_000 : 6_000);
            long examFee = rupees(level <= 0 ? 1_000 : level >= 9 ? 3_000 : 2_000);
            long[] t = FeeMath.splitEvenly(tuitionFee, 4);
            long[] e = FeeMath.splitEvenly(examFee, 2);
            List<InstalmentForm> instalments = List.of(
                    new InstalmentForm("Quarter 1", DUE[0], List.of(share(tuition, t[0]), share(annual, annualFee))),
                    new InstalmentForm("Quarter 2", DUE[1], List.of(share(tuition, t[1]), share(exam, e[0]))),
                    new InstalmentForm("Quarter 3", DUE[2], List.of(share(tuition, t[2]))),
                    new InstalmentForm("Quarter 4", DUE[3], List.of(share(tuition, t[3]), share(exam, e[1]))));
            StructureResult created = setup.createStructure(new StructureForm(year.id(), c.classId(),
                    List.of(share(tuition, tuitionFee), share(annual, annualFee), share(exam, examFee)), instalments),
                    accountant);
            setup.publish(created.structure().id(), accountant);
            structures++;
        }

        List<StudentRow> roster = activeStudents(year.id());
        for (StudentRow s : roster) {
            if (s.fullName().equals(DIYA)) {
                setup.grant(new ConcessionForm(s.id(), year.id(), ConcessionType.SIBLING, ConcessionMode.PERCENT,
                        new BigDecimal("10"), null, List.of(tuition), "Second child: sibling of " + ARJUN + "."),
                        accountant);
            }
            if (RTE_STUDENTS.contains(s.fullName())) {
                setup.grant(new ConcessionForm(s.id(), year.id(), ConcessionType.RTE, null, null, null, null,
                        "RTE 25% quota admission for 2026-27."), accountant);
            }
        }

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        List<Planned> planned = new ArrayList<>();
        Planned bounced = null;
        for (int i = 0; i < roster.size(); i++) {
            StudentRow s = roster.get(i);
            List<InstalmentDue> instalments = dues.studentFees(s.id()).instalments();
            if (instalments.size() < 4) {
                continue;
            }
            boolean arjun = s.fullName().equals(ARJUN);
            // Diya is always up to date and Arjun always owes Q2, so the demo parent sees one of each.
            boolean diya = s.fullName().equals(DIYA);
            boolean q1Unpaid = !diya && i % 20 == 7;
            boolean q2Unpaid = q1Unpaid || arjun || (!diya && i % 9 == 4);
            boolean q2Partial = q2Unpaid && !arjun && !q1Unpaid && i % 2 == 0;

            InstalmentDue q1 = instalments.get(0);
            if (!q1Unpaid && q1.balancePaise() > 0) {
                LocalDate on = i % 13 == 2 ? LocalDate.of(2026, 6, 20) : LocalDate.of(2026, 6, 1 + i % 9);
                planned.add(plan(on, i, s.id(), q1.instalmentId(),
                        q1.balancePaise() + FeeMath.lateFee(rule, q1.dueDate(), on), 1));
            }
            InstalmentDue q2 = instalments.get(1);
            if (!q2Unpaid && q2.balancePaise() > 0) {
                LocalDate on = i % 11 == 3 ? LocalDate.of(2026, 9, 22) : LocalDate.of(2026, 9, 1 + i % 9);
                planned.add(plan(on, i, s.id(), q2.instalmentId(),
                        q2.balancePaise() + FeeMath.lateFee(rule, q2.dueDate(), on), 2));
            } else if (q2Partial && q2.balancePaise() > rupees(4_000)) {
                planned.add(plan(LocalDate.of(2026, 9, 5), i, s.id(), q2.instalmentId(), rupees(4_000), 2));
            } else if (bounced == null && q2Unpaid && !arjun && !q1Unpaid && q2.balancePaise() > 0) {
                bounced = new Planned(LocalDate.of(2026, 9, 8), LocalTime.of(11, 0), i, s.id(), q2.instalmentId(),
                        q2.balancePaise(), PaymentMode.CHEQUE, String.format("%06d", 310_000 + i), BANKS.get(0), null);
                planned.add(bounced);
            }
            InstalmentDue q3 = instalments.get(2);
            if (i % 10 == 0 && !q2Unpaid && q3.balancePaise() > 0) {
                planned.add(plan(LocalDate.of(2026, 10, 1 + i % 5), i, s.id(), q3.instalmentId(),
                        q3.balancePaise(), 3));
            }
        }
        planned.sort(Comparator.comparing(Planned::date).thenComparing(Planned::time)
                .thenComparingInt(Planned::index));

        int receipts = 0;
        UUID bouncedReceipt = null;
        for (Planned p : planned) {
            if (p.date().isAfter(today)) {
                continue;
            }
            ReceiptView receipt = payments.recordCounterPayment(p.studentId(), new PaymentForm(p.amount(), p.mode(),
                    p.chequeNo(), p.bankName(), p.reference(), List.of(p.instalmentId()), true, null), p.date(),
                    p.time(), accountant);
            receipts++;
            if (p == bounced) {
                bouncedReceipt = receipt.id();
            }
        }
        if (bouncedReceipt != null) {
            payments.cancel(bouncedReceipt, "Cheque returned unpaid by the bank.", accountant);
        }
        log.info("Seeded demo fees: {} structures, {} receipts", structures, receipts);
        return receipts;
    }

    private static Planned plan(LocalDate on, int index, UUID studentId, UUID instalmentId, long amount, int quarter) {
        PaymentMode mode = MODES.get((index + quarter) % MODES.size());
        LocalTime time = LocalTime.of(9, 0).plusMinutes(7L * index + quarter);
        String chequeNo = null;
        String bankName = null;
        String reference = null;
        switch (mode) {
            case CHEQUE -> {
                chequeNo = String.format("%06d", 100_000 + 37 * index + quarter);
                bankName = BANKS.get(index % BANKS.size());
            }
            case UPI -> reference = String.valueOf(612_345_670_000L + 101L * index + quarter);
            case CARD -> reference = "POS-" + (5_000 + 3 * index + quarter);
            case BANK_TRANSFER -> reference = "SBINR5202609" + String.format("%06d", 1_000 + 13 * index + quarter);
            default -> {
            }
        }
        return new Planned(on, time, index, studentId, instalmentId, amount, mode, chequeNo, bankName, reference);
    }

    private List<StudentRow> activeStudents(UUID yearId) {
        List<StudentRow> rows = new ArrayList<>();
        int page = 0;
        while (true) {
            StudentPage result = students.list(new StudentQuery(yearId, null, null, StudentStatus.ACTIVE, null, page,
                    StudentService.MAX_PAGE_SIZE));
            rows.addAll(result.items());
            if ((long) (page + 1) * StudentService.MAX_PAGE_SIZE >= result.total()) {
                break;
            }
            page++;
        }
        rows.sort(Comparator.comparing(StudentRow::admissionNo));
        return rows;
    }

    private static HeadAmount share(UUID headId, long amountPaise) {
        return new HeadAmount(headId, amountPaise);
    }

    private static long rupees(long rupees) {
        return rupees * 100;
    }
}
