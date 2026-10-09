import { screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import type { Child } from "@/lib/types";
import { ARJUN_FEES } from "@/test/fees-fixtures";
import { ADMIN_PERMISSIONS, renderAs } from "@/test/render";
import { DashboardView } from "./dashboard-view";
import { MyChildren, MyClass } from "./my-children";

const { myChildren, myStudentRecord, childFees } = vi.hoisted(() => ({
  myChildren: vi.fn(),
  myStudentRecord: vi.fn(),
  childFees: vi.fn(),
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, myChildren, myStudentRecord } };
});

vi.mock("@/lib/fees-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/fees-api")>();
  return { ...actual, feesApi: { ...actual.feesApi, childFees } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/dashboard",
}));

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

const DIYA: Child = {
  ...ARJUN,
  id: "st2",
  fullName: "Diya Sharma",
  admissionNo: "AKS/2026/002",
  className: "Class 2",
  rollNo: 4,
  classTeacherName: null,
};

const PARENT = ["dashboard.view", "child.view"];

beforeEach(() => {
  myChildren.mockResolvedValue([ARJUN, DIYA]);
  myStudentRecord.mockResolvedValue(ARJUN);
  childFees.mockImplementation((id: string) =>
    Promise.resolve(id === "st1" ? ARJUN_FEES : { ...ARJUN_FEES, instalments: [], receipts: [] }),
  );
});

describe("MyChildren", () => {
  it("shows one card per child with class, roll number and class teacher", async () => {
    renderAs(PARENT, <MyChildren />, ["PARENT"]);
    const cards = await screen.findAllByTestId("child-card");
    expect(cards).toHaveLength(2);
    const arjun = screen.getByRole("article", { name: "Arjun Sharma" });
    expect(within(arjun).getByText("Class 5 A")).toBeInTheDocument();
    expect(within(arjun).getByText("Ravi Kumar")).toBeInTheDocument();
    expect(within(arjun).getByText("2026-27")).toBeInTheDocument();
    const diya = screen.getByRole("article", { name: "Diya Sharma" });
    expect(within(diya).getByText("Class 2 A")).toBeInTheDocument();
    expect(within(diya).getByText("—")).toBeInTheDocument();
  });

  it("shows each child's fees on their card", async () => {
    renderAs(PARENT, <MyChildren />, ["PARENT"]);
    const arjun = await screen.findByRole("article", { name: "Arjun Sharma" });
    expect(await within(arjun).findByText("₹11,600 overdue")).toBeInTheDocument();
    expect(within(arjun).getByRole("link", { name: "Pay fees" })).toHaveAttribute("href", "/app/children/st1/fees");
    const diya = screen.getByRole("article", { name: "Diya Sharma" });
    expect(await within(diya).findByText("No fees have been set yet.")).toBeInTheDocument();
  });

  it("explains when no child is linked yet", async () => {
    myChildren.mockResolvedValue([]);
    renderAs(PARENT, <MyChildren />, ["PARENT"]);
    expect(await screen.findByText(/No children are linked to your sign-in yet/)).toBeInTheDocument();
  });
});

describe("MyClass", () => {
  it("shows the student's own class", async () => {
    renderAs(["dashboard.view", "child.view"], <MyClass />, ["STUDENT"]);
    expect(await screen.findByRole("heading", { name: "My class" })).toBeInTheDocument();
    expect(screen.getByRole("article", { name: "Arjun Sharma" })).toBeInTheDocument();
  });

  it("explains a sign-in that is not linked to a record", async () => {
    myStudentRecord.mockRejectedValue(new ApiError({ status: 404, title: "Not found" }));
    renderAs(["dashboard.view", "child.view"], <MyClass />, ["STUDENT"]);
    expect(await screen.findByText(/not linked to a student record yet/)).toBeInTheDocument();
  });
});

describe("Dashboard", () => {
  it("shows a parent their children", async () => {
    renderAs(PARENT, <DashboardView />, ["PARENT"]);
    expect(await screen.findAllByTestId("child-card")).toHaveLength(2);
    expect(myStudentRecord).not.toHaveBeenCalled();
  });

  it("does not show school staff a My children section", () => {
    renderAs(
      ADMIN_PERMISSIONS.filter((p) => !p.startsWith("users") && !p.startsWith("roles") && !p.startsWith("audit")),
      <DashboardView />,
    );
    expect(screen.queryByRole("heading", { name: "My children" })).not.toBeInTheDocument();
    expect(myChildren).not.toHaveBeenCalled();
    expect(childFees).not.toHaveBeenCalled();
  });
});
