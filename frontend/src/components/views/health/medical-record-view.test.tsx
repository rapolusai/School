import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { record } from "@/test/health-fixtures";
import { renderAs } from "@/test/render";
import { chartPoints, growthProblems, itemProblems, MedicalRecordView, recordProblems } from "./medical-record-view";

const m = vi.hoisted(() => ({
  record: vi.fn(),
  saveRecord: vi.fn(),
  saveConsent: vi.fn(),
  addItem: vi.fn(),
  updateItem: vi.fn(),
  removeItem: vi.fn(),
  addGrowth: vi.fn(),
  removeGrowth: vi.fn(),
}));

vi.mock("@/lib/health-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/health-api")>();
  return { ...actual, healthApi: { ...actual.healthApi, ...m } };
});
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/health/students/s1",
}));

const NURSE = ["dashboard.view", "students.read", "health.read", "health.manage"];
const TEACHER = ["dashboard.view", "students.read", "health.read"];

beforeEach(() => {
  vi.clearAllMocks();
  m.record.mockResolvedValue(record());
  m.saveRecord.mockResolvedValue(record({ bloodGroup: "A+" }));
  m.saveConsent.mockResolvedValue(record());
  m.addItem.mockResolvedValue(record().allergies[0]);
});

describe("chartPoints", () => {
  it("places measurements by age and value, leaving out the missing series", () => {
    const points = chartPoints(record().chart.points, "heightCm");
    expect(points.map((p) => p.label)).toEqual(["2026-06-10", "2026-09-10"]);
    expect(points[0].x).toBe(0);
    expect(points[1].x).toBe(100);
    // The higher value sits higher on the chart (a smaller y).
    expect(points[1].y).toBeLessThan(points[0].y);
    expect(points.every((p) => p.y >= 0 && p.y <= 100)).toBe(true);
    expect(chartPoints([{ date: "2026-01-01", ageMonths: 0, heightCm: null, weightKg: 20 }], "heightCm")).toEqual([]);
    // A single point sits in the middle.
    expect(chartPoints([{ date: "2026-01-01", ageMonths: 100, heightCm: 120, weightKg: null }], "heightCm")[0].x).toBe(50);
  });
});

describe("the client-side checks", () => {
  it("mirror the API: a severity for an allergy, a date for a vaccination, a phone that is Indian", () => {
    expect(itemProblems({ kind: "ALLERGY", name: "Peanuts", detail: "", severity: "", itemDate: "" })).toEqual({ severity: "health.v.severity" });
    expect(itemProblems({ kind: "VACCINATION", name: "MMR", detail: "", severity: "", itemDate: "" })).toEqual({ itemDate: "health.v.date" });
    expect(itemProblems({ kind: "CONDITION", name: " ", detail: "", severity: "", itemDate: "" })).toEqual({ name: "health.v.name" });
    expect(itemProblems({ kind: "ALLERGY", name: "Peanuts", detail: "", severity: "SEVERE", itemDate: "" })).toEqual({});
    expect(recordProblems({ bloodGroup: "", doctorName: "", doctorPhone: "12", insuranceProvider: "", insurancePolicyNo: "", notes: "" })).toEqual({ doctorPhone: "health.v.phone" });
    expect(growthProblems({ measuredOn: "2026-10-12", heightCm: "", weightKg: "" }, "2026-10-12")).toEqual({ heightCm: "health.v.growth" });
    expect(growthProblems({ measuredOn: "2026-10-13", heightCm: "300", weightKg: "0" }, "2026-10-12")).toEqual({
      measuredOn: "health.v.futureDate",
      heightCm: "health.v.height",
      weightKg: "health.v.weight",
    });
  });
});

describe("MedicalRecordView", () => {
  it("shows the record with the masked policy number, the allergy badge, the chart and the visits", async () => {
    renderAs(NURSE, <MedicalRecordView studentId="s1" />);
    expect(await screen.findByRole("heading", { level: 1 })).toHaveTextContent("Asha Rao");
    expect(m.record).toHaveBeenCalledWith("s1", "student_medical");
    const head = screen.getByTestId("record-head");
    expect(head).toHaveTextContent("O+");
    expect(head).toHaveTextContent("Star Health · ••••8812");
    expect(head).toHaveTextContent("Lata Rao · 98765•••01");
    expect(within(screen.getByTestId("record-allergy-badges")).getByText("Peanuts")).toBeInTheDocument();
    expect(screen.getByTestId("height-chart").querySelector("polyline")).not.toBeNull();
    expect(screen.getAllByTestId("growth-row")).toHaveLength(2);
    expect(within(screen.getByTestId("record-visits")).getByText("Sent home")).toBeInTheDocument();
    expect(within(screen.getByTestId("items-ALLERGY")).getByText("By the parent")).toBeInTheDocument();
  });

  it("lets the nurse edit the head of the record without ever showing the full policy number", async () => {
    const user = userEvent.setup();
    renderAs(NURSE, <MedicalRecordView studentId="s1" />);
    await user.click(await screen.findByTestId("record-edit"));
    const form = screen.getByTestId("record-form");
    expect(within(form).getByLabelText("Policy number")).toHaveValue("");
    expect(form).toHaveTextContent("On file: ••••8812");
    await user.selectOptions(within(form).getByLabelText("Blood group"), "A+");
    await user.click(screen.getByTestId("record-save"));
    await waitFor(() => expect(m.saveRecord).toHaveBeenCalledWith("s1", expect.objectContaining({ bloodGroup: "A+", insurancePolicyNo: null })));
    expect(m.record).toHaveBeenCalledTimes(2);
  });

  it("adds an allergy with a severity and toggles the consent", async () => {
    const user = userEvent.setup();
    renderAs(NURSE, <MedicalRecordView studentId="s1" />);
    await user.click(await screen.findByTestId("add-ALLERGY"));
    const form = screen.getByTestId("item-form");
    await user.type(within(form).getByLabelText("Name"), "Dust");
    await user.click(screen.getByTestId("item-save"));
    expect(await within(form).findByText("Choose the severity.")).toBeInTheDocument();
    expect(m.addItem).not.toHaveBeenCalled();
    await user.selectOptions(within(form).getByLabelText("Severity"), "MILD");
    await user.click(screen.getByTestId("item-save"));
    await waitFor(() => expect(m.addItem).toHaveBeenCalledWith("s1", { kind: "ALLERGY", name: "Dust", detail: null, severity: "MILD", itemDate: null }));
    await user.click(within(screen.getByTestId("consent-card")).getByLabelText("The school may take the child to a hospital"));
    await waitFor(() => expect(m.saveConsent).toHaveBeenCalledWith("s1", { firstAid: true, hospital: true }));
  });

  it("is read-only for a class teacher and says so when the student is outside their classes", async () => {
    renderAs(TEACHER, <MedicalRecordView studentId="s1" />);
    expect(await screen.findByRole("heading", { level: 1 })).toHaveTextContent("Asha Rao");
    expect(screen.queryByTestId("record-edit")).toBeNull();
    expect(screen.queryByTestId("add-ALLERGY")).toBeNull();
    expect(screen.getByTestId("consent-card").querySelector("input")).toBeDisabled();
  });
});
