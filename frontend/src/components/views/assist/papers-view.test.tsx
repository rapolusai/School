import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { PaperView } from "@/lib/types";
import { renderAs } from "@/test/render";
import { blueprintMarks, blueprintProblem, DEFAULT_SECTIONS, PaperBuilderView, PaperDetailView, questionStarts } from "./papers-view";

const m = vi.hoisted(() => ({
  settings: vi.fn(),
  draftPaper: vi.fn(),
  savePaper: vi.fn(),
  paper: vi.fn(),
  deletePaper: vi.fn(),
  listClasses: vi.fn(),
  topics: vi.fn(),
  push: vi.fn(),
}));

vi.mock("@/lib/assist-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/assist-api")>();
  return {
    ...actual,
    assistApi: { ...actual.assistApi, settings: m.settings, draftPaper: m.draftPaper, savePaper: m.savePaper, paper: m.paper, deletePaper: m.deletePaper },
  };
});

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, listClasses: m.listClasses } };
});

vi.mock("@/lib/lessons-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/lessons-api")>();
  return { ...actual, lessonsApi: { ...actual.lessonsApi, topics: m.topics } };
});

vi.mock("next/navigation", () => ({ usePathname: () => "/app/assist/papers/new", useRouter: () => ({ push: m.push }) }));

const TEACHER = ["dashboard.view", "assist.use", "lessons.read", "lessons.manage", "academics.read"];

const DRAFT: PaperView = {
  id: null,
  classId: "c5",
  className: "Class 5",
  subjectId: "maths",
  subjectName: "Mathematics",
  title: "Unit test 1",
  durationMinutes: 60,
  totalMarks: 4,
  instructions: "Time allowed: 60 minutes. Maximum marks: 4.",
  blueprint: { sections: [{ name: "Section A", questions: 2, marksEach: 1 }, { name: "Section B", questions: 1, marksEach: 2 }], easyPercent: 40, mediumPercent: 40, hardPercent: 20, bloomLevels: [], topicIds: [] },
  sections: [
    {
      name: "Section A",
      instructions: "Answer all 2 questions. Each carries 1 mark.",
      marksEach: 1,
      wanted: 2,
      questions: [
        { questionId: "q1", text: "What is 2 + 2?", type: "MCQ_SINGLE", difficulty: "EASY", bloomLevel: "REMEMBER", marks: 1, options: ["3", "4"] },
        { questionId: "q2", text: "Name a prime number.", type: "SHORT_ANSWER", difficulty: "MEDIUM", bloomLevel: null, marks: 1, options: [] },
      ],
    },
    { name: "Section B", instructions: null, marksEach: 2, wanted: 1, questions: [{ questionId: "q3", text: "Explain place value.", type: "SHORT_ANSWER", difficulty: "HARD", bloomLevel: null, marks: 2, options: [] }] },
  ],
  provider: "simulated",
  simulated: true,
  warnings: ["Section B: only 1 of 2 questions found in the bank."],
  createdByName: "Ravi Kumar",
  updatedAt: null,
  version: 0,
};

describe("blueprint helpers", () => {
  it("adds up marks, checks the blueprint and numbers questions through the paper", () => {
    expect(blueprintMarks(DEFAULT_SECTIONS)).toBe(25);
    const ok = { sections: DEFAULT_SECTIONS, easyPercent: 40, mediumPercent: 40, hardPercent: 20, bloomLevels: [], topicIds: [] };
    expect(blueprintProblem(ok)).toBeNull();
    expect(blueprintProblem({ ...ok, easyPercent: 50 })).toBe("assist.papers.v.mix");
    expect(blueprintProblem({ ...ok, easyPercent: 0, mediumPercent: 0, hardPercent: 0 })).toBeNull();
    expect(blueprintProblem({ ...ok, sections: [] })).toBe("assist.papers.v.sections");
    expect(blueprintProblem({ ...ok, sections: [{ name: " ", questions: 2, marksEach: 1 }] })).toBe("assist.papers.v.section");
    expect(questionStarts(DRAFT)).toEqual([0, 2]);
  });
});

