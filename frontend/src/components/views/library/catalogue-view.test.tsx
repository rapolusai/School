import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { TitleDetail, TitlePage } from "@/lib/types";
import { renderAs } from "@/test/render";
import { CatalogueView } from "./catalogue-view";

const m = vi.hoisted(() => ({
  titles: vi.fn(),
  subjects: vi.fn(),
  summary: vi.fn(),
  isbn: vi.fn(),
  createTitle: vi.fn(),
  push: vi.fn(),
}));

vi.mock("@/lib/library-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/library-api")>();
  return {
    ...actual,
    libraryApi: { ...actual.libraryApi, titles: m.titles, subjects: m.subjects, summary: m.summary, isbn: m.isbn, createTitle: m.createTitle },
  };
});
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: m.push }),
  usePathname: () => "/app/library",
}));

const LIBRARIAN = ["dashboard.view", "library.read", "library.manage"];
const TEACHER = ["dashboard.view", "students.read", "library.read"];

const PAGE: TitlePage = {
  items: [
    {
      id: "t1",
      isbn13: "9788171673407",
      title: "The Jungle Book",
      subtitle: null,
      authors: "Rudyard Kipling",
      subject: "Fiction",
      callNumber: "823 KIP",
      shelfLocation: "Shelf A2",
      copies: 2,
      available: 0,
      issued: 2,
    },
  ],
  page: 0,
  size: 25,
  total: 1,
  stats: { titles: 1, copies: 2, issued: 2, overdue: 1 },
};

const DETAIL: TitleDetail = {
  id: "t9",
  isbn13: null,
  isbn10: null,
  title: "Panchatantra",
  subtitle: null,
  authors: "Vishnu Sharma",
  publisher: null,
  publicationYear: null,
  edition: null,
  language: "Hindi",
  subject: null,
  callNumber: null,
  shelfLocation: null,
  copiesTotal: 2,
  available: 2,
  timesBorrowed: 0,
  copies: [],
  queue: [],
};

beforeEach(() => {
  m.titles.mockResolvedValue(PAGE);
  m.subjects.mockResolvedValue(["Fiction", "Science"]);
  m.summary.mockResolvedValue({ issued: 4, overdue: 1, dueToday: 0, holdsReady: 1, finesDuePaise: 1600 });
});

afterEach(() => vi.clearAllMocks());

describe("CatalogueView", () => {
  it("lets any member of staff search, without the librarian's tools", async () => {
    const user = userEvent.setup();
    renderAs(TEACHER, <CatalogueView />, ["TEACHER"]);
    const table = await screen.findByTestId("catalogue-table");
    expect(within(table).getByRole("link", { name: /The Jungle Book/ })).toHaveAttribute("href", "/app/library/titles/t1");
    expect(within(table).getByText("0 of 2 on the shelf")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add title" })).not.toBeInTheDocument();
    expect(screen.queryByTestId("library-summary")).not.toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "Library sections" })).not.toBeInTheDocument();
    expect(m.summary).not.toHaveBeenCalled();

    await user.type(screen.getByRole("searchbox"), "kipling");
    await waitFor(() => expect(m.titles).toHaveBeenLastCalledWith({ q: "kipling", subject: undefined, page: 0, size: 25 }));
    await user.selectOptions(screen.getByRole("combobox", { name: "Subject" }), "Fiction");
    await waitFor(() => expect(m.titles).toHaveBeenLastCalledWith({ q: "kipling", subject: "Fiction", page: 0, size: 25 }));
  });

  it("shows the librarian the day's numbers and the library sections", async () => {
    renderAs(LIBRARIAN, <CatalogueView />, ["LIBRARIAN"]);
    const summary = await screen.findByTestId("library-summary");
    expect(within(summary).getByRole("link", { name: /Overdue\s*1/ })).toHaveAttribute("href", "/app/reports/library-overdue");
    expect(within(summary).getByRole("link", { name: /Fines due\s*₹16/ })).toHaveAttribute("href", "/app/library/fines");
    const nav = screen.getByRole("navigation", { name: "Library sections" });
    expect(within(nav).getByRole("link", { name: "Catalogue" })).toHaveAttribute("aria-current", "page");
    expect(within(nav).getByRole("link", { name: "Desk" })).toHaveAttribute("href", "/app/library/desk");
  });

  it("adds a title with two copies after checking its ISBN", async () => {
    const user = userEvent.setup();
    m.isbn.mockResolvedValue({
      input: "8171673406",
      valid: true,
      problem: null,
      isbn13: "9788171673407",
      isbn10: "8171673406",
      existing: null,
      suggestion: null,
    });
    m.createTitle.mockResolvedValue(DETAIL);
    renderAs(LIBRARIAN, <CatalogueView />, ["LIBRARIAN"]);
    await screen.findByTestId("catalogue-table");

    await user.click(screen.getByRole("button", { name: "Add title" }));
    const dialog = screen.getByRole("dialog", { name: "Add title" });
    const isbn = within(dialog).getByLabelText("ISBN");
    await user.type(isbn, "81-7167-340-7");
    await user.click(within(dialog).getByRole("button", { name: "Check ISBN" }));
    // The check digit is wrong: caught before asking the API.
    expect(await within(dialog).findByText(/This ISBN does not add up/)).toBeInTheDocument();
    expect(m.isbn).not.toHaveBeenCalled();

    await user.clear(isbn);
    await user.type(isbn, "8171673406");
    await user.click(within(dialog).getByRole("button", { name: "Check ISBN" }));
    expect(await within(dialog).findByTestId("isbn-result")).toHaveTextContent(
      "Valid ISBN · ISBN-13 9788171673407 · ISBN-10 8171673406",
    );
    expect(within(dialog).getByTestId("isbn-result")).toHaveTextContent("Online lookup is off");

    await user.click(within(dialog).getByRole("button", { name: "Add title" }));
    expect(within(dialog).getByText("Fill in this field.")).toBeInTheDocument();
    expect(m.createTitle).not.toHaveBeenCalled();

    await user.type(within(dialog).getByLabelText("Title"), "Panchatantra");
    const copies = within(dialog).getByLabelText("Number of copies");
    await user.clear(copies);
    await user.type(copies, "2");
    await user.type(within(dialog).getByLabelText("Price per copy (₹)"), "120");
    await user.click(within(dialog).getByRole("button", { name: "Add title" }));

    await waitFor(() =>
      expect(m.createTitle).toHaveBeenCalledWith(
        expect.objectContaining({ isbn: "8171673406", title: "Panchatantra", copies: 2, pricePaise: 12000, source: "PURCHASE" }),
      ),
    );
    expect(m.push).toHaveBeenCalledWith("/app/library/titles/t9");
    expect(await screen.findByText("Panchatantra added with 2 copies.")).toBeInTheDocument();
  });
});
