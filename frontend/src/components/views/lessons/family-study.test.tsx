import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Child } from "@/lib/types";
import { study, studyTest } from "@/test/lessons-fixtures";
import { renderAs } from "@/test/render";
import { FamilyStudyView } from "./family-study";

const m = vi.hoisted(() => ({ myChildren: vi.fn(), childStudy: vi.fn(), myStudy: vi.fn() }));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, myChildren: m.myChildren } };
});

vi.mock("@/lib/lessons-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/lessons-api")>();
  return { ...actual, lessonsApi: { ...actual.lessonsApi, childStudy: m.childStudy, myStudy: m.myStudy } };
});

const FAMILY = ["dashboard.view", "child.view"];

const ARJUN: Child = {
  id: "st1",
  fullName: "Arjun Sharma",
  admissionNo: "AKS/2026/001",
  status: "ACTIVE",
  className: "Class 5",
  sectionName: "A",
  rollNo: 1,
  classTeacherName: "Ravi Kumar",
  academicYearName: "2026-27",
};
const DIYA: Child = { ...ARJUN, id: "st2", fullName: "Diya Sharma", className: "Class 2", rollNo: 4 };

describe("FamilyStudyView", () => {
  beforeEach(() => {
    for (const mock of Object.values(m)) mock.mockReset();
    m.myChildren.mockResolvedValue([ARJUN, DIYA]);
    m.childStudy.mockImplementation(async (id: string) => study(id));
    m.myStudy.mockResolvedValue(study("st1"));
  });

  afterEach(() => {
    window.localStorage.clear();
  });

  it("shows a parent the child's tests, released scores and material, without opening the tests", async () => {
    const user = userEvent.setup();
    renderAs(FAMILY, <FamilyStudyView />, ["PARENT"]);
    const tests = await screen.findByTestId("study-tests");
    expect(m.childStudy).toHaveBeenCalledWith("st1");
    expect(screen.getByRole("heading", { level: 1, name: "Study" })).toBeInTheDocument();

    const rows = within(tests).getAllByTestId("study-test");
    expect(rows.map((r) => within(r).getByText(/quiz|test/).textContent)).toEqual(["Fractions quiz", "Place value test"]);
    expect(rows[0].tagName).not.toBe("A");
    expect(within(rows[0]).getByTestId("study-test-status")).toHaveTextContent("0 of 1 attempts used");
    expect(within(rows[1]).getByTestId("study-test-status")).toHaveTextContent("Score 3.5 / 4 · 87.5%");

    const material = screen.getByTestId("study-material");
    expect(within(material).getByText("Fractions notes")).toBeInTheDocument();
    expect(within(material).getByText("Read pages 40 to 45.")).toBeInTheDocument();

    await user.click(screen.getByRole("radio", { name: "Diya Sharma" }));
    expect(m.childStudy).toHaveBeenCalledWith("st2");
    expect(await screen.findByText("Diya Sharma · Class 2 A")).toBeInTheDocument();
  });

  it("lets a student open their tests", async () => {
    m.myStudy.mockResolvedValue(study("st1", { tests: [studyTest({ state: "UPCOMING", canStart: false })], material: [] }));
    renderAs(["dashboard.view"], <FamilyStudyView />, ["STUDENT"]);
    const row = await screen.findByTestId("study-test");
    expect(row).toHaveAttribute("href", "/app/family/study/tests/ot1");
    expect(row).toHaveTextContent(/Opens /);
    expect(screen.getByText("No study material yet.")).toBeInTheDocument();
    expect(m.childStudy).not.toHaveBeenCalled();
  });
});
