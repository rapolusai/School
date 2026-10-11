import { describe, expect, it } from "vitest";
import type { AnswerReview } from "@/lib/types";
import { lessonsOptions, studyTest } from "@/test/lessons-fixtures";
import { sortStudyTests } from "./family-study";
import { classChoices, mondayOf, plusDays } from "./lessons-shared";
import { validateMaterial } from "./material-view";
import { questionRequest, validateQuestion, type QuestionValues } from "./question-bank-view";
import { syllabusProblems, syllabusRequest, type EditUnit } from "./syllabus-editor";
import { changedMarks } from "./test-results-view";
import { testRequest, validateTest, type TestValues } from "./tests-view";

const YEAR = { startsOn: "2026-06-01", endsOn: "2027-03-31" };

describe("weeks", () => {
  it("finds the Monday of a date's week and adds days across months", () => {
    expect(mondayOf("2026-10-12")).toBe("2026-10-12");
    expect(mondayOf("2026-10-18")).toBe("2026-10-12");
    expect(mondayOf("2026-11-01")).toBe("2026-10-26");
    expect(plusDays("2026-10-26", 6)).toBe("2026-11-01");
    expect(plusDays("2026-10-12", -7)).toBe("2026-10-05");
  });
});

describe("classChoices", () => {
  it("narrows a teacher to the sections and subjects they teach", () => {
    expect(classChoices(lessonsOptions())).toEqual([
      {
        id: "c5",
        name: "Class 5",
        sections: [{ id: "s5a", name: "A", label: "Class 5 A" }],
        subjects: [{ id: "math", name: "Mathematics" }],
      },
    ]);
    expect(classChoices(lessonsOptions({ wholeSchool: true, teaching: [] }))[0].sections).toHaveLength(2);
  });
});

function unit(key: string, title: string, topics: Partial<EditUnit["topics"][number]>[]): EditUnit {
  return {
    key,
    id: null,
    title,
    topics: topics.map((topic, i) => ({
      key: `${key}-${i}`,
      id: null,
      title: "Topic",
      periods: "4",
      month: "",
      hasPlans: false,
      ...topic,
    })),
  };
}

describe("syllabusProblems", () => {
  it("accepts named units and topics with periods and months within the year", () => {
    expect(syllabusProblems([unit("u1", "Numbers", [{ title: "Place value", month: "2026-07" }, { month: "2027-03" }])], YEAR)).toEqual(
      {},
    );
  });

  it("names the unit or topic field that needs attention", () => {
    expect(
      syllabusProblems(
        [
          unit("u1", " ", [
            { title: "" },
            { periods: "0" },
            { periods: "201" },
            { month: "2026-13" },
            { month: "2026-05" },
            { month: "2027-04" },
          ]),
        ],
        YEAR,
      ),
    ).toEqual({
      "u:u1": "lessons.v.unitTitle",
      "t:u1-0:title": "lessons.v.topicTitle",
      "t:u1-1:periods": "lessons.v.plannedPeriods",
      "t:u1-2:periods": "lessons.v.plannedPeriods",
      "t:u1-3:month": "lessons.v.month",
      "t:u1-4:month": "lessons.v.monthInYear",
      "t:u1-5:month": "lessons.v.monthInYear",
    });
  });

  it("limits the units, the topics in a unit and in all", () => {
    expect(syllabusProblems(Array.from({ length: 41 }, (_, i) => unit(`u${i}`, "Unit", []))).units).toBe("lessons.v.tooManyUnits");
    expect(syllabusProblems([unit("u1", "Unit", Array.from({ length: 61 }, () => ({})))])["u:u1"]).toBe("lessons.v.tooManyTopicsUnit");
    const many = Array.from({ length: 11 }, (_, i) => unit(`u${i}`, "Unit", Array.from({ length: 55 }, () => ({}))));
    expect(syllabusProblems(many).units).toBe("lessons.v.tooManyTopics");
  });

  it("builds the request with trimmed titles, numbers and null months", () => {
    const units = [unit("u1", " Numbers ", [{ title: " Fractions ", periods: "6", month: "" }])];
    units[0].id = "unit-1";
    expect(syllabusRequest(units, { yearId: "y1", classId: "c5", subjectId: "math", version: 2 })).toEqual({
      academicYearId: "y1",
      classId: "c5",
      subjectId: "math",
      version: 2,
      units: [{ id: "unit-1", title: "Numbers", topics: [{ id: null, title: "Fractions", plannedPeriods: 6, targetMonth: null }] }],
    });
  });
});

