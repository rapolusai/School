import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import type { AdmissionsBoard, ApplicationPage } from "@/lib/types";
import {
  ADMISSIONS_STAFF,
  ADMISSIONS_VIEWER,
  applicationDetail,
  applicationRow,
  CLASSES,
  TODAY,
  YEAR,
} from "@/test/admissions-fixtures";
import { renderAs } from "@/test/render";
import { addDays } from "./admission-time";
import { AdmissionsCard } from "./admissions-card";
import { AdmissionsView, toApplicationQuery } from "./admissions-view";
import { placeNextTo } from "./stage-menu";

const mocks = vi.hoisted(() => ({
  board: vi.fn(),
  list: vi.fn(),
  upcomingSlots: vi.fn(),
  summary: vi.fn(),
  staff: vi.fn(),
  create: vi.fn(),
  moveStage: vi.fn(),
  admit: vi.fn(),
  listYears: vi.fn(),
  listClasses: vi.fn(),
}));

vi.mock("@/lib/admissions-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/admissions-api")>();
  const { board, list, upcomingSlots, summary, staff, create, moveStage, admit } = mocks;
  return {
    ...actual,
    admissionsApi: { ...actual.admissionsApi, board, list, upcomingSlots, summary, staff, create, moveStage, admit },
  };
});

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, listYears: mocks.listYears, listClasses: mocks.listClasses } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/admissions",
}));

const BOARD: AdmissionsBoard = {
  lanes: [
    {
      stage: "ENQUIRY",
      total: 3,
      cards: [
        applicationRow({ id: "a1", followUpOn: "2026-01-02" }),
        applicationRow({ id: "a2", childName: "Vedant Rao", daysInStage: 0, source: "WALK_IN" }),
      ],
    },
    {
      stage: "APPLICATION",
      total: 1,
      cards: [
        applicationRow({
          id: "a3",
          childName: "Saisha Nair",
          stage: "APPLICATION",
          daysInStage: 1,
          followUpOn: "2099-12-31",
          nextStages: ["ASSESSMENT", "OFFERED", "REJECTED", "WITHDRAWN"],
        }),
      ],
    },
    { stage: "ASSESSMENT", total: 0, cards: [] },
    {
      stage: "OFFERED",
      total: 1,
      cards: [applicationRow({ id: "a4", childName: "Aditi Rao", stage: "OFFERED", nextStages: ["ADMITTED", "WITHDRAWN"] })],
    },
    {
      stage: "ADMITTED",
      total: 1,
      cards: [applicationRow({ id: "a5", childName: "Riaan Gupta", stage: "ADMITTED", nextStages: [] })],
    },
  ],
  closed: 2,
};

const PAGE: ApplicationPage = {
  items: [
    applicationRow({ id: "a1" }),
    applicationRow({ id: "a4", childName: "Aditi Rao", stage: "OFFERED", source: "REFERRAL", contactPhone: "98•••••004" }),
  ],
  page: 0,
  size: 25,
  total: 2,
  stageCounts: { ENQUIRY: 3, APPLICATION: 1, ASSESSMENT: 0, OFFERED: 1, ADMITTED: 1, REJECTED: 1, WITHDRAWN: 1 },
};

beforeEach(() => {
  vi.clearAllMocks();
  mocks.board.mockResolvedValue(BOARD);
  mocks.list.mockResolvedValue(PAGE);
  mocks.upcomingSlots.mockResolvedValue([]);
  mocks.staff.mockResolvedValue([{ id: "u7", name: "Suresh Rao" }]);
  mocks.listYears.mockResolvedValue([YEAR]);
  mocks.listClasses.mockResolvedValue(CLASSES);
});

const lane = (stage: string) => screen.getByTestId(`lane-${stage}`);
const card = (stage: string, name: string) => within(lane(stage)).getByRole("article", { name });

describe("toApplicationQuery", () => {
  it("leaves out empty filters and trims the search", () => {
    expect(toApplicationQuery({ yearId: "", classId: "c1", source: "" }, "  meera ", "OFFERED", 2)).toEqual({
      yearId: undefined,
      classId: "c1",
      source: undefined,
      q: "meera",
      stage: "OFFERED",
      page: 2,
      size: 25,
    });
  });
});

