import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import type { CodeListType } from "@/lib/types";
import {
  CODES,
  fieldsRow,
  GOV_MANAGER,
  GOV_READER,
  sectionGrid,
} from "@/test/govreport-fixtures";
import { renderAs } from "@/test/render";
import { FieldsGrid, rowProblems } from "./students-view";

const m = vi.hoisted(() => ({
  codes: vi.fn(),
  saveGrid: vi.fn(),
  grid: vi.fn(),
}));

vi.mock("@/lib/govreport-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/govreport-api")>();
  return { ...actual, govreportApi: { ...actual.govreportApi, ...m } };
});
vi.mock("next/navigation", () => ({
  usePathname: () => "/app/govreport/students",
}));

beforeEach(() => {
  m.codes.mockImplementation(async (list: CodeListType) => CODES[list] ?? []);
  m.grid.mockResolvedValue(sectionGrid());
});
afterEach(() => vi.clearAllMocks());

const blank = {
  apaarId: "",
  pen: "",
  socialCategory: "",
  religion: "",
  motherTongue: "",
  bpl: "",
  cwsnType: "",
  rteAdmitted: "",
  previousSchoolUdise: "",
};

describe("rowProblems", () => {
  it("checks the APAAR ID, PEN and previous school UDISE like the API", () => {
    expect(rowProblems(blank)).toEqual({});
    expect(
      rowProblems({
        ...blank,
        apaarId: "1234",
        pen: "bad pen!",
        previousSchoolUdise: "12",
      }),
    ).toEqual({
      apaarId: "govreport.v.apaar",
      pen: "govreport.v.pen",
      previousSchoolUdise: "govreport.v.udise",
    });
    expect(
      rowProblems({
        ...blank,
        apaarId: "123456789012",
        pen: "TS123",
        previousSchoolUdise: "36011200451",
      }),
    ).toEqual({});
  });
});

describe("FieldsGrid", () => {
  it("shows every student of the section with what is missing, read-only without govreport.manage", async () => {
    renderAs(
      GOV_READER,
      <FieldsGrid sectionId="sec1" initial={sectionGrid()} />,
    );
    const rows = await screen.findAllByTestId("govreport-grid-row");
    expect(rows).toHaveLength(2);
    expect(within(rows[0]).getByText("Asha Rao")).toBeInTheDocument();
    expect(within(rows[0]).getByText("7 missing")).toBeInTheDocument();
    expect(within(rows[0]).getByLabelText("APAAR ID")).toBeDisabled();
    expect(screen.getByTestId("govreport-grid-summary")).toHaveTextContent(
      "0 of 2 complete",
    );
    expect(
      screen.queryByRole("button", { name: /Save changes/ }),
    ).not.toBeInTheDocument();
  });

  it("blocks a bad APAAR ID before sending and saves only the changed rows", async () => {
    const user = userEvent.setup();
    m.saveGrid.mockResolvedValue({ saved: 1, rows: [] });
    renderAs(
      GOV_MANAGER,
      <FieldsGrid sectionId="sec1" initial={sectionGrid()} />,
    );
    const rows = await screen.findAllByTestId("govreport-grid-row");
    const asha = within(rows[0]);
    await user.type(asha.getByLabelText("APAAR ID"), "1234");
    await user.click(screen.getByRole("button", { name: "Save changes (1)" }));
    expect(
      await asha.findByText("An APAAR ID has 12 digits."),
    ).toBeInTheDocument();
    expect(m.saveGrid).not.toHaveBeenCalled();

    await user.clear(asha.getByLabelText("APAAR ID"));
    await user.type(asha.getByLabelText("APAAR ID"), "123456789012");
    await waitFor(() =>
      expect(
        asha.getByRole("option", { name: "Scheduled Caste" }),
      ).toBeInTheDocument(),
    );
    await user.selectOptions(asha.getByLabelText("Social category"), "SC");
    await user.selectOptions(asha.getByLabelText("BPL"), "N");
    await user.click(screen.getByRole("button", { name: "Save changes (1)" }));
    await waitFor(() => expect(m.saveGrid).toHaveBeenCalledTimes(1));
    expect(m.saveGrid).toHaveBeenCalledWith([
      expect.objectContaining({
        studentId: "s1",
        apaarId: "123456789012",
        socialCategory: "SC",
        bpl: false,
        pen: null,
        rteAdmitted: null,
      }),
    ]);
  });

  it("shows the server's field error on the row it belongs to", async () => {
    const user = userEvent.setup();
    m.saveGrid.mockRejectedValue(
      new ApiError({
        status: 400,
        title: "Bad request",
        detail: "Invalid",
        errors: { "entries[0].pen": "A PEN has up to 20 letters or digits." },
      }),
    );
    const grid = sectionGrid([
      fieldsRow(),
      fieldsRow({
        studentId: "s2",
        fullName: "Bala Iyer",
        admissionNo: "GOV-2",
      }),
    ]);
    renderAs(GOV_MANAGER, <FieldsGrid sectionId="sec1" initial={grid} />);
    const rows = await screen.findAllByTestId("govreport-grid-row");
    await user.type(within(rows[1]).getByLabelText("PEN"), "X1");
    await user.click(screen.getByRole("button", { name: "Save changes (1)" }));
    expect(
      await within(rows[1]).findByText("A PEN has up to 20 letters or digits."),
    ).toBeInTheDocument();
    expect(
      within(rows[0]).queryByText("A PEN has up to 20 letters or digits."),
    ).not.toBeInTheDocument();
  });
});
