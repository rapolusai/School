import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { code128Bars } from "@/lib/code128";
import type { LabelCopy } from "@/lib/types";
import { renderAs } from "@/test/render";
import { LabelSheet, labelsPerSheet, sheetsOf } from "./label-sheet";
import { LabelsView } from "./labels-view";
import { shortTitle } from "./library-shared";

const m = vi.hoisted(() => ({ labels: vi.fn() }));

vi.mock("@/lib/library-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/library-api")>();
  return { ...actual, libraryApi: { ...actual.libraryApi, labels: m.labels } };
});
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/library/labels",
}));

const LIBRARIAN = ["dashboard.view", "library.read", "library.manage"];

function copy(n: number, overrides: Partial<LabelCopy> = {}): LabelCopy {
  return {
    id: `c${n}`,
    accessionNo: `LIB${String(n).padStart(5, "0")}`,
    titleId: "t1",
    title: "Wings of Fire: An Autobiography of A P J Abdul Kalam",
    authors: "A P J Abdul Kalam",
    callNumber: "923.7 KAL",
    status: "AVAILABLE",
    ...overrides,
  };
}

const copies = (count: number) => Array.from({ length: count }, (_, i) => copy(i + 1));

afterEach(() => vi.clearAllMocks());

describe("sheetsOf", () => {
  it("fills A4 sheets of 3 × 8 barcode labels and 5 × 13 spine labels", () => {
    expect(labelsPerSheet("barcode")).toBe(24);
    expect(labelsPerSheet("spine")).toBe(65);
    expect(sheetsOf(copies(24), "barcode").map((s) => s.length)).toEqual([24]);
    expect(sheetsOf(copies(25), "barcode").map((s) => s.length)).toEqual([24, 1]);
    expect(sheetsOf(copies(70), "spine").map((s) => s.length)).toEqual([65, 5]);
    expect(sheetsOf([], "barcode")).toEqual([]);
  });

  it("leaves the labels already used on a part sheet empty", () => {
    const sheets = sheetsOf(copies(3), "barcode", 22);
    expect(sheets).toHaveLength(2);
    expect(sheets[0].slice(0, 22).every((place) => place === null)).toBe(true);
    expect(sheets[0].slice(22).map((c) => c?.accessionNo)).toEqual(["LIB00001", "LIB00002"]);
    expect(sheets[1].map((c) => c?.accessionNo)).toEqual(["LIB00003"]);
    // At most a whole sheet less one can be skipped.
    expect(sheetsOf(copies(1), "barcode", 99)[0].filter((p) => p === null)).toHaveLength(23);
  });
});

describe("LabelSheet", () => {
  it("draws a Code 128 barcode of each accession number with the number and a short title under it", () => {
    render(<LabelSheet copies={copies(2)} kind="barcode" />);
    const labels = screen.getAllByTestId("label");
    expect(labels).toHaveLength(2);
    const first = labels[0];
    const svg = within(first).getByRole("img", { name: "Barcode LIB00001" });
    const drawn = code128Bars("LIB00001");
    expect(svg).toHaveAttribute("viewBox", `0 0 ${drawn.modules} 28`);
    // a white background and one black rectangle per bar
    expect(svg.querySelectorAll('rect[fill="#000"]')).toHaveLength(drawn.bars.length);
    const bars = Array.from(svg.querySelectorAll('rect[fill="#000"]')).map((r) => [Number(r.getAttribute("x")), Number(r.getAttribute("width"))]);
    expect(bars).toEqual(drawn.bars);
    expect(first).toHaveTextContent("LIB00001");
    expect(first).toHaveTextContent("Wings of Fire: An…");
    expect(screen.getByRole("region", { name: "Sheet 1 of 1" })).toBeInTheDocument();
  });

  it("puts blank places first and starts a new sheet after 24 labels", () => {
    render(<LabelSheet copies={copies(25)} kind="barcode" skip={2} />);
    const sheets = screen.getAllByRole("region");
    expect(sheets.map((s) => s.getAttribute("aria-label"))).toEqual(["Sheet 1 of 2", "Sheet 2 of 2"]);
    expect(within(sheets[0]).getAllByTestId("label")).toHaveLength(22);
    expect(within(sheets[1]).getAllByTestId("label")).toHaveLength(3);
    expect(sheets[0].querySelectorAll(".label-blank")).toHaveLength(2);
  });

  it("prints spine labels with the call number on its own lines", () => {
    render(<LabelSheet copies={[copy(1), copy(2, { callNumber: null })]} kind="spine" />);
    const [first, second] = screen.getAllByTestId("spine-label");
    expect(within(first).getByText("923.7")).toBeInTheDocument();
    expect(within(first).getByText("KAL")).toBeInTheDocument();
    expect(first).toHaveTextContent("LIB00001");
    expect(second).toHaveTextContent("—LIB00002");
    expect(screen.queryByRole("img")).not.toBeInTheDocument();
  });
});