describe("The admissions board", () => {
  it("shows a column per stage with counts, cards and how long each has waited", async () => {
    renderAs(ADMISSIONS_STAFF, <AdmissionsView />);
    await screen.findByTestId("admissions-board");

    expect(screen.getByText("5 open applications")).toBeInTheDocument();
    expect(within(lane("ENQUIRY")).getByRole("heading", { name: "Enquiry 3" })).toBeInTheDocument();
    const aanya = card("ENQUIRY", "Aanya Bhatt");
    expect(within(aanya).getByRole("link", { name: "Aanya Bhatt" })).toHaveAttribute("href", "/app/admissions/a1");
    expect(within(aanya).getByText("LKG · 2026-27")).toBeInTheDocument();
    expect(within(aanya).getByText("4 days in this stage")).toBeInTheDocument();
    expect(within(aanya).getByText(/^Follow-up due/)).toBeInTheDocument();
    expect(within(card("ENQUIRY", "Vedant Rao")).getByText("In this stage since today")).toBeInTheDocument();
    expect(within(card("APPLICATION", "Saisha Nair")).getByText(/^Follow up /)).toBeInTheDocument();
    expect(within(lane("ASSESSMENT")).getByText("Nothing here.")).toBeInTheDocument();
    expect(within(lane("ENQUIRY")).getByRole("button", { name: "See 1 more" })).toBeInTheDocument();
    expect(screen.getByText(/2 closed applications/)).toBeInTheDocument();

    // An admitted application has no further moves.
    expect(within(card("ADMITTED", "Riaan Gupta")).queryByRole("button")).not.toBeInTheDocument();
    expect(mocks.board).toHaveBeenCalledWith({ yearId: undefined, classId: undefined, source: undefined, q: undefined });
  });

  it("moves a card through the menu with the offer dates", async () => {
    const user = userEvent.setup();
    mocks.moveStage.mockResolvedValue(applicationDetail({ id: "a3", childName: "Saisha Nair", stage: "OFFERED" }));
    renderAs(ADMISSIONS_STAFF, <AdmissionsView />);
    await screen.findByTestId("admissions-board");

    await user.click(within(card("APPLICATION", "Saisha Nair")).getByRole("button", { name: "Move Saisha Nair to another stage" }));
    const menu = screen.getByRole("menu", { name: "Move Saisha Nair to another stage" });
    expect(within(menu).getAllByRole("menuitem").map((item) => item.textContent)).toEqual([
      "Move to assessment",
      "Offer a place",
      "Reject",
      "Mark as withdrawn",
    ]);
    await user.click(within(menu).getByRole("menuitem", { name: "Offer a place" }));

    const dialog = await screen.findByRole("dialog", { name: "Offer a place" });
    expect(within(dialog).getByText("Move Saisha Nair from Application to Offered.")).toBeInTheDocument();
    expect(within(dialog).getByLabelText("Offered on")).toHaveValue(TODAY);
    expect(within(dialog).getByLabelText(/Offer valid until/)).toHaveValue(addDays(TODAY, 14));
    await user.click(within(dialog).getByRole("button", { name: "Offer a place" }));

    expect(mocks.moveStage).toHaveBeenCalledWith("a3", {
      stage: "OFFERED",
      note: null,
      offeredOn: TODAY,
      offerValidUntil: addDays(TODAY, 14),
    });
    expect(await screen.findByText("Saisha Nair moved to Offered.")).toBeInTheDocument();
    await waitFor(() => expect(mocks.board).toHaveBeenCalledTimes(2));
  });

  it("checks the offer dates before moving", async () => {
    const user = userEvent.setup();
    renderAs(ADMISSIONS_STAFF, <AdmissionsView />);
    await screen.findByTestId("admissions-board");
    await user.click(within(card("APPLICATION", "Saisha Nair")).getByRole("button", { name: /^Move Saisha Nair/ }));
    await user.click(screen.getByRole("menuitem", { name: "Offer a place" }));
    const dialog = await screen.findByRole("dialog", { name: "Offer a place" });

    fireEvent.change(within(dialog).getByLabelText(/Offer valid until/), { target: { value: addDays(TODAY, -1) } });
    await user.click(within(dialog).getByRole("button", { name: "Offer a place" }));
    expect(within(dialog).getByText("The offer can't end before it is made.")).toBeInTheDocument();
    expect(mocks.moveStage).not.toHaveBeenCalled();
  });

  it("asks for a reason when rejecting and shows the API's refusal", async () => {
    const user = userEvent.setup();
    mocks.moveStage.mockRejectedValue(
      new ApiError({ status: 409, title: "Conflict", detail: "This application has already moved on." }),
    );
    renderAs(ADMISSIONS_STAFF, <AdmissionsView />);
    await screen.findByTestId("admissions-board");
    await user.click(within(card("ENQUIRY", "Aanya Bhatt")).getByRole("button", { name: /^Move Aanya Bhatt/ }));
    await user.click(screen.getByRole("menuitem", { name: "Reject" }));
    const dialog = await screen.findByRole("dialog", { name: "Reject" });
    await user.type(within(dialog).getByLabelText("Reason"), "Class is full");
    await user.click(within(dialog).getByRole("button", { name: "Reject" }));

    expect(mocks.moveStage).toHaveBeenCalledWith("a1", { stage: "REJECTED", note: "Class is full" });
    expect(await within(dialog).findByText("This application has already moved on.")).toBeInTheDocument();
  });

  it("opens and closes the move menu from the keyboard", async () => {
    const user = userEvent.setup();
    renderAs(ADMISSIONS_STAFF, <AdmissionsView />);
    await screen.findByTestId("admissions-board");
    const button = within(card("OFFERED", "Aditi Rao")).getByRole("button", { name: /^Move Aditi Rao/ });
    button.focus();
    await user.keyboard("{ArrowDown}");
    const items = within(screen.getByRole("menu")).getAllByRole("menuitem");
    expect(items.map((item) => item.textContent)).toEqual(["Admit as student", "Mark as withdrawn"]);
    expect(items[0]).toHaveFocus();
    await user.keyboard("{ArrowDown}");
    expect(items[1]).toHaveFocus();
    await user.keyboard("{Escape}");
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    expect(button).toHaveFocus();
  });

  it("admits an offered child from the board and refreshes once the dialog is closed", async () => {
    const user = userEvent.setup();
    mocks.admit.mockResolvedValue(
      applicationDetail({ id: "a4", childName: "Aditi Rao", stage: "ADMITTED", nextStages: [], studentId: "st42" }),
    );
    renderAs(ADMISSIONS_STAFF, <AdmissionsView />);
    await screen.findByTestId("admissions-board");
    await user.click(within(card("OFFERED", "Aditi Rao")).getByRole("button", { name: /^Move Aditi Rao/ }));
    await user.click(screen.getByRole("menuitem", { name: "Admit as student" }));

    const dialog = await screen.findByRole("dialog", { name: "Admit Aditi Rao" });
    // The class has one section, so it is picked already.
    expect(await within(dialog).findByLabelText("Class and section")).toHaveValue("s1");
    await user.click(within(dialog).getByRole("button", { name: "Admit" }));
    expect(within(dialog).getByText("Fill in this field.")).toBeInTheDocument();
    expect(mocks.admit).not.toHaveBeenCalled();

    await user.type(within(dialog).getByLabelText("Admission number"), "AKS/2026/150");
    await user.click(within(dialog).getByRole("button", { name: "Admit" }));
    expect(mocks.admit).toHaveBeenCalledWith("a4", {
      sectionId: "s1",
      admissionNo: "AKS/2026/150",
      rollNo: null,
      admissionDate: TODAY,
      gender: null,
    });

    const done = await within(dialog).findByTestId("admit-done");
    expect(within(done).getByText("Aditi Rao is admitted. The student record is ready.")).toBeInTheDocument();
    expect(within(done).getByRole("link", { name: "Open student record" })).toHaveAttribute("href", "/app/students/st42");
    expect(mocks.board).toHaveBeenCalledTimes(1);

    await user.click(within(done).getByRole("button", { name: "Close" }));
    await waitFor(() => expect(mocks.board).toHaveBeenCalledTimes(2));
  });

  it("explains an empty pipeline and offers to add an enquiry", async () => {
    mocks.board.mockResolvedValue({ lanes: BOARD.lanes.map((l) => ({ ...l, total: 0, cards: [] })), closed: 0 });
    renderAs(ADMISSIONS_STAFF, <AdmissionsView />);
    const empty = await screen.findByTestId("admissions-empty");
    expect(within(empty).getByText(/No enquiries yet/)).toBeInTheDocument();
    expect(within(empty).getByRole("button", { name: "New enquiry" })).toBeInTheDocument();
  });

  it("shows the error with a retry when the board cannot load", async () => {
    const user = userEvent.setup();
    mocks.board.mockRejectedValueOnce(new ApiError({ status: 500, title: "Server error" }));
    renderAs(ADMISSIONS_STAFF, <AdmissionsView />);
    await user.click(await screen.findByRole("button", { name: "Try again" }));
    expect(await screen.findByTestId("admissions-board")).toBeInTheDocument();
  });

  it("is read-only without admissions.manage", async () => {
    renderAs(ADMISSIONS_VIEWER, <AdmissionsView />, ["TEACHER"]);
    await screen.findByTestId("admissions-board");
    expect(screen.queryByRole("button", { name: "New enquiry" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Move / })).not.toBeInTheDocument();
    // The year and class filters need the school setup permission.
    expect(screen.queryByRole("combobox", { name: "Academic year" })).not.toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Source" })).toBeInTheDocument();
  });

  it("lists the tests and interviews coming up", async () => {
    mocks.upcomingSlots.mockResolvedValue([
      {
        id: "sl1",
        applicationId: "a7",
        childName: "Advika Menon",
        className: "UKG",
        kind: "INTERVIEW",
        scheduledAt: "2026-10-11T04:30:00Z",
        mode: "IN_PERSON",
        location: "Principal's office",
        meetingLink: null,
        interviewer: { id: "u2", name: "Lakshmi Iyer" },
      },
    ]);
    renderAs(ADMISSIONS_STAFF, <AdmissionsView />);
    const slots = await screen.findByTestId("upcoming-slots");
    expect(within(slots).getByRole("link", { name: "Advika Menon" })).toHaveAttribute("href", "/app/admissions/a7");
    expect(within(slots).getByText("Interview · Principal's office · Lakshmi Iyer")).toBeInTheDocument();
    expect(mocks.upcomingSlots).toHaveBeenCalledWith(14);
  });
});

