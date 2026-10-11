import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import { translate, type Translate } from "@/lib/i18n";
import type { FineReceipt, FineView, LibraryMember, LoanView, MemberAccount, ReturnResult } from "@/lib/types";
import { renderAs } from "@/test/render";
import { blockedReason, DeskView } from "./desk-view";

const m = vi.hoisted(() => ({
  members: vi.fn(),
  account: vi.fn(),
  issue: vi.fn(),
  giveBack: vi.fn(),
  collect: vi.fn(),
  renew: vi.fn(),
  lost: vi.fn(),
}));

vi.mock("@/lib/library-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/library-api")>();
  return { ...actual, libraryApi: { ...actual.libraryApi, ...m } };
});
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/library/desk",
}));

const LIBRARIAN = ["dashboard.view", "library.read", "library.manage"];

const ASHA: LibraryMember = { type: "STUDENT", id: "st1", name: "Asha Rao", code: "LIB-1", detail: "Class 5 A", active: true };

function loan(overrides: Partial<LoanView> = {}): LoanView {
  return {
    id: "loan1",
    copyId: "c1",
    accessionNo: "LIB00001",
    titleId: "t1",
    title: "The Jungle Book",
    authors: "Rudyard Kipling",
    member: ASHA,
    issuedOn: "2026-10-09",
    dueOn: "2026-10-24",
    renewals: 0,
    renewalsLeft: 1,
    status: "ISSUED",
    returnedOn: null,
    overdue: false,
    daysOverdue: 0,
    fineSoFarPaise: 0,
    issuedByName: "Kavitha Rao",
    returnedByName: null,
    ...overrides,
  };
}

function fine(overrides: Partial<FineView> = {}): FineView {
  return {
    id: "f1",
    loanId: "loan1",
    titleId: "t1",
    title: "The Jungle Book",
    accessionNo: "LIB00001",
    member: ASHA,
    kind: "OVERDUE",
    amountPaise: 1600,
    daysLate: 16,
    note: null,
    status: "DUE",
    receiptNo: null,
    paymentMode: null,
    paymentReference: null,
    paidAt: null,
    collectedByName: null,
    waiveReason: null,
    waivedAt: null,
    waivedByName: null,
    createdAt: "2026-10-11T05:00:00Z",
    ...overrides,
  };
}

function account(overrides: Partial<MemberAccount> = {}): MemberAccount {
  return {
    member: ASHA,
    rule: { memberType: "STUDENT", maxBooks: 2, loanDays: 14, maxRenewals: 1, finePerDayPaise: 100, maxFinePaise: 5000, custom: false },
    loans: [],
    finesDue: [],
    finesDuePaise: 0,
    holds: [],
    history: [],
    canBorrow: true,
    blockedReason: null,
    ...overrides,
  };
}

async function pickAsha(user: ReturnType<typeof userEvent.setup>) {
  m.members.mockResolvedValue([ASHA]);
  await user.type(screen.getByRole("searchbox", { name: "Find a student or member of staff" }), "asha");
  await user.click(await screen.findByRole("button", { name: /Asha Rao/ }));
  return within(screen.getByTestId("issue-form")).findByLabelText("Accession number");
}

beforeEach(() => {
  m.account.mockResolvedValue(account());
});

afterEach(() => {
  vi.clearAllMocks();
});

