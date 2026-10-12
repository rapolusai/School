import { screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { qrMatrix } from "@/lib/qr";
import { renderAs } from "@/test/render";
import { certificate, issued } from "@/test/certificates-fixtures";
import { CertificateDocument, verifyUrl } from "./certificate-document";

const OFFICE = ["dashboard.view", "certificates.read", "certificates.issue"];

describe("verifyUrl", () => {
  it("points the QR code at the public verification page", () => {
    expect(verifyUrl("https://school.example", "tok123")).toBe("https://school.example/verify/tok123");
    expect(verifyUrl("https://school.example/", "a b")).toBe("https://school.example/verify/a%20b");
  });
});

describe("CertificateDocument", () => {
  it("prints the school header, serial number, wording, signatory and a scannable QR code once issued", () => {
    renderAs(OFFICE, <CertificateDocument certificate={issued()} origin="https://school.example" />);
    const doc = screen.getByTestId("certificate-document");
    expect(doc).toHaveClass("cert-doc");
    expect(doc).not.toHaveClass("cert-draft");
    expect(doc).toHaveAttribute("data-status", "ISSUED");
    expect(within(doc).getByRole("heading", { level: 2 })).toHaveTextContent("Akshara Demo School");
    expect(doc).toHaveTextContent("12 MG Road, Bengaluru 560001");
    expect(doc).toHaveTextContent("Affiliation no. CBSE/1234");
    expect(screen.getByTestId("certificate-serial")).toHaveTextContent("BC/2026-27/0001");
    expect(within(doc).getByRole("heading", { level: 1 })).toHaveTextContent("Bonafide Certificate");
    expect(doc).toHaveTextContent("is a bonafide student of Class 5 A");
    expect(doc).toHaveTextContent("Prem Principal");
    expect(doc).toHaveTextContent("Principal");
    // The two paragraphs of the wording become two <p>s.
    expect(doc.querySelectorAll(".cert-body p")).toHaveLength(2);
    const qr = screen.getByTestId("qr-code");
    expect(qr).toHaveAttribute("role", "img");
    expect(qr.getAttribute("viewBox")).toBe(`0 0 ${qrMatrix("https://school.example/verify/abc123tokenxyz").size + 8} ${qrMatrix("https://school.example/verify/abc123tokenxyz").size + 8}`);
    expect(screen.getByTestId("certificate-verify-url")).toHaveTextContent("Verify at https://school.example/verify/abc123tokenxyz");
    expect(screen.queryByText("Draft")).not.toBeInTheDocument();
    expect(screen.queryByText("Cancelled")).not.toBeInTheDocument();
  });

  it("marks a prepared certificate as a draft with no serial number and no QR code", () => {
    renderAs(OFFICE, <CertificateDocument certificate={certificate()} origin="https://school.example" />);
    const doc = screen.getByTestId("certificate-document");
    expect(doc).toHaveClass("cert-draft");
    expect(screen.getByText("Draft")).toBeInTheDocument();
    expect(screen.getByTestId("certificate-serial")).toHaveTextContent("Not yet issued");
    expect(screen.queryByTestId("qr-code")).not.toBeInTheDocument();
    expect(screen.queryByTestId("certificate-verify-url")).not.toBeInTheDocument();
  });

  it("stamps a cancelled certificate but keeps its serial number and QR code", () => {
    renderAs(OFFICE, <CertificateDocument certificate={issued({ status: "CANCELLED", cancelledAt: "2026-10-03T05:00:00Z", cancelReason: "name misspelt" })} origin="https://school.example" />);
    expect(screen.getByText("Cancelled")).toHaveClass("cert-stamp");
    expect(screen.getByTestId("certificate-serial")).toHaveTextContent("BC/2026-27/0001");
    expect(screen.getByTestId("qr-code")).toBeInTheDocument();
  });
});