describe("The admissions list", () => {
  it("shows counts per stage, masked mobile numbers and filters by stage", async () => {
    const user = userEvent.setup();
    renderAs(ADMISSIONS_STAFF, <AdmissionsView />);
    await screen.findByTestId("admissions-board");
    await user.click(screen.getByRole("tab", { name: "List" }));

    const table = await screen.findByTestId("admissions-table");
    expect(mocks.list).toHaveBeenLastCalledWith({ page: 0, size: 25 });
    expect(within(table).getByRole("link", { name: "Aditi Rao" })).toHaveAttribute("href", "/app/admissions/a4");
    expect(within(table).getByText("98•••••004")).toBeInTheDocument();
    expect(within(table).getByText("Referral")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "All stages 8" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByText("1–2 of 2")).toBeInTheDocument();
    expect(within(screen.getByTestId("admissions-cards")).getAllByRole("link")).toHaveLength(2);

    await user.click(screen.getByRole("button", { name: "Offered 1" }));
    await waitFor(() => expect(mocks.list).toHaveBeenLastCalledWith(expect.objectContaining({ stage: "OFFERED", page: 0 })));
    expect(screen.getByRole("button", { name: "Offered 1" })).toHaveAttribute("aria-pressed", "true");
  });

  it("opens the list at a stage from 'See more' and sends the search and filters", async () => {
    const user = userEvent.setup();
    renderAs(ADMISSIONS_STAFF, <AdmissionsView />);
    await screen.findByTestId("admissions-board");
    await user.click(screen.getByRole("button", { name: "See 1 more" }));
    await waitFor(() => expect(mocks.list).toHaveBeenLastCalledWith(expect.objectContaining({ stage: "ENQUIRY" })));

    await user.selectOptions(screen.getByRole("combobox", { name: "Source" }), "WEBSITE");
    await user.type(screen.getByRole("searchbox"), "9876");
    await waitFor(() =>
      expect(mocks.list).toHaveBeenLastCalledWith(
        expect.objectContaining({ stage: "ENQUIRY", source: "WEBSITE", q: "9876", page: 0 }),
      ),
    );
  });

  it("explains when nothing matches", async () => {
    const user = userEvent.setup();
    mocks.list.mockResolvedValue({ ...PAGE, items: [], total: 0 });
    renderAs(ADMISSIONS_STAFF, <AdmissionsView />);
    await screen.findByTestId("admissions-board");
    await user.click(screen.getByRole("tab", { name: "List" }));
    expect(await screen.findByTestId("admissions-list-empty")).toHaveTextContent("No applications match these filters.");
  });
});

