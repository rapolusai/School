import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import { formatPlainDate } from "@/lib/format";
import type { ApplicationDetail, SchoolProfile } from "@/lib/types";
import { ADMISSIONS_STAFF, ADMISSIONS_VIEWER, applicationDetail, CLASSES, TODAY } from "@/test/admissions-fixtures";
import { renderAs } from "@/test/render";
import { addDays, fromIndiaInput } from "./admission-time";
import { validateFee, validateSlot } from "./application-dialogs";
import { ApplicationDetailView } from "./application-detail-view";
import { OfferLetterView } from "./offer-letter-view";

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  update: vi.fn(),
  staff: vi.fn(),
  addNote: vi.fn(),
  recordFee: vi.fn(),
  scheduleSlot: vi.fn(),
  recordOutcome: vi.fn(),
  cancelSlot: vi.fn(),
  admit: vi.fn(),
  moveStage: vi.fn(),
  listClasses: vi.fn(),
  listYears: vi.fn(),
  getSchoolProfile: vi.fn(),
}));

vi.mock("@/lib/admissions-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/admissions-api")>();
  const { get, update, staff, addNote, recordFee, scheduleSlot, recordOutcome, cancelSlot, admit, moveStage } = mocks;
  return {
    ...actual,
    admissionsApi: {
      ...actual.admissionsApi,
      get,
      update,
      staff,
      addNote,
      recordFee,
      scheduleSlot,
      recordOutcome,
      cancelSlot,
      admit,
      moveStage,
    },
  };
});

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  const { listClasses, listYears, getSchoolProfile } = mocks;
  return { ...actual, api: { ...actual.api, listClasses, listYears, getSchoolProfile } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/admissions/a7",
}));

const SLOT_AT = "2026-10-11T04:30:00Z";

const ADVIKA: ApplicationDetail = applicationDetail({
  id: "a7",
  firstName: "Advika",
  lastName: "Menon",
  childName: "Advika Menon",
  stage: "ASSESSMENT",
  daysInStage: 2,
  nextStages: ["OFFERED", "REJECTED", "WITHDRAWN"],
  source: "WALK_IN",
  assignedTo: { id: "u7", name: "Suresh Rao" },
  message: null,
  consentAt: null,
  consentVersion: null,
  guardians: [
    { name: "Divya Menon", relation: "MOTHER", phone: "9876502010", email: "divya@example.com", primary: true },
    { name: "Arun Menon", relation: "FATHER", phone: "9876502011", email: null, primary: false },
  ],
  fee: { status: "PAID", amountPaise: 50000, method: "CASH", reference: "R-101", paidOn: "2026-10-06" },
  slots: [
    {
      id: "sl1",
      kind: "INTERVIEW",
      scheduledAt: SLOT_AT,
      mode: "IN_PERSON",
      location: "Principal's office",
      meetingLink: null,
      interviewer: { id: "u2", name: "Lakshmi Iyer" },
      status: "SCHEDULED",
      outcomeNotes: null,
    },
  ],
  timeline: [
    {
      id: "t4",
      at: "2026-10-07T05:00:00Z",
      kind: "SLOT_SCHEDULED",
      actorName: "Suresh Rao",
      fromStage: null,
      toStage: null,
      note: null,
      details: { kind: "INTERVIEW", scheduledAt: SLOT_AT },
    },
    {
      id: "t3",
      at: "2026-10-06T05:00:00Z",
      kind: "FEE_RECORDED",
      actorName: "Suresh Rao",
      fromStage: null,
      toStage: null,
      note: null,
      details: { status: "PAID", amountPaise: 50000, method: "CASH", paidOn: "2026-10-06" },
    },
    {
      id: "t2",
      at: "2026-10-06T04:00:00Z",
      kind: "STAGE_CHANGED",
      actorName: "Suresh Rao",
      fromStage: "APPLICATION",
      toStage: "ASSESSMENT",
      note: "Documents checked",
      details: {},
    },
    {
      id: "t1",
      at: "2026-10-05T04:00:00Z",
      kind: "CREATED",
      actorName: "Suresh Rao",
      fromStage: null,
      toStage: "APPLICATION",
      note: null,
      details: {},
    },
  ],
});

