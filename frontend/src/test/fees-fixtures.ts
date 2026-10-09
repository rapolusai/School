import type { FeeHead, InstalmentDue, Receipt, StudentFees, StudentHit } from "@/lib/types";

/** Test-only fee data: Arjun Sharma of Class 5 A, 2026-27, four quarterly instalments. */

export const ACCOUNTANT = [
  "dashboard.view",
  "students.read",
  "fees.read",
  "fees.collect",
  "fees.manage",
  "academics.read",
];
export const PRINCIPAL_FEES = ["dashboard.view", "students.read", "fees.read", "academics.read"];
export const PARENT = ["dashboard.view", "child.view"];

export const HEADS: FeeHead[] = [
  { id: "h-tuition", name: "Tuition fee", kind: "TUITION", oneTime: false, active: true, displayOrder: 1, inUse: true },
  { id: "h-admission", name: "Admission fee", kind: "ADMISSION", oneTime: true, active: true, displayOrder: 2, inUse: false },
  { id: "h-annual", name: "Annual charges", kind: "ANNUAL", oneTime: false, active: true, displayOrder: 3, inUse: true },
];

export const ARJUN_HIT: StudentHit = {
  id: "st1",
  fullName: "Arjun Sharma",
  admissionNo: "AKS/2026/001",
  status: "ACTIVE",
  className: "Class 5",
  sectionName: "A",
  rollNo: 1,
  guardianName: "Anitha Sharma",
};

function instalment(overrides: Partial<InstalmentDue>): InstalmentDue {
  return {
    instalmentId: "q1",
    seq: 1,
    label: "Quarter 1",
    dueDate: "2026-06-10",
    academicYearId: "y1",
    academicYearName: "2026-27",
    status: "PAID",
    grossPaise: 11_500_00,
    concessionPaise: 0,
    netPaise: 11_500_00,
    paidPaise: 11_500_00,
    balancePaise: 0,
    lateFeePaise: 0,
    lateFeeWaived: false,
    daysOverdue: 0,
    heads: [
      {
        dueId: "d1",
        headId: "h-tuition",
        headName: "Tuition fee",
        grossPaise: 10_000_00,
        concessionPaise: 0,
        netPaise: 10_000_00,
        paidPaise: 10_000_00,
        balancePaise: 0,
        status: "PAID",
      },
      {
        dueId: "d2",
        headId: "h-annual",
        headName: "Annual charges",
        grossPaise: 1_500_00,
        concessionPaise: 0,
        netPaise: 1_500_00,
        paidPaise: 1_500_00,
        balancePaise: 0,
        status: "PAID",
      },
    ],
    ...overrides,
  };
}

const unpaidHeads = (prefix: string) =>
  instalment({}).heads.map((h) => ({
    ...h,
    dueId: `${prefix}-${h.dueId}`,
    paidPaise: 0,
    balancePaise: h.netPaise,
    status: "UPCOMING" as const,
  }));

/** Q1 paid; Q2 overdue by 29 days with a ₹100 late fee; Q3 and Q4 upcoming. */
export const ARJUN_FEES: StudentFees = {
  student: {
    id: "st1",
    fullName: "Arjun Sharma",
    admissionNo: "AKS/2026/001",
    status: "ACTIVE",
    className: "Class 5",
    sectionName: "A",
    rollNo: 1,
  },
  instalments: [
    instalment({}),
    instalment({
      instalmentId: "q2",
      seq: 2,
      label: "Quarter 2",
      dueDate: "2026-09-10",
      status: "OVERDUE",
      paidPaise: 0,
      balancePaise: 11_500_00,
      lateFeePaise: 100_00,
      daysOverdue: 29,
      heads: unpaidHeads("q2").map((h) => ({ ...h, status: "OVERDUE" as const })),
    }),
    instalment({
      instalmentId: "q3",
      seq: 3,
      label: "Quarter 3",
      dueDate: "2026-12-10",
      status: "UPCOMING",
      paidPaise: 0,
      balancePaise: 11_500_00,
      heads: unpaidHeads("q3"),
    }),
    instalment({
      instalmentId: "q4",
      seq: 4,
      label: "Quarter 4",
      dueDate: "2027-02-10",
      status: "UPCOMING",
      paidPaise: 0,
      balancePaise: 11_500_00,
      heads: unpaidHeads("q4"),
    }),
  ],
  totals: {
    grossPaise: 46_000_00,
    concessionPaise: 0,
    netPaise: 46_000_00,
    paidPaise: 11_500_00,
    balancePaise: 34_500_00,
    overduePaise: 11_500_00,
    lateFeePaise: 100_00,
    payableNowPaise: 11_600_00,
  },
  concessions: [],
  receipts: [
    {
      id: "r1",
      receiptNo: "RCPT/2026-27/000007",
      receivedOn: "2026-06-05",
      receivedAt: "2026-06-05T05:30:00Z",
      studentId: "st1",
      studentName: "Arjun Sharma",
      admissionNo: "AKS/2026/001",
      classLabel: "Class 5 A",
      mode: "UPI",
      source: "COUNTER",
      amountPaise: 11_500_00,
      lateFeePaise: 0,
      status: "ISSUED",
      collectedByName: "Meena Reddy",
    },
  ],
  lateFeeRule: { mode: "PER_DAY", graceDays: 7, flatPaise: 0, perDayPaise: 10_00, capPaise: 500_00 },
  asOf: "2026-10-09",
};

export function receipt(overrides: Partial<Receipt> = {}): Receipt {
  return {
    id: "r42",
    receiptNo: "RCPT/2026-27/000042",
    financialYear: "2026-27",
    receivedOn: "2026-10-09",
    receivedAt: "2026-10-09T06:00:00Z",
    studentId: "st1",
    studentName: "Arjun Sharma",
    admissionNo: "AKS/2026/001",
    classLabel: "Class 5 A",
    mode: "CHEQUE",
    chequeNo: "123456",
    bankName: "State Bank of India",
    reference: null,
    source: "COUNTER",
    gatewayPaymentId: null,
    amountPaise: 11_500_00,
    lateFeePaise: 0,
    amountInWords: "Rupees Eleven Thousand Five Hundred Only",
    remarks: null,
    collectedByName: "Meena Reddy",
    status: "ISSUED",
    cancelledAt: null,
    cancelledByName: null,
    cancelReason: null,
    lines: [
      { kind: "DUE", instalmentLabel: "Quarter 2", headName: "Tuition fee", amountPaise: 10_000_00 },
      { kind: "DUE", instalmentLabel: "Quarter 2", headName: "Annual charges", amountPaise: 1_500_00 },
    ],
    school: {
      id: "t1",
      name: "Sunrise Public School",
      code: "sunrise-public",
      board: "CBSE",
      city: "Vijayawada",
      address: "12 MG Road, Vijayawada",
      phone: "0866 2345678",
      contactEmail: "office@sunrise.test",
      udiseCode: "28161234567",
    },
    ...overrides,
  };
}
