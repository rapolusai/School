import { describe, expect, it } from "vitest";
import { validateCollect } from "./fine-dialogs";
import { cleanIsbn, isbnProblem, shiftDate, toIsbn13 } from "./library-shared";
import { validateRule, validateSettings } from "./settings-view";
import { EMPTY_TITLE, toTitleRequest, validateTitle } from "./title-dialogs";

const TODAY = "2026-10-11";

describe("isbnProblem and toIsbn13 (the API's ISBN rules)", () => {
  it("accepts ISBN-10 and ISBN-13 as printed, with hyphens, spaces and a lower-case x", () => {
    expect(isbnProblem("978-81-7167-340-7")).toBeNull();
    expect(isbnProblem("81 7167 340 6")).toBeNull();
    expect(isbnProblem("0-8044-2957-x")).toBeNull();
    expect(cleanIsbn("0-8044-2957-x")).toBe("080442957X");
  });

  it("converts an ISBN-10 to its ISBN-13 with a new check digit", () => {
    expect(toIsbn13("8171673406")).toBe("9788171673407");
    expect(toIsbn13("080442957X")).toBe("9780804429573");
    expect(toIsbn13("9788171673407")).toBe("9788171673407");
    expect(toIsbn13("9788171673408")).toBeNull();
  });

  it("names what is wrong", () => {
    expect(isbnProblem("9788171673408")).toBe("library.v.isbnCheck");
    expect(isbnProblem("8171673407")).toBe("library.v.isbnCheck");
    expect(isbnProblem("1234567890123")).toBe("library.v.isbnPrefix");
    expect(isbnProblem("12345")).toBe("library.v.isbnDigits");
    expect(isbnProblem("97881716734X7")).toBe("library.v.isbnDigits");
  });
});

describe("validateTitle", () => {
  it("needs a title, a valid ISBN when one is given, and sensible numbers", () => {
    expect(validateTitle(EMPTY_TITLE, true, TODAY)).toEqual({ title: "validation.required" });
    const ok = { ...EMPTY_TITLE, title: "Matilda", isbn: "978-0-14-241037-0", copies: "2", price: "₹ 299", publicationYear: "1988" };
    expect(validateTitle(ok, true, TODAY)).toEqual({});
    expect(
      validateTitle(
        { ...ok, isbn: "9780142410372", publicationYear: "88", copies: "101", price: "abc", acquiredOn: "2026-10-12" },
        true,
        TODAY,
      ),
    ).toEqual({
      isbn: "library.v.isbnCheck",
      publicationYear: "library.v.year",
      copies: "library.v.copies",
      pricePaise: "library.v.amount",
      acquiredOn: "library.v.future",
    });
    expect(validateTitle({ ...ok, title: "x".repeat(301) }, true, TODAY)).toEqual({ title: "validation.tooLong" });
  });

  it("ignores the copy fields when editing", () => {
    expect(validateTitle({ ...EMPTY_TITLE, title: "Matilda", copies: "x" }, false, TODAY)).toEqual({});
  });
});

describe("toTitleRequest", () => {
  it("trims, sends empty fields as null and the price in paise", () => {
    const values = { ...EMPTY_TITLE, title: " Matilda ", authors: "Roald Dahl", copies: "2", price: "1,250.50", acquiredOn: "2026-06-01" };
    expect(toTitleRequest(values, true)).toEqual({
      isbn: null,
      title: "Matilda",
      subtitle: null,
      authors: "Roald Dahl",
      publisher: null,
      publicationYear: null,
      edition: null,
      language: null,
      subject: null,
      callNumber: null,
      shelfLocation: null,
      copies: 2,
      pricePaise: 125050,
      source: "PURCHASE",
      acquiredOn: "2026-06-01",
    });
    expect(toTitleRequest(values, false)).not.toHaveProperty("copies");
  });
});

describe("validateCollect", () => {
  it("needs a fine and, for a cheque or bank transfer, the reference", () => {
    expect(validateCollect({ fineIds: [], mode: "CASH", reference: "" })).toEqual({ fineIds: "library.v.pickFine" });
    expect(validateCollect({ fineIds: ["f1"], mode: "CHEQUE", reference: " " })).toEqual({ reference: "library.v.reference" });
    expect(validateCollect({ fineIds: ["f1"], mode: "BANK_TRANSFER", reference: "UTR123" })).toEqual({});
    expect(validateCollect({ fineIds: ["f1"], mode: "UPI", reference: "" })).toEqual({});
  });
});

describe("validateSettings and validateRule", () => {
  it("checks the prefix and the hold days", () => {
    expect(validateSettings({ accessionPrefix: "LIB", holdDays: "3", remindersEnabled: false })).toEqual({});
    expect(validateSettings({ accessionPrefix: "", holdDays: "14", remindersEnabled: true })).toEqual({});
    expect(validateSettings({ accessionPrefix: "lib", holdDays: "0", remindersEnabled: false })).toEqual({
      accessionPrefix: "library.v.prefix",
      holdDays: "library.v.holdDays",
    });
  });

  it("checks a borrowing rule against the API's limits", () => {
    const rule = { maxBooks: "2", loanDays: "14", maxRenewals: "1", finePerDay: "1", maxFine: "50" };
    expect(validateRule(rule)).toEqual({});
    expect(validateRule({ maxBooks: "0", loanDays: "181", maxRenewals: "11", finePerDay: "1001", maxFine: "x" })).toEqual({
      maxBooks: "library.v.maxBooks",
      loanDays: "library.v.loanDays",
      maxRenewals: "library.v.renewals",
      finePerDayPaise: "library.v.finePerDay",
      maxFinePaise: "library.v.maxFine",
    });
  });
});

describe("shiftDate", () => {
  it("moves a plain date across months", () => {
    expect(shiftDate("2026-10-11", -60)).toBe("2026-08-12");
    expect(shiftDate("2026-12-31", 1)).toBe("2027-01-01");
  });
});