describe("shortTitle", () => {
  it("cuts long titles at a word with an ellipsis", () => {
    expect(shortTitle("Matilda")).toBe("Matilda");
    expect(shortTitle("Wings of Fire: An Autobiography of A P J Abdul Kalam")).toBe("Wings of Fire: An…");
    expect(shortTitle("  The   Jungle Book ")).toBe("The Jungle Book");
    expect(shortTitle("Supercalifragilisticexpialidocious-and-more-words", 20)).toHaveLength(20);
  });
});

describe("LabelsView", () => {
  it("lists a title's copies, prints only the chosen ones and checks the starting label", async () => {
    const user = userEvent.setup();
    m.labels.mockResolvedValue([copy(1), copy(2), copy(3, { status: "ISSUED" })]);
    renderAs(LIBRARIAN, <LabelsView initialTitleId="t1" />, ["LIBRARIAN"]);

    await waitFor(() => expect(m.labels).toHaveBeenCalledWith({ titleId: "t1" }));
    expect(await screen.findByTestId("labels-count")).toHaveTextContent("3 of 3 copies chosen · 1 A4 sheet");
    expect(screen.getAllByTestId("label")).toHaveLength(3);
    expect(screen.getByText("Lent")).toBeInTheDocument();

    await user.click(screen.getByRole("checkbox", { name: /LIB00002/ }));
    expect(screen.getAllByTestId("label").map((l) => l.querySelector(".label-accession")?.textContent)).toEqual([
      "LIB00001",
      "LIB00003",
    ]);

    const startAt = screen.getByLabelText("Start at label (1–24)");
    await user.clear(startAt);
    await user.type(startAt, "30");
    expect(screen.getByText("Choose a label from 1 to 24.")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Print labels" })).toBeDisabled();
    await user.clear(startAt);
    await user.type(startAt, "24");
    expect(screen.getByRole("button", { name: "Print labels" })).toBeEnabled();
    expect(screen.getByTestId("labels-count")).toHaveTextContent("2 of 3 copies chosen · 2 A4 sheets");

    await user.click(screen.getByRole("radio", { name: "Spine labels (5 × 13)" }));
    expect(screen.getAllByTestId("spine-label")).toHaveLength(2);
  });

  it("finds copies by accession number range", async () => {
    const user = userEvent.setup();
    m.labels.mockResolvedValue([]);
    renderAs(LIBRARIAN, <LabelsView />, ["LIBRARIAN"]);
    expect(await screen.findByText("No copies found.")).toBeInTheDocument();
    await user.type(screen.getByLabelText("From accession no."), "LIB00001");
    await user.type(screen.getByLabelText("To accession no."), "LIB00010");
    await user.click(screen.getByRole("button", { name: "Find copies" }));
    await waitFor(() => expect(m.labels).toHaveBeenLastCalledWith({ from: "LIB00001", to: "LIB00010", q: undefined }));
  });
});
