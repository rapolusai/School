import { fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import { setLang } from "@/lib/i18n";
import type { PublicSchoolInfo } from "@/lib/types";
import { TODAY } from "@/test/admissions-fixtures";
import { addDays, yearsBefore } from "./admission-time";
import { PublicEnquiryView, validateEnquiry, type EnquiryValues } from "./public-enquiry-view";

const mocks = vi.hoisted(() => ({ publicInfo: vi.fn(), sendEnquiry: vi.fn() }));

vi.mock("@/lib/admissions-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/admissions-api")>();
  return {
    ...actual,
    admissionsApi: { ...actual.admissionsApi, publicInfo: mocks.publicInfo, sendEnquiry: mocks.sendEnquiry },
  };
});

const INFO: PublicSchoolInfo = {
  name: "Akshara Demo School",
  board: "CBSE",
  city: "Pune",
  classes: ["Nursery", "LKG", "UKG", "Class 1"],
  years: [
    { code: "CURRENT", name: "2026-27" },
    { code: "NEXT", name: "2027-28" },
  ],
  consentVersion: "enquiry-2026-10",
};

beforeEach(() => {
  vi.clearAllMocks();
  mocks.publicInfo.mockResolvedValue(INFO);
  mocks.sendEnquiry.mockResolvedValue({ received: true });
});

afterEach(() => {
  setLang("en");
});

async function openForm() {
  const user = userEvent.setup();
  render(<PublicEnquiryView schoolCode="akshara-demo" />);
  await screen.findByTestId("enquiry-school");
  return user;
}

async function fillValidForm(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText("Your name"), " Meera Bhatt ");
  await user.type(screen.getByLabelText("Mobile number"), "+91 98765 02001");
  await user.selectOptions(screen.getByLabelText("You are the child's"), "MOTHER");
  await user.type(screen.getByLabelText("First name"), "Aanya");
  await user.type(screen.getByLabelText("Last name"), "Bhatt");
  fireEvent.change(screen.getByLabelText("Date of birth"), { target: { value: "2022-03-14" } });
  await user.selectOptions(screen.getByLabelText("Class"), "LKG");
  await user.click(screen.getByRole("radio", { name: "2027-28" }));
  await user.click(screen.getByRole("checkbox"));
}

