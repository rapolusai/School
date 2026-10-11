import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import { lessonsOptions, planDetail } from "@/test/lessons-fixtures";
import { renderAs } from "@/test/render";
import { planChoices } from "./lessons-shared";
import { PlanDialog, planRequest, validatePlan, type PlanValues } from "./plan-editor";

const m = vi.hoisted(() => ({ topics: vi.fn(), createPlan: vi.fn(), updatePlan: vi.fn() }));

vi.mock("@/lib/lessons-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/lessons-api")>();
  return { ...actual, lessonsApi: { ...actual.lessonsApi, ...m } };
});

const TEACHER = ["dashboard.view", "lessons.read", "lessons.manage"];
const YEAR = { startsOn: "2026-06-01", endsOn: "2027-03-31" };

function values(overrides: Partial<PlanValues> = {}): PlanValues {
  return {
    sectionId: "s5a",
    subjectId: "math",
    topicId: "t1",
    title: "",
    weekStart: "2026-10-12",
    planDate: "",
    periods: "2",
    objectives: "Compare fractions",
    teachingMethod: "",
    resources: "",
    resourceLinks: "",
    homeworkIdea: "",
    assessmentNote: "",
    ...overrides,
  };
}

describe("validatePlan", () => {
  it("accepts a plan for a syllabus topic without a title", () => {
    expect(validatePlan(values(), YEAR)).toEqual({});
  });

  it("asks for the section, subject and a title or topic", () => {
    expect(validatePlan(values({ sectionId: "", subjectId: "", topicId: "", title: "  " }), YEAR)).toEqual({
      sectionId: "lessons.v.section",
      subjectId: "lessons.v.subject",
      title: "lessons.v.titleOrTopic",
    });
    expect(validatePlan(values({ topicId: "", title: "Revision" }), YEAR)).toEqual({});
    expect(validatePlan(values({ title: "x".repeat(201) }), YEAR)).toEqual({ title: "lessons.v.titleLong" });
  });

  it("keeps the week within the academic year and the day within the week", () => {
    expect(validatePlan(values({ weekStart: "" }), YEAR)).toEqual({ weekStart: "lessons.v.date" });
    expect(validatePlan(values({ weekStart: "2026-05-25" }), YEAR)).toEqual({ weekStart: "lessons.v.weekInYear" });
    expect(validatePlan(values({ weekStart: "2027-04-05" }), YEAR)).toEqual({ weekStart: "lessons.v.weekInYear" });
    // A week that starts before the year but runs into it can be planned (a year starting on a Wednesday).
    expect(validatePlan(values({ weekStart: "2026-03-30" }), { startsOn: "2026-04-01", endsOn: "2027-03-31" })).toEqual({});
    expect(validatePlan(values({ weekStart: "2027-03-29" }), YEAR)).toEqual({});
    // Any day of the week names the week; the day must fall Monday to Sunday of it.
    expect(validatePlan(values({ weekStart: "2026-10-14", planDate: "2026-10-18" }), YEAR)).toEqual({});
    expect(validatePlan(values({ planDate: "2026-10-19" }), YEAR)).toEqual({ planDate: "lessons.v.planDate" });
    expect(validatePlan(values({ planDate: "2026-10-11" }), YEAR)).toEqual({ planDate: "lessons.v.planDate" });
  });

  it("limits the periods, the text and the links", () => {
    expect(validatePlan(values({ periods: "0" }), YEAR)).toEqual({ periods: "lessons.v.periods" });
    expect(validatePlan(values({ periods: "21" }), YEAR)).toEqual({ periods: "lessons.v.periods" });
    expect(validatePlan(values({ periods: "1.5" }), YEAR)).toEqual({ periods: "lessons.v.periods" });
    expect(validatePlan(values({ objectives: "x".repeat(2001), homeworkIdea: "y".repeat(1001) }), YEAR)).toEqual({
      objectives: "lessons.v.long2000",
      homeworkIdea: "lessons.v.long1000",
    });
    expect(validatePlan(values({ resourceLinks: "https://example.org/a\n\n http://example.org/b " }), YEAR)).toEqual({});
    expect(validatePlan(values({ resourceLinks: "https://example.org/a\nexample.org" }), YEAR)).toEqual({
      resourceLinks: "lessons.v.links",
    });
  });
});

describe("planRequest", () => {
  it("trims the text, drops blank links and sends nulls for empty fields", () => {
    expect(
      planRequest(
        values({ title: "  ", objectives: "  Compare fractions ", resourceLinks: " https://a.org \n\nhttps://b.org" }),
        "y1",
        3,
      ),
    ).toEqual({
      academicYearId: "y1",
      sectionId: "s5a",
      subjectId: "math",
      topicId: "t1",
      title: null,
      weekStart: "2026-10-12",
      planDate: null,
      periods: 2,
      objectives: "Compare fractions",
      teachingMethod: null,
      resources: null,
      resourceLinks: "https://a.org\nhttps://b.org",
      homeworkIdea: null,
      assessmentNote: null,
      version: 3,
    });
    expect(planRequest(values({ topicId: "", title: "Revision" }), null)).toMatchObject({
      academicYearId: null,
      topicId: null,
      title: "Revision",
      version: null,
    });
  });
});

describe("planChoices", () => {
  it("offers a teacher only the sections and subjects they teach, and leadership every one", () => {
    expect(planChoices(lessonsOptions())).toEqual([
      { sectionId: "s5a", label: "Class 5 A", classId: "c5", subjects: [{ id: "math", name: "Mathematics" }] },
    ]);
    expect(planChoices(lessonsOptions({ wholeSchool: true, teaching: [] })).map((c) => c.sectionId)).toEqual(["s5a", "s5b"]);
    expect(planChoices(lessonsOptions({ teaching: [] }))).toEqual([]);
  });
});

