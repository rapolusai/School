import { screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { loginErrorMessage, SCHOOL_PAUSED_TYPE } from "@/components/views/login-view";
import { ApiError } from "@/lib/api";
import { translate, type Translate } from "@/lib/i18n";
import { BILLING_READ_ONLY, invoice, NO_NOTICE, PLANS, renderInSchool } from "@/test/billing-fixtures";
import {
  detailsProblems,
  gstinCheckCharacter,
  isValidGstin,
  noticeText,
  PlanCards,
  rateLabel,
  stateLabel,
} from "./billing-ui";
import { InvoiceDocument } from "./invoice-document";

const t: Translate = (key, vars) => translate("en", key, vars);
const hi: Translate = (key, vars) => translate("hi", key, vars);

const validGstin = (state: string) => {
  const first14 = `${state}ABCDE1234F1Z`;
  return first14 + gstinCheckCharacter(first14);
};

describe("GSTIN and billing details checks", () => {
  it("accepts a GSTIN with the right check character, like the API", () => {
    const gstin = validGstin("27");
    expect(isValidGstin(gstin)).toBe(true);
    const wrong = gstin.slice(0, 14) + (gstin[14] === "A" ? "B" : "A");
    expect(isValidGstin(wrong)).toBe(false);
    expect(isValidGstin(gstin.toLowerCase())).toBe(false);
    // The seller's placeholder has the shape but can never be valid.
    expect(isValidGstin("36XXXXX0000X1ZX")).toBe(false);
  });

  it("requires the state and checks an optional GSTIN against it", () => {
    const blank = { legalName: "", address: "", stateCode: "", gstin: "" };
    expect(detailsProblems(blank)).toEqual({ stateCode: "billing.details.stateRequired" });
    expect(detailsProblems({ ...blank, stateCode: "25" })).toEqual({ stateCode: "billing.details.stateRequired" });
    expect(detailsProblems({ ...blank, stateCode: "36" })).toEqual({});
    expect(detailsProblems({ ...blank, stateCode: "36", gstin: "36ABC" })).toEqual({
      gstin: "billing.details.gstinInvalid",
    });
    expect(detailsProblems({ ...blank, stateCode: "36", gstin: validGstin("29") })).toEqual({
      gstin: "billing.details.gstinState",
    });
    expect(detailsProblems({ ...blank, stateCode: "29", gstin: validGstin("29").toLowerCase() })).toEqual({});
    expect(detailsProblems({ ...blank, stateCode: "36", legalName: "x".repeat(201) })).toEqual({
      legalName: "validation.tooLong",
    });
  });

  it("names states in both languages with their code", () => {
    expect(stateLabel(t, "36")).toBe("Telangana (36)");
    expect(stateLabel(hi, "27")).toBe("महाराष्ट्र (27)");
    expect(stateLabel(t, null)).toBe("");
    expect(rateLabel(900)).toBe("9%");
    expect(rateLabel(1800)).toBe("18%");
    expect(rateLabel(950)).toBe("9.5%");
  });
});

describe("noticeText", () => {
  it("puts an overdue payment in words, with the amount and the oldest due date", () => {
    expect(
      noticeText(
        { ...NO_NOTICE, kind: "PAYMENT_OVERDUE", overduePaise: 23_600_00, overdueInvoices: 1, oldestDueDate: "2026-09-14" },
        t,
        "en-IN",
      ),
    ).toBe(
      "A payment of ₹23,600 to Akshara is overdue since 14 Sept 2026. Please arrange the payment to keep using Akshara without interruption.",
    );
    expect(noticeText({ ...NO_NOTICE, kind: "PAYMENT_OVERDUE" }, t, "en-IN")).toContain("Your payment to Akshara is overdue");
  });

  it("counts the trial's days left and says when it ended", () => {
    const ending = { ...NO_NOTICE, kind: "TRIAL_ENDING" as const, trialEndsAt: "2026-10-12T06:30:00Z", trialDaysLeft: 1 };
    expect(noticeText(ending, t, "en-IN")).toBe(
      "Your free trial ends on 12 Oct 2026: 1 day left. Choose a plan to keep your school's data and access.",
    );
    expect(noticeText({ ...ending, trialDaysLeft: 3 }, t, "en-IN")).toContain("3 days left");
    expect(noticeText({ ...ending, kind: "TRIAL_ENDED", trialDaysLeft: 0 }, t, "en-IN")).toBe(
      "Your free trial ended on 12 Oct 2026. Choose a plan to keep using Akshara.",
    );
    expect(noticeText(NO_NOTICE, t, "en-IN")).toBeNull();
  });
});

describe("InvoiceDocument", () => {
  it("prints a GST invoice with CGST and SGST in the seller's state, the SAC code and the amount in words", () => {
    renderInSchool(BILLING_READ_ONLY, <InvoiceDocument invoice={invoice()} />);
    const doc = screen.getByRole("article", { name: "Invoice AKS/26-27/000007" });
    expect(within(doc).getByText("Tax invoice")).toBeInTheDocument();
    expect(within(doc).getByText("Akshara School Cloud (sample seller)")).toBeInTheDocument();
    expect(within(doc).getByText("Sunrise Educational Trust")).toBeInTheDocument();
    expect(within(doc).getByText("GSTIN: not registered")).toBeInTheDocument();
    expect(within(doc).getByText("Akshara School Cloud, Growth plan, yearly subscription")).toBeInTheDocument();
    expect(within(doc).getAllByText("998315").length).toBeGreaterThan(0);
    expect(within(doc).getByText("CGST 9%")).toBeInTheDocument();
    expect(within(doc).getByText("SGST 9%")).toBeInTheDocument();
    expect(within(doc).queryByText(/IGST/)).not.toBeInTheDocument();
    expect(within(doc).getAllByText("₹4,500")).toHaveLength(2);
    expect(within(doc).getByTestId("invoice-total")).toHaveTextContent("₹59,000");
    expect(within(doc).getByText("Rupees Fifty Nine Thousand Only")).toBeInTheDocument();
    expect(within(doc).getAllByText("Telangana (36)").length).toBeGreaterThan(0);
    expect(within(doc).getByText("9 Oct 2026")).toBeInTheDocument();
    // The placeholder seller GSTIN makes it a sample, and says so.
    expect(screen.getByTestId("invoice-sample")).toHaveTextContent("not a valid tax invoice");
    expect(within(doc).getByText("Unpaid")).toBeInTheDocument();
  });

  it("shows IGST for a school in another state, the payments and a paid status", () => {
    renderInSchool(
      BILLING_READ_ONLY,
      <InvoiceDocument
        invoice={invoice({
          buyer: { name: "Pune Learning Trust", address: null, stateCode: "27", gstin: "27ABCDE1234F1Z5" },
          taxSplit: "IGST",
          cgstRateBp: 0,
          sgstRateBp: 0,
          igstRateBp: 1800,
          cgstPaise: 0,
          sgstPaise: 0,
          igstPaise: 9_000_00,
          status: "PAID",
          paidPaise: 59_000_00,
          balancePaise: 0,
          paidOn: "2026-10-15",
          sample: false,
          payments: [
            {
              id: "p1",
              amountPaise: 59_000_00,
              mode: "BANK_TRANSFER",
              reference: "UTR-123456",
              paidOn: "2026-10-15",
              recordedByName: "Platform Admin",
              recordedAt: "2026-10-15T05:00:00Z",
            },
          ],
        })}
      />,
    );
    expect(screen.getByText("IGST 18%")).toBeInTheDocument();
    expect(screen.queryByText(/CGST/)).not.toBeInTheDocument();
    expect(screen.getByText("₹9,000")).toBeInTheDocument();
    expect(screen.getByText("Maharashtra (27)", { selector: "dd" })).toBeInTheDocument();
    expect(screen.getByText("27ABCDE1234F1Z5")).toBeInTheDocument();
    expect(screen.getByText("UTR-123456")).toBeInTheDocument();
    expect(screen.getByText("Bank transfer")).toBeInTheDocument();
    expect(screen.getByTestId("invoice-balance")).toHaveTextContent("₹0");
    expect(screen.getByText("Paid")).toBeInTheDocument();
    expect(screen.queryByTestId("invoice-sample")).not.toBeInTheDocument();
  });

  it("stamps a cancelled invoice with who cancelled it and why", () => {
    renderInSchool(
      BILLING_READ_ONLY,
      <InvoiceDocument
        invoice={invoice({
          status: "CANCELLED",
          cancelledAt: "2026-10-09T08:00:00Z",
          cancelledByName: "Platform Admin",
          cancelReason: "Wrong number of students",
        })}
      />,
    );
    expect(screen.getByTestId("invoice")).toHaveAttribute("data-stamp", "Cancelled");
    expect(screen.getByTestId("invoice-cancelled")).toHaveTextContent(
      /Cancelled on 9 Oct 2026, 1:30 pm by Platform Admin\. Reason: Wrong number of students/i,
    );
  });
});

describe("PlanCards", () => {
  it("shows prices per student, limits and features, and marks the current plan", () => {
    renderInSchool(BILLING_READ_ONLY, <PlanCards plans={PLANS} current="STARTER" />);
    const starter = screen.getByRole("region", { name: "Starter" });
    expect(within(starter).getByText("Current plan")).toBeInTheDocument();
    expect(within(starter).getByText("₹83")).toBeInTheDocument();
    expect(within(starter).getByText("Up to 300 students · 1 branch")).toBeInTheDocument();
    expect(within(starter).getByText("Custom report cards")).toBeInTheDocument();
    const growth = screen.getByRole("region", { name: "Growth" });
    expect(within(growth).getByText("Most popular")).toBeInTheDocument();
    expect(within(growth).getByText("or ₹20 per student a month, billed monthly")).toBeInTheDocument();
    const enterprise = screen.getByRole("region", { name: "Enterprise" });
    expect(within(enterprise).getByText("Unlimited students · unlimited branches")).toBeInTheDocument();
  });
});

describe("loginErrorMessage", () => {
  it("tells someone with the right password that their school is paused, and nobody else", () => {
    const paused = new ApiError({ status: 401, title: "Sign-in paused", type: SCHOOL_PAUSED_TYPE });
    expect(loginErrorMessage(paused, "school", t)).toBe(
      "Sign-in to this school is paused at the moment. Please contact the school office.",
    );
    expect(loginErrorMessage(new ApiError({ status: 401, title: "Unauthorized" }), "school", t)).toBe(
      t("login.error.invalid"),
    );
    expect(loginErrorMessage(paused, "platform", t)).toBe(t("login.error.invalidPlatform"));
  });
});
