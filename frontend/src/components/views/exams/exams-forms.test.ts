import { describe, expect, it } from "vitest";
import { translate, type Translate } from "@/lib/i18n";
import type { ExamMarks, ExamStructure, MarkSheetRow, StudentTermRecord } from "@/lib/types";
import { examPaper } from "@/test/exams-fixtures";
import { changeText, examOptions } from "../reports/exam-analysis-report";
import { paperDrafts, paperProblem, toPaperRequests, type PaperDraft } from "./exam-class-view";
import { checkWeights, validateExam, validateTerm } from "./exams-home";
import { formatPct, formatScore, maxParts } from "./exams-shared";
import { schemeRequest } from "./grading-view";
import { sheetHref, sheetsToShow } from "./marks-overview";
import { periodOf } from "./report-cards-view";
import { recordRequest } from "./term-records";

const t: Translate = (key, vars) => translate("en", key, vars);
const YEAR = { startsOn: "2026-06-01", endsOn: "2027-03-31" };

describe("exam setup checks (mirroring the API)", () => {
  it("checks a term's name and dates inside the year", () => {
    expect(validateTerm({ name: "Term 1", startsOn: "2026-06-01", endsOn: "2026-12-31" }, YEAR)).toEqual({});
    expect(validateTerm({ name: " ", startsOn: "", endsOn: "2026-12-31" }, YEAR)).toEqual({
      name: "exams.v.name",
      startsOn: "exams.v.date",
    });
    expect(validateTerm({ name: "x".repeat(61), startsOn: "2026-06-01", endsOn: "2026-12-31" }, YEAR).name).toBe(
      "exams.v.termNameLong",
    );
    expect(validateTerm({ name: "Term 1", startsOn: "2026-10-01", endsOn: "2026-09-30" }, YEAR).endsOn).toBe(
      "exams.v.dateOrder",
    );
    expect(validateTerm({ name: "Term 2", startsOn: "2027-01-01", endsOn: "2027-04-30" }, YEAR).endsOn).toBe(
      "exams.v.termInYear",
    );
  });

  it("checks an exam's name and instructions", () => {
    expect(validateExam({ name: "Periodic Test 1", instructions: "", classIds: [] })).toEqual({});
    expect(validateExam({ name: "", instructions: "x".repeat(2001), classIds: [] })).toEqual({
      name: "exams.v.name",
      instructions: "exams.v.instructionsLong",
    });
  });

  it("needs weights from 0 to 100 that add up to 100", () => {
    expect(checkWeights(["20", "30.5", "49.5"])).toEqual({ weights: [20, 30.5, 49.5] });
    expect(checkWeights(["50", "40"])).toEqual({ problem: "exams.v.weightTotal" });
    expect(checkWeights(["50", ""])).toEqual({ problem: "exams.v.weight" });
    expect(checkWeights(["101", "-1"])).toEqual({ problem: "exams.v.weight" });
  });
});

describe("papers and the date sheet", () => {
  const draft = (overrides: Partial<PaperDraft> = {}): PaperDraft => ({
    subjectId: "sub1",
    subjectName: "English",
    include: true,
    maxTheory: "80",
    maxPractical: "",
    maxInternal: "20",
    date: "2026-12-07",
    startTime: "09:00",
    duration: "150",
    room: "Room 12",
    ...overrides,
  });

  it("lists every subject of the class, with its paper when it has one", () => {
    const drafts = paperDrafts({
      classSubjects: [
        { id: "sub1", name: "English", code: null },
        { id: "sub3", name: "Hindi", code: null },
      ],
      papers: [examPaper(), examPaper({ id: "p9", subjectId: "sub9", subjectName: "Sanskrit", maxInternal: 0, date: null, startTime: null, durationMinutes: null, endTime: null, room: null })],
    });
    expect(drafts.map((d) => [d.subjectName, d.include])).toEqual([
      ["English", true],
      ["Hindi", false],
      ["Sanskrit", true],
    ]);
    expect(drafts[0]).toMatchObject({ maxTheory: "80", maxPractical: "", maxInternal: "20", startTime: "09:00", duration: "150" });
    expect(drafts[2]).toMatchObject({ maxInternal: "", date: "", startTime: "", duration: "" });
  });

  it("checks marks and that the slot is given whole", () => {
    expect(paperProblem(draft())).toBeNull();
    expect(paperProblem(draft({ include: false, maxTheory: "x" }))).toBeNull();
    expect(paperProblem(draft({ maxTheory: "0", maxInternal: "" }))).toBe("exams.v.paperMarks");
    expect(paperProblem(draft({ maxTheory: "900", maxInternal: "200" }))).toBe("exams.v.paperMarks");
    expect(paperProblem(draft({ startTime: "" }))).toBe("exams.v.paperSlot");
    expect(paperProblem(draft({ startTime: "9:00" }))).toBe("exams.v.time");
    expect(paperProblem(draft({ duration: "10" }))).toBe("exams.v.duration");
    expect(paperProblem(draft({ room: "x".repeat(61) }))).toBe("exams.v.room");
    expect(paperProblem(draft({ date: "", startTime: "", duration: "", room: "" }))).toBeNull();
  });

  it("sends only the papers that are set, blanks as null", () => {
    expect(toPaperRequests([draft(), draft({ subjectId: "sub3", include: false })])).toEqual([
      {
        subjectId: "sub1",
        maxTheory: 80,
        maxPractical: null,
        maxInternal: 20,
        date: "2026-12-07",
        startTime: "09:00",
        durationMinutes: 150,
        room: "Room 12",
      },
    ]);
  });

  it("describes a paper's maximum marks", () => {
    expect(maxParts(t, { maxTheory: 80, maxPractical: 0, maxInternal: 20 })).toBe("Theory 80 + Internal 20");
    expect(formatScore(46.255)).toBe("46.26");
    expect(formatPct(null)).toBe("—");
  });
});