describe("PlanDialog", () => {
  beforeEach(() => {
    for (const mock of Object.values(m)) mock.mockReset();
    m.topics.mockResolvedValue([
      { id: "t1", title: "Fractions", unitTitle: "Numbers", targetMonth: "2026-10" },
      { id: "t2", title: "Decimals", unitTitle: "Numbers", targetMonth: "2026-11" },
    ]);
  });

  it("writes a new plan for a syllabus topic and saves it as a draft", async () => {
    const saved = planDetail();
    m.createPlan.mockResolvedValue(saved);
    const onSaved = vi.fn();
    const user = userEvent.setup();
    renderAs(
      TEACHER,
      <PlanDialog open plan={null} options={lessonsOptions()} week="2026-10-12" onClose={vi.fn()} onSaved={onSaved} />,
      ["TEACHER"],
    );
    const editor = screen.getByTestId("plan-editor");
    expect(editor).toHaveAttribute("method", "post");

    // Only the section the teacher teaches is offered; choosing it picks its one subject and loads the topics.
    const section = within(editor).getByLabelText("Section");
    expect(within(section).getAllByRole("option").map((o) => o.textContent)).toEqual(["Choose…", "Class 5 A"]);
    await user.selectOptions(section, "s5a");
    expect(within(editor).getByLabelText("Subject")).toHaveValue("math");
    await waitFor(() => expect(m.topics).toHaveBeenCalledWith("c5", "math", "y1"));
    const topic = within(editor).getByLabelText("Topic");
    await within(topic).findByRole("option", { name: /Numbers · Fractions/ });
    await user.selectOptions(topic, "t1");
    expect(within(editor).getByLabelText("Title")).toHaveAttribute("placeholder", "The topic's title");

    await user.clear(within(editor).getByLabelText("Periods"));
    await user.type(within(editor).getByLabelText("Periods"), "2");
    await user.type(within(editor).getByLabelText("Learning objectives"), "Compare fractions");
    await user.click(within(editor).getByRole("button", { name: "Add method, resources, links, homework and assessment" }));
    await user.type(within(editor).getByLabelText("Web links"), "https://example.org/fractions");
    await user.click(within(editor).getByRole("button", { name: "Save draft" }));

    await waitFor(() => expect(onSaved).toHaveBeenCalledWith(saved));
    expect(m.createPlan).toHaveBeenCalledWith(
      expect.objectContaining({
        academicYearId: "y1",
        sectionId: "s5a",
        subjectId: "math",
        topicId: "t1",
        title: null,
        weekStart: "2026-10-12",
        periods: 2,
        objectives: "Compare fractions",
        resourceLinks: "https://example.org/fractions",
        version: null,
      }),
    );
  });

  it("shows the problems instead of saving", async () => {
    const user = userEvent.setup();
    renderAs(
      TEACHER,
      <PlanDialog open plan={null} options={lessonsOptions()} week="2026-10-12" onClose={vi.fn()} onSaved={vi.fn()} />,
      ["TEACHER"],
    );
    await user.click(screen.getByRole("button", { name: "Save draft" }));
    expect(await screen.findByText("Choose the section.")).toBeInTheDocument();
    expect(screen.getByText("Choose the subject.")).toBeInTheDocument();
    expect(screen.getByText("Give the plan a title or choose a topic.")).toBeInTheDocument();
    expect(screen.getByLabelText("Section")).toHaveAttribute("aria-invalid", "true");
    expect(m.createPlan).not.toHaveBeenCalled();
  });

  it("changes a returned plan with its version and shows the API's field problems", async () => {
    const plan = planDetail({ status: "RETURNED", version: 4, title: "Fractions", resourceLinks: ["https://a.org"] });
    m.updatePlan.mockRejectedValueOnce(
      new ApiError({ status: 400, title: "Bad Request", errors: { periods: "Give from 1 to 20 periods." } }),
    );
    const onSaved = vi.fn();
    const user = userEvent.setup();
    renderAs(
      TEACHER,
      <PlanDialog open plan={plan} options={lessonsOptions()} week="2026-10-12" onClose={vi.fn()} onSaved={onSaved} />,
      ["TEACHER"],
    );
    expect(screen.getByRole("dialog", { name: "Change plan" })).toBeInTheDocument();
    // The title equals the topic's, so it is left to the topic; the extra fields are open for an existing plan.
    expect(screen.getByLabelText("Title")).toHaveValue("");
    expect(screen.getByLabelText("Web links")).toHaveValue("https://a.org");
    await user.click(screen.getByRole("button", { name: "Save draft" }));

    expect(await screen.findByText("Give from 1 to 20 periods.")).toBeInTheDocument();
    expect(m.updatePlan).toHaveBeenCalledWith("p1", expect.objectContaining({ version: 4, topicId: "t1", periods: 2 }));
    expect(onSaved).not.toHaveBeenCalled();
  });

  it("explains when the teacher teaches no section yet", () => {
    renderAs(
      TEACHER,
      <PlanDialog
        open
        plan={null}
        options={lessonsOptions({ teaching: [] })}
        week="2026-10-12"
        onClose={vi.fn()}
        onSaved={vi.fn()}
      />,
      ["TEACHER"],
    );
    expect(screen.getByText(/You do not teach any section/)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save draft" })).toBeDisabled();
  });
});
