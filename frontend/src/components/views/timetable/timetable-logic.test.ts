import { describe, expect, it } from "vitest";
import type { BellSchedule, SubjectLoad } from "@/lib/types";
import {
  bellRowProblems,
  gridFromSlots,
  gridRows,
  hasPeriod,
  minutes,
  overLimit,
  sameGrid,
  teacherClashes,
  toCells,
  workingDays,
  type Grid,
} from "./timetable-logic";
import { subjectColours } from "./timetable-shared";
import { timetableTabs } from "./timetable-view";

const BELLS: BellSchedule = {
  workingDays: ["SATURDAY", "MONDAY", "TUESDAY"],
  saturdaySchedule: true,
  weekday: [
    { number: 1, label: "Period 1", startsAt: "08:30", endsAt: "09:10", breakTime: false },
    { number: null, label: "Lunch", startsAt: "09:10", endsAt: "09:40", breakTime: true },
    { number: 2, label: "Period 2", startsAt: "09:40", endsAt: "10:20", breakTime: false },
  ],
  saturday: [
    { number: 1, label: "Period 1", startsAt: "08:30", endsAt: "09:00", breakTime: false },
    { number: 2, label: "Period 2", startsAt: "09:00", endsAt: "09:30", breakTime: false },
    { number: 3, label: "Period 3", startsAt: "09:30", endsAt: "10:00", breakTime: false },
  ],
  weekdayPeriods: 2,
  saturdayPeriods: 3,
};

const SUBJECTS: SubjectLoad[] = [
  { subjectId: "maths", subjectName: "Mathematics", assignmentId: "a1", teacherId: "t1", teacherName: "Ravi", periodsPerWeek: 1, scheduled: 0 },
  { subjectId: "art", subjectName: "Art", assignmentId: null, teacherId: null, teacherName: null, periodsPerWeek: null, scheduled: 0 },
];

describe("timetable logic", () => {
  it("lists working days in week order and adds Saturday's extra periods as rows", () => {
    expect(workingDays(BELLS)).toEqual(["MONDAY", "TUESDAY", "SATURDAY"]);
    expect(gridRows(BELLS).map((r) => (r.kind === "period" ? r.number : r.label))).toEqual([1, "Lunch", 2, 3]);
    expect(hasPeriod(BELLS, "SATURDAY", 3)).toBe(true);
    expect(hasPeriod(BELLS, "MONDAY", 3)).toBe(false);
  });

  it("finds cells whose teacher is booked in another section at that time", () => {
    const grid: Grid = {
      "MONDAY-1": { subjectId: "maths", teacherId: "t1", room: "" },
      "MONDAY-2": { subjectId: "maths", teacherId: "t1", room: "" },
    };
    const busy = [
      { teacherId: "t1", day: "MONDAY" as const, period: 2, sectionId: "s2", sectionLabel: "Class 5 B", subjectName: "Mathematics" },
      { teacherId: "t9", day: "MONDAY" as const, period: 1, sectionId: "s3", sectionLabel: "Class 6 A", subjectName: "Science" },
    ];
    expect(Object.keys(teacherClashes(grid, busy))).toEqual(["MONDAY-2"]);
    expect(teacherClashes(grid, busy)["MONDAY-2"].sectionLabel).toBe("Class 5 B");
  });

  it("warns only for subjects with a weekly allowance that is exceeded", () => {
    const grid: Grid = {
      "MONDAY-1": { subjectId: "maths", teacherId: "t1", room: "" },
      "TUESDAY-1": { subjectId: "maths", teacherId: "t1", room: "" },
      "MONDAY-2": { subjectId: "art", teacherId: null, room: "" },
      "TUESDAY-2": { subjectId: "art", teacherId: null, room: "" },
    };
    expect(overLimit(grid, SUBJECTS)).toEqual([{ subjectId: "maths", subjectName: "Mathematics", scheduled: 2, allowed: 1 }]);
  });

  it("sends cells in week order with blank rooms as null, and compares grids ignoring spaces", () => {
    const grid: Grid = {
      "TUESDAY-1": { subjectId: "maths", teacherId: "t1", room: " " },
      "MONDAY-2": { subjectId: "art", teacherId: null, room: "Art room " },
    };
    expect(toCells(grid)).toEqual([
      { day: "MONDAY", period: 2, subjectId: "art", teacherId: null, room: "Art room" },
      { day: "TUESDAY", period: 1, subjectId: "maths", teacherId: "t1", room: null },
    ]);
    const same = gridFromSlots([
      { day: "TUESDAY", period: 1, subjectId: "maths", subjectName: "Mathematics", teacherId: "t1", teacherName: "Ravi", room: null },
      { day: "MONDAY", period: 2, subjectId: "art", subjectName: "Art", teacherId: null, teacherName: null, room: "Art room" },
    ]);
    expect(sameGrid(grid, same)).toBe(true);
    expect(sameGrid(grid, { ...same, "MONDAY-2": { ...same["MONDAY-2"], subjectId: "maths" } })).toBe(false);
  });

  it("checks bell rows: names, times, order and overlaps", () => {
    expect(minutes("08:30")).toBe(510);
    expect(minutes("24:00")).toBeNaN();
    expect(
      bellRowProblems([
        { label: "Period 1", startsAt: "08:30", endsAt: "09:10" },
        { label: " ", startsAt: "09:10", endsAt: "09:50" },
        { label: "Period 3", startsAt: "9:50", endsAt: "10:30" },
        { label: "Period 4", startsAt: "10:30", endsAt: "10:30" },
        { label: "Period 5", startsAt: "09:00", endsAt: "11:00" },
        { label: "Period 6", startsAt: "11:00", endsAt: "11:40" },
      ]),
    ).toEqual({ 1: "label", 2: "time", 3: "order", 4: "overlap" });
  });

  it("gives every subject of a section its own colour", () => {
    const colours = subjectColours(["Telugu", "English", "Art", "Hindi", "Maths", "Science", "Music"]);
    expect(new Set(colours.values()).size).toBe(7);
    expect(colours.get("Art")).toBe(0);
  });

  it("shows teachers their own timetable first and the set-up tabs only to managers", () => {
    expect(timetableTabs(true, false)).toEqual(["sections", "teachers", "substitutions", "bells", "assignments", "clashes"]);
    expect(timetableTabs(false, true)).toEqual(["mine", "sections", "teachers", "substitutions", "bells"]);
  });
});
