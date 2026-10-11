import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import type { PtmFamily, PtmFamilyEvent, PtmFamilySlot, PtmFamilyTeacher } from "@/lib/types";
import { renderAs } from "@/test/render";
import { canPick, FamilyPtmView } from "./family-ptm";

const m = vi.hoisted(() => ({ family: vi.fn(), familyBook: vi.fn(), familyMove: vi.fn(), familyCancel: vi.fn() }));

vi.mock("@/lib/chat-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/chat-api")>();
  return {
    ...actual,
    ptmApi: { ...actual.ptmApi, family: m.family, familyBook: m.familyBook, familyMove: m.familyMove, familyCancel: m.familyCancel },
  };
});
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/family/ptm",
}));

const PARENT = ["dashboard.view", "child.view"];
const LATER = "2099-10-24T00:00:00Z";
const GONE = "2000-01-01T00:00:00Z";

const slot = (id: string, startsAt: string, endsAt: string, available = true, bookableUntil = LATER): PtmFamilySlot => ({
  id,
  startsAt,
  endsAt,
  available,
  bookableUntil,
});

const ravi = (overrides: Partial<PtmFamilyTeacher> = {}): PtmFamilyTeacher => ({
  teacherId: "t1",
  name: "Ravi Kumar",
  detail: "Class teacher",
  slots: [
    slot("s0900", "09:00:00", "09:10:00", false),
    slot("s0910", "09:10:00", "09:20:00"),
    slot("s0920", "09:20:00", "09:30:00"),
    slot("s0930", "09:30:00", "09:40:00", true, GONE),
  ],
  booking: null,
  ...overrides,
});

const event = (overrides: Partial<PtmFamilyEvent> = {}): PtmFamilyEvent => ({
  id: "e1",
  title: "Half-yearly parent-teacher meeting",
  date: "2099-10-24",
  startsAt: "09:00:00",
  endsAt: "12:30:00",
  slotMinutes: 10,
  mode: "IN_PERSON",
  location: "Class 5 classrooms, first floor",
  videoLink: null,
  note: null,
  status: "SCHEDULED",
  cancelReason: null,
  cutoffHours: 12,
  past: false,
  teachers: [ravi()],
  ...overrides,
});

const family = (events: PtmFamilyEvent[] = [event()]): PtmFamily => ({
  children: [{ studentId: "st1", name: "Arjun Sharma", classLabel: "Class 5 A", events }],
  remindersEnabled: true,
  serverTime: "2026-10-11T10:00:00Z",
});

afterEach(() => vi.clearAllMocks());

