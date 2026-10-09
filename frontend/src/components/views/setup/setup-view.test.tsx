import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import type { AcademicYear, ClassView } from "@/lib/types";
import { ADMIN_PERMISSIONS, renderAs, TEACHER_PERMISSIONS } from "@/test/render";
import { validateClass, validateSection } from "./classes-panel";
import { SetupView } from "./setup-view";
import { validateYear } from "./years-panel";

const { listYears, createYear, deleteYear, listClasses, listTeachers, listSubjects, getSchoolProfile } = vi.hoisted(() => ({
  listYears: vi.fn(),
  createYear: vi.fn(),
  deleteYear: vi.fn(),
  listClasses: vi.fn(),
  listTeachers: vi.fn(),
  listSubjects: vi.fn(),
  getSchoolProfile: vi.fn(),
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, listYears, createYear, deleteYear, listClasses, listTeachers, listSubjects, getSchoolProfile },
  };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/setup",
}));

const YEARS: AcademicYear[] = [
  { id: "y2", name: "2026-27", startsOn: "2026-06-01", endsOn: "2027-03-31", current: true },
  { id: "y1", name: "2025-26", startsOn: "2025-06-01", endsOn: "2026-03-31", current: false },
];

const CLASSES: ClassView[] = [
  {
    id: "c5",
    name: "Class 5",
    displayOrder: 5,
    subjects: [{ id: "m", name: "Mathematics", code: "MATH" }],
    sections: [
      {
        id: "s5a",
        classId: "c5",
        className: "Class 5",
        name: "A",
        capacity: 40,
        classTeacher: { id: "t1", name: "Ravi Kumar" },
        studentCount: 32,
      },
    ],
  },
];

beforeEach(() => {
  listYears.mockResolvedValue(YEARS);
  listClasses.mockResolvedValue(CLASSES);
  listTeachers.mockResolvedValue([{ id: "t1", name: "Ravi Kumar" }]);
  listSubjects.mockResolvedValue([]);
  getSchoolProfile.mockReturnValue(new Promise(() => {}));
  createYear.mockReset();
  deleteYear.mockReset();
});

describe("setup validation (mirrors the API)", () => {
  it("checks academic years", () => {
    expect(validateYear({ name: "", startsOn: "2026-06-01", endsOn: "2027-03-31", current: false })).toEqual({});
    expect(validateYear({ name: "", startsOn: "", endsOn: "", current: false })).toEqual({
      name: "validation.required",
      startsOn: "validation.date",
      endsOn: "validation.date",
    });
    expect(validateYear({ name: "x", startsOn: "2026-06-01", endsOn: "2026-06-01", current: false })).toEqual({
      endsOn: "setup.years.endAfterStart",
    });
  });

  it("checks classes and sections", () => {
    expect(validateClass({ name: "Class 5", displayOrder: "" })).toEqual({});
    expect(validateClass({ name: " ", displayOrder: "1000" })).toEqual({
      name: "validation.required",
      displayOrder: "setup.classes.orderRange",
    });
    expect(validateSection({ name: "A", capacity: "40", classTeacherId: "" })).toEqual({});
    expect(validateSection({ name: "A", capacity: "0", classTeacherId: "" })).toEqual({
      capacity: "setup.sections.capacityRange",
    });
  });
});

