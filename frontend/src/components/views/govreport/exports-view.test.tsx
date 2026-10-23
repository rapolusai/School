import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { GOV_READER, udisePreview } from "@/test/govreport-fixtures";
import { renderAs } from "@/test/render";
import { ExportPreviewPanel, ExportsView } from "./exports-view";

const m = vi.hoisted(() => ({
  udise: vi.fn(),
  oasis: vi.fn(),
  registration: vi.fn(),
  registrationClasses: vi.fn(),
  download: vi.fn(),
}));
const y = vi.hoisted(() => ({ listYears: vi.fn() }));

vi.mock("@/lib/govreport-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/govreport-api")>();
  return { ...actual, govreportApi: { ...actual.govreportApi, ...m } };
});
vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, listYears: y.listYears } };
});
vi.mock("next/navigation", () => ({
  usePathname: () => "/app/govreport/exports",
}));

beforeEach(() => {
  y.listYears.mockResolvedValue([
    {
      id: "y1",
      name: "2026-27",
      startsOn: "2026-04-01",
      endsOn: "2027-03-31",
      current: true,
    },
  ]);
  m.udise.mockResolvedValue(udisePreview());
  m.oasis.mockResolvedValue({ ...udisePreview(), kind: "OASIS_XLSX" });
  m.registrationClasses.mockResolvedValue([]);
  m.download.mockResolvedValue("udise-plus.xlsx");
});
afterEach(() => vi.clearAllMocks());

describe("ExportPreviewPanel", () => {
  it("shows each table as it will be in the workbook and downloads the workbook and one table", async () => {
    const user = userEvent.setup();
    renderAs(
      GOV_READER,
      <ExportPreviewPanel
        preview={udisePreview()}
        kind="udise"
        params={{ yearId: "y1", asOf: "2026-09-30" }}
      />,
    );
    const enrolment = screen.getByTestId("govreport-export-table-enrolment");
    expect(
      within(enrolment).getByRole("columnheader", { name: "Girls" }),
    ).toBeInTheDocument();
    expect(within(enrolment).getAllByRole("row")).toHaveLength(3);
    expect(
      screen.getByText(
        "Verify against the current portal template before uploading.",
      ),
    ).toBeInTheDocument();
    await user.click(screen.getByTestId("govreport-download-workbook"));
    expect(m.download).toHaveBeenCalledWith(
      "/api/govreport/exports/udise.xlsx?yearId=y1&asOf=2026-09-30",
      "udise.xlsx",
    );
    await user.click(within(enrolment).getByRole("button", { name: "CSV" }));
    expect(m.download).toHaveBeenLastCalledWith(
      "/api/govreport/exports/udise/enrolment.csv?yearId=y1&asOf=2026-09-30",
      "udise-enrolment.csv",
    );
  });
});

describe("ExportsView", () => {
  it("previews UDISE+ for the current year with the verify label, and switches to OASIS", async () => {
    const user = userEvent.setup();
    renderAs(GOV_READER, <ExportsView />);
    expect(
      await screen.findByTestId("govreport-export-preview"),
    ).toBeInTheDocument();
    expect(screen.getByTestId("govreport-verify")).toHaveTextContent(
      "Verify against the current portal template",
    );
    expect(m.udise).toHaveBeenCalledWith(
      expect.objectContaining({ yearId: "y1" }),
    );
    await user.click(screen.getByTestId("govreport-export-oasis"));
    await waitFor(() => expect(m.oasis).toHaveBeenCalled());
  });

  it("says when the school has no class to register with CBSE", async () => {
    const user = userEvent.setup();
    renderAs(GOV_READER, <ExportsView />);
    await screen.findByTestId("govreport-export-preview");
    await user.click(screen.getByTestId("govreport-export-registration"));
    expect(
      await screen.findByText("No Class 9 or Class 11 in this school."),
    ).toBeInTheDocument();
    expect(m.registration).not.toHaveBeenCalled();
  });
});
