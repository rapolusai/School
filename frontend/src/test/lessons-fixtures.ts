import type {
  CoverageReport,
  LearnerAttempt,
  LessonsOptions,
  PlanDetail,
  Study,
  StudyTest,
} from "@/lib/types";

/* Test-only lessons data: Class 5 A and B, Ravi Kumar teaching Mathematics in 5 A, Arjun Sharma of Class 5 A. */

export function lessonsOptions(overrides: Partial<LessonsOptions> = {}): LessonsOptions {
  return {
    yearId: "y1",
    years: [{ id: "y1", name: "2026-27", startsOn: "2026-06-01", endsOn: "2027-03-31", current: true }],
    classes: [
      {
        id: "c5",
        name: "Class 5",
        sections: [
          { id: "s5a", name: "A", label: "Class 5 A" },
          { id: "s5b", name: "B", label: "Class 5 B" },
        ],
        subjects: [
          { id: "math", name: "Mathematics" },
          { id: "eng", name: "English" },
        ],
      },
    ],
    userId: "u1",
    wholeSchool: false,
    canManage: true,
    canApprove: false,
    teaching: [{ sectionId: "s5a", subjectId: "math" }],
    ...overrides,
  };
}

export function planDetail(overrides: Partial<PlanDetail> = {}): PlanDetail {
  return {
    id: "p1",
    yearId: "y1",
    classId: "c5",
    sectionId: "s5a",
    sectionLabel: "Class 5 A",
    subjectId: "math",
    subjectName: "Mathematics",
    topicId: "t1",
    topicTitle: "Fractions",
    unitTitle: "Numbers",
    title: "Fractions",
    weekStart: "2026-10-12",
    planDate: null,
    periods: 2,
    objectives: "Compare fractions",
    teachingMethod: null,
    resources: null,
    resourceLinks: [],
    homeworkIdea: null,
    assessmentNote: null,
    status: "DRAFT",
    teacherId: "u1",
    teacherName: "Ravi Kumar",
    submittedAt: null,
    reviewedAt: null,
    reviewedByName: null,
    reviewComment: null,
    taughtOn: null,
    updatedAt: "2026-10-09T10:00:00Z",
    version: 0,
    canEdit: true,
    canSubmit: true,
    canReview: false,
    canMarkTaught: false,
    ...overrides,
  };
}

export function studyTest(overrides: Partial<StudyTest> = {}): StudyTest {
  return {
    id: "ot1",
    title: "Fractions quiz",
    subjectId: "math",
    subjectName: "Mathematics",
    opensAt: "2026-10-09T03:30:00Z",
    closesAt: "2026-10-16T11:30:00Z",
    durationMinutes: 20,
    questions: 3,
    totalMarks: 4,
    attemptsAllowed: 1,
    attemptsUsed: 0,
    state: "OPEN",
    inProgressAttemptId: null,
    canStart: true,
    submitted: false,
    released: false,
    score: null,
    maxScore: null,
    percent: null,
    reviewAttemptId: null,
    ...overrides,
  };
}

export function study(studentId = "st1", overrides: Partial<Study> = {}): Study {
  return {
    studentId,
    studentName: studentId === "st1" ? "Arjun Sharma" : "Diya Sharma",
    classLabel: "Class 5 A",
    today: "2026-10-09",
    material: [
      {
        id: "m1",
        title: "Fractions notes",
        notes: "Read pages 40 to 45.",
        linkUrl: null,
        subjectId: "math",
        subjectName: "Mathematics",
        topicTitle: "Fractions",
        publishOn: "2026-10-08",
        createdByName: "Ravi Kumar",
        files: [],
      },
    ],
    tests: [
      studyTest(),
      studyTest({
        id: "ot0",
        title: "Place value test",
        state: "CLOSED",
        closesAt: "2026-09-20T11:30:00Z",
        canStart: false,
        attemptsUsed: 1,
        submitted: true,
        released: true,
        score: 3.5,
        maxScore: 4,
        percent: 87.5,
        reviewAttemptId: "a0",
      }),
    ],
    ...overrides,
  };
}

/** A running attempt with one question of each kind the screen shows differently. */
export function learnerAttempt(overrides: Partial<LearnerAttempt> = {}): LearnerAttempt {
  return {
    id: "a1",
    testId: "ot1",
    testTitle: "Fractions quiz",
    subjectName: "Mathematics",
    attemptNo: 1,
    startedAt: "2026-10-09T05:00:00Z",
    deadlineAt: "2026-10-09T05:20:00Z",
    serverNow: "2026-10-09T05:00:00Z",
    secondsLeft: 1200,
    submitted: false,
    autoSubmitted: false,
    submittedAt: null,
    answered: 0,
    total: 3,
    questions: [
      {
        answerId: "ans1",
        position: 1,
        type: "MCQ_SINGLE",
        text: "Which is bigger?",
        marks: 1,
        options: [
          { id: "o1", text: "1/2" },
          { id: "o2", text: "1/3" },
        ],
        selectedOptionIds: [],
        response: null,
        answered: false,
      },
      {
        answerId: "ans2",
        position: 2,
        type: "TRUE_FALSE",
        text: "2/4 equals 1/2.",
        marks: 1,
        options: [],
        selectedOptionIds: [],
        response: null,
        answered: false,
      },
      {
        answerId: "ans3",
        position: 3,
        type: "SHORT_ANSWER",
        text: "Explain how you compare fractions.",
        marks: 2,
        options: [],
        selectedOptionIds: [],
        response: null,
        answered: false,
      },
    ],
    released: false,
    score: null,
    maxScore: null,
    review: [],
    ...overrides,
  };
}

export function coverageReport(overrides: Partial<CoverageReport> = {}): CoverageReport {
  return {
    yearId: "y1",
    yearName: "2026-27",
    month: "2026-10",
    sections: 2,
    averagePercent: 37.5,
    behind: 1,
    rows: [
      {
        sectionId: "s5a",
        sectionLabel: "Class 5 A",
        classId: "c5",
        className: "Class 5",
        subjectId: "math",
        subjectName: "Mathematics",
        topics: 4,
        taught: 1,
        percent: 25,
        due: 3,
        overdue: 2,
        expectedPercent: 75,
        behind: true,
        lastTaughtOn: "2026-08-14",
        teacherName: "Ravi Kumar",
      },
      {
        sectionId: "s5b",
        sectionLabel: "Class 5 B",
        classId: "c5",
        className: "Class 5",
        subjectId: "math",
        subjectName: "Mathematics",
        topics: 4,
        taught: 2,
        percent: 50,
        due: 2,
        overdue: 0,
        expectedPercent: 50,
        behind: false,
        lastTaughtOn: "2026-10-05",
        teacherName: "Sita Devi",
      },
    ],
    ...overrides,
  };
}