describe("FamilyPtmView", () => {
  it("shows free and taken slots and books one for the child", async () => {
    const user = userEvent.setup();
    m.family.mockResolvedValue(family());
    m.familyBook.mockResolvedValue({ id: "b1", slotId: "s0910", startsAt: "09:10:00", endsAt: "09:20:00", bookedBy: "PARENT", canChange: true, changeUntil: LATER, attendance: null, parentNote: null });
    renderAs(PARENT, <FamilyPtmView />, ["PARENT"]);

    const card = await screen.findByTestId("ptm-family-event");
    expect(card).toHaveTextContent("Half-yearly parent-teacher meeting");
    expect(card).toHaveTextContent("24 Oct 2099 · 09:00–12:30");
    expect(card).toHaveTextContent("At school: Class 5 classrooms, first floor");
    const teacher = within(card).getByTestId("ptm-family-teacher");
    expect(teacher).toHaveTextContent("Ravi Kumar");
    expect(teacher).toHaveTextContent("Not booked");
    const grid = within(teacher).getByTestId("ptm-slots");
    expect(within(grid).getByRole("button", { name: "09:00 is taken" })).toBeDisabled();
    expect(within(grid).getByRole("button", { name: "09:30 is taken" })).toBeDisabled();
    expect(teacher).toHaveTextContent("2 free slots.");

    await user.click(within(grid).getByRole("button", { name: "Book 09:10" }));
    const dialog = screen.getByRole("dialog", { name: "Book 09:10 with Ravi Kumar?" });
    expect(dialog).toHaveTextContent("For Arjun Sharma on 24 Oct 2099. You can change or cancel until 12 hours before the slot.");
    await user.click(within(dialog).getByRole("button", { name: "Book" }));
    await waitFor(() => expect(m.familyBook).toHaveBeenCalledWith("s0910", "st1"));
    await waitFor(() => expect(m.family).toHaveBeenCalledTimes(2));
    expect(await screen.findByText("Booked for 09:10.")).toBeInTheDocument();
    expect(screen.getByText("You get a reminder the day before and an hour before your slot.")).toBeInTheDocument();
  });

  it("shows the API's refusal when another family took the slot first", async () => {
    const user = userEvent.setup();
    m.family.mockResolvedValue(family());
    m.familyBook.mockRejectedValue(
      new ApiError({ status: 409, title: "Conflict", detail: "This slot was just booked by another family. Choose another." }),
    );
    renderAs(PARENT, <FamilyPtmView />, ["PARENT"]);
    await user.click(await screen.findByRole("button", { name: "Book 09:20" }));
    const dialog = screen.getByRole("dialog");
    await user.click(within(dialog).getByRole("button", { name: "Book" }));
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("This slot was just booked by another family.");
  });

  it("changes or cancels a booking until the cut-off", async () => {
    const user = userEvent.setup();
    const booking = { id: "b1", slotId: "s0910", startsAt: "09:10:00", endsAt: "09:20:00", bookedBy: "PARENT" as const, canChange: true, changeUntil: "2099-10-23T09:10:00Z", attendance: null, parentNote: null };
    m.family.mockResolvedValue(family([event({ teachers: [ravi({ booking })] })]));
    m.familyMove.mockResolvedValue({ ...booking, slotId: "s0920", startsAt: "09:20:00", endsAt: "09:30:00" });
    m.familyCancel.mockResolvedValue(undefined);
    renderAs(PARENT, <FamilyPtmView />, ["PARENT"]);

    const teacher = await screen.findByTestId("ptm-family-teacher");
    expect(teacher).toHaveTextContent("Booked 09:10–09:20");
    expect(within(teacher).queryByTestId("ptm-slots")).not.toBeInTheDocument();

    await user.click(within(teacher).getByRole("button", { name: "Change time" }));
    await user.click(within(teacher).getByRole("button", { name: "Book 09:20" }));
    const move = screen.getByRole("dialog", { name: "Move to 09:20 with Ravi Kumar?" });
    await user.click(within(move).getByRole("button", { name: "Move" }));
    await waitFor(() => expect(m.familyMove).toHaveBeenCalledWith("b1", "s0920"));

    await user.click(within(teacher).getByRole("button", { name: "Cancel booking" }));
    const cancel = screen.getByRole("dialog", { name: "Cancel this meeting?" });
    expect(cancel).toHaveTextContent("Cancel the meeting with Ravi Kumar at 09:10?");
    await user.click(within(cancel).getByRole("button", { name: "Cancel booking" }));
    await waitFor(() => expect(m.familyCancel).toHaveBeenCalledWith("b1"));
  });

  it("shows whether the family came and the teacher's note after the meeting", async () => {
    m.family.mockResolvedValue(
      family([
        event({
          past: true,
          teachers: [
            ravi({
              booking: { id: "b1", slotId: "s0910", startsAt: "09:10:00", endsAt: "09:20:00", bookedBy: "PARENT", canChange: false, changeUntil: GONE, attendance: "ATTENDED", parentNote: "Practise tables daily." },
            }),
          ],
        }),
      ]),
    );
    renderAs(PARENT, <FamilyPtmView />, ["PARENT"]);
    const teacher = await screen.findByTestId("ptm-family-teacher");
    expect(teacher).toHaveTextContent("Came");
    expect(within(teacher).getByTestId("ptm-family-note")).toHaveTextContent("Teacher's note: Practise tables daily.");
    expect(within(teacher).queryByRole("button")).not.toBeInTheDocument();
  });

  it("says so when no meeting is planned or the meeting was cancelled", async () => {
    m.family.mockResolvedValue(family([]));
    const { unmount } = renderAs(PARENT, <FamilyPtmView />, ["PARENT"]);
    expect(await screen.findByTestId("ptm-family-empty")).toHaveTextContent("No parent-teacher meeting is planned for Arjun Sharma.");
    unmount();
    m.family.mockResolvedValue(family([event({ status: "CANCELLED", cancelReason: "Heavy rain." })]));
    renderAs(PARENT, <FamilyPtmView />, ["PARENT"]);
    expect(await screen.findByText("This meeting was cancelled. Heavy rain.")).toBeInTheDocument();
    expect(screen.queryByTestId("ptm-slots")).not.toBeInTheDocument();
  });
});

describe("canPick", () => {
  it("offers only free slots before the cut-off", () => {
    const now = new Date("2026-10-11T10:00:00Z");
    expect(canPick(slot("a", "09:00:00", "09:10:00"), now)).toBe(true);
    expect(canPick(slot("a", "09:00:00", "09:10:00", false), now)).toBe(false);
    expect(canPick(slot("a", "09:00:00", "09:10:00", true, "2026-10-11T09:59:59Z"), now)).toBe(false);
  });
});
