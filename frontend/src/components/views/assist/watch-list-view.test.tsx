import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { WatchList, WatchRow } from "@/lib/types";
import { renderAs } from "@/test/render";
import { WatchListView } from "./watch-list-view";

const m = vi.hoisted(() => ({
  settings: vi.fn(),
  watchList: vi.fn(),
  runWatchList: vi.fn(),
  watchEntry: vi.fn(),
  assign: vi.fn(),
  addNote: vi.fn(),
  informParent: vi.fn(),
  setStatus: vi.fn(),
  listClasses: vi.fn(),
}));

vi.mock("@/lib/assist-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/assist-api")>();
  return {
    ...actual,
    assistApi: {
      ...actual.assistApi,
      settings: m.settings,
      watchList: m.watchList,
      runWatchList: m.runWatchList,
      watchEntry: m.watchEntry,
      assign: m.assign,
      addNote: m.addNote,
      informParent: m.informParent,
      setStatus: m.setStatus,
    },
  };
});

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, listClasses: m.listClasses } };
});

vi.mock("next/navigation", () => ({ usePathname: () => "/app/assist/watch-list", useRouter: () => ({ push: vi.fn() }) }));

const PRINCIPAL = ["dashboard.view", "assist.use"];

const BALA: WatchRow = {
  id: "w1",
  studentId: "st2",
  studentName: "Bala Rao",
  sectionId: "s5a",
  classLabel: "Class 5 A",
  score: 74,
  level: "HIGH",
  reasons: [
    { signal: "ATTENDANCE_30", points: 25, value: 25, detail: "Attendance 25% in the last 30 days" },
    { signal: "MARKS_LOW", points: 25, value: 27.5, detail: "27.5% in Unit Test 1" },
  ],
  computedAt: "2026-10-22T20:00:00Z",
  status: "OPEN",
  assignedToId: null,
  assignedToName: null,
  parentInformedAt: null,
  notes: 0,
};

const LIST: WatchList = {
  lastRunAt: "2026-10-22T20:00:00Z",
  lastRunStudents: 2,
  lastRunListed: 1,
  high: 1,
  medium: 0,
  low: 0,
  assignees: [{ id: "u-ravi", name: "Ravi Kumar" }],
  entries: [BALA],
};

describe("WatchListView", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    m.settings.mockResolvedValue({ enabled: true, earlyWarningEnabled: true, simulated: true });
    m.listClasses.mockResolvedValue([]);
    m.watchList.mockResolvedValue(LIST);
    m.watchEntry.mockResolvedValue({ entry: BALA, notes: [] });
  });

  it("lists students with every reason and its points, staff only", async () => {
    renderAs(PRINCIPAL, <WatchListView />);
    const row = await screen.findByTestId("watch-row");
    expect(row).toHaveTextContent("Bala Rao");
    expect(row).toHaveTextContent("Score 74");
    expect(row).toHaveTextContent("High");
    expect(row).toHaveTextContent("Attendance 25% in the last 30 days");
    expect(row).toHaveTextContent("+25");
    expect(row).toHaveTextContent("Not assigned");
    expect(screen.getByText(/nothing here is shown to parents or students/)).toBeInTheDocument();
  });

  it("runs the score now and reloads", async () => {
    m.runWatchList.mockResolvedValue({ at: "2026-10-23T05:00:00Z", students: 60, listed: 4 });
    renderAs(PRINCIPAL, <WatchListView />);
    await screen.findByTestId("watch-row");
    await userEvent.setup().click(screen.getByTestId("assist-run-now"));
    expect(await screen.findByText("Checked 60 students; 4 on the list.")).toBeInTheDocument();
    expect(m.watchList).toHaveBeenCalledTimes(2);
  });

  it("assigns a student, adds a note and marks the parent informed", async () => {
    const assigned = { ...BALA, assignedToId: "u-ravi", assignedToName: "Ravi Kumar", status: "FOLLOWING" as const };
    m.assign.mockResolvedValue({ entry: assigned, notes: [] });
    m.addNote.mockResolvedValue({ entry: assigned, notes: [] });
    m.informParent.mockResolvedValue({ entry: assigned, notes: [] });
    const user = userEvent.setup();
    renderAs(PRINCIPAL, <WatchListView />);
    await user.click(await screen.findByRole("button", { name: "Open Bala Rao" }));
    const dialog = await screen.findByTestId("watch-detail");
    await user.selectOptions(within(dialog).getByLabelText("Assign to"), "u-ravi");
    await waitFor(() => expect(m.assign).toHaveBeenCalledWith("w1", "u-ravi"));
    await user.type(within(dialog).getByLabelText("Note"), "Call home on Monday.");
    await user.click(within(dialog).getByRole("button", { name: "Add note" }));
    await waitFor(() => expect(m.addNote).toHaveBeenCalledWith("w1", "Call home on Monday."));
    await user.click(within(dialog).getByRole("button", { name: "Mark parent informed" }));
    await waitFor(() => expect(m.informParent).toHaveBeenCalledWith("w1"));
  });

  it("says when the list has never been computed", async () => {
    m.watchList.mockResolvedValue({ ...LIST, lastRunAt: null, high: 0, entries: [] });
    renderAs(PRINCIPAL, <WatchListView />);
    expect(await screen.findByText("The watch list has not been computed yet. Use Run now.")).toBeInTheDocument();
  });
});