describe("SetupView", () => {
  it("lists academic years with the current one marked", async () => {
    renderAs(ADMIN_PERMISSIONS, <SetupView />);
    const list = await screen.findByTestId("years-list");
    expect(within(list).getByText("1 Jun 2026 to 31 Mar 2027")).toBeInTheDocument();
    expect(within(list).getByText("Current")).toBeInTheDocument();
    expect(screen.getByText("Current academic year 2026-27")).toBeInTheDocument();
    expect(within(list).getByRole("button", { name: "Make current" })).toBeInTheDocument();
    expect(within(list).getByRole("button", { name: "Delete 2025-26" })).toBeInTheDocument();
    expect(within(list).queryByRole("button", { name: "Delete 2026-27" })).not.toBeInTheDocument();
  });

  it("validates a new year and names it from the start date", async () => {
    const user = userEvent.setup();
    createYear.mockResolvedValue({ id: "y3", name: "2027-28", startsOn: "2027-06-01", endsOn: "2028-03-31", current: false });
    renderAs(ADMIN_PERMISSIONS, <SetupView />);
    await screen.findByTestId("years-list");
    await user.click(screen.getByRole("button", { name: "Add year" }));
    const dialog = screen.getByRole("dialog", { name: "Add year" });

    await user.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(createYear).not.toHaveBeenCalled();
    expect(within(dialog).getAllByText("Enter a valid date.")).toHaveLength(2);

    fireEvent.change(within(dialog).getByLabelText("Starts on"), { target: { value: "2027-06-01" } });
    fireEvent.change(within(dialog).getByLabelText("Ends on"), { target: { value: "2027-05-01" } });
    await user.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(within(dialog).getByText("The end date must be after the start date.")).toBeInTheDocument();

    fireEvent.change(within(dialog).getByLabelText("Ends on"), { target: { value: "2028-03-31" } });
    expect(within(dialog).getByLabelText("Name")).toHaveAttribute("placeholder", "2027-28");
    await user.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(createYear).toHaveBeenCalledWith({
      name: "2027-28",
      startsOn: "2027-06-01",
      endsOn: "2028-03-31",
      current: false,
    });
    expect(await screen.findByText("2027-28 saved.")).toBeInTheDocument();
  });

  it("shows the API's reason when a year overlaps another", async () => {
    const user = userEvent.setup();
    createYear.mockRejectedValue(
      new ApiError({ status: 409, title: "Conflict", detail: "This year overlaps 2026-27." }),
    );
    renderAs(ADMIN_PERMISSIONS, <SetupView />);
    await screen.findByTestId("years-list");
    await user.click(screen.getByRole("button", { name: "Add year" }));
    const dialog = screen.getByRole("dialog", { name: "Add year" });
    fireEvent.change(within(dialog).getByLabelText("Starts on"), { target: { value: "2026-09-01" } });
    fireEvent.change(within(dialog).getByLabelText("Ends on"), { target: { value: "2027-08-31" } });
    await user.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(await within(dialog).findByText("This year overlaps 2026-27.")).toBeInTheDocument();
  });

  it("deletes a past year after confirming", async () => {
    const user = userEvent.setup();
    deleteYear.mockResolvedValue(undefined);
    renderAs(ADMIN_PERMISSIONS, <SetupView />);
    await screen.findByTestId("years-list");
    await user.click(screen.getByRole("button", { name: "Delete 2025-26" }));
    const dialog = screen.getByRole("dialog", { name: "Delete 2025-26?" });
    await user.click(within(dialog).getByRole("button", { name: "Delete" }));
    expect(deleteYear).toHaveBeenCalledWith("y1");
    expect(await screen.findByText("2025-26 deleted.")).toBeInTheDocument();
  });

  it("is read-only for teachers", async () => {
    renderAs(TEACHER_PERMISSIONS, <SetupView />, ["TEACHER"]);
    const list = await screen.findByTestId("years-list");
    expect(screen.queryByRole("button", { name: "Add year" })).not.toBeInTheDocument();
    expect(within(list).queryByRole("button", { name: "Make current" })).not.toBeInTheDocument();

    await userEvent.setup().click(screen.getByRole("tab", { name: "Classes & sections" }));
    await screen.findByTestId("classes-list");
    expect(screen.queryByRole("button", { name: "Add class" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add section" })).not.toBeInTheDocument();
    expect(listTeachers).not.toHaveBeenCalled();
  });

  it("shows sections with their class teacher and seats, and moves between tabs with the arrow keys", async () => {
    const user = userEvent.setup();
    renderAs(ADMIN_PERMISSIONS, <SetupView />);
    await screen.findByTestId("years-list");
    const yearsTab = screen.getByRole("tab", { name: "Academic years" });
    expect(yearsTab).toHaveAttribute("aria-selected", "true");

    yearsTab.focus();
    await user.keyboard("{ArrowRight}");
    const classesTab = screen.getByRole("tab", { name: "Classes & sections" });
    expect(classesTab).toHaveAttribute("aria-selected", "true");
    expect(classesTab).toHaveFocus();

    const classCard = within(await screen.findByTestId("classes-list")).getByRole("article", { name: "Class 5" });
    expect(within(classCard).getByText("Section A")).toBeInTheDocument();
    expect(within(classCard).getByText("Class teacher Ravi Kumar")).toBeInTheDocument();
    expect(within(classCard).getByText("32 of 40")).toBeInTheDocument();
    expect(within(classCard).getByText("Mathematics")).toBeInTheDocument();
    expect(within(classCard).getByText("1 section · 1 subject")).toBeInTheDocument();
    await waitFor(() => expect(listTeachers).toHaveBeenCalled());

    await user.keyboard("{End}");
    expect(screen.getByRole("tab", { name: "School profile" })).toHaveAttribute("aria-selected", "true");
  });
});
