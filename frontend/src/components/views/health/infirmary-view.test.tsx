import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { InfirmaryDay } from "@/lib/types";
import { visit } from "@/test/health-fixtures";
import { renderAs } from "@/test/render";
import { InfirmaryView, visitProblems } from "./infirmary-view";

const m = vi.hoisted(() => ({
  day: vi.fn(),
  people: vi.fn(),
  medicines: vi.fn(),
  addVisit: vi.fn(),
  closeVisit: vi.fn(),
}));

vi.mock("@/lib/health-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/health-api")>();
  return { ...actual, healthApi: { ...actual.healthApi, ...m } };
});
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/health",
}));

const NURSE = ["dashboard.view", "students.read", "health.read", "health.manage"];
const TEACHER = ["dashboard.view", "students.read", "health.read"];

function day(visits = [visit()]): InfirmaryDay {
  return { date: "2026-10-12", visits, open: visits.filter((v) => v.outcome === null).length, medicinesLow: 1 };
}

const VALUES = {
  personKind: "STUDENT" as const,
  person: { id: "s1", kind: "STUDENT" as const, name: "Asha Rao", admissionNo: "AKS/1", label: "Class 5 A" },
  timeIn: "2026-10-12T10:15",
  complaint: "Fever",
  temperatureC: "38.6",
  treatment: "",
  medicines: [{ medicineId: "m1", quantity: "1" }],
};

beforeEach(() => {
  vi.clearAllMocks();
  m.day.mockResolvedValue(day());
  m.people.mockResolvedValue([{ id: "s2", kind: "STUDENT", name: "Bala Iyer", admissionNo: "AKS/2", label: "Class 5 A" }]);
  m.medicines.mockResolvedValue([{ id: "m1", name: "Paracetamol", unit: "tablet", quantity: 8, reorderLevel: 5, expiresOn: null, low: false, expired: false }]);
  m.addVisit.mockResolvedValue(visit({ id: "v2", personName: "Bala Iyer" }));
  m.closeVisit.mockResolvedValue(visit({ outcome: "SENT_HOME", timeOut: "2026-10-12T06:00:00Z", parentInformed: true }));
});

describe("visitProblems", () => {
  it("mirrors the API: a person, a complaint, a sane temperature and whole quantities", () => {
    expect(visitProblems(VALUES)).toEqual({});
    expect(visitProblems({ ...VALUES, person: null, complaint: " ", temperatureC: "50" })).toEqual({
      person: "health.v.student",
      complaint: "health.v.complaint",
      temperatureC: "health.v.temperature",
    });
    expect(visitProblems({ ...VALUES, personKind: "STAFF", person: null })).toMatchObject({ person: "health.v.staff" });
    expect(visitProblems({ ...VALUES, medicines: [{ medicineId: "", quantity: "1" }] })).toEqual({ medicines: "health.v.medicines" });
    expect(visitProblems({ ...VALUES, medicines: [{ medicineId: "m1", quantity: "0" }] })).toEqual({ medicines: "health.v.medicines" });
  });
});

describe("InfirmaryView", () => {
  it("lists the day's visits with the allergy badge, the medicine given and the counts", async () => {
    renderAs(NURSE, <InfirmaryView />);
    const row = await screen.findByTestId("visit-row");
    expect(row).toHaveAttribute("data-outcome", "OPEN");
    expect(within(row).getByText("Asha Rao")).toHaveAttribute("href", "/app/health/students/s1");
    expect(within(row).getByText("Peanuts")).toBeInTheDocument();
    expect(row).toHaveTextContent("Paracetamol ×1");
    expect(row).toHaveTextContent("38.6 °C");
    expect(screen.getByTestId("infirmary-counts")).toHaveTextContent("1 visit · 1 in the infirmary now · 1 medicine running low");
  });

  it("records a visit: finds the student, takes the complaint and a tablet from the stock", async () => {
    const user = userEvent.setup();
    renderAs(NURSE, <InfirmaryView />);
    await user.click(await screen.findByTestId("visit-add"));
    const form = screen.getByTestId("visit-form");
    await user.click(screen.getByTestId("visit-save"));
    expect(await within(form).findByText("Pick the student.")).toBeInTheDocument();
    expect(m.addVisit).not.toHaveBeenCalled();
    await user.type(within(form).getByRole("textbox", { name: "Student" }), "Ba");
    await user.click(await screen.findByRole("button", { name: /Bala Iyer/ }));
    expect(screen.getByTestId("visit-person")).toHaveTextContent("Bala Iyer");
    await user.type(within(form).getByLabelText("Complaint"), "Fever");
    await user.type(within(form).getByLabelText("Temperature (°C)"), "38.6");
    await user.click(screen.getByTestId("visit-add-medicine"));
    await user.selectOptions(within(form).getByLabelText("Medicine"), "m1");
    await user.click(screen.getByTestId("visit-save"));
    await waitFor(() =>
      expect(m.addVisit).toHaveBeenCalledWith(
        expect.objectContaining({
          personKind: "STUDENT",
          studentId: "s2",
          staffUserId: null,
          complaint: "Fever",
          temperatureC: 38.6,
          medicines: [{ medicineId: "m1", quantity: 1 }],
        }),
      ),
    );
    expect(m.day).toHaveBeenCalledTimes(2);
  });

  it("closes a visit as sent home, telling the parent", async () => {
    const user = userEvent.setup();
    renderAs(NURSE, <InfirmaryView />);
    await user.click(await screen.findByTestId("visit-close"));
    const form = screen.getByTestId("close-form");
    expect(within(form).queryByLabelText(/The parent has been informed/)).toBeNull();
    await user.click(within(form).getByLabelText("Sent home"));
    expect(within(form).getByLabelText(/The parent has been informed/)).toBeChecked();
    await user.click(screen.getByTestId("close-save"));
    await waitFor(() => expect(m.closeVisit).toHaveBeenCalledWith("v1", { outcome: "SENT_HOME", timeOut: null, treatment: null, parentInformed: true }));
    expect(await screen.findByText("Visit closed. The parent has been sent a message.")).toBeInTheDocument();
  });

  it("is read-only for a class teacher", async () => {
    renderAs(TEACHER, <InfirmaryView />);
    await screen.findByTestId("visit-row");
    expect(screen.queryByTestId("visit-add")).toBeNull();
    expect(screen.queryByTestId("visit-close")).toBeNull();
  });
});
