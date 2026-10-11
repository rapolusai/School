import type { ExamPaper, FamilyResults, ReportCard, ReportCards } from "@/lib/types";

/* Test-only exam data: Arjun Sharma of Class 5 A, Periodic Test 1 published, the Half-yearly date sheet out. */

export function examPaper(overrides: Partial<ExamPaper> = {}): ExamPaper {
  return {
    id: "p1",
    subjectId: "sub1",
    subjectName: "English",
    subjectCode: null,
    maxTheory: 80,
    maxPractical: 0,
    maxInternal: 20,
    maxTotal: 100,
    date: "2026-12-07",
    startTime: "09:00:00",
    durationMinutes: 150,
    endTime: "11:30:00",
    room: "Room 12",
    hasMarks: false,
    ...overrides,
  };
}

export function familyResults(studentId = "st1", overrides: Partial<FamilyResults> = {}): FamilyResults {
  return {
    studentId,
    fullName: studentId === "st1" ? "Arjun Sharma" : "Diya Sharma",
    classLabel: "Class 5 A",
    year: { id: "y1", name: "2026-27", startsOn: "2026-06-01", endsOn: "2027-03-31", current: true },
    terms: [
      { id: "t1", name: "Term 1", published: true },
      { id: "t2", name: "Term 2", published: false },
    ],
    results: [
      {
        examId: "e1",
        examName: "Periodic Test 1",
        termId: "t1",
        termName: "Term 1",
        publishedAt: "2026-09-25T05:00:00Z",
        percent: 89.5,
        grade: "A2",
        result: "PASS",
      },
    ],
    upcoming: [
      {
        examId: "e2",
        examName: "Half-Yearly Examination",
        termName: "Term 1",
        papers: [examPaper(), examPaper({ id: "p2", subjectId: "sub2", subjectName: "Mathematics", date: "2026-12-08" })],
      },
    ],
    ...overrides,
  };
}

const BANDS = [
  { minPercent: 91, grade: "A1", gradePoint: 10, remark: "Outstanding" },
  { minPercent: 81, grade: "A2", gradePoint: 9, remark: "Excellent" },
  { minPercent: 33, grade: "D", gradePoint: 4, remark: "Pass" },
  { minPercent: 0, grade: "E", gradePoint: null, remark: "Needs improvement" },
];

export function reportCard(overrides: Partial<ReportCard> = {}): ReportCard {
  return {
    studentId: "st1",
    fullName: "Arjun Sharma",
    admissionNo: "AKS/2025/036",
    rollNo: 2,
    classLabel: "Class 5 A",
    subjects: [
      {
        subjectId: "sub1",
        subjectName: "English",
        cells: [{ columnId: "e1", obtained: 37, max: 40, percent: 92.5, grade: "A1", code: null }],
        percent: 92.5,
        grade: "A1",
        gradePoint: 10,
        passed: true,
      },
      {
        subjectId: "sub2",
        subjectName: "Mathematics",
        cells: [{ columnId: "e1", obtained: null, max: 40, percent: null, grade: null, code: "ML" }],
        percent: null,
        grade: null,
        gradePoint: null,
        passed: null,
      },
    ],
    percent: 92.5,
    grade: "A1",
    gpa: 10,
    result: "PASS",
    rank: 1,
    attendance: {
      from: "2026-06-01",
      to: "2026-12-31",
      daysMarked: 19,
      present: 17,
      absent: 1,
      late: 1,
      halfDay: 0,
      leave: 0,
      presentPercent: 94.7,
    },
    coScholastic: { workEducation: "A", artEducation: "B", healthPhysical: "A", discipline: null },
    remark: "Arjun is curious and works hard.",
    hpc: {
      physical: "PROFICIENT",
      socioEmotional: "ADVANCED",
      cognitive: "ADVANCED",
      language: "PROFICIENT",
      numeracy: "ADVANCED",
      aesthetic: "PROGRESSING",
      teacherReflection: "Arjun solves problems in more than one way.",
      selfReflection: null,
      parentReflection: "He enjoys science programmes at home.",
    },
    ...overrides,
  };
}

export function reportCards(overrides: Partial<ReportCards> = {}): ReportCards {
  return {
    school: {
      name: "Akshara Demo School",
      code: "demo",
      board: "CBSE",
      city: "Hyderabad",
      address: null,
      phone: null,
    },
    kind: "TERM",
    academicYearId: "y1",
    academicYearName: "2026-27",
    termId: "t1",
    termName: "Term 1",
    classId: "c5",
    className: "Class 5",
    sectionId: "s5a",
    sectionLabel: "Class 5 A",
    classTeacherName: "Ravi Kumar",
    scheme: {
      id: "sch1",
      name: "CBSE classes 1 to 8",
      passPercent: 33,
      overallPassPercent: 33,
      gradePoints: true,
      bands: BANDS,
    },
    ranksEnabled: false,
    hpcEnabled: true,
    publishedOnly: true,
    columns: [{ id: "e1", name: "Periodic Test 1", weight: 20 }],
    cards: [reportCard()],
    ...overrides,
  };
}