const ADITI: ApplicationDetail = applicationDetail({
  id: "a4",
  firstName: "Aditi",
  lastName: "Rao",
  childName: "Aditi Rao",
  stage: "OFFERED",
  gender: null,
  nextStages: ["ADMITTED", "WITHDRAWN"],
  offer: { offeredOn: "2026-10-08", validUntil: "2026-10-22" },
});

beforeEach(() => {
  vi.clearAllMocks();
  mocks.get.mockImplementation((id: string) => Promise.resolve(id === "a4" ? ADITI : ADVIKA));
  mocks.staff.mockResolvedValue([
    { id: "u2", name: "Lakshmi Iyer" },
    { id: "u7", name: "Suresh Rao" },
  ]);
  mocks.listClasses.mockResolvedValue(CLASSES);
  mocks.listYears.mockResolvedValue([]);
});

describe("An application's page", () => {
  it("shows the child, the contacts, the tests, the fee and the timeline", async () => {
    renderAs(ADMISSIONS_STAFF, <ApplicationDetailView id="a7" />);
    expect(await screen.findByRole("heading", { level: 1, name: /Advika Menon/ })).toBeInTheDocument();
    expect(screen.getByText("LKG · 2026-27")).toBeInTheDocument();
    expect(screen.getByText("2 days in this stage · Walk-in")).toBeInTheDocument();

    const contacts = screen.getByTestId("contacts-list");
    expect(within(contacts).getByRole("link", { name: "98765 02010" })).toHaveAttribute("href", "tel:+919876502010");
    expect(within(contacts).getByText("Primary")).toBeInTheDocument();
    expect(within(contacts).getByText("Father")).toBeInTheDocument();

    const slots = screen.getByTestId("slots-list");
    expect(within(slots).getByText("Scheduled")).toBeInTheDocument();
    expect(within(slots).getByText(/Principal's office · Lakshmi Iyer/)).toBeInTheDocument();

    const fee = screen.getByTestId("fee-details");
    expect(within(fee).getByText("₹500")).toBeInTheDocument();
    expect(within(fee).getByText("Cash")).toBeInTheDocument();
    expect(within(fee).getByText("R-101")).toBeInTheDocument();

    const timeline = screen.getByTestId("timeline");
    const titles = within(timeline)
      .getAllByRole("listitem")
      .map((item) => item.querySelector("p")?.textContent);
    expect(titles).toEqual([
      "Interview scheduled",
      "Application fee of ₹500 paid by Cash",
      "Moved from Application to Assessment",
      "Added at the Application stage",
    ]);
    expect(within(timeline).getByText("Documents checked")).toBeInTheDocument();

    expect(screen.getByRole("button", { name: "Edit details" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Move Advika Menon to another stage" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Admit as student" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Offer letter" })).not.toBeInTheDocument();
  });

  it("shows what came from the public form", async () => {
    mocks.get.mockResolvedValue(applicationDetail());
    renderAs(ADMISSIONS_STAFF, <ApplicationDetailView id="a1" />);
    expect(await screen.findByText("Is there a school bus from Kothrud?")).toBeInTheDocument();
    expect(screen.getByText("enquiry-2026-10")).toBeInTheDocument();
    const timeline = screen.getByTestId("timeline");
    expect(within(timeline).getByText("Enquiry sent from the website")).toBeInTheDocument();
    expect(within(timeline).getByText(/^Website form/)).toBeInTheDocument();
    // Tests and interviews come after the application form.
    expect(screen.getByText("No tests or interviews.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Schedule" })).not.toBeInTheDocument();
  });

  it("says when the application does not exist", async () => {
    mocks.get.mockRejectedValue(new ApiError({ status: 404, title: "Not found" }));
    renderAs(ADMISSIONS_STAFF, <ApplicationDetailView id="nope" />);
    expect(await screen.findByText("This application could not be found.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "All applications" })).toHaveAttribute("href", "/app/admissions");
  });

  it("is read-only without admissions.manage", async () => {
    renderAs(ADMISSIONS_VIEWER, <ApplicationDetailView id="a7" />, ["TEACHER"]);
    await screen.findByTestId("timeline");
    for (const name of ["Edit details", "Schedule", "Reschedule", "Record outcome", "Change", "Add note"]) {
      expect(screen.queryByRole("button", { name }), name).not.toBeInTheDocument();
    }
    expect(screen.queryByRole("button", { name: /^Move / })).not.toBeInTheDocument();
    expect(screen.queryByLabelText("Add a note")).not.toBeInTheDocument();
  });

  it("explains a closed application", async () => {
    mocks.get.mockResolvedValue(applicationDetail({ stage: "REJECTED", nextStages: [] }));
    renderAs(ADMISSIONS_STAFF, <ApplicationDetailView id="a1" />);
    expect(await screen.findByText("This application is closed (rejected). Its history stays below.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Edit details" })).not.toBeInTheDocument();
  });

  it("adds a note to the timeline", async () => {
    const user = userEvent.setup();
    mocks.addNote.mockResolvedValue({
      ...ADVIKA,
      timeline: [
        {
          id: "t5",
          at: "2026-10-08T05:00:00Z",
          kind: "NOTE",
          actorName: "Suresh Rao",
          fromStage: null,
          toStage: null,
          note: "Father called about the bus",
          details: {},
        },
        ...ADVIKA.timeline,
      ],
    });
    renderAs(ADMISSIONS_STAFF, <ApplicationDetailView id="a7" />);
    await screen.findByTestId("timeline");
    await user.click(screen.getByRole("button", { name: "Add note" }));
    expect(screen.getByText("Fill in this field.")).toBeInTheDocument();
    expect(mocks.addNote).not.toHaveBeenCalled();

    await user.type(screen.getByLabelText("Add a note"), "  Father called about the bus ");
    await user.click(screen.getByRole("button", { name: "Add note" }));
    expect(mocks.addNote).toHaveBeenCalledWith("a7", "Father called about the bus");
    expect(await screen.findByText("Note added.")).toBeInTheDocument();
    expect(within(screen.getByTestId("timeline")).getByText("Father called about the bus")).toBeInTheDocument();
    expect(screen.getByLabelText("Add a note")).toHaveValue("");
  });

  it("edits the details without sending a stage or a note", async () => {
    const user = userEvent.setup();
    mocks.update.mockResolvedValue({ ...ADVIKA, firstName: "Advika S", childName: "Advika S Menon" });
    renderAs(ADMISSIONS_STAFF, <ApplicationDetailView id="a7" />);
    await screen.findByTestId("timeline");
    mocks.listYears.mockResolvedValue([
      { id: "y1", name: "2026-27", startsOn: addDays(TODAY, -100), endsOn: addDays(TODAY, 100), current: true },
    ]);
    await user.click(screen.getByRole("button", { name: "Edit details" }));
    const dialog = await screen.findByRole("dialog", { name: "Edit Advika Menon's details" });
    const first = await within(dialog).findByLabelText("First name");
    expect(first).toHaveValue("Advika");
    expect(within(dialog).queryByRole("radiogroup", { name: "Start as" })).not.toBeInTheDocument();
    await user.clear(first);
    await user.type(first, "Advika S");
    await user.click(within(dialog).getByRole("button", { name: "Save" }));

    expect(mocks.update).toHaveBeenCalledWith(
      "a7",
      expect.objectContaining({ firstName: "Advika S", lastName: "Menon", source: "WALK_IN", assignedToId: "u7" }),
    );
    const body = mocks.update.mock.calls[0][1];
    expect(body).not.toHaveProperty("stage");
    expect(body).not.toHaveProperty("note");
    expect(body.guardians).toHaveLength(2);
    expect(await screen.findByText("Changes saved.")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: /Advika S Menon/ })).toBeInTheDocument();
  });
});

describe("Tests and interviews", () => {
  it("checks the time and place before scheduling, then schedules an online interview", async () => {
    const user = userEvent.setup();
    mocks.scheduleSlot.mockResolvedValue(ADVIKA);
    renderAs(ADMISSIONS_STAFF, <ApplicationDetailView id="a7" />);
    await screen.findByTestId("timeline");
    await user.click(screen.getByRole("button", { name: "Schedule" }));
    const dialog = await screen.findByRole("dialog", { name: "Schedule a test or interview" });

    fireEvent.change(within(dialog).getByLabelText("Date and time"), { target: { value: `${addDays(TODAY, -1)}T10:00` } });
    await user.click(within(dialog).getByRole("button", { name: "Schedule" }));
    expect(within(dialog).getByText("Pick a time in the future.")).toBeInTheDocument();
    expect(within(dialog).getByText("Say where it will happen.")).toBeInTheDocument();

    await user.click(within(dialog).getByRole("radio", { name: "Online" }));
    await user.type(within(dialog).getByLabelText("Meeting link"), "http://meet.example.com/x");
    const at = `${addDays(TODAY, 3)}T10:00`;
    fireEvent.change(within(dialog).getByLabelText("Date and time"), { target: { value: at } });
    await user.click(within(dialog).getByRole("button", { name: "Schedule" }));
    expect(within(dialog).getByText("Enter a meeting link that starts with https://")).toBeInTheDocument();
    expect(mocks.scheduleSlot).not.toHaveBeenCalled();

    await user.clear(within(dialog).getByLabelText("Meeting link"));
    await user.type(within(dialog).getByLabelText("Meeting link"), "https://meet.example.com/x");
    await user.selectOptions(within(dialog).getByLabelText("Interviewer"), "u2");
    await user.click(within(dialog).getByRole("button", { name: "Schedule" }));
    expect(mocks.scheduleSlot).toHaveBeenCalledWith("a7", {
      kind: "INTERVIEW",
      scheduledAt: fromIndiaInput(at),
      mode: "ONLINE",
      location: null,
      meetingLink: "https://meet.example.com/x",
      interviewerId: "u2",
    });
    expect(await screen.findByText("Test or interview saved.")).toBeInTheDocument();
  });

  it("records an outcome", async () => {
    const user = userEvent.setup();
    mocks.recordOutcome.mockResolvedValue(ADVIKA);
    renderAs(ADMISSIONS_STAFF, <ApplicationDetailView id="a7" />);
    await user.click(await screen.findByRole("button", { name: "Record outcome" }));
    const dialog = await screen.findByRole("dialog", { name: "Outcome" });
    expect(within(dialog).getByText("Interview for Advika Menon. Write what was observed and any decision.")).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(within(dialog).getByText("Fill in this field.")).toBeInTheDocument();

    await user.type(within(dialog).getByLabelText("Outcome notes"), "Confident; reads well.");
    await user.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(mocks.recordOutcome).toHaveBeenCalledWith("a7", "sl1", "Confident; reads well.");
    expect(await screen.findByText("Outcome saved.")).toBeInTheDocument();
  });

  it("cancels a scheduled interview after asking", async () => {
    const user = userEvent.setup();
    mocks.cancelSlot.mockResolvedValue(ADVIKA);
    renderAs(ADMISSIONS_STAFF, <ApplicationDetailView id="a7" />);
    const slots = await screen.findByTestId("slots-list");
    await user.click(within(slots).getByRole("button", { name: "Cancel" }));
    const dialog = await screen.findByRole("dialog", { name: "Cancel this test or interview?" });
    expect(within(dialog).getByText(/^The interview on .* will be marked as cancelled/)).toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Cancel it" }));
    expect(mocks.cancelSlot).toHaveBeenCalledWith("a7", "sl1");
    expect(await screen.findByText("Cancelled.")).toBeInTheDocument();
  });

  it("validates a slot like the API", () => {
    const now = new Date("2026-10-09T06:00:00Z");
    const base = { kind: "TEST" as const, mode: "IN_PERSON" as const, location: "Room 4", meetingLink: "", interviewerId: "" };
    expect(validateSlot({ ...base, at: "2026-10-12T10:00" }, now)).toEqual({});
    expect(validateSlot({ ...base, at: "2026-10-12" }, now)).toEqual({ scheduledAt: "admissions.v.dateTime" });
    expect(validateSlot({ ...base, at: "2027-12-01T10:00" }, now)).toEqual({ scheduledAt: "admissions.v.withinYear" });
    expect(validateSlot({ ...base, mode: "ONLINE", at: "2026-10-12T10:00" }, now)).toEqual({
      meetingLink: "admissions.v.link",
    });
  });
});

describe("The application fee", () => {
  it("records a fee in rupees as paise", async () => {
    const user = userEvent.setup();
    mocks.get.mockResolvedValue({ ...ADVIKA, fee: null });
    mocks.recordFee.mockResolvedValue(ADVIKA);
    renderAs(ADMISSIONS_STAFF, <ApplicationDetailView id="a7" />);
    expect(await screen.findByText("No application fee recorded.")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Record fee" }));
    const dialog = await screen.findByRole("dialog", { name: "Application fee" });

    await user.type(within(dialog).getByLabelText("Amount (₹)"), "abc");
    await user.click(within(dialog).getByRole("button", { name: "Save fee" }));
    expect(within(dialog).getByText("Enter an amount in rupees, such as 500.")).toBeInTheDocument();
    expect(within(dialog).getByText("Choose one.")).toBeInTheDocument();

    await user.clear(within(dialog).getByLabelText("Amount (₹)"));
    await user.type(within(dialog).getByLabelText("Amount (₹)"), "₹1,250");
    await user.selectOptions(within(dialog).getByLabelText("Paid by"), "UPI");
    await user.type(within(dialog).getByLabelText("Receipt or reference"), "UPI-778");
    await user.click(within(dialog).getByRole("button", { name: "Save fee" }));
    expect(mocks.recordFee).toHaveBeenCalledWith("a7", {
      status: "PAID",
      amountPaise: 125000,
      method: "UPI",
      reference: "UPI-778",
      paidOn: TODAY,
      note: null,
    });
    expect(await screen.findByText("Application fee saved.")).toBeInTheDocument();
  });

  it("records a waiver without an amount", async () => {
    const user = userEvent.setup();
    mocks.recordFee.mockResolvedValue(ADVIKA);
    renderAs(ADMISSIONS_STAFF, <ApplicationDetailView id="a7" />);
    await user.click(await screen.findByRole("button", { name: "Change" }));
    const dialog = await screen.findByRole("dialog", { name: "Application fee" });
    expect(within(dialog).getByLabelText("Amount (₹)")).toHaveValue("500");
    await user.click(within(dialog).getByRole("radio", { name: "Waived" }));
    expect(within(dialog).queryByLabelText("Amount (₹)")).not.toBeInTheDocument();
    await user.click(within(dialog).getByRole("button", { name: "Save fee" }));
    expect(mocks.recordFee).toHaveBeenCalledWith("a7", {
      status: "WAIVED",
      amountPaise: null,
      method: null,
      reference: "R-101",
      paidOn: "2026-10-06",
      note: null,
    });
  });

  it("refuses a future date and an amount above the limit", () => {
    const values = { status: "PAID" as const, amount: "12.345", method: "CASH" as const, reference: "", note: "" };
    expect(validateFee({ ...values, paidOn: addDays(TODAY, 1) }, TODAY)).toEqual({
      amountPaise: "admissions.v.amount",
      paidOn: "admissions.v.notFuture",
    });
    expect(validateFee({ ...values, amount: "1000001", paidOn: TODAY }, TODAY)).toEqual({
      amountPaise: "admissions.v.amountTooLarge",
    });
  });
});

describe("Admitting from the application", () => {
  it("asks for the gender when the application has none and shows the new student", async () => {
    const user = userEvent.setup();
    mocks.admit.mockResolvedValue({ ...ADITI, stage: "ADMITTED", nextStages: [], studentId: "st42", gender: "FEMALE" });
    renderAs(ADMISSIONS_STAFF, <ApplicationDetailView id="a4" />);
    expect(await screen.findByRole("link", { name: "Offer letter" })).toHaveAttribute(
      "href",
      "/app/admissions/a4/offer-letter",
    );
    expect(screen.getByTestId("offer-details")).toHaveTextContent(formatPlainDate("2026-10-22"));
    await user.click(screen.getByRole("button", { name: "Admit as student" }));

    const dialog = await screen.findByRole("dialog", { name: "Admit Aditi Rao" });
    await user.type(await within(dialog).findByLabelText("Admission number"), "AKS/2026/150");
    await user.type(within(dialog).getByLabelText("Roll number"), "0");
    await user.click(within(dialog).getByRole("button", { name: "Admit" }));
    expect(within(dialog).getByText("Choose one.")).toBeInTheDocument();
    expect(within(dialog).getByText("Use a roll number from 1 to 999.")).toBeInTheDocument();
    expect(mocks.admit).not.toHaveBeenCalled();

    await user.clear(within(dialog).getByLabelText("Roll number"));
    await user.type(within(dialog).getByLabelText("Roll number"), "7");
    await user.selectOptions(within(dialog).getByLabelText("Gender"), "FEMALE");
    await user.click(within(dialog).getByRole("button", { name: "Admit" }));
    expect(mocks.admit).toHaveBeenCalledWith("a4", {
      sectionId: "s1",
      admissionNo: "AKS/2026/150",
      rollNo: 7,
      admissionDate: TODAY,
      gender: "FEMALE",
    });

    const done = await within(dialog).findByTestId("admit-done");
    await user.click(within(done).getByRole("button", { name: "Close" }));
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    const banner = screen.getByTestId("admitted-banner");
    expect(banner).toHaveTextContent("Aditi Rao is now a student.");
    expect(within(banner).getByRole("link", { name: "Open student record" })).toHaveAttribute("href", "/app/students/st42");
    expect(screen.queryByRole("button", { name: "Admit as student" })).not.toBeInTheDocument();
  });

  it("explains that the class needs a section first", async () => {
    const user = userEvent.setup();
    mocks.listClasses.mockResolvedValue([{ ...CLASSES[0], sections: [] }]);
    renderAs(ADMISSIONS_STAFF, <ApplicationDetailView id="a4" />);
    await user.click(await screen.findByRole("button", { name: "Admit as student" }));
    const dialog = await screen.findByRole("dialog", { name: "Admit Aditi Rao" });
    expect(await within(dialog).findByText("LKG has no sections yet. Add one in School setup first.")).toBeInTheDocument();
  });
});

describe("The offer letter", () => {
  const PROFILE: SchoolProfile = {
    id: "t1",
    name: "Akshara Demo School",
    code: "akshara-demo",
    board: "CBSE",
    city: "Pune",
    address: "12 MG Road, Pune 411001",
    phone: "020 2612 3456",
    contactEmail: "office@akshara-demo.test",
    udiseCode: "27251234567",
  };

  it("prints a letter on the school's letterhead", async () => {
    const user = userEvent.setup();
    const print = vi.spyOn(window, "print").mockImplementation(() => {});
    mocks.getSchoolProfile.mockResolvedValue(PROFILE);
    renderAs(ADMISSIONS_STAFF, <OfferLetterView id="a4" />);
    const letter = await screen.findByTestId("offer-letter");
    expect(within(letter).getByText("Akshara Demo School", { selector: ".offer-letter-school" })).toBeInTheDocument();
    expect(within(letter).getByText("12 MG Road, Pune 411001")).toBeInTheDocument();
    expect(within(letter).getByText(/UDISE 27251234567/)).toBeInTheDocument();
    expect(within(letter).getByText("To: Meera Bhatt", { exact: false })).toBeInTheDocument();
    expect(within(letter).getByText("Subject: Offer of admission to LKG for 2026-27")).toBeInTheDocument();
    expect(
      within(letter).getByText("We are pleased to offer Aditi Rao a place in LKG for the academic year 2026-27."),
    ).toBeInTheDocument();
    expect(within(letter).getByText(/^Please confirm and complete the admission by/)).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Print" }));
    expect(print).toHaveBeenCalled();
    print.mockRestore();
  });

  it("says when there is no offer yet", async () => {
    mocks.getSchoolProfile.mockResolvedValue(PROFILE);
    renderAs(ADMISSIONS_STAFF, <OfferLetterView id="a7" />);
    expect(await screen.findByText("This application has no offer yet.")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Print" })).not.toBeInTheDocument();
  });
});