function question(overrides: Partial<QuestionValues> = {}): QuestionValues {
  return {
    classId: "c5",
    subjectId: "math",
    topicId: "",
    type: "MCQ_SINGLE",
    difficulty: "EASY",
    bloomLevel: "",
    text: "Which is bigger?",
    answerText: "",
    explanation: "",
    marks: "1",
    shared: true,
    options: [
      { text: "1/2", correct: true },
      { text: "1/3", correct: false },
    ],
    ...overrides,
  };
}

describe("validateQuestion", () => {
  it("checks the options of each kind of choice question", () => {
    expect(validateQuestion(question())).toEqual({});
    expect(validateQuestion(question({ options: [{ text: "1/2", correct: true }] }))).toEqual({ options: "lessons.v.optionCount" });
    expect(validateQuestion(question({ options: [{ text: "1/2", correct: true }, { text: " ", correct: false }] }))).toEqual({
      options: "lessons.v.optionEmpty",
    });
    expect(validateQuestion(question({ options: [{ text: "Half", correct: true }, { text: "half ", correct: false }] }))).toEqual({
      options: "lessons.v.optionTwice",
    });
    const both = [
      { text: "1/2", correct: true },
      { text: "2/4", correct: true },
    ];
    expect(validateQuestion(question({ options: both }))).toEqual({ options: "lessons.v.oneCorrect" });
    expect(validateQuestion(question({ type: "MCQ_MULTI", options: both }))).toEqual({});
    expect(
      validateQuestion(question({ type: "MCQ_MULTI", options: both.map((o) => ({ ...o, correct: false })) })),
    ).toEqual({ options: "lessons.v.someCorrect" });
  });

  it("asks for the answer of true-or-false and fill-in-the-blank questions", () => {
    expect(validateQuestion(question({ type: "TRUE_FALSE", options: [] }))).toEqual({ answerText: "lessons.v.trueFalse" });
    expect(validateQuestion(question({ type: "TRUE_FALSE", answerText: "FALSE", options: [] }))).toEqual({});
    expect(validateQuestion(question({ type: "FILL_BLANK", answerText: "  ", options: [] }))).toEqual({
      answerText: "lessons.v.accepted",
    });
    // A short answer's model answer is optional.
    expect(validateQuestion(question({ type: "SHORT_ANSWER", options: [] }))).toEqual({});
  });

  it("checks the class, subject, text and marks", () => {
    expect(validateQuestion(question({ classId: "", subjectId: "", text: " ", marks: "0.1" }))).toEqual({
      classId: "lessons.v.class",
      subjectId: "lessons.v.subject",
      text: "lessons.v.question",
      marks: "lessons.v.marks",
    });
    expect(validateQuestion(question({ marks: "100.5" })).marks).toBe("lessons.v.marks");
    expect(validateQuestion(question({ marks: "0.25" }))).toEqual({});
  });

  it("sends options only for choice questions", () => {
    expect(questionRequest(question({ text: " Which is bigger? ", bloomLevel: "APPLY" }))).toMatchObject({
      text: "Which is bigger?",
      bloomLevel: "APPLY",
      answerText: null,
      topicId: null,
      marks: 1,
      options: [
        { text: "1/2", correct: true },
        { text: "1/3", correct: false },
      ],
    });
    expect(questionRequest(question({ type: "FILL_BLANK", answerText: " half \none half " }))).toMatchObject({
      answerText: "half \none half",
      options: null,
    });
  });
});

function testValues(overrides: Partial<TestValues> = {}): TestValues {
  return {
    classId: "c5",
    subjectId: "math",
    sectionIds: ["s5a"],
    title: "Fractions quiz",
    instructions: "",
    opensAt: "2026-10-12T09:00",
    closesAt: "2026-10-12T17:00",
    durationMinutes: "20",
    attemptsAllowed: "1",
    shuffleQuestions: false,
    shuffleOptions: true,
    showAnswers: "WITH_RESULTS",
    ...overrides,
  };
}