describe("the desk: lending", () => {
  it("lends the copy a scanner types with Enter, and keeps the cursor in the field for the next book", async () => {
    const user = userEvent.setup();
    m.issue.mockResolvedValue(loan());
    renderAs(LIBRARIAN, <DeskView />, ["LIBRARIAN"]);

    const field = await pickAsha(user);
    expect(m.account).toHaveBeenCalledWith("STUDENT", "st1");
    expect(screen.getByTestId("desk-rule")).toHaveTextContent("Up to 2 books for 14 days. 1 renewal. Fine ₹1 a day late.");
    await waitFor(() => expect(field).toHaveFocus());

    // A scanner sends the number in whatever case its label has, then Enter.
    await user.type(field, "lib00001{Enter}");

    await waitFor(() =>
      expect(m.issue).toHaveBeenCalledWith({ accessionNo: "LIB00001", memberType: "STUDENT", memberId: "st1", issuedOn: null }),
    );
    expect(await screen.findByText("The Jungle Book lent, due 24 Oct 2026.")).toBeInTheDocument();
    expect(field).toHaveValue("");
    expect(field).toHaveFocus();
    // The account is shown again with the new loan.
    await waitFor(() => expect(m.account).toHaveBeenCalledTimes(2));

    // The next book goes straight in.
    await user.keyboard("LIB00002{Enter}");
    await waitFor(() => expect(m.issue).toHaveBeenLastCalledWith(expect.objectContaining({ accessionNo: "LIB00002" })));
  });

  it("shows why a copy cannot be lent under the field, keeps the number selected and the focus", async () => {
    const user = userEvent.setup();
    m.issue.mockRejectedValue(
      new ApiError({ status: 409, title: "Conflict", detail: "Copy LIB00001 is already lent to Bala Iyer." }),
    );
    renderAs(LIBRARIAN, <DeskView />, ["LIBRARIAN"]);
    const field = await pickAsha(user);

    await user.type(field, "LIB00001{Enter}");

    expect(await within(screen.getByTestId("issue-form")).findByRole("alert")).toHaveTextContent(
      "Copy LIB00001 is already lent to Bala Iyer.",
    );
    expect(field).toHaveValue("LIB00001");
    await waitFor(() => expect(field).toHaveFocus());
    expect(field).toHaveAttribute("aria-invalid", "true");
  });

  it("checks the number before sending it", async () => {
    const user = userEvent.setup();
    renderAs(LIBRARIAN, <DeskView />, ["LIBRARIAN"]);
    const field = await pickAsha(user);

    await user.type(field, "{Enter}");
    expect(await screen.findByText("Scan or type the accession number.")).toBeInTheDocument();
    await user.type(field, "LIB 01{Enter}");
    expect(await screen.findByText("Use up to 30 letters, digits, / and -.")).toBeInTheDocument();
    expect(m.issue).not.toHaveBeenCalled();
    expect(field).toHaveFocus();
  });

  it("records an earlier issue date only within the last 60 days", async () => {
    const user = userEvent.setup();
    m.issue.mockResolvedValue(loan());
    renderAs(LIBRARIAN, <DeskView />, ["LIBRARIAN"]);
    const field = await pickAsha(user);

    await user.click(screen.getByRole("checkbox", { name: "Record an earlier issue date" }));
    const date = screen.getByLabelText("Lent on");
    await user.clear(date);
    await user.type(date, "2020-01-01");
    expect(screen.getByText("Choose today or a day in the last 60 days.")).toBeInTheDocument();
    await user.type(field, "LIB00001{Enter}");
    expect(m.issue).not.toHaveBeenCalled();
  });

  it("shows the member's books, fines and the limit, and collects the fines under one receipt", async () => {
    const user = userEvent.setup();
    const late = loan({ overdue: true, daysOverdue: 3, fineSoFarPaise: 300, dueOn: "2026-10-08" });
    m.account.mockResolvedValue(
      account({ loans: [late, loan({ id: "loan2", accessionNo: "LIB00007", title: "Matilda" })], finesDue: [fine()], finesDuePaise: 1600 }),
    );
    const receipt: FineReceipt = {
      receiptNo: "LIBF/2026-27/000001",
      schoolName: "Sunrise Public School",
      member: ASHA,
      fines: [fine({ status: "PAID", receiptNo: "LIBF/2026-27/000001" })],
      totalPaise: 1600,
      mode: "CASH",
      reference: null,
      paidAt: "2026-10-11T05:00:00Z",
      collectedByName: "Kavitha Rao",
    };
    m.collect.mockResolvedValue(receipt);
    renderAs(LIBRARIAN, <DeskView />, ["LIBRARIAN"]);
    await pickAsha(user);

    expect(await screen.findByTestId("desk-blocked")).toHaveTextContent("Asha Rao already has 2 books, the most allowed.");
    const loans = screen.getByTestId("desk-loans");
    expect(within(loans).getByText("3 days late")).toBeInTheDocument();
    expect(within(loans).getByText("Fine so far ₹3")).toBeInTheDocument();
    // An overdue book cannot be renewed; it is taken back first.
    expect(within(loans).getAllByRole("button", { name: "Renew" })[0]).toBeDisabled();

    expect(screen.getByTestId("desk-fines")).toHaveTextContent("Fines due: ₹16");
    await user.click(within(screen.getByTestId("desk-fines")).getByRole("button", { name: "Collect" }));
    const dialog = screen.getByRole("dialog", { name: "Collect fines from Asha Rao" });
    expect(within(dialog).getByTestId("collect-total")).toHaveTextContent("₹16");
    await user.click(within(dialog).getByRole("button", { name: "Collect ₹16" }));

    await waitFor(() => expect(m.collect).toHaveBeenCalledWith({ fineIds: ["f1"], mode: "CASH", reference: null }));
    expect(await screen.findByText("₹16 collected · receipt LIBF/2026-27/000001")).toBeInTheDocument();
  });

  it("asks for the reference of a cheque", async () => {
    const user = userEvent.setup();
    m.account.mockResolvedValue(account({ finesDue: [fine()], finesDuePaise: 1600 }));
    renderAs(LIBRARIAN, <DeskView />, ["LIBRARIAN"]);
    await pickAsha(user);
    await user.click(await screen.findByRole("button", { name: "Collect" }));
    const dialog = screen.getByRole("dialog", { name: "Collect fines from Asha Rao" });
    await user.selectOptions(within(dialog).getByLabelText("Paid by"), "CHEQUE");
    await user.click(within(dialog).getByRole("button", { name: "Collect ₹16" }));
    expect(await within(dialog).findByText("Enter the cheque or transfer reference.")).toBeInTheDocument();
    expect(m.collect).not.toHaveBeenCalled();
  });
});

