import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { translate, type Translate } from "@/lib/i18n";
import type { FunnelStage } from "@/lib/types";
import {
  BarChart,
  compactRupees,
  FunnelBars,
  funnelRows,
  LineChart,
  lineRuns,
  niceCeil,
  percentDomain,
  xAt,
  yAt,
} from "./charts";

const t: Translate = (key, vars) => translate("en", key, vars);
const pct = (v: number) => `${v}%`;

describe("chart maths", () => {
  it("starts the percentage axis ten points below the lowest value, rounded down", () => {
    expect(percentDomain([92.5, 88, null, 97])).toEqual([70, 100]);
    expect(percentDomain([100, 100])).toEqual([90, 100]);
    expect(percentDomain([4])).toEqual([0, 100]);
    expect(percentDomain([null, null])).toEqual([0, 100]);
  });

  it("places points across and up the plot", () => {
    expect(xAt(0, 30)).toBe(0);
    expect(xAt(29, 30)).toBe(100);
    expect(xAt(0, 1)).toBe(50);
    expect(yAt(100, [80, 100])).toBe(0);
    expect(yAt(80, [80, 100])).toBe(100);
    expect(yAt(90, [80, 100])).toBe(50);
    expect(yAt(50, [80, 100])).toBe(100);
  });

  it("breaks the line where a day has no value", () => {
    expect(lineRuns([90, 100, null, 80, 90], [80, 100])).toEqual(["0,50 25,0", "75,100 100,50"]);
    expect(lineRuns([null, null], [0, 100])).toEqual([]);
  });

  it("rounds an axis maximum up to a tidy number", () => {
    expect(niceCeil(0)).toBe(0);
    expect(niceCeil(7)).toBe(10);
    expect(niceCeil(1_840_000)).toBe(2_000_000);
    expect(niceCeil(2_100)).toBe(2_500);
    expect(niceCeil(4_999)).toBe(5_000);
    expect(niceCeil(100)).toBe(100);
  });

  it("writes short rupee labels from paise", () => {
    expect(compactRupees(0)).toBe("₹0");
    expect(compactRupees(95_000)).toBe("₹950");
    expect(compactRupees(1_200_000)).toBe("₹12K");
    expect(compactRupees(15_000_000)).toBe("₹1.5L");
    expect(compactRupees(2_000_000_000)).toBe("₹2Cr");
  });
});

describe("LineChart", () => {
  it("draws a line with a gap, a dot per value and a table for screen readers", () => {
    const { container } = render(
      <LineChart
        points={[
          { key: "a", label: "1 Oct 2026", value: 90 },
          { key: "b", label: "3 Oct 2026", value: 95 },
          { key: "c", label: "5 Oct 2026", value: null },
          { key: "d", label: "6 Oct 2026", value: 85 },
        ]}
        caption="Attendance trend"
        head={["Date", "Attendance %"]}
        formatValue={pct}
        testId="trend"
      />,
    );
    expect(container.querySelectorAll("polyline")).toHaveLength(2);
    expect(container.querySelectorAll(".chart-dot")).toHaveLength(3);
    const table = screen.getByRole("table", { name: "Attendance trend" });
    const rows = within(table).getAllByRole("row");
    expect(rows).toHaveLength(5);
    expect(rows[3]).toHaveTextContent("5 Oct 2026—");
    expect(rows[4]).toHaveTextContent("6 Oct 202685%");
    expect(screen.getByTestId("trend")).toHaveTextContent("1 Oct 2026");
  });
});

describe("BarChart", () => {
  it("scales bars to a tidy maximum and highlights today", () => {
    const { container } = render(
      <BarChart
        bars={[
          { key: "1", label: "1 Oct", tick: "1", value: 100_000 },
          { key: "2", label: "2 Oct", value: 0 },
          { key: "3", label: "3 Oct", value: 200_000, highlight: true },
        ]}
        caption="Collections"
        head={["Date", "Amount"]}
        formatValue={(v) => `₹${v / 100}`}
        formatAxis={compactRupees}
      />,
    );
    const bars = container.querySelectorAll<HTMLElement>(".chart-bar");
    expect(bars).toHaveLength(3);
    expect(bars[0].style.height).toBe("50%");
    expect(bars[1].style.height).toBe("0%");
    expect(bars[2].style.height).toBe("100%");
    expect(bars[2]).toHaveClass("is-highlight");
    expect(container.querySelector(".chart-frame")).toHaveTextContent("₹2K");
    expect(screen.getByRole("table", { name: "Collections" })).toHaveTextContent("3 Oct₹2000");
  });
});

describe("FunnelBars", () => {
  it("narrows stage by stage with the conversion from the stage before", () => {
    const stages: FunnelStage[] = [
      { stage: "ENQUIRY", reached: 10, current: 2, fromPrevious: null, fromEnquiry: 100 },
      { stage: "APPLICATION", reached: 6, current: 1, fromPrevious: 60, fromEnquiry: 60 },
      { stage: "ADMITTED", reached: 3, current: 3, fromPrevious: 50, fromEnquiry: 30 },
    ];
    const rows = funnelRows(stages, t);
    expect(rows.map((r) => [r.label, r.value, r.note])).toEqual([
      ["Enquiry", 10, undefined],
      ["Application", 6, "60% of the stage before"],
      ["Admitted", 3, "50% of the stage before"],
    ]);
    const { container } = render(<FunnelBars rows={rows} caption="Funnel" />);
    const widths = [...container.querySelectorAll<HTMLElement>(".funnel-bar")].map((el) => el.style.width);
    expect(widths).toEqual(["100%", "60%", "30%"]);
    expect(screen.getByRole("figure", { name: "Funnel" })).toHaveTextContent("Application6");
  });
});