describe("grading schemes", () => {
  const values = {
    name: "Our scale",
    kind: "SCHOLASTIC" as const,
    description: "",
    passPercent: "35",
    overallPassPercent: "40",
    bands: [
      { grade: "B", minPercent: "50", gradePoint: "", remark: "" },
      { grade: "A", minPercent: "75", gradePoint: "9.5", remark: " Very good " },
      { grade: "C", minPercent: "0", gradePoint: "", remark: "" },
    ],
  };

  it("sends the bands best first", () => {
    expect(schemeRequest(values)).toEqual({
      request: {
        name: "Our scale",
        kind: "SCHOLASTIC",
        description: null,
        passPercent: 35,
        overallPassPercent: 40,
        bands: [
          { grade: "A", minPercent: 75, gradePoint: 9.5, remark: "Very good" },
          { grade: "B", minPercent: 50, gradePoint: null, remark: null },
          { grade: "C", minPercent: 0, gradePoint: null, remark: null },
        ],
      },
    });
  });

  it("refuses what the API refuses", () => {
    const band = (i: number, change: object) => ({
      ...values,
      bands: values.bands.map((b, j) => (j === i ? { ...b, ...change } : b)),
    });
    expect(schemeRequest({ ...values, name: " " })).toEqual({ problem: "exams.v.name" });
    expect(schemeRequest({ ...values, passPercent: "101" })).toEqual({ problem: "exams.v.passPercent" });
    expect(schemeRequest({ ...values, bands: [] })).toEqual({ problem: "exams.v.bandCount" });
    expect(schemeRequest(band(0, { grade: "a" }))).toEqual({ problem: "exams.v.bandGradeTwice" });
    expect(schemeRequest(band(0, { minPercent: "75" }))).toEqual({ problem: "exams.v.bandMinTwice" });
    expect(schemeRequest(band(1, { gradePoint: "10.25" }))).toEqual({ problem: "exams.v.bandPoint" });
    expect(schemeRequest(band(2, { minPercent: "10" }))).toEqual({ problem: "exams.v.bandZero" });
  });
});

describe("marks, report cards and analysis", () => {
  const row = (status: MarkSheetRow["status"], sectionId: string): MarkSheetRow => ({
    examClassId: "ec1",
    classId: "c5",
    className: "Class 5",
    paperId: "p/1",
    subjectId: "sub1",
    subjectName: "English",
    sectionId,
    sectionLabel: `Class 5 ${sectionId}`,
    paperDate: null,
    status,
    students: 30,
    entered: 0,
    canEdit: true,
    updatedByName: null,
    updatedAt: null,
    resultsPublished: false,
  });

  it("shows the sheets still to finish, or all", () => {
    const exam = { sheets: [row("LOCKED", "A"), row("DRAFT", "B"), row("NOT_STARTED", "C")] } as ExamMarks;
    expect(sheetsToShow(exam, "todo").map((s) => s.sectionId)).toEqual(["B", "C"]);
    expect(sheetsToShow(exam, "all")).toHaveLength(3);
    expect(sheetHref(row("DRAFT", "B"))).toBe("/app/exams/marks/p%2F1/B");
  });

  it("reads the report card period from the filter", () => {
    expect(periodOf("term:t1")).toEqual({ termId: "t1" });
    expect(periodOf("year:y1")).toEqual({ yearId: "y1" });
    expect(periodOf("")).toBeNull();
    expect(periodOf("month:1")).toBeNull();
  });

  it("sends a term record with blanks as null", () => {
    const record: StudentTermRecord = {
      studentId: "st1",
      fullName: "Arjun Sharma",
      admissionNo: "AKS/2025/036",
      rollNo: 2,
      coScholastic: { workEducation: "A", artEducation: "", healthPhysical: null, discipline: "B" },
      remark: "  ",
      hpc: {
        physical: "PROFICIENT",
        socioEmotional: null,
        cognitive: null,
        language: null,
        numeracy: null,
        aesthetic: null,
        teacherReflection: " Curious ",
        selfReflection: "",
        parentReflection: null,
      },
    };
    expect(recordRequest(record)).toEqual({
      studentId: "st1",
      workEducation: "A",
      artEducation: null,
      healthPhysical: null,
      discipline: "B",
      remark: null,
      physical: "PROFICIENT",
      socioEmotional: null,
      cognitive: null,
      language: null,
      numeracy: null,
      aesthetic: null,
      teacherReflection: "Curious",
      selfReflection: null,
      parentReflection: null,
    });
  });

  it("shows the change since the last exam and lists exams for the filters", () => {
    expect(changeText(4.5)).toBe("+4.5");
    expect(changeText(-2)).toBe("-2");
    expect(changeText(0)).toBe("0");
    expect(changeText(null)).toBe("—");
    const structure = {
      terms: [
        {
          id: "t1",
          name: "Term 1",
          exams: [{ id: "e1", name: "Periodic Test 1", classes: [{ classId: "c5", className: "Class 5" }] }],
        },
      ],
    } as unknown as ExamStructure;
    expect(examOptions(structure)).toEqual([
      { id: "e1", name: "Periodic Test 1", termName: "Term 1", classes: [{ id: "c5", name: "Class 5" }] },
    ]);
    expect(examOptions(undefined)).toEqual([]);
  });
});
