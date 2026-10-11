import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { PtmBooking, PtmEventDetail, PtmMyEvent, PtmTeacherSchedule } from "@/lib/types";
import { renderAs } from "@/test/render";
import { classBookings } from "./ptm-print";
import { emptyEventForm, eventProblems, eventRequest } from "./ptm-shared";
import { PtmEventView, PtmHomeView } from "./ptm-views";

const m = vi.hoisted(() => ({
  events: vi.fn(),
  event: vi.fn(),
  settings: vi.fn(),
  saveSettings: vi.fn(),
  block: vi.fn(),
  mine: vi.fn(),
  myEvent: vi.fn(),
  myMark: vi.fn(),
  push: vi.fn(),
}));

vi.mock("@/lib/chat-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/chat-api")>();
  return {
    ...actual,
    ptmApi: {
      ...actual.ptmApi,
      events: m.events,
      event: m.event,
      settings: m.settings,
      saveSettings: m.saveSettings,
      block: m.block,
      mine: m.mine,
      myEvent: m.myEvent,
      myMark: m.myMark,
    },
  };
});
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: m.push }),
  usePathname: () => "/app/ptm",
}));

const PRINCIPAL = ["dashboard.view", "students.read", "chat.use", "chat.oversee", "ptm.manage"];
const TEACHER = ["dashboard.view", "students.read", "chat.use"];

const booking = (overrides: Partial<PtmBooking> = {}): PtmBooking => ({
  id: "b1",
  slotId: "s2",
  startsAt: "09:30:00",
  endsAt: "09:40:00",
  teacherId: "t1",
  teacherName: "Ravi Kumar",
  student: { id: "st1", name: "Arjun Sharma", classLabel: "Class 5 A", sectionId: "secA" },
  parentName: "Anitha Sharma",
  bookedBy: "PARENT",
  bookedByName: "Anitha Sharma",
  attendance: null,
  privateNote: null,
  parentNote: null,
  markedAt: null,
  markedByName: null,
  createdAt: "2026-10-11T05:00:00Z",
  ...overrides,
});

const schedule = (overrides: Partial<PtmTeacherSchedule> = {}): PtmTeacherSchedule => ({
  ptmTeacherId: "pt1",
  teacherId: "t1",
  name: "Ravi Kumar",
  kind: "CLASS_TEACHER",
  subjects: null,
  availableFrom: "09:00:00",
  availableTo: "09:40:00",
  booked: 1,
  free: 2,
  slots: [
    { id: "s0", startsAt: "09:00:00", endsAt: "09:10:00", blocked: false, booking: null },
    { id: "s1", startsAt: "09:10:00", endsAt: "09:20:00", blocked: true, booking: null },
    { id: "s2", startsAt: "09:30:00", endsAt: "09:40:00", blocked: false, booking: booking() },
  ],
  ...overrides,
});

const detail = (overrides: Partial<PtmEventDetail> = {}): PtmEventDetail => ({
  id: "e1",
  title: "Half-yearly parent-teacher meeting",
  date: "2099-10-24",
  startsAt: "09:00:00",
  endsAt: "12:30:00",
  slotMinutes: 10,
  breaks: [{ from: "10:30:00", to: "10:45:00" }],
  mode: "IN_PERSON",
  location: "Class 5 classrooms",
  videoLink: null,
  includeSubjectTeachers: false,
  cutoffHours: 12,
  note: null,
  status: "SCHEDULED",
  cancelledByName: null,
  cancelReason: null,
  cancelledAt: null,
  createdByName: "Lakshmi Iyer",
  createdAt: "2026-10-11T05:00:00Z",
  past: false,
  sections: [{ id: "secA", label: "Class 5 A", classTeacherId: "t1", classTeacherName: "Ravi Kumar", students: 30, studentsBooked: 1 }],
  teachers: [schedule()],
  remindersEnabled: true,
  ...overrides,
});

afterEach(() => vi.clearAllMocks());