describe("validateTest", () => {
  it("accepts a test with sections, a window, duration and attempts", () => {
    expect(validateTest(testValues())).toEqual({});
  });

  it("checks the window, duration and attempts", () => {
    expect(validateTest(testValues({ closesAt: "2026-10-12T09:00" }))).toEqual({ closesAt: "lessons.v.closeAfterOpen" });
    expect(validateTest(testValues({ closesAt: "2027-01-12T09:00" }))).toEqual({ closesAt: "lessons.v.openTooLong" });
    expect(validateTest(testValues({ opensAt: "", sectionIds: [] }))).toEqual({
      opensAt: "lessons.v.dateTime",
      sectionIds: "lessons.v.sections",
    });
    expect(validateTest(testValues({ durationMinutes: "301", attemptsAllowed: "6" }))).toEqual({
      durationMinutes: "lessons.v.duration",
      attemptsAllowed: "lessons.v.attempts",
    });
  });

  it("sends the window as instants in India time", () => {
    expect(testRequest(testValues({ title: " Fractions quiz " }), "y1", 2)).toEqual({
      academicYearId: "y1",
      classId: "c5",
      subjectId: "math",
      sectionIds: ["s5a"],
      title: "Fractions quiz",
      instructions: null,
      opensAt: "2026-10-12T03:30:00.000Z",
      closesAt: "2026-10-12T11:30:00.000Z",
      durationMinutes: 20,
      attemptsAllowed: 1,
      shuffleQuestions: false,
      shuffleOptions: true,
      showAnswers: "WITH_RESULTS",
      version: 2,
    });
  });
});

describe("validateMaterial", () => {
  const material = {
    classId: "c5",
    sectionId: "",
    subjectId: "math",
    topicId: "",
    title: "Fractions notes",
    notes: "",
    linkUrl: "",
    publishOn: "2026-10-12",
  };

  it("checks the title, link and publish date", () => {
    expect(validateMaterial(material, YEAR)).toEqual({});
    expect(validateMaterial({ ...material, title: "", linkUrl: "example.org", publishOn: "2027-04-01" }, YEAR)).toEqual({
      title: "lessons.v.title",
      linkUrl: "lessons.v.link",
      publishOn: "lessons.v.dateInYear",
    });
    expect(validateMaterial({ ...material, linkUrl: "https://example.org/fractions" }, YEAR)).toEqual({});
  });
});

function answer(overrides: Partial<AnswerReview>): AnswerReview {
  return {
    answerId: "ans3",
    position: 3,
    testQuestionId: "tq3",
    type: "SHORT_ANSWER",
    text: "Explain how you compare fractions.",
    maxMarks: 2,
    options: [],
    selectedOptionIds: [],
    response: "Same denominator",
    answerText: null,
    explanation: null,
    marks: null,
    autoMarked: false,
    feedback: null,
    markedByName: null,
    ...overrides,
  };
}

describe("changedMarks", () => {
  it("sends new and changed marks only, and refuses marks out of range", () => {
    const answers = [answer({}), answer({ answerId: "ans1", type: "MCQ_SINGLE", maxMarks: 1, marks: 1, autoMarked: true })];
    expect(changedMarks(answers, { ans3: { marks: "", feedback: "" }, ans1: { marks: "1", feedback: "" } })).toEqual([]);
    expect(changedMarks(answers, { ans3: { marks: "1.5", feedback: " Good " }, ans1: { marks: "1", feedback: "" } })).toEqual([
      { answerId: "ans3", marks: 1.5, feedback: "Good" },
    ]);
    expect(changedMarks(answers, { ans1: { marks: "0", feedback: "" } })).toEqual([{ answerId: "ans1", marks: 0, feedback: null }]);
    expect(changedMarks(answers, { ans3: { marks: "3", feedback: "" } })).toBe("invalid");
    expect(changedMarks(answers, { ans1: { marks: "", feedback: "" } })).toBe("invalid");
    expect(changedMarks(answers, { ans3: { marks: "1", feedback: "x".repeat(501) } })).toBe("invalid");
  });
});

describe("sortStudyTests", () => {
  it("lists open tests closing soonest first, then upcoming, then closed latest first", () => {
    const tests = [
      studyTest({ id: "closed-old", state: "CLOSED", closesAt: "2026-09-01T10:00:00Z" }),
      studyTest({ id: "open-late", state: "OPEN", closesAt: "2026-10-20T10:00:00Z" }),
      studyTest({ id: "upcoming", state: "UPCOMING", opensAt: "2026-10-15T10:00:00Z" }),
      studyTest({ id: "closed-new", state: "CLOSED", closesAt: "2026-10-01T10:00:00Z" }),
      studyTest({ id: "open-soon", state: "OPEN", closesAt: "2026-10-10T10:00:00Z" }),
    ];
    expect(sortStudyTests(tests).map((t) => t.id)).toEqual(["open-soon", "open-late", "upcoming", "closed-new", "closed-old"]);
  });
});