describe("The public enquiry page", () => {
  it("shows only what a parent needs about the school", async () => {
    await openForm();
    expect(mocks.publicInfo).toHaveBeenCalledWith("akshara-demo");
    expect(screen.getByRole("heading", { level: 1, name: "Akshara Demo School" })).toBeInTheDocument();
    expect(screen.getByText("CBSE · Pune")).toBeInTheDocument();
    const classes = within(screen.getByLabelText("Class")).getAllByRole("option");
    expect(classes.map((o) => o.textContent)).toEqual(["Choose…", "Nursery", "LKG", "UKG", "Class 1"]);
    const years = within(screen.getByRole("radiogroup", { name: "Academic year" })).getAllByRole("radio");
    expect(years.map((radio) => (radio as HTMLInputElement).checked)).toEqual([false, false]);
    expect(screen.getByRole("checkbox")).not.toBeChecked();
    expect(screen.getByText(/I agree that Akshara Demo School may keep these details/)).toBeInTheDocument();
  });

  it("checks the form before sending and asks for consent", async () => {
    const user = await openForm();
    await user.click(screen.getByRole("button", { name: "Send enquiry" }));

    expect(mocks.sendEnquiry).not.toHaveBeenCalled();
    expect(screen.getAllByText("Fill in this field.")).toHaveLength(3);
    expect(screen.getByText("Choose one.")).toBeInTheDocument();
    expect(screen.getByText("Enter a valid date.")).toBeInTheDocument();
    expect(screen.getByText("Pick a class.")).toBeInTheDocument();
    expect(screen.getByText("Pick an academic year.")).toBeInTheDocument();
    expect(screen.getByText("Tick the box to agree before sending.")).toBeInTheDocument();
    expect(screen.getByRole("checkbox")).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByLabelText("Your name")).toHaveFocus();
  });

  it("sends the enquiry and thanks the parent", async () => {
    const user = await openForm();
    await fillValidForm(user);
    await user.click(screen.getByRole("button", { name: "Send enquiry" }));

    expect(mocks.sendEnquiry).toHaveBeenCalledWith("akshara-demo", {
      parentName: "Meera Bhatt",
      relation: "MOTHER",
      mobile: "+91 98765 02001",
      email: null,
      childFirstName: "Aanya",
      childLastName: "Bhatt",
      dateOfBirth: "2022-03-14",
      className: "LKG",
      academicYear: "NEXT",
      message: null,
      consent: true,
      consentVersion: "enquiry-2026-10",
      website: "",
    });
    const sent = await screen.findByTestId("enquiry-sent");
    expect(within(sent).getByRole("heading", { name: "Thank you, Meera Bhatt" })).toBeInTheDocument();
    expect(
      within(sent).getByText("Akshara Demo School has your enquiry for Aanya Bhatt and will contact you on 98765 02001."),
    ).toBeInTheDocument();

    await user.click(within(sent).getByRole("button", { name: "Send another enquiry" }));
    expect(screen.getByLabelText("Your name")).toHaveValue("");
    expect(screen.getByRole("checkbox")).not.toBeChecked();
  });

  it("keeps the honeypot out of sight and out of the tab order", async () => {
    await openForm();
    const trap = document.querySelector<HTMLInputElement>('input[name="website"]');
    expect(trap).not.toBeNull();
    expect(trap).toHaveAttribute("tabindex", "-1");
    expect(trap?.closest(".hp-field")).toHaveAttribute("aria-hidden", "true");
    expect(screen.queryByRole("textbox", { name: "Website" })).not.toBeInTheDocument();
  });

  it("shows the API's field errors", async () => {
    mocks.sendEnquiry.mockRejectedValue(
      new ApiError({ status: 400, title: "Invalid request", errors: { className: "This school has no class with this name." } }),
    );
    const user = await openForm();
    await fillValidForm(user);
    await user.click(screen.getByRole("button", { name: "Send enquiry" }));
    expect(await screen.findByText("This school has no class with this name.")).toBeInTheDocument();
    expect(screen.getByText("Check the highlighted fields.")).toBeInTheDocument();
  });

  it("asks the parent to wait when too many enquiries were sent", async () => {
    mocks.sendEnquiry.mockRejectedValue(new ApiError({ status: 429, title: "Too many requests" }));
    const user = await openForm();
    await fillValidForm(user);
    await user.click(screen.getByRole("button", { name: "Send enquiry" }));
    expect(
      await screen.findByText("Too many enquiries have been sent from here. Wait a while and try again."),
    ).toBeInTheDocument();
    expect(screen.queryByTestId("enquiry-sent")).not.toBeInTheDocument();
  });

  it("says when the link does not lead to a school", async () => {
    mocks.publicInfo.mockRejectedValue(new ApiError({ status: 404, title: "Not found" }));
    render(<PublicEnquiryView schoolCode="nowhere" />);
    const box = await screen.findByTestId("enquiry-unavailable");
    expect(within(box).getByRole("heading", { name: "Enquiry form not available" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Send enquiry" })).not.toBeInTheDocument();
  });

  it("is available in Hindi", async () => {
    const user = await openForm();
    await user.selectOptions(screen.getByLabelText("Language"), "hi");
    expect(await screen.findByText("आपके बारे में")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "पूछताछ भेजें" })).toBeInTheDocument();
  });
});

describe("validateEnquiry", () => {
  const valid: EnquiryValues = {
    parentName: "Meera Bhatt",
    mobile: "9876502001",
    relation: "MOTHER",
    email: "",
    childFirstName: "Aanya",
    childLastName: "",
    dateOfBirth: "2022-03-14",
    className: "LKG",
    academicYear: "CURRENT",
    message: "",
    consent: true,
    website: "",
  };

  it("accepts a complete enquiry", () => {
    expect(validateEnquiry(valid, TODAY)).toEqual({});
  });

  it("refuses the dates, lengths and email the API refuses", () => {
    expect(
      validateEnquiry(
        {
          ...valid,
          dateOfBirth: addDays(yearsBefore(TODAY, 25), -1),
          email: "not-an-email",
          message: "x".repeat(1001),
          mobile: "12345 67890",
        },
        TODAY,
      ),
    ).toEqual({
      dateOfBirth: "admissions.v.dob",
      email: "validation.email",
      message: "validation.tooLong",
      mobile: "students.v.phone",
    });
    expect(validateEnquiry({ ...valid, dateOfBirth: TODAY }, TODAY)).toEqual({ dateOfBirth: "students.v.dobPast" });
  });
});