describe("PTM for staff", () => {
  it("lists the meetings and the reminders switch for leadership", async () => {
    m.events.mockResolvedValue([
      {
        id: "e1",
        title: "Half-yearly parent-teacher meeting",
        date: "2099-10-24",
        startsAt: "09:00:00",
        endsAt: "12:30:00",
        slotMinutes: 10,
        mode: "IN_PERSON",
        location: "Class 5 classrooms",
        status: "SCHEDULED",
        sections: [{ id: "secA", label: "Class 5 A" }],
        teachers: 1,
        slots: 20,
        booked: 3,
        attended: 0,
        noShow: 0,
        past: false,
      },
    ]);
    m.settings.mockResolvedValue({ remindersEnabled: false });
    m.saveSettings.mockResolvedValue({ remindersEnabled: true });
    m.mine.mockResolvedValue([]);
    const user = userEvent.setup();
    renderAs(PRINCIPAL, <PtmHomeView />, ["PRINCIPAL"]);
    const list = await screen.findByTestId("ptm-events");
    expect(within(list).getAllByRole("link", { name: "Half-yearly parent-teacher meeting" })[0]).toHaveAttribute("href", "/app/ptm/e1");
    expect(list).toHaveTextContent("3 / 20");
    expect(screen.getByTestId("ptm-mine")).toHaveTextContent("You have no parent-teacher meetings.");
    await user.click(await screen.findByRole("checkbox", { name: /Remind parents a day before/ }));
    await waitFor(() => expect(m.saveSettings).toHaveBeenCalledWith(true));
  });

  it("shows every teacher's slots to leadership and blocks a free one", async () => {
    const user = userEvent.setup();
    m.event.mockResolvedValue(detail());
    m.block.mockResolvedValue({ id: "s0", startsAt: "09:00:00", endsAt: "09:10:00", blocked: true, booking: null });
    renderAs(PRINCIPAL, <PtmEventView id="e1" />, ["PRINCIPAL"]);
    expect(await screen.findByTestId("ptm-info")).toHaveTextContent("24 Oct 2099 · 09:00–12:30");
    expect(screen.getByTestId("ptm-info")).toHaveTextContent("10 minutes · breaks 10:30–10:45");
    expect(screen.getByTestId("ptm-sections")).toHaveTextContent("1 of 30 booked");
    const card = screen.getByTestId("ptm-schedule");
    expect(card).toHaveTextContent("Class teacher · 09:00–09:40");
    const rows = within(card).getAllByTestId("ptm-slot-row");
    expect(rows[0]).toHaveTextContent("Free");
    expect(rows[1]).toHaveTextContent("Blocked");
    expect(rows[2]).toHaveTextContent("Arjun Sharma");
    expect(rows[2]).toHaveTextContent("Anitha Sharma");
    expect(rows[2]).toHaveTextContent("Not marked");
    expect(screen.getByRole("link", { name: "Print schedules" })).toHaveAttribute("href", "/app/ptm/e1/print");
    await user.click(within(rows[0]).getByRole("button", { name: "Block" }));
    await waitFor(() => expect(m.block).toHaveBeenCalledWith("s0", true));
    expect(m.event).toHaveBeenCalledTimes(2);
  });

  it("gives a teacher their own schedule and lets them add notes before the day", async () => {
    const user = userEvent.setup();
    const mine: PtmMyEvent = {
      event: {
        id: "e1",
        title: "Half-yearly parent-teacher meeting",
        date: "2099-10-24",
        startsAt: "09:00:00",
        endsAt: "12:30:00",
        slotMinutes: 10,
        mode: "IN_PERSON",
        location: "Class 5 classrooms",
        status: "SCHEDULED",
        sections: [{ id: "secA", label: "Class 5 A" }],
        teachers: 1,
        slots: 3,
        booked: 1,
        attended: 0,
        noShow: 0,
        past: false,
      },
      videoLink: null,
      note: null,
      cancelReason: null,
      cutoffHours: 12,
      schedule: schedule(),
    };
    m.myEvent.mockResolvedValue(mine);
    m.myMark.mockResolvedValue(booking({ privateNote: "Needs reading support" }));
    renderAs(TEACHER, <PtmEventView id="e1" />, ["TEACHER"]);
    const card = await screen.findByTestId("ptm-schedule");
    expect(within(card).queryByRole("button", { name: "Book for a family" })).not.toBeInTheDocument();
    await user.click(within(within(card).getAllByTestId("ptm-slot-row")[2]).getByRole("button", { name: "Mark" }));
    const dialog = screen.getByRole("dialog", { name: "Arjun Sharma at 09:30" });
    expect(within(dialog).getByText("You can mark attendance on the day of the meeting.")).toBeInTheDocument();
    expect(within(dialog).getByRole("radio", { name: "Came" })).toBeDisabled();
    await user.type(within(dialog).getByRole("textbox", { name: "Private note" }), "Needs reading support");
    await user.click(within(dialog).getByRole("button", { name: "Save" }));
    await waitFor(() =>
      expect(m.myMark).toHaveBeenCalledWith("b1", { attendance: null, privateNote: "Needs reading support", parentNote: null }),
    );
  });
});

