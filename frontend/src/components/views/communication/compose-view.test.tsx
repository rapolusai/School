import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { circular, estimate, PRINCIPAL_OPTIONS, TEACHER_OPTIONS } from "@/test/communication-fixtures";
import { renderAs } from "@/test/render";
import { audienceProblem, formatPaise, indiaLocalToInstant, instantToIndiaLocal } from "./communication-labels";
import { ComposeView, initialValues, toCircularRequest, validateCircular } from "./compose-view";

const { audienceOptions, estimateApi, create, update, submit, get, push } = vi.hoisted(() => ({
  audienceOptions: vi.fn(),
  estimateApi: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  submit: vi.fn(),
  get: vi.fn(),
  push: vi.fn(),
}));

vi.mock("@/lib/communication-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/communication-api")>();
  return {
    ...actual,
    noticesApi: { ...actual.noticesApi, audienceOptions, estimate: estimateApi, create, update, submit, get },
  };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push }),
  usePathname: () => "/app/notices/new",
}));

const TEACHER = ["dashboard.view", "notices.send", "notices.read"];
const PRINCIPAL = ["dashboard.view", "notices.send", "notices.read", "notices.approve", "calendar.manage"];

beforeEach(() => {
  estimateApi.mockResolvedValue(estimate());
  create.mockResolvedValue(circular({ actions: { ...circular().actions, edit: true, submit: true } }));
  submit.mockResolvedValue(circular({ status: "PENDING_APPROVAL", submittedAt: "2026-10-09T04:05:00Z" }));
});

describe("ComposeView", () => {
  it("lets a class teacher write to the parents of their own section and submit it for approval", async () => {
    audienceOptions.mockResolvedValue(TEACHER_OPTIONS);
    const user = userEvent.setup();
    renderAs(TEACHER, <ComposeView />, ["TEACHER"]);

    const picker = await screen.findByTestId("audience-picker");
    expect(within(picker).getByRole("checkbox", { name: "Class 5 A" })).toBeChecked();
    expect(within(picker).getByRole("checkbox", { name: "Parents" })).toBeChecked();
    expect(within(picker).queryByRole("checkbox", { name: /Whole school/ })).not.toBeInTheDocument();
    expect(screen.getByText(/checked by the principal or school admin/)).toBeInTheDocument();

    // The estimate follows the audience.
    expect(await screen.findByTestId("estimate-people")).toHaveTextContent("2 parents, 0 students and 0 staff");
    expect(estimateApi).toHaveBeenLastCalledWith(
      expect.objectContaining({ audience: { wholeSchool: false, classIds: [], sectionIds: ["s5a"], roles: ["PARENT"] } }),
    );

    await user.type(screen.getByLabelText("Title"), "Class 5 A project day");
    await user.type(screen.getByLabelText("Message"), "Dear parents,{enter}The project is due on Friday.");
    await user.click(screen.getByRole("button", { name: "Submit for approval" }));
    const dialog = await screen.findByRole("dialog", { name: "Submit for approval?" });
    await user.click(within(dialog).getByRole("button", { name: "Submit for approval" }));

    await waitFor(() => expect(push).toHaveBeenCalledWith("/app/notices/n1"));
    expect(create).toHaveBeenCalledWith({
      title: "Class 5 A project day",
      body: "Dear parents,\nThe project is due on Friday.",
      category: "GENERAL",
      audience: { wholeSchool: false, classIds: [], sectionIds: ["s5a"], roles: ["PARENT"] },
      channels: [],
      scheduledAt: null,
    });
    expect(submit).toHaveBeenCalledWith("n1");
    expect(await screen.findByText("Sent for approval.")).toBeInTheDocument();
  });

  it("checks the form the way the API does before saving", async () => {
    audienceOptions.mockResolvedValue(TEACHER_OPTIONS);
    const user = userEvent.setup();
    renderAs(TEACHER, <ComposeView />, ["TEACHER"]);
    const picker = await screen.findByTestId("audience-picker");

    await user.click(within(picker).getByRole("checkbox", { name: "Parents" }));
    await user.click(screen.getByRole("button", { name: "Save draft" }));
    expect(await screen.findAllByText("Fill in this field.")).toHaveLength(2);
    expect(
      within(picker).getByText("Choose parents, students or both for the chosen classes and sections."),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("Title")).toHaveFocus();
    expect(create).not.toHaveBeenCalled();
  });

  it("estimates a whole-school SMS and refuses a schedule in the past", async () => {
    audienceOptions.mockResolvedValue(PRINCIPAL_OPTIONS);
    estimateApi.mockResolvedValue(
      estimate({
        staff: 12,
        parents: 120,
        students: 30,
        inApp: 90,
        phones: 118,
        parentsWithoutPhone: 2,
        channels: [{ channel: "SMS", messages: 118, units: 236, costPaise: 4720 }],
        costPaise: 4720,
        smsParts: 2,
      }),
    );
    const user = userEvent.setup();
    renderAs(PRINCIPAL, <ComposeView />, ["PRINCIPAL"]);
    const picker = await screen.findByTestId("audience-picker");

    // Staff roles and every class are offered to a principal.
    expect(within(picker).getByRole("checkbox", { name: "Teacher" })).toBeInTheDocument();
    expect(within(picker).getByRole("checkbox", { name: "Class 5 B" })).toBeInTheDocument();
    await user.click(within(picker).getByRole("checkbox", { name: /Whole school/ }));
    await user.click(screen.getByRole("checkbox", { name: "SMS" }));
    await waitFor(() =>
      expect(estimateApi).toHaveBeenLastCalledWith(
        expect.objectContaining({
          audience: { wholeSchool: true, classIds: [], sectionIds: [], roles: [] },
          channels: ["SMS"],
        }),
      ),
    );
    expect(await screen.findByTestId("estimate-total")).toHaveTextContent("₹47.20");
    expect(screen.getByText("2 parents have no mobile number on file.")).toBeInTheDocument();
    expect(screen.getByText("2 parts each")).toBeInTheDocument();

    await user.type(screen.getByLabelText("Title"), "Sports day");
    await user.type(screen.getByLabelText("Message"), "Sports day is on Saturday.");
    await user.click(screen.getByRole("radio", { name: "Schedule" }));
    fireEvent.change(screen.getByLabelText("Send on"), { target: { value: "2020-01-01T09:00" } });
    await user.click(screen.getByRole("button", { name: "Schedule" }));
    expect(await screen.findByText("Pick a time in the future, or send it now.")).toBeInTheDocument();
    expect(create).not.toHaveBeenCalled();
  });

  it("does not open a circular that is no longer a draft for editing", async () => {
    audienceOptions.mockResolvedValue(PRINCIPAL_OPTIONS);
    get.mockResolvedValue(circular({ status: "SENT" }));
    renderAs(PRINCIPAL, <ComposeView id="n1" />, ["PRINCIPAL"]);
    expect(await screen.findByTestId("not-editable")).toHaveTextContent("Only drafts can be changed.");
    expect(screen.queryByLabelText("Title")).not.toBeInTheDocument();
  });
});