describe("The new enquiry dialog", () => {
  async function openDialog() {
    const user = userEvent.setup();
    renderAs(ADMISSIONS_STAFF, <AdmissionsView />);
    await screen.findByTestId("admissions-board");
    await user.click(screen.getByRole("button", { name: "New enquiry" }));
    const dialog = await screen.findByRole("dialog", { name: "New enquiry" });
    await within(dialog).findByRole("button", { name: "Add enquiry" });
    return { user, dialog };
  }

  it("checks the form the same way the API does", async () => {
    const { user, dialog } = await openDialog();
    await user.type(within(dialog).getByLabelText("Mobile number"), "12345");
    fireEvent.change(within(dialog).getByLabelText("Date of birth"), { target: { value: addDays(TODAY, 2) } });
    await user.click(within(dialog).getByRole("button", { name: "Add enquiry" }));

    expect(mocks.create).not.toHaveBeenCalled();
    expect(within(dialog).getAllByText("Fill in this field.")).toHaveLength(2);
    expect(within(dialog).getByText("The date of birth must be in the past.")).toBeInTheDocument();
    expect(within(dialog).getByText("Pick a class.")).toBeInTheDocument();
    expect(within(dialog).getByText("Enter a 10-digit Indian mobile number.")).toBeInTheDocument();
    expect(within(dialog).getAllByText("Choose one.")).toHaveLength(2);
    expect(within(dialog).getByLabelText("First name")).toHaveFocus();
  });

  it("sends the enquiry with the current year and its primary contact", async () => {
    mocks.create.mockResolvedValue(applicationDetail({ id: "a9", childName: "Kiara Shah" }));
    const { user, dialog } = await openDialog();
    expect(within(dialog).getByLabelText("Academic year")).toHaveValue("y1");

    await user.type(within(dialog).getByLabelText("First name"), "Kiara");
    await user.type(within(dialog).getByLabelText("Last name"), "Shah");
    fireEvent.change(within(dialog).getByLabelText("Date of birth"), { target: { value: "2021-05-01" } });
    await user.selectOptions(within(dialog).getByLabelText("Class applied for"), "c1");
    await user.type(within(dialog).getByLabelText("Name"), "Pooja Shah");
    await user.selectOptions(within(dialog).getByLabelText("Relation"), "MOTHER");
    await user.type(within(dialog).getByLabelText("Mobile number"), "98765 11111");
    await user.selectOptions(within(dialog).getByLabelText("Source"), "WALK_IN");
    await user.selectOptions(within(dialog).getByLabelText("Counsellor"), "u7");
    await user.click(within(dialog).getByRole("button", { name: "Add enquiry" }));

    expect(mocks.create).toHaveBeenCalledWith({
      stage: "ENQUIRY",
      note: null,
      firstName: "Kiara",
      lastName: "Shah",
      dateOfBirth: "2021-05-01",
      gender: null,
      previousSchool: null,
      classId: "c1",
      academicYearId: "y1",
      source: "WALK_IN",
      assignedToId: "u7",
      followUpOn: null,
      guardians: [{ name: "Pooja Shah", relation: "MOTHER", phone: "98765 11111", email: null, primary: true }],
    });
    expect(await screen.findByText("Enquiry for Kiara Shah added.")).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("asks for school setup first when there are no classes", async () => {
    mocks.listClasses.mockResolvedValue([]);
    const user = userEvent.setup();
    renderAs(ADMISSIONS_STAFF, <AdmissionsView />);
    await screen.findByTestId("admissions-board");
    await user.click(screen.getByRole("button", { name: "New enquiry" }));
    const dialog = await screen.findByRole("dialog", { name: "New enquiry" });
    expect(await within(dialog).findByText(/Add classes and an academic year in School setup/)).toBeInTheDocument();
  });
});

describe("The dashboard card", () => {
  it("shows the admissions numbers", async () => {
    mocks.summary.mockResolvedValue({
      openEnquiries: 5,
      inProgress: 5,
      offersPending: 2,
      admittedThisYear: 1,
      upcomingSlots: 3,
    });
    renderAs(ADMISSIONS_STAFF, <AdmissionsCard />);
    const cardEl = screen.getByTestId("admissions-card");
    expect(await within(cardEl).findByText("3 tests or interviews coming up.")).toBeInTheDocument();
    expect(within(cardEl).getByText("New enquiries").nextElementSibling).toHaveTextContent("5");
    expect(within(cardEl).getByText("Offers awaiting admission").nextElementSibling).toHaveTextContent("2");
    expect(within(cardEl).getByText("Admitted this year").nextElementSibling).toHaveTextContent("1");
    expect(within(cardEl).getByRole("link", { name: "Open admissions" })).toHaveAttribute("href", "/app/admissions");
  });
});

describe("placeNextTo", () => {
  const rect = (left: number, top: number, width = 80, height = 32) =>
    ({ left, top, right: left + width, bottom: top + height, width, height }) as DOMRect;

  it("opens below the button, lined up with its right edge", () => {
    expect(placeNextTo(rect(600, 100), 1440, 900)).toEqual({ top: 138, bottom: "auto", left: "auto", right: 760 });
  });

  it("opens above the button near the bottom of the window", () => {
    expect(placeNextTo(rect(600, 820), 1440, 900)).toEqual({ top: "auto", bottom: 86, left: "auto", right: 760 });
  });

  it("lines up with the left edge near the left of a phone screen", () => {
    expect(placeNextTo(rect(20, 100), 390, 800)).toEqual({ top: 138, bottom: "auto", left: 20, right: "auto" });
  });
});
