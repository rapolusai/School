import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import type { VisitorDay, VisitorView } from "@/lib/types";
import { renderAs } from "@/test/render";
import { downscaleImage, PhotoCapture } from "./frontoffice-shared";
import { BLOCKED_TYPE, visitorProblems, VisitorsView } from "./visitors-view";

const m = vi.hoisted(() => ({
  day: vi.fn(),
  staff: vi.fn(),
  addVisitor: vi.fn(),
  checkIn: vi.fn(),
  checkOut: vi.fn(),
  visitorPhoto: vi.fn(),
}));

vi.mock("@/lib/frontoffice-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/frontoffice-api")>();
  return { ...actual, frontOfficeApi: { ...actual.frontOfficeApi, ...m } };
});
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/frontoffice",
}));

const OFFICE = ["dashboard.view", "frontoffice.read", "frontoffice.manage", "gate.pass"];
const GUARD = ["dashboard.view", "gate.pass"];

function visitor(overrides: Partial<VisitorView> = {}): VisitorView {
  return {
    id: "v1",
    name: "Sunita Verma",
    phoneMasked: "98765•••01",
    phone: null,
    purpose: "MEETING",
    purposeNote: null,
    toMeetUserId: null,
    toMeetName: "Principal",
    peopleCount: 2,
    idType: "VOTER_ID",
    badgeNo: null,
    photoFileId: null,
    status: "EXPECTED",
    expectedOn: "2026-10-12",
    checkedInAt: null,
    checkedOutAt: null,
    addedByName: "Olivia Office",
    checkedInByName: null,
    checkedOutByName: null,
    blocked: null,
    ...overrides,
  };
}

function day(visitors: VisitorView[]): VisitorDay {
  return { date: "2026-10-12", visitors, inside: visitors.filter((v) => v.status === "IN").length, expected: visitors.filter((v) => v.status === "EXPECTED") };
}

const VALUES = {
  name: "Sunita Verma",
  phone: "9876500001",
  purpose: "MEETING" as const,
  purposeNote: "",
  toMeetUserId: "",
  toMeetName: "Principal",
  peopleCount: "2",
  idType: "NONE" as const,
  badgeNo: "",
  expected: false,
  expectedOn: "",
  override: false,
};

