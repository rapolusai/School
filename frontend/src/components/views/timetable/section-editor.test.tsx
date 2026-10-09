import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import type { BellSchedule, SectionTimetable } from "@/lib/types";
import { renderAs } from "@/test/render";
import { SectionEditor } from "./section-editor";

const { saveSection } = vi.hoisted(() => ({ saveSection: vi.fn() }));

vi.mock("@/lib/timetable-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/timetable-api")>();
  return { ...actual, timetableApi: { ...actual.timetableApi, saveSection } };
});

const MANAGER = ["dashboard.view", "timetable.read", "timetable.manage", "academics.read"];

const BELLS: BellSchedule = {
  workingDays: ["MONDAY", "TUESDAY"],
  saturdaySchedule: false,
  weekday: [
    { number: 1, label: "Period 1", startsAt: "08:30", endsAt: "09:10", breakTime: false },
    { number: null, label: "Short break", startsAt: "09:10", endsAt: "09:25", breakTime: true },
    { number: 2, label: "Period 2", startsAt: "09:25", endsAt: "10:05", breakTime: false },
  ],
  saturday: [],
  weekdayPeriods: 2,
  saturdayPeriods: 0,
};

function view(overrides: Partial<SectionTimetable> = {}): SectionTimetable {
  return {
    sectionId: "s5a",
    label: "Class 5 A",
    classId: "c5",
    className: "Class 5",
    sectionName: "A",
    classTeacherName: "Ravi Kumar",
    academicYear: { id: "y1", name: "2026-27", startsOn: "2026-06-01", endsOn: "2027-03-31" },
    bells: BELLS,
    slots: [
      {
        day: "MONDAY",
        period: 1,
        subjectId: "maths",
        subjectName: "Mathematics",
        teacherId: "t-ravi",
        teacherName: "Ravi Kumar",
        room: "Room 12",
      },
    ],
    subjects: [
      {
        subjectId: "english",
        subjectName: "English",
        assignmentId: "a2",
        teacherId: "t-kavitha",
        teacherName: "Kavitha Menon",
        periodsPerWeek: 1,
        scheduled: 0,
      },
      {
        subjectId: "maths",
        subjectName: "Mathematics",
        assignmentId: "a1",
        teacherId: "t-ravi",
        teacherName: "Ravi Kumar",
        periodsPerWeek: 3,
        scheduled: 1,
      },
    ],
    // Ravi Kumar teaches Class 5 B in Monday's second period.
    busy: [
      {
        teacherId: "t-ravi",
        day: "MONDAY",
        period: 2,
        sectionId: "s5b",
        sectionLabel: "Class 5 B",
        subjectName: "Mathematics",
      },
    ],
    clashes: [],
    warnings: [],
    canEdit: true,
    ...overrides,
  };
}

beforeEach(() => {
  saveSection.mockImplementation((sectionId: string) => Promise.resolve(view({ sectionId })));
});

describe("SectionEditor", () => {
  it("warns about a teacher clash while editing, refuses to save it, and saves once it is fixed", async () => {
    const user = userEvent.setup();
    const onSaved = vi.fn();
    renderAs(MANAGER, <SectionEditor view={view()} onSaved={onSaved} />);

    const save = screen.getByTestId("timetable-save");
    expect(save).toBeDisabled();
    expect(screen.getByTestId("editor-status")).toHaveTextContent("No clashes.");

    const cell = screen.getByLabelText("Monday, period 2");
    await user.selectOptions(cell, "Mathematics");
    const box = screen.getByTestId("cell-MONDAY-2");
    expect(within(box).getByText("Ravi Kumar is teaching Class 5 B then")).toBeInTheDocument();
    expect(cell).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByTestId("editor-status")).toHaveTextContent("1 period clashes with another section.");

    await user.click(save);
    expect(saveSection).not.toHaveBeenCalled();
    expect(screen.getByRole("alert")).toHaveTextContent("Fix the clash marked in red before saving.");

    // Give the period to English instead: its teacher is free, and the warning goes away.
    await user.selectOptions(cell, "English");
    expect(within(box).queryByText(/is teaching/)).not.toBeInTheDocument();
    expect(within(box).getByText("Kavitha Menon")).toBeInTheDocument();
    await user.type(screen.getByLabelText("Room for Monday, period 2"), "Room 14");

    await user.click(save);
    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(saveSection).toHaveBeenCalledWith("s5a", [
      { day: "MONDAY", period: 1, subjectId: "maths", teacherId: "t-ravi", room: "Room 12" },
      { day: "MONDAY", period: 2, subjectId: "english", teacherId: "t-kavitha", room: "Room 14" },
    ]);
    expect(await screen.findByText("Timetable saved for Class 5 A.")).toBeInTheDocument();
  });

  it("marks the cells the API names when it refuses a clash (409)", async () => {
    const user = userEvent.setup();
    saveSection.mockRejectedValue(
      new ApiError({
        status: 409,
        title: "Teacher clash",
        detail: "Kavitha Menon is already teaching Class 6 A on Tuesday, period 1.",
        errors: { "TUESDAY-1": "Kavitha Menon is teaching Class 6 A then." },
      }),
    );
    const onSaved = vi.fn();
    renderAs(MANAGER, <SectionEditor view={view()} onSaved={onSaved} />);

    await user.selectOptions(screen.getByLabelText("Tuesday, period 1"), "English");
    await user.click(screen.getByTestId("timetable-save"));

    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Kavitha Menon is already teaching Class 6 A on Tuesday, period 1.",
    );
    const box = screen.getByTestId("cell-TUESDAY-1");
    expect(within(box).getByText("Kavitha Menon is teaching Class 6 A then.")).toBeInTheDocument();
    expect(screen.getByLabelText("Tuesday, period 1")).toHaveAttribute("aria-invalid", "true");
    expect(onSaved).not.toHaveBeenCalled();

    // Changing the cell clears its mark.
    await user.selectOptions(screen.getByLabelText("Tuesday, period 1"), "Free");
    expect(within(box).queryByText("Kavitha Menon is teaching Class 6 A then.")).not.toBeInTheDocument();
  });

  it("warns, without blocking, when a subject gets more periods than planned", async () => {
    const user = userEvent.setup();
    renderAs(MANAGER, <SectionEditor view={view()} onSaved={vi.fn()} />);

    await user.selectOptions(screen.getByLabelText("Tuesday, period 1"), "English");
    expect(screen.queryByText(/English has/)).not.toBeInTheDocument();
    await user.selectOptions(screen.getByLabelText("Tuesday, period 2"), "English");

    expect(
      screen.getByText("English has 2 periods this week; 1 are planned. You can still save."),
    ).toBeInTheDocument();
    expect(screen.getByTestId("editor-status")).toHaveTextContent(
      "No clashes. Some subjects have more periods than planned.",
    );
    await user.click(screen.getByTestId("timetable-save"));
    await waitFor(() => expect(saveSection).toHaveBeenCalledTimes(1));
  });
});