describe("compose rules", () => {
  const now = new Date("2026-10-09T06:00:00Z");

  it("mirrors the API's audience rules", () => {
    const base = { wholeSchool: false, classIds: [], sectionIds: [], roles: [] as string[] };
    expect(audienceProblem(base, PRINCIPAL_OPTIONS)).toBe("notices.v.audience");
    expect(audienceProblem({ ...base, roles: ["PARENT"] }, PRINCIPAL_OPTIONS)).toBeNull();
    expect(audienceProblem({ ...base, classIds: ["c5"], roles: ["TEACHER"] }, PRINCIPAL_OPTIONS)).toBe(
      "notices.v.families",
    );
    expect(audienceProblem({ ...base, wholeSchool: true }, TEACHER_OPTIONS)).toBe("notices.v.teacherLimit");
    expect(audienceProblem({ ...base, roles: ["PARENT"] }, TEACHER_OPTIONS)).toBe("notices.v.teacherLimit");
    expect(audienceProblem({ ...base, sectionIds: ["s5a"], roles: ["PARENT", "STUDENT"] }, TEACHER_OPTIONS)).toBeNull();
    expect(audienceProblem({ ...base, sectionIds: ["s6a"], roles: ["PARENT"] }, TEACHER_OPTIONS)).toBe(
      "notices.v.teacherLimit",
    );
  });

  it("validates title, body and schedule, and sends India time as an instant", () => {
    const values = { ...initialValues(PRINCIPAL_OPTIONS), title: "Sports day", body: "On Saturday." };
    expect(validateCircular(values, PRINCIPAL_OPTIONS, now)).toEqual({});
    expect(validateCircular({ ...values, title: " ", body: "" }, PRINCIPAL_OPTIONS, now)).toEqual({
      title: "validation.required",
      body: "validation.required",
    });
    expect(validateCircular({ ...values, title: "x".repeat(201) }, PRINCIPAL_OPTIONS, now).title).toBe(
      "validation.tooLong",
    );
    const later = { ...values, when: "later" as const };
    expect(validateCircular({ ...later, scheduledLocal: "" }, PRINCIPAL_OPTIONS, now).scheduledAt).toBe(
      "notices.v.schedule",
    );
    expect(validateCircular({ ...later, scheduledLocal: "2026-10-09T11:00" }, PRINCIPAL_OPTIONS, now).scheduledAt).toBe(
      "notices.v.schedulePast",
    );
    expect(validateCircular({ ...later, scheduledLocal: "2028-01-01T09:00" }, PRINCIPAL_OPTIONS, now).scheduledAt).toBe(
      "notices.v.scheduleFar",
    );
    const request = toCircularRequest({ ...later, scheduledLocal: "2026-10-12T09:30" });
    expect(request.scheduledAt).toBe("2026-10-12T04:00:00.000Z");
    expect(
      toCircularRequest({ ...values, audience: { wholeSchool: true, classIds: ["c5"], sectionIds: [], roles: ["PARENT"] } })
        .audience,
    ).toEqual({ wholeSchool: true, classIds: [], sectionIds: [], roles: [] });
  });

  it("converts between India time and instants, and formats paise", () => {
    expect(indiaLocalToInstant("2026-10-12T09:30")).toBe("2026-10-12T04:00:00.000Z");
    expect(indiaLocalToInstant("2026-02-30T09:30")).toBeNull();
    expect(indiaLocalToInstant("tomorrow")).toBeNull();
    expect(instantToIndiaLocal("2026-10-12T04:00:00Z")).toBe("2026-10-12T09:30");
    expect(instantToIndiaLocal(null)).toBe("");
    expect(formatPaise(80)).toBe("₹0.80");
    expect(formatPaise(125000)).toBe("₹1,250.00");
  });

  it("starts an edit from the saved draft", () => {
    const values = initialValues(
      PRINCIPAL_OPTIONS,
      circular({ scheduledAt: "2026-10-12T04:00:00Z", channels: ["SMS"] }),
    );
    expect(values).toMatchObject({
      title: "Class 5 A project day",
      audience: { wholeSchool: false, classIds: [], sectionIds: ["s5a"], roles: ["PARENT"] },
      channels: ["SMS"],
      when: "later",
      scheduledLocal: "2026-10-12T09:30",
    });
  });
});