beforeEach(() => {
  vi.clearAllMocks();
  m.staff.mockResolvedValue([{ id: "u2", name: "Tara Teacher", designation: "Class teacher" }]);
  m.checkIn.mockResolvedValue(visitor({ status: "IN", checkedInAt: "2026-10-12T04:30:00Z" }));
  m.checkOut.mockResolvedValue(visitor({ status: "OUT" }));
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("visitorProblems", () => {
  it("mirrors the API: a name, a 10 digit phone, 1 to 50 people, an expected date that is not past", () => {
    expect(visitorProblems(VALUES, "2026-10-12")).toEqual({});
    expect(visitorProblems({ ...VALUES, name: " ", phone: "12", peopleCount: "0" }, "2026-10-12")).toEqual({
      name: "frontoffice.v.name",
      phone: "frontoffice.v.phone",
      peopleCount: "frontoffice.v.people",
    });
    expect(visitorProblems({ ...VALUES, expected: true, expectedOn: "" }, "2026-10-12")).toEqual({ expectedOn: "frontoffice.v.expectedOn" });
    expect(visitorProblems({ ...VALUES, expected: true, expectedOn: "2026-10-11" }, "2026-10-12")).toEqual({ expectedOn: "frontoffice.v.expectedPast" });
    expect(visitorProblems({ ...VALUES, phone: "" }, "2026-10-12")).toEqual({});
  });
});

describe("the camera photo", () => {
  it("hands the original file over when the browser cannot shrink it (no createImageBitmap)", async () => {
    vi.stubGlobal("createImageBitmap", undefined);
    const file = new File(["png-bytes"], "guest.png", { type: "image/png" });
    await expect(downscaleImage(file)).resolves.toBe(file);
  });

  it("opens the phone camera and rejects a file that is not an image", async () => {
    vi.stubGlobal("createImageBitmap", undefined);
    const onPhoto = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup({ applyAccept: false });
    renderAs(OFFICE, <PhotoCapture label="Take photo" onPhoto={onPhoto} testId="visitor-photo" />);
    const input = screen.getByLabelText("Take photo") as HTMLInputElement;
    expect(input).toHaveAttribute("type", "file");
    expect(input).toHaveAttribute("accept", "image/*");
    expect(input).toHaveAttribute("capture", "environment");

    await user.upload(input, new File(["%PDF"], "id.pdf", { type: "application/pdf" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Choose an image file.");
    expect(onPhoto).not.toHaveBeenCalled();

    const photo = new File(["jpg-bytes"], "guest.jpg", { type: "image/jpeg" });
    await user.upload(input, photo);
    await waitFor(() => expect(onPhoto).toHaveBeenCalledWith(photo, "guest.jpg"));
  });
});

describe("VisitorsView", () => {
  it("lists who is inside and expected and checks a visitor in", async () => {
    m.day.mockResolvedValue(day([visitor({ id: "v2", name: "Ravi Courier", status: "IN", purpose: "DELIVERY", checkedInAt: "2026-10-12T04:30:00Z" }), visitor()]));
    const user = userEvent.setup();
    renderAs(OFFICE, <VisitorsView />);
    const inside = await screen.findByTestId("visitors-inside");
    expect(within(inside).getByText("Ravi Courier")).toBeInTheDocument();
    const expected = screen.getByTestId("visitors-expected");
    expect(within(expected).getByText("Sunita Verma")).toBeInTheDocument();
    expect(within(expected).getByText(/98765•••01/)).toBeInTheDocument();

    await user.click(within(expected).getByTestId("visitor-check-in"));
    await waitFor(() => expect(m.checkIn).toHaveBeenCalledWith("v1", { override: false }));
    expect(m.day).toHaveBeenCalledTimes(2);
  });

  it("checks a new visitor in from the form with the fields the API wants", async () => {
    m.day.mockResolvedValue(day([]));
    m.addVisitor.mockResolvedValue(visitor({ status: "IN" }));
    const user = userEvent.setup();
    renderAs(OFFICE, <VisitorsView />);
    await user.click(await screen.findByTestId("visitor-add"));
    const form = within(screen.getByTestId("visitor-form"));

    await user.click(form.getByTestId("visitor-save"));
    expect(form.getByText("Give the name.")).toBeInTheDocument();
    expect(m.addVisitor).not.toHaveBeenCalled();

    await user.type(form.getByLabelText(/^Name/), " Sunita Verma ");
    await user.type(form.getByLabelText(/Mobile number/), "9876500001");
    await user.selectOptions(form.getByLabelText(/Purpose$/), "ADMISSION");
    await user.type(form.getByLabelText(/Whom to meet/), "Principal");
    await user.click(form.getByTestId("visitor-save"));
    await waitFor(() =>
      expect(m.addVisitor).toHaveBeenCalledWith(
        expect.objectContaining({ name: "Sunita Verma", phone: "9876500001", purpose: "ADMISSION", toMeetName: "Principal", peopleCount: 1, idType: "NONE", expectedOn: null }),
      ),
    );
    await waitFor(() => expect(screen.queryByTestId("visitor-form")).not.toBeInTheDocument());
    expect(m.day).toHaveBeenCalledTimes(2);
  });

  it("shows the blocklist refusal and lets the office override it", async () => {
    m.day.mockResolvedValue(day([]));
    m.addVisitor
      .mockRejectedValueOnce(new ApiError({ status: 409, title: "Blocked", detail: "This number is on the blocklist: Abusive at the gate.", type: BLOCKED_TYPE }))
      .mockResolvedValueOnce(visitor({ status: "IN" }));
    const user = userEvent.setup();
    renderAs(OFFICE, <VisitorsView />);
    await user.click(await screen.findByTestId("visitor-add"));
    const form = within(screen.getByTestId("visitor-form"));
    await user.type(form.getByLabelText(/^Name/), "Blocked Person");
    await user.click(form.getByTestId("visitor-save"));
    const blocked = await screen.findByTestId("visitor-blocked");
    expect(blocked).toHaveTextContent("Abusive at the gate");
    await user.click(within(blocked).getByLabelText(/Let them in anyway/));
    await user.click(form.getByTestId("visitor-save"));
    await waitFor(() => expect(m.addVisitor).toHaveBeenLastCalledWith(expect.objectContaining({ override: true })));
  });

  it("gives the guard no override and no edit", async () => {
    m.day.mockResolvedValue(day([visitor()]));
    renderAs(GUARD, <VisitorsView />, ["SECURITY"]);
    await screen.findByText("Sunita Verma");
    expect(screen.queryByLabelText(/Let them in anyway/)).not.toBeInTheDocument();
  });
});
