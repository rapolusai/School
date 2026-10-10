import { describe, expect, it } from "vitest";
import { translate, type Translate } from "@/lib/i18n";
import { privacyQuery } from "@/lib/privacy-api";
import { paperProblems } from "./consents-view";
import { requestProblems } from "./my-privacy-view";
import { officerProblems, publishProblems } from "./notice-admin-view";
import { gateExempt } from "./privacy-gate";
import { dueLabel, parseNotice } from "./privacy-ui";
import { closeProblems, erasureConfirmed } from "./request-detail-view";
import { REQUESTS_PAGE_SIZE, toRequestQuery } from "./requests-view";

const t: Translate = (key, vars) => translate("en", key, vars);

describe("parseNotice", () => {
  it("reads headings, lists and paragraphs from plain text", () => {
    const text = "## What we collect\nName and class\nof your child.\n\n- Attendance\n- Fees\n\nLast line.";
    expect(parseNotice(text)).toEqual([
      { kind: "heading", text: "What we collect" },
      { kind: "paragraph", text: "Name and class of your child." },
      { kind: "list", items: ["Attendance", "Fees"] },
      { kind: "paragraph", text: "Last line." },
    ]);
  });

  it("never treats markup as anything but text", () => {
    expect(parseNotice("<b>bold</b>\r\n<script>x</script>")).toEqual([
      { kind: "paragraph", text: "<b>bold</b> <script>x</script>" },
    ]);
  });
});

describe("dueLabel", () => {
  it("says how long is left, or how late", () => {
    expect(dueLabel(t, { status: "SUBMITTED", daysLeft: 1 })).toBe("1 day left");
    expect(dueLabel(t, { status: "IN_PROGRESS", daysLeft: 12 })).toBe("12 days left");
    expect(dueLabel(t, { status: "SUBMITTED", daysLeft: 0 })).toBe("Due today");
    expect(dueLabel(t, { status: "SUBMITTED", daysLeft: -3 })).toBe("3 days overdue");
    expect(dueLabel(t, { status: "CLOSED", daysLeft: -3 })).toBe("");
  });
});

describe("queries", () => {
  it("builds the queue query from the filters", () => {
    expect(toRequestQuery("OPEN", "", "  ", 0)).toEqual({
      status: "OPEN",
      type: undefined,
      q: undefined,
      page: 0,
      size: REQUESTS_PAGE_SIZE,
    });
    expect(toRequestQuery("ALL", "ERASURE", " Khan ", 2)).toMatchObject({ status: undefined, type: "ERASURE", q: "Khan" });
    expect(privacyQuery({ status: "OPEN", type: undefined, q: "", page: 0 })).toBe("?status=OPEN&page=0");
    expect(privacyQuery({})).toBe("");
  });
});

describe("form checks", () => {
  it("checks the grievance officer like the API", () => {
    expect(officerProblems({ name: "", email: "x", phone: "12" })).toEqual({
      name: "validation.required",
      email: "validation.email",
      phone: "privacy.officer.v.phone",
    });
    expect(officerProblems({ name: "Lakshmi Iyer", email: "li@school.test", phone: "040 2345 6789" })).toEqual({});
  });

  it("needs both languages, and a change summary after the first version", () => {
    const values = { bodyEn: "Notice", bodyHi: "", changeSummary: "" };
    expect(publishProblems(values, true)).toEqual({ bodyHi: "validation.required" });
    expect(publishProblems({ ...values, bodyHi: "सूचना" }, false)).toEqual({ changeSummary: "privacy.publish.v.summary" });
    expect(publishProblems({ ...values, bodyHi: "सूचना", changeSummary: "New purpose" }, false)).toEqual({});
  });

  it("checks a paper form: signer, a date not in the future, a short reference", () => {
    const values = { givenByName: "", signedOn: "2026-10-10", paperReference: "x".repeat(101), photos: false, whatsapp: false };
    expect(paperProblems(values, "2026-10-09")).toEqual({
      givenByName: "validation.required",
      signedOn: "privacy.paper.v.future",
      paperReference: "validation.tooLong",
    });
    expect(paperProblems({ ...values, givenByName: "Farah Khan", signedOn: "2026-10-09", paperReference: "" }, "2026-10-09")).toEqual(
      {},
    );
  });

  it("checks a parent's new request", () => {
    expect(requestProblems({ type: "", about: "", details: "" })).toEqual({
      type: "validation.choose",
      about: "validation.choose",
    });
    expect(requestProblems({ type: "ERASURE", about: "SELF", details: "" })).toEqual({
      about: "privacy.new.v.erasureChild",
    });
    expect(requestProblems({ type: "GRIEVANCE", about: "st1", details: " " })).toEqual({ details: "validation.required" });
    expect(requestProblems({ type: "ACCESS", about: "SELF", details: "" })).toEqual({});
  });

  it("closes with an outcome, and a reason when declining", () => {
    expect(closeProblems({ resolution: "", note: "" })).toEqual({ resolution: "validation.choose" });
    expect(closeProblems({ resolution: "DECLINED", note: " " })).toEqual({ note: "privacy.close.v.note" });
    expect(closeProblems({ resolution: "COMPLETED", note: "" })).toEqual({});
  });

  it("confirms an erasure only with the exact admission number", () => {
    expect(erasureConfirmed(" aks/2026/003 ", "AKS/2026/003")).toBe(true);
    expect(erasureConfirmed("AKS/2026/00", "AKS/2026/003")).toBe(false);
    expect(erasureConfirmed("", null)).toBe(false);
  });
});

describe("gateExempt", () => {
  it("lets a parent reach their privacy page and requests before accepting", () => {
    expect(gateExempt("/app/my-privacy")).toBe(true);
    expect(gateExempt("/app/my-privacy/requests/r1")).toBe(true);
    expect(gateExempt("/app/my-privacy-other")).toBe(false);
    expect(gateExempt("/app/dashboard")).toBe(false);
  });
});
