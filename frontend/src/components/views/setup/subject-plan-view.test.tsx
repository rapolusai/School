import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { SubjectPlan } from "@/lib/types";
import { ADMIN_PERMISSIONS, renderAs, TEACHER_PERMISSIONS } from "@/test/render";
import { newKey, planRows, SubjectPlanView } from "./subject-plan-view";

const { getSubjectPlan, saveSubjectPlan } = vi.hoisted(() => ({
  getSubjectPlan: vi.fn(),
  saveSubjectPlan: vi.fn(),
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, getSubjectPlan, saveSubjectPlan } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/setup/subjects",
}));

const PLAN: SubjectPlan = {
  board: "CBSE",
  schoolBoard: "CBSE",
  boards: ["CBSE", "ICSE", "STATE", "IB", "CAMBRIDGE"],
  subjects: [{ id: "eng", name: "English", code: "ENG" }],
  standard: [
    { name: "English", code: "ENG", subjectId: "eng" },
    { name: "Mathematics", code: "MAT", subjectId: null },
    { name: "Science", code: "SCI", subjectId: null },
    { name: "French", code: "FRE", subjectId: null },
    { name: "Physics", code: "PHY", subjectId: null },
  ],
  classes: [
    { id: "c1", name: "Class 1", level: 1, subjectIds: ["eng"], suggested: ["English", "Mathematics"], optional: [] },
    {
      id: "c7",
      name: "Class 7",
      level: 7,
      subjectIds: [],
      suggested: ["English", "Mathematics", "Science"],
      optional: ["French"],
    },
  ],
};

beforeEach(() => {
  getSubjectPlan.mockReset();
  saveSubjectPlan.mockReset();
  getSubjectPlan.mockResolvedValue(PLAN);
});

describe("planRows", () => {
  it("lists the school's subjects, then usual subjects some class here takes, without duplicates", () => {
    const rows = planRows(PLAN, [{ key: newKey("english "), name: "english ", code: null, isNew: true }], {});
    expect(rows.map((r) => r.name)).toEqual(["English", "Mathematics", "Science", "French"]);
    expect(rows.find((r) => r.name === "Mathematics")).toMatchObject({ key: "new:mathematics", isNew: true });
  });
});

describe("SubjectPlanView", () => {
  it("fills in the usual subjects, lets the admin change them and saves only changed classes", async () => {
    const user = userEvent.setup();
    saveSubjectPlan.mockResolvedValue({ subjectsCreated: 2, classesChanged: 2 });
    renderAs(ADMIN_PERMISSIONS, <SubjectPlanView />);

    const grid = await screen.findByTestId("subject-plan-grid");
    expect(within(grid).getByLabelText("English in Class 1")).toBeChecked();
    expect(within(grid).getByLabelText("Mathematics in Class 7")).not.toBeChecked();

    await user.click(screen.getByRole("button", { name: "Fill in usual subjects" }));
    expect(within(grid).getByLabelText("Mathematics in Class 1")).toBeChecked();
    expect(within(grid).getByLabelText("Science in Class 7")).toBeChecked();
    // Optional subjects are offered, not ticked.
    expect(within(grid).getByLabelText("French in Class 7")).not.toBeChecked();

    await user.click(within(grid).getByLabelText("Science in Class 7"));
    await user.type(screen.getByLabelText("Add another subject"), "Yoga");
    await user.click(screen.getByRole("button", { name: "Add row" }));
    await user.click(within(grid).getByLabelText("Yoga in Class 1"));
    expect(screen.getByText("2 classes changed.")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Save" }));
    await waitFor(() => expect(saveSubjectPlan).toHaveBeenCalledTimes(1));
    const body = saveSubjectPlan.mock.calls[0][0];
    expect(body.newSubjects).toEqual(
      expect.arrayContaining([
        { name: "Mathematics", code: "MAT" },
        { name: "Yoga", code: null },
      ]),
    );
    expect(body.newSubjects).toHaveLength(2);
    expect(body.classes).toEqual(
      expect.arrayContaining([
        { classId: "c1", subjectIds: ["eng"], newSubjects: expect.arrayContaining(["Mathematics", "Yoga"]) },
        { classId: "c7", subjectIds: ["eng"], newSubjects: ["Mathematics"] },
      ]),
    );
    await waitFor(() => expect(getSubjectPlan).toHaveBeenCalledTimes(2));
  });

  it("copies another class's subjects into a class", async () => {
    const user = userEvent.setup();
    renderAs(ADMIN_PERMISSIONS, <SubjectPlanView />);
    const grid = await screen.findByTestId("subject-plan-grid");
    await user.selectOptions(screen.getByLabelText("Copy subjects into Class 7 from another class"), "c1");
    expect(within(grid).getByLabelText("English in Class 7")).toBeChecked();
    await user.click(screen.getByRole("button", { name: "Undo changes" }));
    expect(within(grid).getByLabelText("English in Class 7")).not.toBeChecked();
  });

  it("refuses a subject that is already in the list", async () => {
    const user = userEvent.setup();
    renderAs(ADMIN_PERMISSIONS, <SubjectPlanView />);
    await screen.findByTestId("subject-plan-grid");
    await user.type(screen.getByLabelText("Add another subject"), "  english");
    await user.click(screen.getByRole("button", { name: "Add row" }));
    expect(screen.getByText("english is already in the list.")).toBeInTheDocument();
  });

  it("is read-only for teachers", async () => {
    renderAs(TEACHER_PERMISSIONS, <SubjectPlanView />);
    const grid = await screen.findByTestId("subject-plan-grid");
    expect(within(grid).getByLabelText("English in Class 1")).toBeDisabled();
    expect(screen.queryByRole("button", { name: "Fill in usual subjects" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Save" })).not.toBeInTheDocument();
  });

  it("asks for classes first when the school has none", async () => {
    getSubjectPlan.mockResolvedValue({ ...PLAN, classes: [] });
    renderAs(ADMIN_PERMISSIONS, <SubjectPlanView />);
    expect(await screen.findByRole("link", { name: "Import from Excel" })).toHaveAttribute("href", "/app/setup/import");
  });
});
