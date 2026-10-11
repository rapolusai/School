import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import type { LearnerAttempt, TestIntro } from "@/lib/types";
import { learnerAttempt, studyTest } from "@/test/lessons-fixtures";
import { renderAs } from "@/test/render";
import { clockText, isAnswered, TakeTestView } from "./take-test";

const m = vi.hoisted(() => ({
  myTest: vi.fn(),
  startAttempt: vi.fn(),
  myAttempt: vi.fn(),
  saveAnswer: vi.fn(),
  submitAttempt: vi.fn(),
}));

vi.mock("@/lib/lessons-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/lessons-api")>();
  return { ...actual, lessonsApi: { ...actual.lessonsApi, ...m } };
});

const STUDENT = ["dashboard.view"];

function intro(overrides: Partial<TestIntro> = {}): TestIntro {
  return { test: studyTest(), instructions: "Use a pencil for rough work.", attempts: [], ...overrides };
}

function submitted(overrides: Partial<LearnerAttempt> = {}): LearnerAttempt {
  return learnerAttempt({
    submitted: true,
    submittedAt: "2026-10-09T05:12:00Z",
    secondsLeft: 0,
    answered: 3,
    ...overrides,
  });
}

describe("clockText", () => {
  it("shows minutes and seconds, and hours once the test is that long", () => {
    expect(clockText(1200)).toBe("20:00");
    expect(clockText(754)).toBe("12:34");
    expect(clockText(59.9)).toBe("0:59");
    expect(clockText(3725)).toBe("1:02:05");
    expect(clockText(-5)).toBe("0:00");
  });
});

describe("isAnswered", () => {
  it("counts a chosen option or written text, not blank space", () => {
    expect(isAnswered(undefined)).toBe(false);
    expect(isAnswered({ optionIds: [], text: "  " })).toBe(false);
    expect(isAnswered({ optionIds: ["o1"], text: "" })).toBe(true);
    expect(isAnswered({ optionIds: [], text: "TRUE" })).toBe(true);
  });
});