describe("PaperBuilderView", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    m.settings.mockResolvedValue({ enabled: true, papersEnabled: true, simulated: true });
    m.listClasses.mockResolvedValue([{ id: "c5", name: "Class 5", displayOrder: 5, sections: [], subjects: [{ id: "maths", name: "Mathematics", code: null }] }]);
    m.topics.mockResolvedValue([{ id: "t1", title: "Fractions", unitTitle: "Numbers", targetMonth: null }]);
  });

  it("drafts a paper from the blueprint, shows warnings and saves it", async () => {
    m.draftPaper.mockResolvedValue(DRAFT);
    m.savePaper.mockResolvedValue({ ...DRAFT, id: "p1" });
    const user = userEvent.setup();
    renderAs(TEACHER, <PaperBuilderView />);
    await user.selectOptions(await screen.findByLabelText("Class"), "c5");
    await user.selectOptions(screen.getByLabelText("Subject"), "maths");
    await user.type(screen.getByLabelText("Title"), "Unit test 1");
    await user.click(await screen.findByLabelText("Fractions"));
    await user.click(screen.getByLabelText("Apply"));
    expect(screen.getByTestId("blueprint-marks")).toHaveTextContent("25 marks in all");
    await user.click(screen.getByTestId("paper-draft"));
    await waitFor(() => expect(m.draftPaper).toHaveBeenCalled());
    expect(m.draftPaper.mock.calls[0][0]).toMatchObject({
      classId: "c5",
      subjectId: "maths",
      title: "Unit test 1",
      durationMinutes: 60,
      blueprint: { easyPercent: 40, mediumPercent: 40, hardPercent: 20, bloomLevels: ["APPLY"], topicIds: ["t1"] },
    });
    expect(await screen.findByTestId("paper-warnings")).toHaveTextContent("only 1 of 2 questions");
    const doc = screen.getByTestId("paper-document");
    expect(within(doc).getAllByTestId("paper-question")).toHaveLength(3);
    expect(within(doc).getByText("Explain place value.").closest("li")).toHaveTextContent("3.");
    expect(within(doc).getByTestId("simulated-label")).toBeInTheDocument();
    await user.click(screen.getByTestId("paper-save"));
    await waitFor(() => expect(m.push).toHaveBeenCalledWith("/app/assist/papers/p1"));
    expect(m.savePaper.mock.calls[0][0]).toMatchObject({ title: "Unit test 1", version: null, sections: DRAFT.sections });
  });

  it("stops a difficulty mix that does not add up to 100%", async () => {
    const user = userEvent.setup();
    renderAs(TEACHER, <PaperBuilderView />);
    await user.selectOptions(await screen.findByLabelText("Class"), "c5");
    await user.selectOptions(screen.getByLabelText("Subject"), "maths");
    await user.type(screen.getByLabelText("Title"), "Quiz");
    const easy = screen.getByLabelText("Easy");
    await user.clear(easy);
    await user.type(easy, "70");
    await user.click(screen.getByTestId("paper-draft"));
    expect(await screen.findByText("The difficulty mix must add up to 100%.")).toBeInTheDocument();
    expect(m.draftPaper).not.toHaveBeenCalled();
  });
});

describe("PaperDetailView", () => {
  it("prints the saved paper on A4 and offers no export", async () => {
    m.settings.mockResolvedValue({ enabled: true, simulated: true });
    m.paper.mockResolvedValue({ ...DRAFT, id: "p1", warnings: [] });
    const print = vi.spyOn(window, "print").mockImplementation(() => {});
    renderAs(TEACHER, <PaperDetailView paperId="p1" />);
    const doc = await screen.findByTestId("paper-document");
    expect(doc).toHaveClass("print-area");
    expect(doc).toHaveTextContent("Maximum marks: 4");
    expect(screen.queryByRole("button", { name: /export|download|csv|excel/i })).not.toBeInTheDocument();
    await userEvent.setup().click(screen.getByTestId("paper-print"));
    expect(print).toHaveBeenCalled();
  });
});
