import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { CodeListType } from "@/lib/types";
import {
  CODES,
  GOV_MANAGER,
  GOV_READER,
  intakeDetail,
  runDetail,
} from "@/test/govreport-fixtures";
import { renderAs } from "@/test/render";
import {
  IntakePanel,
  validateLottery,
  validateScreening,
} from "./rte-intake-view";
import { RunDocument } from "./rte-run-view";

const m = vi.hoisted(() => ({
  codes: vi.fn(),
  candidates: vi.fn(),
  runLottery: vi.fn(),
  screen: vi.fn(),
  withdraw: vi.fn(),
  addApplication: vi.fn(),
}));

vi.mock("@/lib/govreport-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/govreport-api")>();
  return { ...actual, govreportApi: { ...actual.govreportApi, ...m } };
});
vi.mock("next/navigation", () => ({
  usePathname: () => "/app/govreport/rte/i1",
}));

beforeEach(() => {
  m.codes.mockImplementation(async (list: CodeListType) => CODES[list] ?? []);
  m.candidates.mockResolvedValue([]);
});
afterEach(() => vi.clearAllMocks());

describe("validateLottery and validateScreening", () => {
  it("needs a witness, and a reason for a rerun", () => {
    expect(validateLottery({ witnessName: " ", reason: "" }, false)).toEqual({
      witnessName: "govreport.v.witness",
    });
    expect(validateLottery({ witnessName: "SMC", reason: "" }, false)).toEqual(
      {},
    );
    expect(validateLottery({ witnessName: "SMC", reason: "" }, true)).toEqual({
      reason: "govreport.v.rerunReason",
    });
  });

  it("needs the declaration for an eligible application and a reason for an ineligible one", () => {
    const base = {
      categoryCode: "",
      income: "",
      incomeDeclared: false,
      distanceKm: "",
      eligibility: "ELIGIBLE" as const,
      ineligibleReason: "",
      docs: {},
    };
    expect(validateScreening(base)).toEqual({
      incomeDeclared: "govreport.v.declaration",
    });
    expect(validateScreening({ ...base, eligibility: "INELIGIBLE" })).toEqual({
      ineligibleReason: "govreport.v.reason",
    });
    expect(
      validateScreening({
        ...base,
        incomeDeclared: true,
        distanceKm: "-1",
        income: "abc",
      }),
    ).toEqual({
      distanceKm: "govreport.v.distance",
      annualIncomePaise: "govreport.v.amount",
    });
  });
});

describe("IntakePanel", () => {
  it("lists applications with masked phones, and offers no lottery without govreport.manage", async () => {
    renderAs(
      GOV_READER,
      <IntakePanel detail={intakeDetail()} reload={vi.fn()} />,
    );
    const apps = await screen.findAllByTestId("rte-application");
    expect(apps).toHaveLength(2);
    expect(apps[0]).toHaveTextContent("XXXXXX2010");
    expect(screen.queryByTestId("rte-lottery")).not.toBeInTheDocument();
    expect(screen.queryByTestId("rte-screen")).not.toBeInTheDocument();
  });

  it("runs the lottery with the witness, after checking it is named", async () => {
    const user = userEvent.setup();
    const reload = vi.fn();
    m.runLottery.mockResolvedValue(runDetail());
    renderAs(
      GOV_MANAGER,
      <IntakePanel detail={intakeDetail()} reload={reload} />,
    );
    const card = await screen.findByTestId("rte-lottery");
    expect(
      within(card).getByText(/2 eligible applications for 2 seats/),
    ).toBeInTheDocument();
    await user.click(within(card).getByRole("button", { name: "Run lottery" }));
    expect(
      await within(card).findByText("Name the witness."),
    ).toBeInTheDocument();
    expect(m.runLottery).not.toHaveBeenCalled();
    await user.type(within(card).getByLabelText("Witness"), "SMC member");
    await user.click(within(card).getByRole("button", { name: "Run lottery" }));
    await waitFor(() =>
      expect(m.runLottery).toHaveBeenCalledWith("i1", {
        witnessName: "SMC member",
        reason: null,
      }),
    );
    expect(reload).toHaveBeenCalled();
  });

  it("asks for a reason when the lottery has already run", async () => {
    const user = userEvent.setup();
    const detail = intakeDetail({
      runs: [runDetail().run],
      intake: runDetail().intake,
    });
    renderAs(GOV_MANAGER, <IntakePanel detail={detail} reload={vi.fn()} />);
    const card = await screen.findByTestId("rte-lottery");
    expect(
      within(card).getByRole("heading", { name: "Run the lottery again" }),
    ).toBeInTheDocument();
    await user.type(within(card).getByLabelText("Witness"), "SMC member");
    await user.click(within(card).getByRole("button", { name: "Run lottery" }));
    expect(
      await within(card).findByText(
        "Give the reason for running the lottery again.",
      ),
    ).toBeInTheDocument();
    expect(m.runLottery).not.toHaveBeenCalled();
    expect(screen.getByTestId("rte-runs")).toHaveTextContent("Run 1");
    // After the lottery the applications cannot be screened again.
    expect(screen.queryByTestId("rte-screen")).not.toBeInTheDocument();
  });
});

describe("RunDocument", () => {
  it("prints the ordered list with the waitlist, and allotment letters for the allotted only", async () => {
    const user = userEvent.setup();
    renderAs(GOV_READER, <RunDocument detail={runDetail()} />);
    const rows = screen.getAllByTestId("rte-allotment");
    expect(rows.map((r) => r.textContent)).toEqual([
      expect.stringContaining("Meher Shaik"),
      expect.stringContaining("Aarav Naik"),
      expect.stringContaining("Waitlist 1"),
    ]);
    expect(screen.getByTestId("rte-run-list")).toHaveTextContent("seed 12345");
    expect(screen.getByTestId("rte-run-list")).toHaveTextContent(
      "witness: SMC member",
    );
    await user.click(screen.getByTestId("rte-toggle-letters"));
    const letters = screen.getByTestId("rte-letters");
    expect(within(letters).getAllByRole("article")).toHaveLength(2);
    expect(letters).not.toHaveTextContent("Lasya Gowd");
  });
});
