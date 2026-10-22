import type { InfirmaryVisit, MedicalRecord } from "@/lib/types";

/** Asha Rao's infirmary visit this morning, still open. */
export function visit(overrides: Partial<InfirmaryVisit> = {}): InfirmaryVisit {
  return {
    id: "v1",
    personKind: "STUDENT",
    studentId: "s1",
    staffUserId: null,
    personName: "Asha Rao",
    className: "Class 5 A",
    sectionId: "sec1",
    timeIn: "2026-10-12T04:45:00Z",
    timeOut: null,
    complaint: "Fever and headache",
    temperatureC: 38.6,
    treatment: null,
    outcome: null,
    parentInformed: false,
    medicines: [{ medicineId: "m1", name: "Paracetamol", unit: "tablet", quantity: 1 }],
    allergies: [{ studentId: "s1", severity: "SEVERE", label: "Peanuts" }],
    recordedByName: "Mary Thomas",
    ...overrides,
  };
}

/** Asha Rao's medical record: a severe peanut allergy her mother added, two measurements, one visit. */
export function record(overrides: Partial<MedicalRecord> = {}): MedicalRecord {
  return {
    studentId: "s1",
    studentName: "Asha Rao",
    admissionNo: "AKS/2026/001",
    className: "Class 5 A",
    dateOfBirth: "2016-04-12",
    exists: true,
    bloodGroup: "O+",
    doctorName: "Dr Rao",
    doctorPhone: "9876543210",
    insuranceProvider: "Star Health",
    insurancePolicyNoMasked: "••••8812",
    notes: null,
    consent: { firstAid: true, hospital: false, byName: "Lata Rao", at: "2026-06-01T04:00:00Z" },
    emergencyContact: { guardianId: "g1", name: "Lata Rao", phone: null, phoneMasked: "98765•••01", email: null },
    allergies: [{ id: "i1", kind: "ALLERGY", name: "Peanuts", detail: "EpiPen in bag", severity: "SEVERE", itemDate: null, source: "PARENT", addedByName: "Lata Rao" }],
    conditions: [{ id: "i2", kind: "CONDITION", name: "Asthma", detail: null, severity: null, itemDate: "2024-01-01", source: "PARENT", addedByName: "Lata Rao" }],
    medications: [{ id: "i3", kind: "MEDICATION", name: "Salbutamol inhaler", detail: "When wheezing", severity: null, itemDate: null, source: "STAFF", addedByName: "Mary Thomas" }],
    vaccinations: [],
    growth: [
      { id: "g1", measuredOn: "2026-06-10", heightCm: 128.5, weightKg: 25.5, recordedByName: "Mary Thomas" },
      { id: "g2", measuredOn: "2026-09-10", heightCm: 130, weightKg: 26.5, recordedByName: "Mary Thomas" },
    ],
    chart: {
      points: [
        { date: "2026-06-10", ageMonths: 121, heightCm: 128.5, weightKg: 25.5 },
        { date: "2026-09-10", ageMonths: 124, heightCm: 130, weightKg: 26.5 },
      ],
      minHeight: 128.5,
      maxHeight: 130,
      minWeight: 25.5,
      maxWeight: 26.5,
    },
    visits: [visit({ outcome: "SENT_HOME", timeOut: "2026-10-12T06:00:00Z", parentInformed: true })],
    updatedAt: "2026-10-12T04:00:00Z",
    ...overrides,
  };
}
