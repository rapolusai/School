import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { CalendarEntryList, HolidaySuggestions } from "@/lib/types";
import { entry } from "@/test/communication-fixtures";
import { renderAs } from "@/test/render";
import { CalendarView } from "./calendar-view";
import { entryValues, toEntryRequest, validateEntry } from "./calendar-entry-dialog";
import { addMonths, entriesByDay, monthGridDays } from "./communication-labels";
import { chosenHolidays, suggestionRows } from "./holiday-suggestions-dialog";

const { entries, create, createAll, holidaySuggestions, ics, saveTextFile } = vi.hoisted(() => ({
  entries: vi.fn(),
  create: vi.fn(),
  createAll: vi.fn(),
  holidaySuggestions: vi.fn(),
  ics: vi.fn(),
  saveTextFile: vi.fn(),
}));

vi.mock("@/lib/communication-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/communication-api")>();
  return {
    ...actual,
    calendarApi: { ...actual.calendarApi, entries, create, createAll, holidaySuggestions, ics },
  };
});

vi.mock("@/lib/attendance-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/attendance-api")>();
  return { ...actual, saveTextFile };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/calendar",
}));

const PARENT = ["dashboard.view", "child.view", "notices.read"];
const ADMIN = ["dashboard.view", "notices.send", "notices.read", "notices.approve", "calendar.manage", "settings.manage"];

const GANDHI = entry();
const DUSSEHRA = entry({
  id: "e2",
  title: "Dussehra break",
  startsOn: "2026-10-19",
  endsOn: "2026-10-21",
});
const PTM = entry({
  id: "e3",
  kind: "PTM",
  title: "Parent-teacher meeting",
  description: "Meet the class teacher in the classroom.",
  startsOn: "2026-10-24",
  endsOn: "2026-10-24",
  startTime: "09:00",
  endTime: "12:30",
  audience: "CLASSES",
  classes: [{ id: "c5", name: "Class 5" }],
  reminderDays: 2,
  reminderChannels: ["SMS"],
});

function list(canManage: boolean): CalendarEntryList {
  return {
    from: "2026-09-28",
    to: "2026-11-01",
    canManage,
    classes: [
      { id: "c5", name: "Class 5" },
      { id: "c6", name: "Class 6" },
    ],
    entries: [GANDHI, DUSSEHRA, PTM],
  };
}

const SUGGESTIONS: HolidaySuggestions = {
  academicYearId: "y1",
  academicYearName: "2026-27",
  from: "2026-04-01",
  to: "2027-03-31",
  items: [
    { date: "2026-08-15", title: "Independence Day", group: "NATIONAL", needsConfirmation: false, alreadyAdded: true },
    { date: "2026-10-02", title: "Gandhi Jayanti", group: "NATIONAL", needsConfirmation: false, alreadyAdded: false },
    { date: "2026-11-08", title: "Diwali", group: "FESTIVAL", needsConfirmation: true, alreadyAdded: false },
  ],
};

const day = (date: string) => within(screen.getByTestId("calendar-grid")).getByRole("group", { name: date });

beforeEach(() => {
  entries.mockResolvedValue(list(true));
});