describe("the desk: taking books back", () => {
  it("takes a book back from the return field, shows the late fine and collects it", async () => {
    const user = userEvent.setup();
    const result: ReturnResult = {
      loan: loan({ status: "RETURNED", returnedOn: "2026-10-11", dueOn: "2026-09-24" }),
      fines: [fine()],
      heldFor: null,
    };
    m.giveBack.mockResolvedValue(result);
    m.collect.mockResolvedValue({
      receiptNo: "LIBF/2026-27/000002",
      schoolName: "Sunrise Public School",
      member: ASHA,
      fines: [fine({ status: "PAID" })],
      totalPaise: 1600,
      mode: "UPI",
      reference: null,
      paidAt: "2026-10-11T05:00:00Z",
      collectedByName: "Kavitha Rao",
    } satisfies FineReceipt);
    renderAs(LIBRARIAN, <DeskView />, ["LIBRARIAN"]);

    // With no member chosen, the cursor starts in the return field.
    const field = within(screen.getByTestId("return-form")).getByLabelText("Accession number");
    expect(field).toHaveFocus();
    await user.type(field, "LIB00001{Enter}");

    await waitFor(() =>
      expect(m.giveBack).toHaveBeenCalledWith({ accessionNo: "LIB00001", damaged: false, chargePaise: null, note: null }),
    );
    const done = await screen.findByTestId("return-result");
    expect(done).toHaveTextContent("The Jungle Book (LIB00001) back from Asha Rao.");
    expect(done).toHaveTextContent("It was due on 24 Sept 2026.");
    expect(done).toHaveTextContent("Late return · 16 days late₹16");
    expect(field).toHaveValue("");
    expect(field).toHaveFocus();

    await user.click(within(done).getByRole("button", { name: "Collect ₹16" }));
    const dialog = screen.getByRole("dialog", { name: "Collect fines from Asha Rao" });
    await user.selectOptions(within(dialog).getByLabelText("Paid by"), "UPI");
    await user.click(within(dialog).getByRole("button", { name: "Collect ₹16" }));
    expect(await screen.findByTestId("return-receipt")).toHaveTextContent("receipt LIBF/2026-27/000002");
    expect(screen.getByRole("link", { name: "View receipt" })).toHaveAttribute(
      "href",
      "/app/library/receipt?no=LIBF%2F2026-27%2F000002",
    );
  });

  it("sends a damaged return with its charge in paise, and says who the copy is kept for", async () => {
    const user = userEvent.setup();
    m.giveBack.mockResolvedValue({
      loan: loan({ status: "RETURNED", returnedOn: "2026-10-11" }),
      fines: [fine({ kind: "DAMAGED", amountPaise: 15050, daysLate: null })],
      heldFor: {
        id: "h1",
        titleId: "t1",
        title: "The Jungle Book",
        authors: null,
        member: { ...ASHA, id: "st2", name: "Sneha Iyengar" },
        status: "READY",
        position: 1,
        copyAccessionNo: "LIB00001",
        holdUntil: "2026-10-14",
        readyAt: "2026-10-11T05:00:00Z",
        createdAt: "2026-10-01T05:00:00Z",
        createdByName: null,
      },
    } satisfies ReturnResult);
    renderAs(LIBRARIAN, <DeskView />, ["LIBRARIAN"]);
    const returns = screen.getByTestId("desk-return");

    await user.click(within(returns).getByRole("checkbox", { name: "Returned damaged" }));
    await user.type(within(returns).getByLabelText("Damage charge (₹, optional)"), "150.50");
    await user.type(within(returns).getByLabelText("Note (optional)"), "Cover torn");
    await user.type(within(returns).getByLabelText("Accession number"), "LIB00001{Enter}");

    await waitFor(() =>
      expect(m.giveBack).toHaveBeenCalledWith({ accessionNo: "LIB00001", damaged: true, chargePaise: 15050, note: "Cover torn" }),
    );
    expect(await screen.findByTestId("return-hold")).toHaveTextContent(
      "Keep this copy for Sneha Iyengar on the hold shelf until 14 Oct 2026.",
    );
    // The damage options reset for the next book.
    expect(within(returns).getByRole("checkbox", { name: "Returned damaged" })).not.toBeChecked();
  });
});

