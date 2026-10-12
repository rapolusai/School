import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ACCOUNTS_ACCOUNTANT, CENTRES, expenseDetail, HEADS, VENDORS } from "@/test/accounts-fixtures";
import { renderAs } from "@/test/render";
import { ExpenseFormDialog, toExpenseRequest } from "./expense-form";

const api = vi.hoisted(() => ({
  heads: vi.fn(),
  vendors: vi.fn(),
  costCentres: vi.fn(),
  createExpense: vi.fn(),
  updateExpense: vi.fn(),
  submitExpense: vi.fn(),
  addAttachment: vi.fn(),
}));

vi.mock("@/lib/accounts-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/accounts-api")>();
  return { ...actual, accountsApi: { ...actual.accountsApi, ...api } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/accounts/expenses",
}));

beforeEach(() => {
  vi.clearAllMocks();
  api.heads.mockResolvedValue(HEADS);
  api.vendors.mockResolvedValue(VENDORS);
  api.costCentres.mockResolvedValue(CENTRES);
  api.createExpense.mockImplementation(async () => expenseDetail({ id: "e-new", status: "DRAFT", number: null }));
  api.addAttachment.mockImplementation(async () => expenseDetail({ id: "e-new", status: "DRAFT", number: null, attachmentCount: 1 }));
  api.submitExpense.mockImplementation(async () => expenseDetail({ id: "e-new", status: "SUBMITTED", number: "EXP-2026-27-0007" }));
});

async function openForm() {
  const onSaved = vi.fn();
  renderAs(ACCOUNTS_ACCOUNTANT, <ExpenseFormDialog expense={null} onClose={vi.fn()} onSaved={onSaved} />);
  const form = await screen.findByTestId("expense-form");
  await within(form).findByRole("option", { name: "ELEC · Electricity" });
  return { form, onSaved };
}

describe("ExpenseFormDialog", () => {
  it("refuses an empty form and names what is missing", async () => {
    const user = userEvent.setup();
    const { form } = await openForm();
    await user.click(within(form).getByRole("button", { name: "Save draft" }));
    expect(await within(form).findByText("Choose a head.")).toBeInTheDocument();
    expect(within(form).getByText("Enter an amount above zero.")).toBeInTheDocument();
    expect(within(form).getByText("Say what the expense was for.")).toBeInTheDocument();
    expect(api.createExpense).not.toHaveBeenCalled();
  });

  it("works out GST from the rate and TDS from the vendor, and sends the bill for approval with its photo", async () => {
    const user = userEvent.setup();
    const { form, onSaved } = await openForm();
    await user.selectOptions(within(form).getByLabelText("Head"), "h-electricity");
    await user.selectOptions(within(form).getByLabelText("Vendor"), "v-sparks");
    await user.type(within(form).getByLabelText("Amount"), "10000");
    await user.selectOptions(within(form).getByLabelText("GST rate"), "18");
    expect(within(form).getByLabelText("GST")).toHaveValue("1800");
    await user.click(within(form).getByRole("checkbox", { name: /Keep back TDS 194C at 2%/ }));
    const totals = within(form).getByTestId("expense-totals");
    expect(totals).toHaveTextContent("₹11,800");
    expect(totals).toHaveTextContent("₹200");
    expect(totals).toHaveTextContent("₹11,600");
    await user.type(within(form).getByLabelText("Description"), "Lab wiring");
    await user.type(within(form).getByLabelText("Bill number"), "SE/1042");
    const photo = new File(["bill"], "bill.jpg", { type: "image/jpeg" });
    await user.upload(within(form).getByLabelText("Choose files"), photo);
    expect(within(form).getByText("bill.jpg")).toBeInTheDocument();

    await user.click(within(form).getByRole("button", { name: "Send for approval" }));

    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(api.createExpense).toHaveBeenCalledWith(
      {
        headId: "h-electricity",
        costCentreId: null,
        vendorId: "v-sparks",
        vendorName: null,
        expenseDate: expect.stringMatching(/^\d{4}-\d{2}-\d{2}$/),
        amountPaise: 10_000_00,
        gstPaise: 1_800_00,
        deductTds: true,
        description: "Lab wiring",
        billNumber: "SE/1042",
      },
      false,
    );
    expect(api.addAttachment).toHaveBeenCalledWith("e-new", photo);
    expect(api.submitExpense).toHaveBeenCalledWith("e-new");
    expect(onSaved).toHaveBeenCalledWith(expect.objectContaining({ status: "SUBMITTED" }), true);
  });

  it("hides GST for a head that does not take it and keeps a one-off payee's name", async () => {
    const user = userEvent.setup();
    const { form, onSaved } = await openForm();
    await user.selectOptions(within(form).getByLabelText("Head"), "h-rent");
    expect(within(form).queryByLabelText("GST rate")).not.toBeInTheDocument();
    await user.type(within(form).getByLabelText("Paid to"), "Mr Rao (landlord)");
    await user.type(within(form).getByLabelText("Amount"), "25000");
    await user.type(within(form).getByLabelText("Description"), "Annexe rent");
    await user.click(within(form).getByRole("button", { name: "Save draft" }));
    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(expect.objectContaining({ status: "DRAFT" }), false));
    expect(api.createExpense.mock.calls[0]?.[0]).toMatchObject({ vendorId: null, vendorName: "Mr Rao (landlord)", gstPaise: 0, deductTds: false });
    expect(api.submitExpense).not.toHaveBeenCalled();
  });
});

describe("toExpenseRequest", () => {
  it("turns rupees into paise and blanks into nulls", () => {
    expect(
      toExpenseRequest({
        headId: "h-1",
        costCentreId: "",
        vendorId: "",
        vendorName: "  ",
        expenseDate: "2026-10-05",
        amount: "1250.50",
        gstRate: "",
        gst: "",
        deductTds: false,
        description: " Chalk ",
        billNumber: "",
      }),
    ).toEqual({
      headId: "h-1",
      costCentreId: null,
      vendorId: null,
      vendorName: null,
      expenseDate: "2026-10-05",
      amountPaise: 125_050,
      gstPaise: 0,
      deductTds: false,
      description: "Chalk",
      billNumber: null,
    });
  });
});