describe("meeting form and print helpers", () => {
  it("checks the meeting form as the API does", () => {
    const today = "2026-10-11";
    const valid = { ...emptyEventForm(), title: "PTM", date: "2026-10-24", sectionIds: ["secA"] };
    expect(eventProblems(valid, today)).toEqual({});
    expect(eventProblems({ ...valid, title: " ", date: "2026-10-01" }, today)).toEqual({ title: "ptm.v.title", date: "ptm.v.datePast" });
    // An existing meeting keeps its own past date.
    expect(eventProblems({ ...valid, date: "2026-10-01" }, today, "2026-10-01")).toEqual({});
    expect(eventProblems({ ...valid, endsAt: "08:00" }, today).endsAt).toBe("ptm.v.timeOrder");
    expect(eventProblems({ ...valid, breaks: [{ from: "08:00", to: "08:15" }] }, today).breaks).toBe("ptm.v.breakInside");
    expect(
      eventProblems({ ...valid, breaks: [{ from: "10:00", to: "10:30" }, { from: "10:15", to: "10:45" }] }, today).breaks,
    ).toBe("ptm.v.breakOverlap");
    expect(eventProblems({ ...valid, mode: "VIDEO", videoLink: "meet.example/x" }, today).videoLink).toBe("ptm.v.videoLink");
    expect(eventProblems({ ...valid, sectionIds: [], cutoffHours: "80" }, today)).toEqual({
      sectionIds: "ptm.v.sections",
      cutoffHours: "ptm.v.cutoff",
    });
    expect(eventRequest({ ...valid, location: "  Hall ", note: " " })).toMatchObject({
      title: "PTM",
      slotMinutes: 10,
      location: "Hall",
      videoLink: null,
      note: null,
      cutoffHours: 12,
    });
  });

  it("collects a class's bookings across teachers by student and time", () => {
    const meera = schedule({
      ptmTeacherId: "pt2",
      teacherId: "t2",
      name: "Meera Das",
      slots: [
        { id: "x1", startsAt: "09:00:00", endsAt: "09:10:00", blocked: false, booking: booking({ id: "b2", startsAt: "09:00:00", teacherName: "Meera Das" }) },
        { id: "x2", startsAt: "09:10:00", endsAt: "09:20:00", blocked: false, booking: booking({ id: "b3", student: { id: "st9", name: "Bala Devi", classLabel: "Class 5 B", sectionId: "secB" } }) },
      ],
    });
    expect(classBookings("secA", [schedule(), meera]).map((b) => [b.student.name, b.teacherName, b.startsAt])).toEqual([
      ["Arjun Sharma", "Meera Das", "09:00:00"],
      ["Arjun Sharma", "Ravi Kumar", "09:30:00"],
    ]);
  });
});