describe("TakeTestView", () => {
  beforeEach(() => {
    for (const mock of Object.values(m)) mock.mockReset();
    m.myTest.mockResolvedValue(intro());
    m.startAttempt.mockResolvedValue(learnerAttempt());
    m.saveAnswer.mockImplementation(async () => learnerAttempt());
  });

  it("starts the test, shows one question per screen, saves each answer and submits", async () => {
    m.submitAttempt.mockResolvedValue(submitted());
    const user = userEvent.setup();
    renderAs(STUDENT, <TakeTestView testId="ot1" />, ["STUDENT"]);

    const introCard = await screen.findByTestId("test-intro");
    expect(screen.getByRole("heading", { level: 1, name: "Fractions quiz" })).toBeInTheDocument();
    expect(within(introCard).getByText("Use a pencil for rough work.")).toBeInTheDocument();
    expect(within(introCard).getByText(/You have 20 minutes once you start/)).toBeInTheDocument();
    expect(within(introCard).getByText("0 of 1 attempts used")).toBeInTheDocument();
    await user.click(within(introCard).getByRole("button", { name: "Start the test" }));
    expect(m.startAttempt).toHaveBeenCalledWith("ot1");

    // One question at a time, with the server's clock and the progress.
    await screen.findByTestId("attempt-runner");
    expect(screen.getByRole("timer")).toHaveAccessibleName(/Time left (20:00|19:59)/);
    expect(screen.getByTestId("attempt-progress")).toHaveTextContent("Question 1 of 3");
    expect(screen.getByText("0 of 3 answered")).toBeInTheDocument();
    expect(screen.getByText("Which is bigger?")).toBeInTheDocument();
    expect(screen.queryByText("2/4 equals 1/2.")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: /Previous/ })).toBeDisabled();

    // A choice is saved straight away.
    await user.click(screen.getByRole("radio", { name: "1/2" }));
    expect(m.saveAnswer).toHaveBeenCalledWith("a1", "ans1", { optionIds: ["o1"], text: null });
    expect(await screen.findByText("Saved")).toBeInTheDocument();
    expect(screen.getByText("1 of 3 answered")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Question 1, answered" })).toHaveAttribute("aria-current", "step");

    await user.click(screen.getByRole("button", { name: /Next/ }));
    expect(screen.getByTestId("attempt-progress")).toHaveTextContent("Question 2 of 3");
    expect(screen.getByText("2/4 equals 1/2.")).toBeInTheDocument();
    await user.click(screen.getByRole("radio", { name: "True" }));
    expect(m.saveAnswer).toHaveBeenLastCalledWith("a1", "ans2", { optionIds: null, text: "TRUE" });

    // A written answer is saved once typing pauses, in one request.
    await user.click(screen.getByRole("button", { name: /Next/ }));
    expect(screen.getByTestId("attempt-progress")).toHaveTextContent("Question 3 of 3");
    await user.type(screen.getByRole("textbox", { name: "Your answer" }), "Same denominator");
    expect(m.saveAnswer).toHaveBeenCalledTimes(2);
    await waitFor(() => expect(m.saveAnswer).toHaveBeenCalledTimes(3), { timeout: 3000 });
    expect(m.saveAnswer).toHaveBeenLastCalledWith("a1", "ans3", { optionIds: null, text: "Same denominator" });
    expect(screen.getByText("3 of 3 answered")).toBeInTheDocument();

    // Going back keeps the answers on screen.
    await user.click(screen.getByRole("button", { name: "Question 1, answered" }));
    expect(screen.getByRole("radio", { name: "1/2" })).toBeChecked();
    await user.click(screen.getByRole("button", { name: "Question 3, answered" }));
    expect(screen.getByRole("textbox", { name: "Your answer" })).toHaveValue("Same denominator");

    await user.click(screen.getByTestId("submit-test"));
    const confirm = await screen.findByRole("dialog", { name: "Submit your answers?" });
    expect(within(confirm).getByText(/You have answered every question/)).toBeInTheDocument();
    await user.click(within(confirm).getByRole("button", { name: "Submit test" }));

    expect(await screen.findByTestId("attempt-done")).toHaveTextContent(
      "Your answers are submitted. Your score will appear when your teacher releases the results.",
    );
    expect(m.submitAttempt).toHaveBeenCalledWith("a1");
  });

  it("warns about unanswered questions before submitting", async () => {
    m.myTest.mockResolvedValue(intro({ test: studyTest({ inProgressAttemptId: "a1", canStart: false, attemptsUsed: 1 }) }));
    m.myAttempt.mockResolvedValue(
      learnerAttempt({
        answered: 1,
        questions: learnerAttempt().questions.map((q) =>
          q.answerId === "ans1" ? { ...q, selectedOptionIds: ["o2"], answered: true } : q,
        ),
      }),
    );
    const user = userEvent.setup();
    renderAs(STUDENT, <TakeTestView testId="ot1" />, ["STUDENT"]);
    await user.click(await screen.findByRole("button", { name: "Continue the test" }));
    expect(m.myAttempt).toHaveBeenCalledWith("a1");

    // The saved answer comes back with the attempt.
    expect(await screen.findByRole("radio", { name: "1/3" })).toBeChecked();
    expect(screen.getByText("1 of 3 answered")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Question 3, not answered" }));
    await user.click(screen.getByTestId("submit-test"));
    const confirm = await screen.findByRole("dialog", { name: "Submit your answers?" });
    expect(confirm).toHaveTextContent("2 questions are not answered.");
    await user.click(within(confirm).getByRole("button", { name: "Cancel" }));
    expect(m.submitAttempt).not.toHaveBeenCalled();
    expect(screen.getByTestId("attempt-runner")).toBeInTheDocument();
  });

  it("submits the saved answers when the time runs out", async () => {
    m.startAttempt.mockResolvedValue(learnerAttempt({ secondsLeft: 1 }));
    m.submitAttempt.mockResolvedValue(submitted({ autoSubmitted: true, answered: 0 }));
    const user = userEvent.setup();
    renderAs(STUDENT, <TakeTestView testId="ot1" />, ["STUDENT"]);
    await user.click(await screen.findByRole("button", { name: "Start the test" }));
    expect(await screen.findByRole("timer")).toHaveAccessibleName(/Time left 0:0[01]/);

    await waitFor(() => expect(m.submitAttempt).toHaveBeenCalledWith("a1"), { timeout: 4000 });
    expect(await screen.findByTestId("attempt-done")).toHaveTextContent("Time ran out, so your saved answers were submitted.");
  });

  it("shows where the attempt stands when the server says the time is up", async () => {
    m.saveAnswer.mockRejectedValue(new ApiError({ status: 409, title: "Conflict", detail: "The time for this test is up." }));
    m.myAttempt.mockResolvedValue(submitted({ autoSubmitted: true, answered: 0 }));
    const user = userEvent.setup();
    renderAs(STUDENT, <TakeTestView testId="ot1" />, ["STUDENT"]);
    await user.click(await screen.findByRole("button", { name: "Start the test" }));
    await user.click(await screen.findByRole("radio", { name: "1/2" }));

    expect(await screen.findByTestId("attempt-done")).toHaveTextContent("Time ran out");
    expect(m.myAttempt).toHaveBeenCalledWith("a1");
  });

  it("keeps the answer on screen and says so when saving fails", async () => {
    m.saveAnswer.mockRejectedValue(new ApiError({ status: 0, title: "Network error" }));
    const user = userEvent.setup();
    renderAs(STUDENT, <TakeTestView testId="ot1" />, ["STUDENT"]);
    await user.click(await screen.findByRole("button", { name: "Start the test" }));
    await user.click(await screen.findByRole("radio", { name: "1/2" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Your last answer was not saved");
    expect(screen.getByRole("radio", { name: "1/2" })).toBeChecked();
  });

  it("shows a released score with the answers", async () => {
    m.myTest.mockResolvedValue(
      intro({
        test: studyTest({
          state: "CLOSED",
          canStart: false,
          attemptsUsed: 1,
          submitted: true,
          released: true,
          score: 3,
          maxScore: 4,
          percent: 75,
          reviewAttemptId: "a1",
        }),
        attempts: [
          {
            id: "a1",
            attemptNo: 1,
            startedAt: "2026-10-09T05:00:00Z",
            submittedAt: "2026-10-09T05:12:00Z",
            autoSubmitted: false,
            score: 3,
            maxScore: 4,
          },
        ],
      }),
    );
    m.myAttempt.mockResolvedValue(
      submitted({
        released: true,
        score: 3,
        maxScore: 4,
        review: [
          {
            position: 1,
            type: "MCQ_SINGLE",
            text: "Which is bigger?",
            maxMarks: 1,
            options: [
              { id: "o1", text: "1/2", correct: true },
              { id: "o2", text: "1/3", correct: false },
            ],
            selectedOptionIds: ["o1"],
            response: null,
            correctAnswer: null,
            explanation: "Halves are bigger than thirds.",
            marks: 1,
            feedback: null,
          },
          {
            position: 3,
            type: "SHORT_ANSWER",
            text: "Explain how you compare fractions.",
            maxMarks: 2,
            options: [],
            selectedOptionIds: [],
            response: "Same denominator",
            correctAnswer: null,
            explanation: null,
            marks: 1,
            feedback: "Say what you do with the numerators.",
          },
        ],
      }),
    );
    const user = userEvent.setup();
    renderAs(STUDENT, <TakeTestView testId="ot1" />, ["STUDENT"]);
    const introCard = await screen.findByTestId("test-intro");
    expect(within(introCard).queryByRole("button", { name: /Start/ })).not.toBeInTheDocument();
    expect(within(introCard).getByText("3 / 4")).toBeInTheDocument();
    await user.click(within(introCard).getByRole("button", { name: "See answers" }));

    expect(await screen.findByTestId("attempt-score")).toHaveTextContent("3 / 4");
    const review = screen.getByTestId("attempt-review");
    expect(within(review).getByText("1/2 (your choice)")).toBeInTheDocument();
    expect(within(review).getByText("Halves are bigger than thirds.")).toBeInTheDocument();
    expect(within(review).getByText("Teacher: Say what you do with the numerators.")).toBeInTheDocument();
  });

  it("says when the test is not the student's", async () => {
    m.myTest.mockRejectedValue(new ApiError({ status: 404, title: "Not Found" }));
    renderAs(STUDENT, <TakeTestView testId="other" />, ["STUDENT"]);
    expect(await screen.findByText("This test is not available to you.")).toBeInTheDocument();
  });
});
