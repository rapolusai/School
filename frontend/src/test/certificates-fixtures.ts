import type { CertificateDetail, CertificateSubject, IdCard, SchoolHeader } from "@/lib/types";

/* Test-only fixtures for the certificates and ID card views (docs/api/phase-2-certificates.md). */

export const SCHOOL: SchoolHeader = {
  name: "Akshara Demo School",
  address: "12 MG Road, Bengaluru 560001",
  affiliationNo: "CBSE/1234",
  logoText: "ADS",
  city: "Bengaluru",
  phone: "08012345678",
};

export const ARJUN: CertificateSubject = {
  type: "STUDENT",
  id: "st1",
  name: "Arjun Sharma",
  firstName: "Arjun",
  code: "ADM-2024-001",
  detail: "Class 5 A",
  active: true,
};

export const TARA: CertificateSubject = {
  type: "STAFF",
  id: "sf1",
  name: "Tara Menon",
  firstName: "Tara",
  code: "EMP-7",
  detail: "PGT Mathematics",
  active: true,
};

export function certificate(overrides: Partial<CertificateDetail> = {}): CertificateDetail {
  return {
    id: "c1",
    type: "BONAFIDE",
    title: "Bonafide Certificate",
    status: "PREPARED",
    subject: ARJUN,
    serialNo: null,
    versionNo: 1,
    purpose: "passport application",
    requestedVia: "OFFICE",
    requestedByName: "Olive Office",
    requestedAt: "2026-10-01T04:30:00Z",
    issuedOn: null,
    signedByName: null,
    academicYear: "2026-27",
    supersedesId: null,
    supersededById: null,
    fields: { student: "Arjun Sharma", father: "Rahul Sharma", class: "Class 5 A", purpose: "passport application" },
    placeholders: ["student", "father", "class", "academicYear", "purpose", "school", "date", "serialNo"],
    body: "This is to certify that Arjun Sharma, son of Rahul Sharma, is a bonafide student of Class 5 A.\n\nIssued for passport application.",
    school: SCHOOL,
    signatory: { name: "Prem Principal", designation: "Principal" },
    verifyToken: null,
    verifyPath: null,
    duesPaise: null,
    preparedByName: "Olive Office",
    preparedAt: "2026-10-01T04:30:00Z",
    signedAt: null,
    cancelledByName: null,
    cancelledAt: null,
    cancelReason: null,
    editable: true,
    ...overrides,
  };
}

export function issued(overrides: Partial<CertificateDetail> = {}): CertificateDetail {
  return certificate({
    status: "ISSUED",
    serialNo: "BC/2026-27/0001",
    issuedOn: "2026-10-02",
    signedByName: "Prem Principal",
    signedAt: "2026-10-02T05:00:00Z",
    verifyToken: "abc123tokenxyz",
    verifyPath: "/verify/abc123tokenxyz",
    editable: false,
    ...overrides,
  });
}

export function card(n: number, overrides: Partial<IdCard> = {}): IdCard {
  return {
    id: `card${n}`,
    subject: { ...ARJUN, id: `st${n}`, name: `Student ${n}`, firstName: `Student`, code: `ADM-2024-${String(n).padStart(3, "0")}` },
    cardNo: `ID/2026-27/${String(n).padStart(4, "0")}`,
    issueNo: 1,
    status: "ACTIVE",
    bloodGroup: "B+",
    emergencyPhone: "9876543210",
    validFrom: "2026-10-01",
    validUntil: "2027-03-31",
    photoFileId: null,
    replacesId: null,
    reissueReason: null,
    lostFeePaise: null,
    feeNote: null,
    issuedByName: "Olive Office",
    createdAt: "2026-10-01T04:30:00Z",
    cancelledAt: null,
    ...overrides,
  };
}

export const cards = (count: number) => Array.from({ length: count }, (_, i) => card(i + 1));
