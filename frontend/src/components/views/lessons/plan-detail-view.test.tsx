import { screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { lessonsOptions, planDetail } from "@/test/lessons-fixtures";
import { renderAs } from "@/test/render";
import { PlanDetailView } from "./plan-detail-view";

const m = vi.hoisted(() => ({
  plan: vi.fn(),
  options: vi.fn(),
  submitPlan: vi.fn(),
  approvePlan: vi.fn(),
  returnPlan: vi.fn(),
  markTaught: vi.fn(),
}));

vi.mock("@/lib/lessons-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/lessons-api")>();
  return { ...actual, lessonsApi: { ...actual.lessonsApi, ...m } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/lessons/plans/p1",
}));

const TEACHER = ["dashboard.view", "lessons.read", "lessons.manage"];
const PRINCIPAL = ["dashboard.view", "lessons.read", "lessons.approve"];

const SUBMITTED = planDetail({
  status: "SUBMITTED",
  submittedAt: "2026-10-09T10:30:00Z",
  canEdit: false,
  canSubmit: false,
  canReview: true,
});

describe("PlanDetailView", () => {
  beforeEach(() => {
    for (const mock of Object.values(m)) mock.mockReset();
    m.options.mockResolvedValue(lessonsOptions());
  });

  it("lets the teacher submit a draft for approval", async () => {
    m.plan.mockResolvedValue(planDetail({ objectives: "Compare fractions", resourceLinks: ["https://example.org/f"] }));
    m.submitPlan.mockResolvedValue(
      planDetail({ status: "SUBMITTED", submittedAt: "2026-10-09T10:30:00Z", canEdit: false, canSubmit: false }),
    );
    const user = userEvent.setup();
    renderAs(TEACHER, <PlanDetailView planId="p1" />, ["TEACHER"]);

    const detail = await screen.findByTestId("plan-detail");
    expect(within(detail).getByText("Draft")).toBeInTheDocument();
    expect(within(detail).getByText("Numbers")).toBeInTheDocument();
    expect(within(detail).getByText("Week of 12 Oct 2026")).toBeInTheDocument();
    expect(within(detail).getByRole("heading", { name: "Learning objectives" })).toBeInTheDocument();
    expect(within(detail).getByRole("link", { name: "https://example.org/f" })).toHaveAttribute("rel", "noopener noreferrer");
    expect(within(detail).queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();

    await user.click(within(detail).getByRole("button", { name: "Submit for approval" }));
    expect(m.submitPlan).toHaveBeenCalledWith("p1");
    expect(await within(detail).findByText("Waiting for approval")).toBeInTheDocument();
    expect(screen.getByText("Plan submitted for approval")).toBeInTheDocument();
    expect(within(detail).queryByRole("button", { name: "Submit for approval" })).not.toBeInTheDocument();
  });

  it("lets the principal approve a submitted plan with an optional comment", async () => {
    m.plan.mockResolvedValue(SUBMITTED);
    m.approvePlan.mockResolvedValue(
      planDetail({
        status: "APPROVED",
        reviewedAt: "2026-10-09T11:00:00Z",
        reviewedByName: "Priya Nair",
        reviewComment: "Good use of fraction strips.",
        canEdit: false,
        canSubmit: false,
      }),
    );
    const user = userEvent.setup();
    renderAs(PRINCIPAL, <PlanDetailView planId="p1" />, ["PRINCIPAL"]);
    const detail = await screen.findByTestId("plan-detail");
    expect(within(detail).getByTestId("plan-status-line")).toHaveTextContent(/^Submitted /);

    await user.click(within(detail).getByRole("button", { name: "Approve" }));
    const dialog = await screen.findByRole("dialog", { name: "Approve this plan?" });
    await user.type(within(dialog).getByLabelText("Comment (optional)"), "Good use of fraction strips.");
    await user.click(within(dialog).getByRole("button", { name: "Approve" }));

    expect(m.approvePlan).toHaveBeenCalledWith("p1", "Good use of fraction strips.");
    expect(await within(detail).findByText("Approved")).toBeInTheDocument();
    expect(within(detail).getByTestId("plan-comment")).toHaveTextContent("Good use of fraction strips.");
    expect(within(detail).getByTestId("plan-status-line")).toHaveTextContent(/^Reviewed by Priya Nair/);
  });

  it("needs a comment to return a plan", async () => {
    m.plan.mockResolvedValue(SUBMITTED);
    m.returnPlan.mockResolvedValue(
      planDetail({ status: "RETURNED", reviewComment: "Add an activity.", reviewedByName: "Priya Nair", reviewedAt: "2026-10-09T11:00:00Z" }),
    );
    const user = userEvent.setup();
    renderAs(PRINCIPAL, <PlanDetailView planId="p1" />, ["PRINCIPAL"]);
    await user.click(await screen.findByRole("button", { name: "Return with comment" }));
    const dialog = await screen.findByRole("dialog", { name: "Return this plan" });
    await user.click(within(dialog).getByRole("button", { name: "Return with comment" }));
    expect(await within(dialog).findByText("Say what the teacher should change.")).toBeInTheDocument();
    expect(m.returnPlan).not.toHaveBeenCalled();

    await user.type(within(dialog).getByLabelText("What should the teacher change?"), "Add an activity.");
    await user.click(within(dialog).getByRole("button", { name: "Return with comment" }));
    expect(m.returnPlan).toHaveBeenCalledWith("p1", "Add an activity.");
    expect(await screen.findByTestId("plan-comment")).toHaveTextContent("Add an activity.");
    expect(screen.getByTestId("plan-comment")).toHaveClass("alert-warn");
  });

  it("marks an approved plan as taught on a day that is not in the future", async () => {
    m.plan.mockResolvedValue(planDetail({ status: "APPROVED", planDate: "2026-10-12", canEdit: false, canSubmit: false, canMarkTaught: true }));
    m.markTaught.mockResolvedValue(planDetail({ status: "TAUGHT", taughtOn: "2026-10-09", canEdit: false, canSubmit: false, canMarkTaught: true }));
    const user = userEvent.setup();
    renderAs(TEACHER, <PlanDetailView planId="p1" />, ["TEACHER"]);
    await user.click(await screen.findByRole("button", { name: "Mark as taught" }));
    const dialog = await screen.findByRole("dialog", { name: "Mark as taught" });
    const day = within(dialog).getByLabelText("Day taught");
    await user.clear(day);
    await user.type(day, "2999-01-01");
    await user.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(await within(dialog).findByText("The day taught cannot be in the future.")).toBeInTheDocument();

    await user.clear(day);
    await user.type(day, "2026-10-09");
    await user.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(m.markTaught).toHaveBeenCalledWith("p1", "2026-10-09");
    expect(await screen.findByTestId("plan-status-line")).toHaveTextContent("Taught on 9 Oct 2026");
    expect(screen.getByRole("button", { name: "Not taught yet" })).toBeInTheDocument();
  });
});
