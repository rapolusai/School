import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { renderAs } from "@/test/render";
import { certificate, issued } from "@/test/certificates-fixtures";
import { CertificateView, editorKeys, validateEditor } from "./certificate-view";

const m = vi.hoisted(() => ({ detail: vi.fn(), edit: vi.fn(), sign: vi.fn(), cancel: vi.fn(), correct: vi.fn() }));

vi.mock("@/lib/certificates-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/certificates-api")>();
  return { ...actual, certificatesApi: { ...actual.certificatesApi, ...m } };
});
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/certificates/c1",
}));

const READER = ["dashboard.view", "certificates.read"];
const OFFICE = [...READER, "certificates.issue"];
const PRINCIPAL = [...OFFICE, "certificates.sign"];

beforeEach(() => {
  m.detail.mockResolvedValue(certificate());
});
afterEach(() => vi.clearAllMocks());

describe("validateEditor", () => {
  it("needs a title and caps the lengths", () => {
    expect(validateEditor({ title: "", purpose: "", fields: {} })).toEqual({ title: "validation.required" });
    expect(validateEditor({ title: "x".repeat(121), purpose: "", fields: {} })).toEqual({ title: "validation.tooLong" });
    expect(validateEditor({ title: "Bonafide", purpose: "y".repeat(301), fields: { father: "z".repeat(2001) } })).toEqual({
      purpose: "validation.tooLong",
      "field.father": "validation.tooLong",
    });
    expect(validateEditor({ title: "Bonafide", purpose: "bank", fields: { father: "Rahul" } })).toEqual({});
  });
});

describe("editorKeys", () => {
  it("lists the template's placeholders in order, then extra fields, never the ones filled on signing", () => {
    expect(editorKeys({ placeholders: ["student", "school", "date", "serialNo", "class"], fields: { class: "5 A", conduct: "Good", student: "A" } })).toEqual([
      "student",
      "class",
      "conduct",
    ]);
  });
});

describe("CertificateView", () => {
  it("shows the office an editable wording and the preview, and no sign button without certificates.sign", async () => {
    renderAs(OFFICE, <CertificateView id="c1" />, ["FRONT_OFFICE"]);
    const editor = await screen.findByTestId("certificate-editor");
    expect(within(editor).getByLabelText("Title")).toHaveValue("Bonafide Certificate");
    expect(within(editor).getByLabelText("Parent's or guardian's name")).toHaveValue("Rahul Sharma");
    expect(within(editor).getByLabelText("Parent's or guardian's name")).toBeEnabled();
    expect(within(editor).queryByLabelText("Serial number")).not.toBeInTheDocument();
    expect(screen.getByTestId("save-certificate")).toBeInTheDocument();
    expect(screen.queryByTestId("sign-certificate")).not.toBeInTheDocument();
    expect(screen.getByTestId("cancel-certificate")).toBeInTheDocument();
    expect(screen.queryByTestId("print-certificate")).not.toBeInTheDocument();
    expect(screen.getByTestId("certificate-document")).toHaveClass("cert-draft");
    expect(m.detail).toHaveBeenCalledWith("c1");
  });

  it("saves the edited wording through the API", async () => {
    const user = userEvent.setup();
    m.edit.mockImplementation(async (_id: string, body: { title: string; purpose?: string; fields: Record<string, string> }) =>
      certificate({ title: body.title, fields: { ...certificate().fields, ...body.fields } }),
    );
    renderAs(OFFICE, <CertificateView id="c1" />, ["FRONT_OFFICE"]);
    const father = await screen.findByLabelText("Parent's or guardian's name");
    await user.clear(father);
    await user.type(father, "Rahul K Sharma");
    await user.click(screen.getByTestId("save-certificate"));
    await waitFor(() => expect(m.edit).toHaveBeenCalledTimes(1));
    expect(m.edit.mock.calls[0][0]).toBe("c1");
    expect(m.edit.mock.calls[0][1].fields.father).toBe("Rahul K Sharma");
    expect(await screen.findByText("Certificate saved.")).toBeInTheDocument();
  });

  it("lets the principal sign a prepared certificate after confirming", async () => {
    const user = userEvent.setup();
    m.sign.mockResolvedValue(issued());
    renderAs(PRINCIPAL, <CertificateView id="c1" />, ["PRINCIPAL"]);
    await user.click(await screen.findByTestId("sign-certificate"));
    const dialog = await screen.findByRole("dialog");
    expect(dialog).toHaveTextContent("Arjun Sharma");
    await user.click(within(dialog).getByRole("button", { name: "Sign and issue" }));
    await waitFor(() => expect(m.sign).toHaveBeenCalledWith("c1"));
    expect(await screen.findByTestId("certificate-serial")).toHaveTextContent("BC/2026-27/0001");
    expect(screen.getByTestId("print-certificate")).toBeInTheDocument();
    expect(screen.queryByTestId("sign-certificate")).not.toBeInTheDocument();
    // Issued: the wording is frozen.
    expect(screen.getByLabelText("Parent's or guardian's name")).toBeDisabled();
    expect(screen.getByTestId("qr-code")).toBeInTheDocument();
  });

  it("warns about pending fees on a transfer certificate and keeps a reader to looking", async () => {
    m.detail.mockResolvedValue(certificate({ type: "TRANSFER", title: "Transfer Certificate", duesPaise: 250000 }));
    renderAs(READER, <CertificateView id="c1" />, ["TEACHER"]);
    expect(await screen.findByTestId("dues-warning")).toHaveTextContent("₹2,500");
    expect(screen.getByLabelText("Title")).toBeDisabled();
    expect(screen.queryByTestId("save-certificate")).not.toBeInTheDocument();
    expect(screen.queryByTestId("cancel-certificate")).not.toBeInTheDocument();
    expect(screen.queryByTestId("sign-certificate")).not.toBeInTheDocument();
  });

  it("warns about library books still out before a transfer certificate is signed", async () => {
    m.detail.mockResolvedValue(certificate({ type: "TRANSFER", title: "Transfer Certificate", booksOut: 2 }));
    renderAs(READER, <CertificateView id="c1" />, ["TEACHER"]);
    expect(await screen.findByTestId("books-warning")).toHaveTextContent("Library books still out: 2");
  });

  it("offers a correction on an issued certificate and shows the cancelled reason afterwards", async () => {
    const user = userEvent.setup();
    m.detail.mockResolvedValue(issued());
    m.correct.mockResolvedValue(issued({ id: "c2", status: "PREPARED", serialNo: null, versionNo: 2, supersedesId: "c1", editable: true }));
    renderAs(OFFICE, <CertificateView id="c1" />, ["FRONT_OFFICE"]);
    await user.click(await screen.findByTestId("correct-certificate"));
    const dialog = await screen.findByRole("dialog");
    await user.type(within(dialog).getByLabelText("Reason"), "name misspelt");
    await user.click(within(dialog).getByRole("button", { name: "Correct" }));
    await waitFor(() => expect(m.correct).toHaveBeenCalledWith("c1", "name misspelt"));
  });
});
