import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Child } from "@/lib/types";
import { record, visit } from "@/test/health-fixtures";
import { renderAs } from "@/test/render";
import { useApiData } from "@/lib/use-api-data";
import { healthApi } from "@/lib/health-api";
import { FamilyHealthCard, FamilyHealthView, visitToday } from "./family-health";

const m = vi.hoisted(() => ({
  myChildren: vi.fn(),
  childHealth: vi.fn(),
  childConsent: vi.fn(),
  childAddItem: vi.fn(),
  childUpdateItem: vi.fn(),
  childRemoveItem: vi.fn(),
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, myChildren: m.myChildren } };
});
vi.mock("@/lib/health-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/health-api")>();
  return { ...actual, healthApi: { ...actual.healthApi, ...m } };
});
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/family/health",
}));

const PARENT = ["dashboard.view", "child.view"];

const ASHA: Child = {
  id: "s1",
  fullName: "Asha Rao",
  admissionNo: "AKS/2026/001",
  status: "ACTIVE",
  className: "Class 5",
  sectionName: "A",
  rollNo: 3,
  classTeacherName: "Tara Teacher",
  academicYearName: "2026-27",
};

beforeEach(() => {
  vi.clearAllMocks();
  m.myChildren.mockResolvedValue([ASHA]);
  m.childHealth.mockResolvedValue(record());
  m.childConsent.mockResolvedValue(record());
  m.childUpdateItem.mockResolvedValue(record().allergies[0]);
  m.childAddItem.mockResolvedValue(record().conditions[0]);
});

describe("visitToday", () => {
  it("finds a visit that started or ended today", () => {
    expect(visitToday(record(), "2026-10-12")?.id).toBe("v1");
    expect(visitToday(record(), "2026-10-13")).toBeUndefined();
    expect(visitToday(undefined, "2026-10-12")).toBeUndefined();
    // Just after midnight in India the instant is still the day before in UTC.
    const lateNight = { ...record(), visits: [{ ...record().visits[0], timeIn: "2026-10-29T19:42:00Z", timeOut: null }] };
    expect(visitToday(lateNight, "2026-10-30")?.id).toBe("v1");
    expect(visitToday(lateNight, "2026-10-29")).toBeUndefined();
  });
});

describe("FamilyHealthView", () => {
  it("shows the child's allergies, conditions, the school's medication and the infirmary visits", async () => {
    renderAs(PARENT, <FamilyHealthView />, ["PARENT"]);
    await screen.findByTestId("family-health");
    expect(m.childHealth).toHaveBeenCalledWith("s1");
    expect(within(screen.getByTestId("items-ALLERGY")).getByText("Peanuts")).toBeInTheDocument();
    expect(within(screen.getByTestId("items-ALLERGY")).getByText("Severe")).toBeInTheDocument();
    expect(within(screen.getByTestId("items-CONDITION")).getByText("Asthma")).toBeInTheDocument();
    // The school's entries are read-only for the parent.
    expect(within(screen.getByTestId("items-MEDICATION")).getByText("Salbutamol inhaler")).toBeInTheDocument();
    expect(screen.queryByTestId("add-MEDICATION")).toBeNull();
    expect(within(screen.getByTestId("family-visits")).getByText("Sent home")).toBeInTheDocument();
  });

  it("lets the parent change an allergy's severity and give consent", async () => {
    const user = userEvent.setup();
    renderAs(PARENT, <FamilyHealthView />, ["PARENT"]);
    await screen.findByTestId("family-health");
    await user.click(within(screen.getByTestId("items-ALLERGY")).getByRole("button", { name: "Edit" }));
    const form = screen.getByTestId("item-form");
    expect(within(form).getByLabelText("Kind")).toBeDisabled();
    await user.selectOptions(within(form).getByLabelText("Severity"), "MODERATE");
    await user.click(screen.getByTestId("item-save"));
    await waitFor(() => expect(m.childUpdateItem).toHaveBeenCalledWith("s1", "i1", expect.objectContaining({ kind: "ALLERGY", name: "Peanuts", severity: "MODERATE" })));
    expect(m.childHealth).toHaveBeenCalledTimes(2);
    await user.click(within(screen.getByTestId("consent-card")).getByLabelText("The school may take the child to a hospital"));
    await waitFor(() => expect(m.childConsent).toHaveBeenCalledWith("s1", { firstAid: true, hospital: true }));
  });

  it("adds a condition, offering only allergies and conditions", async () => {
    const user = userEvent.setup();
    renderAs(PARENT, <FamilyHealthView />, ["PARENT"]);
    await screen.findByTestId("family-health");
    await user.click(screen.getByTestId("add-CONDITION"));
    const form = screen.getByTestId("item-form");
    expect(within(form).getByLabelText("Kind")).toHaveValue("CONDITION");
    expect(within(form).getByLabelText("Kind").querySelectorAll("option")).toHaveLength(1);
    await user.type(within(form).getByLabelText("Name"), "Eczema");
    await user.click(screen.getByTestId("item-save"));
    await waitFor(() => expect(m.childAddItem).toHaveBeenCalledWith("s1", { kind: "CONDITION", name: "Eczema", detail: null, severity: null, itemDate: null }));
  });
});

function Card({ today }: { today: string }) {
  const data = useApiData("health:child:s1", () => healthApi.childHealth("s1"));
  return <FamilyHealthCard data={data} today={today} href="/app/family/health" />;
}

describe("FamilyHealthCard", () => {
  it("shows on the family home only when the child was in the infirmary today", async () => {
    m.childHealth.mockResolvedValue(record({ visits: [visit({ outcome: "SENT_HOME", timeOut: "2026-10-12T06:00:00Z" })] }));
    renderAs(PARENT, <Card today="2026-10-12" />, ["PARENT"]);
    const card = await screen.findByTestId("family-health-card");
    expect(card).toHaveTextContent("Fever and headache");
    expect(within(card).getByText("Sent home")).toBeInTheDocument();
    expect(within(card).getByRole("link")).toHaveAttribute("href", "/app/family/health");
  });

  it("stays away on another day", async () => {
    renderAs(PARENT, <Card today="2026-10-13" />, ["PARENT"]);
    await waitFor(() => expect(m.childHealth).toHaveBeenCalled());
    expect(screen.queryByTestId("family-health-card")).toBeNull();
  });
});