describe("the desk on a phone", () => {
  it("offers the camera only where the browser can read barcodes, and typing everywhere", async () => {
    const { unmount } = renderAs(LIBRARIAN, <DeskView />, ["LIBRARIAN"]);
    expect(screen.queryByRole("button", { name: "Scan with the camera" })).not.toBeInTheDocument();
    expect(screen.getByText(/type the number printed under the barcode/)).toBeInTheDocument();
    unmount();

    Object.defineProperty(window, "BarcodeDetector", {
      configurable: true,
      value: class {
        detect() {
          return Promise.resolve([]);
        }
      },
    });
    Object.defineProperty(navigator, "mediaDevices", {
      configurable: true,
      value: { getUserMedia: () => Promise.reject(new Error("denied")) },
    });
    const user = userEvent.setup();
    renderAs(LIBRARIAN, <DeskView />, ["LIBRARIAN"]);
    const camera = screen.getByRole("button", { name: "Scan with the camera" });
    await user.click(camera);
    const dialog = screen.getByRole("dialog", { name: "Scan a barcode" });
    expect(await within(dialog).findByRole("alert")).toHaveTextContent("Allow camera access, or type the number.");
    Object.defineProperty(navigator, "mediaDevices", { configurable: true, value: undefined });
    Reflect.deleteProperty(window, "BarcodeDetector");
  });
});

describe("blockedReason", () => {
  const t: Translate = (key, vars) => translate("en", key, vars);
  it("explains a member who left or is at the limit, and is null otherwise", () => {
    expect(blockedReason(t, account())).toBeNull();
    expect(blockedReason(t, account({ member: { ...ASHA, active: false } }))).toBe(
      "Asha Rao has left the school and cannot borrow.",
    );
    expect(blockedReason(t, account({ loans: [loan(), loan({ id: "l2" })] }))).toBe(
      "Asha Rao already has 2 books, the most allowed.",
    );
  });
});
