import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { AttemptReview, TestResults } from "@/lib/types";
import { renderAs } from "@/test/render";
import { AttemptMarkingView, TestResultsView } from "./test-results-view";

const m = vi.hoisted(() => ({ results: vi.fn(), release: vi.fn(), attempt: vi.fn(), mark: vi.fn() }));

vi.mock("@/lib/lessons-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/lessons-api")>();
  return { ...actual, lessonsApi: { ...actual.lessonsApi, ...m } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/lessons/tests/ot1/results",
}));

const TEACHER = ["dashboard.view", "lessons.read", "lessons.manage"];

function results(overrides: Partial<TestResults> = {}): TestResults {
  return {
    testId: "ot1",
    title: "Fractions quiz",
    state: "CLOSED",
    released: false,
    resultsReleasedAt: null,
    totalMarks: 4,
    studentCount: 2,
    attempted: 1,
    pendingMarking: 1,
    averagePercent: 50,
    highestPercent: 50,
    lowestPercent: 50,
    students: [
      {
        studentId: "st1",
        studentName: "Arjun Sharma",
        admissionNo: "AKS/2026/001",
        rollNo: 1,
        sectionLabel: "Class 5 A",
        status: "MARKING",
        bestAttemptId: "a1",
        score: 2,
        maxScore: 4,
        percent: 50,
        pendingMarking: 1,
        attempts: [
          {
            id: "a1",
            attemptNo: 1,
            startedAt: "2026-10-09T05:00:00Z",
            submittedAt: "2026-10-09T05:12:00Z",
            autoSubmitted: false,
            score: 2,
            pendingMarking: 1,
          },
        ],
      },
      {
        studentId: "st2",
        studentName: "Meera Iyer",
        admissionNo: "AKS/2026/002",
        rollNo: 2,
        sectionLabel: "Class 5 A",
        status: "NOT_STARTED",
        bestAttemptId: null,
        score: null,
        maxScore: 4,
        percent: null,
        pendingMarking: 0,
        attempts: [],
      },
    ],
    questions: [
      {
        testQuestionId: "tq1",
        position: 1,
        text: "Which is bigger?",
        type: "MCQ_SINGLE",
        difficulty: "EASY",
        marks: 1,
        answered: 1,
        correct: 1,
        averageMarks: 1,
        percentCorrect: 100,
        pendingMarking: 0,
      },
      {
        testQuestionId: "tq3",
        position: 3,
        text: "Explain how you compare fractions.",
        type: "SHORT_ANSWER",
        difficulty: "MEDIUM",
        marks: 2,
        answered: 1,
        correct: 0,
        averageMarks: null,
        percentCorrect: null,
        pendingMarking: 1,
      },
    ],
    canEdit: true,
    releaseBlocker: "1 answer still needs marks.",
    ...overrides,
  };
}

function review(overrides: Partial<AttemptReview> = {}): AttemptReview {
  return {
    attemptId: "a1",
    testId: "ot1",
    testTitle: "Fractions quiz",
    studentId: "st1",
    studentName: "Arjun Sharma",
    sectionLabel: "Class 5 A",
    attemptNo: 1,
    startedAt: "2026-10-09T05:00:00Z",
    deadlineAt: "2026-10-09T05:20:00Z",
    submittedAt: "2026-10-09T05:12:00Z",
    autoSubmitted: false,
    score: 2,
    maxScore: 4,
    pendingMarking: 1,
    canMark: true,
    answers: [
      {
        answerId: "ans1",
        position: 1,
        testQuestionId: "tq1",
        type: "MCQ_SINGLE",
        text: "Which is bigger?",
        maxMarks: 1,
        options: [
          { id: "o1", text: "1/2", correct: true },
          { id: "o2", text: "1/3", correct: false },
        ],
        selectedOptionIds: ["o1"],
        response: null,
        answerText: null,
        explanation: null,
        marks: 1,
        autoMarked: true,
        feedback: null,
        markedByName: null,
      },
      {
        answerId: "ans3",
        position: 3,
        testQuestionId: "tq3",
        type: "SHORT_ANSWER",
        text: "Explain how you compare fractions.",
        maxMarks: 2,
        options: [],
        selectedOptionIds: [],
        response: "Same denominator",
        answerText: "Make the denominators equal, then compare the numerators.",
        explanation: null,
        marks: null,
        autoMarked: false,
        feedback: null,
        markedByName: null,
      },
    ],
    ...overrides,
  };
}

