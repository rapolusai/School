import { screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { renderAs } from "@/test/render";
import { card, cards, SCHOOL } from "@/test/certificates-fixtures";
import { maskPhone, printPhone } from "./certificates-shared";
import { CARDS_PER_SHEET, IdCardBack, IdCardFront, IdCardSheet, sheetsOf } from "./id-card";
import { IdCardsView, validateCard, validateReissue } from "./id-cards-view";

const m = vi.hoisted(() => ({ cards: vi.fn(), issueCards: vi.fn(), listClasses: vi.fn() }));

vi.mock("@/lib/certificates-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/certificates-api")>();
  return { ...actual, certificatesApi: { ...actual.certificatesApi, cards: m.cards, issueCards: m.issueCards } };
});
vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, listClasses: m.listClasses } };
});
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/certificates/id-cards",
}));

const OFFICE = ["dashboard.view", "certificates.read", "certificates.issue", "students.read"];

beforeEach(() => {
  m.listClasses.mockResolvedValue([{ id: "cl5", name: "Class 5", sections: [{ id: "sec5a", name: "A" }] }]);
  m.cards.mockResolvedValue({ school: SCHOOL, items: cards(3), page: 0, size: 500, total: 3 });
});
afterEach(() => vi.clearAllMocks());

describe("phones on cards", () => {
  it("masks the emergency number on screen and prints it in full", () => {
    expect(maskPhone("9876543210")).toBe("XXXXXX3210");
    expect(maskPhone(null)).toBe("—");
    expect(printPhone("9876543210")).toBe("98765 43210");
    expect(printPhone("")).toBe("—");
  });
});

describe("sheetsOf", () => {
  it("fills A4 sheets of ten cards, two columns of five", () => {
    expect(CARDS_PER_SHEET).toBe(10);
    expect(sheetsOf(cards(10)).map((s) => s.length)).toEqual([10]);
    expect(sheetsOf(cards(23)).map((s) => s.length)).toEqual([10, 10, 3]);
    expect(sheetsOf([])).toEqual([]);
  });
});

describe("validateCard and validateReissue", () => {
  it("needs a person and a valid mobile number, and a reason to reissue", () => {
    expect(validateCard({ emergencyPhone: "12345" }, null)).toEqual({ subjectSearch: "certificates.v.person", emergencyPhone: "students.v.phone" });
    expect(validateCard({ emergencyPhone: "9876543210" }, { type: "STUDENT", id: "st1", name: "A", code: null, detail: null })).toEqual({});
    expect(validateReissue({ reason: "", lostFee: "", feeNote: "", bloodGroup: "", emergencyPhone: "" })).toEqual({ reason: "validation.required" });
    expect(validateReissue({ reason: "lost", lostFee: "abc", feeNote: "", bloodGroup: "", emergencyPhone: "" })).toEqual({ lostFee: "certificates.v.fee" });
    expect(validateReissue({ reason: "lost", lostFee: "150", feeNote: "", bloodGroup: "", emergencyPhone: "" })).toEqual({});
  });
});

describe("IdCardFront and IdCardBack", () => {
  it("prints the CR80 front with the photo placeholder, class, admission number barcode and validity", () => {
    renderAs(OFFICE, <IdCardFront card={card(1)} school={SCHOOL} />);
    const front = screen.getByTestId("idcard-front");
    expect(front).toHaveAttribute("data-card-no", "ID/2026-27/0001");
    expect(front).toHaveTextContent("Akshara Demo School");
    expect(front).toHaveTextContent("Student 1");
    expect(front).toHaveTextContent("Class 5 A");
    expect(front).toHaveTextContent("ADM-2024-001");
    expect(screen.getByTestId("idcard-photo-empty")).toBeInTheDocument();
    expect(screen.getByTestId("idcard-barcode")).toHaveAttribute("aria-label", "Barcode of ADM-2024-001");
    expect(front).not.toHaveClass("idcard-cancelled");
  });

  it("prints the back with the blood group, the full emergency number and the school's address", () => {
    renderAs(OFFICE, <IdCardBack card={card(1)} school={SCHOOL} />);
    const back = screen.getByTestId("idcard-back");
    expect(back).toHaveTextContent("B+");
    expect(screen.getByTestId("idcard-phone")).toHaveTextContent("98765 43210");
    expect(back).toHaveTextContent("12 MG Road, Bengaluru 560001");
    expect(back).toHaveTextContent("If found, please return this card to the school.");
  });

  it("lays the sheet out as pairs of front and back, ten per page", () => {
    renderAs(OFFICE, <IdCardSheet cards={cards(12)} school={SCHOOL} />);
    const sheet = screen.getByTestId("idcard-sheet");
    const pages = sheet.querySelectorAll(".idcard-page");
    expect(pages).toHaveLength(2);
    expect(within(pages[0] as HTMLElement).getAllByTestId("idcard-pair")).toHaveLength(10);
    expect(within(pages[1] as HTMLElement).getAllByTestId("idcard-pair")).toHaveLength(2);
    expect(screen.getAllByTestId("idcard-front")).toHaveLength(12);
    expect(screen.getAllByTestId("idcard-back")).toHaveLength(12);
  });
});

describe("IdCardsView", () => {
  it("lists the register with masked phones and prints the active cards as a sheet", async () => {
    const print = vi.spyOn(window, "print").mockImplementation(() => undefined);
    m.cards.mockResolvedValue({ school: SCHOOL, items: [card(1), card(2, { status: "CANCELLED", cancelledAt: "2026-10-02T00:00:00Z" })], page: 0, size: 500, total: 2 });
    renderAs(OFFICE, <IdCardsView />, ["FRONT_OFFICE"]);
    const table = await screen.findByTestId("cards-table");
    expect(within(table).getAllByTestId("card-phone")[0]).toHaveTextContent("XXXXXX3210");
    expect(table).not.toHaveTextContent("98765 43210");
    expect(screen.queryByTestId("idcard-sheet")).not.toBeInTheDocument();
    screen.getByTestId("print-cards").click();
    const sheet = await screen.findByTestId("idcard-sheet");
    // Only the card in use is printed; the sheet carries the number in full.
    expect(within(sheet).getAllByTestId("idcard-pair")).toHaveLength(1);
    expect(within(sheet).getByTestId("idcard-phone")).toHaveTextContent("98765 43210");
    await waitFor(() => expect(print).toHaveBeenCalled());
    print.mockRestore();
  });
});