describe("CalendarView", () => {
  it("shows the month with holidays, multi-day entries and timed meetings", async () => {
    entries.mockResolvedValue(list(false));
    const user = userEvent.setup();
    renderAs(PARENT, <CalendarView initialMonth="2026-10" />, ["PARENT"]);
    await screen.findByTestId("calendar-grid");
    expect(entries).toHaveBeenCalledWith("2026-09-28", "2026-11-01");
    expect(screen.getByRole("heading", { name: "October 2026" })).toBeInTheDocument();

    expect(day("2 Oct 2026")).toHaveTextContent("Gandhi Jayanti");
    expect(day("2 Oct 2026")).toHaveClass("is-holiday");
    for (const d of ["19 Oct 2026", "20 Oct 2026", "21 Oct 2026"]) expect(day(d)).toHaveTextContent("Dussehra break");
    expect(day("22 Oct 2026")).not.toHaveTextContent("Dussehra break");
    expect(day("24 Oct 2026")).toHaveTextContent("9:00 am Parent-teacher meeting");
    expect(day("24 Oct 2026")).not.toHaveClass("is-holiday");
    // Gandhi Jayanti and the three days of the Dussehra break.
    expect(screen.getByText("4 holidays this month")).toBeInTheDocument();
    // The phone agenda lists the same month by day.
    expect(within(screen.getByTestId("calendar-agenda")).getAllByRole("listitem")).toHaveLength(5);

    // Families only look.
    expect(screen.queryByRole("button", { name: "Add entry" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Holiday list" })).not.toBeInTheDocument();

    await user.click(within(day("24 Oct 2026")).getByRole("button", { name: /Parent-teacher meeting/ }));
    const details = await screen.findByTestId("entry-details");
    expect(details).toHaveTextContent("24 Oct 2026, 9:00 am – 12:30 pm");
    expect(details).toHaveTextContent("Classes: Class 5");
    expect(details).toHaveTextContent("2 days before, by Notice board, SMS");
    expect(details).toHaveTextContent("Meet the class teacher in the classroom.");
    expect(within(details).queryByRole("button", { name: "Edit" })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Next month" }));
    await waitFor(() => expect(entries).toHaveBeenLastCalledWith("2026-10-26", "2026-12-06"));
  });

  it("lets an admin add a holiday on a day, checking the form first", async () => {
    create.mockResolvedValue(entry({ id: "e9", title: "Founders Day", startsOn: "2026-10-15", endsOn: "2026-10-15" }));
    const user = userEvent.setup();
    renderAs(ADMIN, <CalendarView initialMonth="2026-10" />, ["SCHOOL_ADMIN"]);
    await screen.findByTestId("calendar-grid");

    await user.click(within(day("15 Oct 2026")).getByRole("button", { name: "Add an entry on 15 Oct 2026" }));
    const dialog = await screen.findByRole("dialog", { name: "Add a calendar entry" });
    expect(within(dialog).getByLabelText("Date")).toHaveValue("2026-10-15");
    await user.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(within(dialog).getByText("Fill in this field.")).toBeInTheDocument();

    await user.selectOptions(within(dialog).getByLabelText("Kind"), "HOLIDAY");
    await user.type(within(dialog).getByLabelText("Title"), "Founders Day");
    fireEvent.change(within(dialog).getByLabelText("Until"), { target: { value: "2026-10-14" } });
    await user.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(within(dialog).getByText("The end date must not be before the start date.")).toBeInTheDocument();
    fireEvent.change(within(dialog).getByLabelText("Until"), { target: { value: "" } });
    await user.click(within(dialog).getByRole("button", { name: "Save" }));

    await waitFor(() =>
      expect(create).toHaveBeenCalledWith({
        kind: "HOLIDAY",
        title: "Founders Day",
        description: null,
        startsOn: "2026-10-15",
        endsOn: null,
        startTime: null,
        endTime: null,
        audience: "SCHOOL",
        classIds: [],
        reminderDays: null,
        reminderChannels: [],
      }),
    );
    expect(await screen.findByText("Founders Day added to the calendar.")).toBeInTheDocument();
    expect(entries).toHaveBeenCalledTimes(2);
  });

  it("adds holidays from the starter list only after festival dates are confirmed", async () => {
    holidaySuggestions.mockResolvedValue(SUGGESTIONS);
    createAll.mockResolvedValue([entry(), entry({ id: "e8", title: "Diwali", startsOn: "2026-11-09" })]);
    const user = userEvent.setup();
    renderAs(ADMIN, <CalendarView initialMonth="2026-10" />, ["SCHOOL_ADMIN"]);
    await screen.findByTestId("calendar-grid");

    await user.click(screen.getByRole("button", { name: "Holiday list" }));
    const dialog = await screen.findByRole("dialog", { name: "Holiday starter list" });
    const starter = await within(dialog).findByTestId("starter-list");
    expect(within(starter).getByRole("checkbox", { name: "Independence Day, 15 Aug 2026" })).toBeDisabled();
    expect(within(starter).getByRole("checkbox", { name: "Gandhi Jayanti, 2 Oct 2026" })).toBeChecked();
    const diwali = within(starter).getByRole("checkbox", { name: "Diwali, 8 Nov 2026" });
    expect(diwali).not.toBeChecked();
    expect(within(starter).getAllByText("Check the date")).toHaveLength(1);

    await user.click(diwali);
    // The admin moves Diwali to the state's date.
    fireEvent.change(within(starter).getByLabelText("Date of Diwali"), { target: { value: "2026-11-09" } });
    await user.click(within(dialog).getByRole("button", { name: "Add to the calendar" }));
    expect(within(dialog).getByText("Confirm that you have checked the festival dates.")).toBeInTheDocument();
    expect(createAll).not.toHaveBeenCalled();

    await user.click(within(dialog).getByRole("checkbox", { name: /I have checked the festival dates/ }));
    await user.click(within(dialog).getByRole("button", { name: "Add to the calendar" }));
    await waitFor(() =>
      expect(createAll).toHaveBeenCalledWith([
        { kind: "HOLIDAY", title: "Gandhi Jayanti", startsOn: "2026-10-02", audience: "SCHOOL" },
        { kind: "HOLIDAY", title: "Diwali", startsOn: "2026-11-09", audience: "SCHOOL" },
      ]),
    );
    expect(await screen.findByText("2 holidays added.")).toBeInTheDocument();
  });

  it("downloads the calendar as an iCalendar file", async () => {
    ics.mockResolvedValue("BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n");
    const user = userEvent.setup();
    renderAs(PARENT, <CalendarView initialMonth="2026-10" />, ["PARENT"]);
    await screen.findByTestId("calendar-grid");
    await user.click(screen.getByRole("button", { name: "Download (.ics)" }));
    await waitFor(() =>
      expect(saveTextFile).toHaveBeenCalledWith(
        "school-calendar.ics",
        "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n",
        "text/calendar;charset=utf-8",
      ),
    );
  });
});

describe("calendar helpers", () => {
  it("lays out whole weeks from Monday to Sunday", () => {
    const days = monthGridDays("2026-10");
    expect(days[0]).toBe("2026-09-28");
    expect(days[days.length - 1]).toBe("2026-11-01");
    expect(days).toHaveLength(35);
    expect(monthGridDays("2027-02")).toHaveLength(28);
    expect(addMonths("2026-12", 1)).toBe("2027-01");
    expect(addMonths("2026-01", -1)).toBe("2025-12");
    const byDay = entriesByDay([DUSSEHRA, GANDHI], days);
    expect(byDay.get("2026-10-20")?.map((e) => e.title)).toEqual(["Dussehra break"]);
    expect(byDay.get("2026-10-02")?.map((e) => e.title)).toEqual(["Gandhi Jayanti"]);
  });

  it("checks entries like the API and builds the request", () => {
    const values = { ...entryValues(null, "2026-10-24"), title: "PTM", kind: "PTM" as const };
    expect(validateEntry(values)).toEqual({});
    expect(validateEntry({ ...values, allDay: false, startTime: "" }).startTime).toBe("calendar.v.time");
    expect(validateEntry({ ...values, allDay: false, startTime: "10:00", endTime: "09:00" }).endTime).toBe(
      "calendar.v.endTime",
    );
    expect(validateEntry({ ...values, endsOn: "2027-03-01" }).endsOn).toBe("calendar.v.tooLong");
    expect(validateEntry({ ...values, audience: "CLASSES" }).classIds).toBe("calendar.v.classes");
    expect(validateEntry({ ...values, remind: true, reminderDays: "31" }).reminderDays).toBe("calendar.v.reminder");
    expect(
      toEntryRequest({
        ...values,
        allDay: false,
        startTime: "09:00",
        endTime: "12:30",
        audience: "CLASSES",
        classIds: ["c5"],
        remind: true,
        reminderDays: "2",
        reminderChannels: ["SMS"],
      }),
    ).toMatchObject({ startTime: "09:00", endTime: "12:30", classIds: ["c5"], reminderDays: 2, reminderChannels: ["SMS"] });
    expect(entryValues(PTM, "2026-10-01")).toMatchObject({ allDay: false, startTime: "09:00", endsOn: "", remind: true });
  });

  it("starts the starter list with national holidays ticked and festivals unticked", () => {
    const rows = suggestionRows(SUGGESTIONS.items);
    expect(rows.map((r) => r.selected)).toEqual([false, true, false]);
    expect(chosenHolidays(rows, false).entries).toHaveLength(1);
    const withDiwali = rows.map((r) => (r.title === "Diwali" ? { ...r, selected: true } : r));
    expect(chosenHolidays(withDiwali, false).problem).toBe("confirm");
    expect(chosenHolidays(withDiwali, true).entries.map((e) => e.title)).toEqual(["Gandhi Jayanti", "Diwali"]);
    expect(chosenHolidays(rows.map((r) => ({ ...r, selected: false })), true).problem).toBe("none");
  });
});