describe("TestResultsView", () => {
  beforeEach(() => {
    for (const mock of Object.values(m)) mock.mockReset();
  });

  it("lists each student's result and holds the release until every answer is marked", async () => {
    m.results.mockResolvedValue(results());
    renderAs(TEACHER, <TestResultsView testId="ot1" />, ["TEACHER"]);
    const students = await screen.findByTestId("results-students");
    expect(screen.getByTestId("results-pending")).toHaveTextContent("1");
    const arjun = within(students).getByRole("link", { name: "Open Arjun Sharma's answers" });
    expect(arjun).toHaveAttribute("href", "/app/lessons/tests/ot1/attempts/a1");
    expect(arjun).toHaveTextContent("To mark");
    expect(arjun).toHaveTextContent("2 / 4 · 50%");
    expect(within(students).getByText("Not taken")).toBeInTheDocument();
    expect(within(screen.getByTestId("results-questions")).getByText(/Short answer · Medium · 1 answer to mark/)).toBeInTheDocument();
    expect(screen.getByTestId("release-blocker")).toHaveTextContent("1 answer still needs marks.");
    expect(screen.getByRole("button", { name: "Release results" })).toBeDisabled();
  });

  it("releases the results once everything is marked", async () => {
    m.results.mockResolvedValue(results({ pendingMarking: 0, releaseBlocker: null }));
    m.release.mockResolvedValue(results({ pendingMarking: 0, releaseBlocker: null, released: true, resultsReleasedAt: "2026-10-10T06:00:00Z" }));
    const user = userEvent.setup();
    renderAs(TEACHER, <TestResultsView testId="ot1" />, ["TEACHER"]);
    await user.click(await screen.findByRole("button", { name: "Release results" }));
    const dialog = await screen.findByRole("dialog", { name: "Release the results?" });
    await user.click(within(dialog).getByRole("button", { name: "Release results" }));
    expect(m.release).toHaveBeenCalledWith("ot1");
    expect(await screen.findByText(/^Released /)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Release results" })).not.toBeInTheDocument();
  });
});

describe("AttemptMarkingView", () => {
  beforeEach(() => {
    for (const mock of Object.values(m)) mock.mockReset();
  });

  it("marks a short answer with feedback", async () => {
    m.attempt.mockResolvedValue(review());
    m.mark.mockResolvedValue(
      review({
        score: 3.5,
        pendingMarking: 0,
        answers: review().answers.map((a) => (a.answerId === "ans3" ? { ...a, marks: 1.5, feedback: "Say why." } : a)),
      }),
    );
    const user = userEvent.setup();
    renderAs(TEACHER, <AttemptMarkingView testId="ot1" attemptId="a1" />, ["TEACHER"]);
    const form = await screen.findByTestId("attempt-marking");
    expect(screen.getByRole("heading", { level: 1, name: "Arjun Sharma" })).toBeInTheDocument();
    const [auto, short] = within(form).getAllByTestId("answer-review");
    expect(within(auto).getByText("Marked automatically")).toBeInTheDocument();
    expect(within(auto).getByText("1/2 (chosen)")).toBeInTheDocument();
    expect(within(short).getByText("To mark")).toBeInTheDocument();
    expect(within(short).getByText(/Make the denominators equal/)).toBeInTheDocument();

    // Nothing to send until marks are given; marks above the question's are refused.
    await user.click(within(form).getByRole("button", { name: "Save marks" }));
    expect(await within(form).findByText("Nothing has changed.")).toBeInTheDocument();
    await user.type(within(short).getByLabelText("Marks (of 2)"), "3");
    await user.click(within(form).getByRole("button", { name: "Save marks" }));
    expect(await within(form).findByText(/Give marks from 0 to each question's marks/)).toBeInTheDocument();
    expect(m.mark).not.toHaveBeenCalled();

    await user.clear(within(short).getByLabelText("Marks (of 2)"));
    await user.type(within(short).getByLabelText("Marks (of 2)"), "1.5");
    await user.type(within(short).getByLabelText("Feedback"), "Say why.");
    await user.click(within(form).getByRole("button", { name: "Save marks" }));
    expect(m.mark).toHaveBeenCalledWith("ot1", "a1", [{ answerId: "ans3", marks: 1.5, feedback: "Say why." }]);
    expect(await screen.findByTestId("attempt-score")).toHaveTextContent("3.5 / 4");
    expect(screen.getByText("Marks saved")).toBeInTheDocument();
  });
});
