import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import type { StudentHomework, StudentHomeworkDetail, StudentHomeworkRow } from "@/lib/types";
import { meWith, renderAs } from "@/test/render";
import { homeworkAudience } from "./homework-pages";
import {
  ChildHomeworkDetailView,
  isToDo,
  learnerGroup,
  StudentHomeworkDetailView,
  StudentHomeworkView,
} from "./learner-homework";

const { mine, myDetail, submit, childDetail } = vi.hoisted(() => ({
  mine: vi.fn(),
  myDetail: vi.fn(),
  submit: vi.fn(),
  childDetail: vi.fn(),
}));

vi.mock("@/lib/homework-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/homework-api")>();
  return { ...actual, homeworkApi: { ...actual.homeworkApi, mine, myDetail, submit, childDetail } };
});

const STUDENT = ["dashboard.view"];
const PARENT = ["dashboard.view", "child.view"];

function row(overrides: Partial<StudentHomeworkRow> = {}): StudentHomeworkRow {
  return {
    id: "hw1",
    title: "Tables 12 to 15",
    subjectId: "maths",
    subjectName: "Mathematics",
    assignedOn: "2026-10-08",
    dueOn: "2026-10-13",
    onlineSubmission: true,
    attachments: 0,
    status: "PENDING",
    late: false,
    overdue: false,
    submittedAt: null,
    grade: null,
    ...overrides,
  };
}

function homework(overrides: Partial<StudentHomeworkDetail> = {}): StudentHomeworkDetail {
  return {
    id: "hw1",
    studentId: "st1",
    studentName: "Arjun Sharma",
    title: "Tables 12 to 15",
    instructions: "Write out the tables from 12 to 15.",
    subjectId: "maths",
    subjectName: "Mathematics",
    assignedOn: "2026-10-08",
    dueOn: "2026-10-13",
    onlineSubmission: true,
    attachments: [],
    createdByName: "Ravi Kumar",
    submission: null,
    canSubmit: true,
    overdue: false,
    maxFiles: 5,
    maxFileBytes: 5 * 1024 * 1024,
    today: "2026-10-09",
    ...overrides,
  };
}

function textFile(name: string): File {
  return new File(["my answer"], name, { type: "text/plain" });
}

beforeEach(() => {
  myDetail.mockResolvedValue(homework());
  submit.mockImplementation((_id: string, input: { text: string; files: File[]; keepFileIds: string[] }) =>
    Promise.resolve(
      homework({
        submission: {
          id: "sub1",
          body: input.text,
          files: input.files.map((f, i) => ({ id: `f${i}`, name: f.name, contentType: f.type, size: f.size })),
          submittedAt: "2026-10-09T05:30:00Z",
          late: false,
          attempts: 1,
          status: "SUBMITTED",
          grade: null,
          remark: null,
          reviewedByName: null,
          reviewedAt: null,
        },
      }),
    ),
  );
});

describe("what counts as still to do", () => {
  it("keeps open online work and redo requests, and drops offline work once it is past due", () => {
    expect(isToDo(row())).toBe(true);
    expect(isToDo(row({ overdue: true }))).toBe(true);
    expect(isToDo(row({ onlineSubmission: false }))).toBe(true);
    expect(isToDo(row({ onlineSubmission: false, overdue: true }))).toBe(false);
    expect(isToDo(row({ status: "NEEDS_REDO", overdue: true }))).toBe(true);
    expect(isToDo(row({ status: "SUBMITTED" }))).toBe(false);
    expect(isToDo(row({ status: "REVIEWED" }))).toBe(false);
  });

  it("lists work to do soonest first and the rest newest first", () => {
    const items = [
      row({ id: "a", dueOn: "2026-10-15" }),
      row({ id: "b", dueOn: "2026-10-10" }),
      row({ id: "c", dueOn: "2026-10-01", status: "REVIEWED" }),
      row({ id: "d", dueOn: "2026-10-05", status: "SUBMITTED" }),
    ];
    expect(learnerGroup(items, "todo").map((r) => r.id)).toEqual(["b", "a"]);
    expect(learnerGroup(items, "done").map((r) => r.id)).toEqual(["d", "c"]);
    expect(learnerGroup(items, "all")).toHaveLength(4);
  });

  it("sends staff, students and parents to their own homework screen", () => {
    expect(homeworkAudience(meWith(["dashboard.view", "homework.manage"], ["TEACHER"]))).toBe("staff");
    expect(homeworkAudience(meWith(STUDENT, ["STUDENT"]))).toBe("student");
    expect(homeworkAudience(meWith(PARENT, ["PARENT"]))).toBe("parent");
    expect(homeworkAudience(meWith(["dashboard.view", "fees.read"], ["ACCOUNTANT"]))).toBeNull();
    expect(homeworkAudience(null)).toBeNull();
  });
});

describe("StudentHomeworkView", () => {
  it("shows what is still to do, and what was handed in on the other tab", async () => {
    const user = userEvent.setup();
    const view: StudentHomework = {
      studentId: "st1",
      studentName: "Arjun Sharma",
      sectionId: "s5a",
      sectionLabel: "Class 5 A",
      today: "2026-10-09",
      items: [
        row({ id: "hw1", title: "Tables 12 to 15" }),
        row({ id: "hw2", title: "Water cycle diagram", status: "NEEDS_REDO", dueOn: "2026-10-05", overdue: true }),
        row({ id: "hw3", title: "Parts of a computer", status: "SUBMITTED", dueOn: "2026-10-11" }),
      ],
    };
    mine.mockResolvedValue(view);
    renderAs(STUDENT, <StudentHomeworkView />, ["STUDENT"]);

    const list = await screen.findByTestId("learner-homework");
    const titles = within(list)
      .getAllByRole("link")
      .map((link) => link.querySelector("b")?.textContent);
    expect(titles).toEqual(["Water cycle diagram", "Tables 12 to 15"]);
    expect(within(list).getByRole("link", { name: /Tables 12 to 15/ })).toHaveAttribute("href", "/app/homework/hw1");

    await user.click(screen.getByRole("radio", { name: "Handed in (1)" }));
    expect(within(screen.getByTestId("learner-homework")).getByText("Parts of a computer")).toBeInTheDocument();
  });
});

describe("StudentHomeworkDetailView", () => {
  it("asks for an answer, then hands in the text and a file", async () => {
    const user = userEvent.setup();
    renderAs(STUDENT, <StudentHomeworkDetailView id="hw1" />, ["STUDENT"]);

    expect(await screen.findByRole("heading", { name: "Hand in your work" })).toBeInTheDocument();
    await user.click(screen.getByTestId("submission-send"));
    expect(screen.getByText("Write your answer or attach a file.")).toBeInTheDocument();
    expect(submit).not.toHaveBeenCalled();

    await user.type(screen.getByLabelText("Your answer"), "12 x 1 = 12");
    const file = textFile("tables.txt");
    await user.upload(document.querySelector<HTMLInputElement>('input[name="files"]')!, file);
    await user.click(screen.getByTestId("submission-send"));

    await waitFor(() => expect(submit).toHaveBeenCalledTimes(1));
    expect(submit).toHaveBeenCalledWith("hw1", { text: "12 x 1 = 12", files: [file], keepFileIds: [] });
    expect(await screen.findByText("Handed in. Your teacher will review it.")).toBeInTheDocument();
    const summary = screen.getByTestId("submission");
    expect(within(summary).getByText("12 x 1 = 12")).toBeInTheDocument();
    expect(within(summary).getByText("tables.txt")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Hand in again" })).toBeInTheDocument();
  });

  it("hands in again after a redo request, late, dropping an earlier file", async () => {
    const user = userEvent.setup();
    const earlier = homework({
      dueOn: "2026-10-05",
      overdue: true,
      submission: {
        id: "sub1",
        body: "Diagram attached.",
        files: [{ id: "old1", name: "water-cycle.txt", contentType: "text/plain", size: 45 }],
        submittedAt: "2026-10-04T13:00:00Z",
        late: false,
        attempts: 1,
        status: "NEEDS_REDO",
        grade: null,
        remark: "Please label the collection step too.",
        reviewedByName: "Farah Khan",
        reviewedAt: "2026-10-06T08:00:00Z",
      },
    });
    myDetail.mockResolvedValue(earlier);
    submit.mockResolvedValue(
      homework({
        dueOn: "2026-10-05",
        overdue: true,
        submission: { ...earlier.submission!, late: true, attempts: 2, status: "SUBMITTED", files: [] },
      }),
    );
    renderAs(STUDENT, <StudentHomeworkDetailView id="hw1" />, ["STUDENT"]);

    const review = await screen.findByTestId("submission-review");
    expect(review).toHaveTextContent("The teacher asked for it again · Farah Khan");
    expect(review).toHaveTextContent("Please label the collection step too.");
    expect(screen.getByText("The due date has passed. You can still hand it in; it will be marked late.")).toBeInTheDocument();
    const answer = screen.getByLabelText("Your answer");
    expect(answer).toHaveValue("Diagram attached.");

    const keep = screen.getByRole("checkbox", { name: "water-cycle.txt" });
    expect(keep).toBeChecked();
    await user.click(keep);
    await user.clear(answer);
    await user.click(screen.getByTestId("submission-send"));
    expect(screen.getByText("Write your answer or attach a file.")).toBeInTheDocument();

    await user.type(answer, "Diagram with the collection step labelled.");
    await user.click(screen.getByTestId("submission-send"));
    await waitFor(() =>
      expect(submit).toHaveBeenCalledWith("hw1", {
        text: "Diagram with the collection step labelled.",
        files: [],
        keepFileIds: [],
      }),
    );
    expect(await screen.findByText("Handed in late. Your teacher will review it.")).toBeInTheDocument();
  });

  it("shows the API's reason when a file is refused", async () => {
    const user = userEvent.setup();
    submit.mockRejectedValue(
      new ApiError({ status: 400, title: "Invalid request", errors: { files: "tables.txt: the file is empty." } }),
    );
    renderAs(STUDENT, <StudentHomeworkDetailView id="hw1" />, ["STUDENT"]);

    await user.upload(
      (await screen.findByTestId("submission-send")).closest("form")!.querySelector<HTMLInputElement>('input[name="files"]')!,
      textFile("tables.txt"),
    );
    await user.click(screen.getByTestId("submission-send"));

    expect(await screen.findByText("tables.txt: the file is empty.")).toBeInTheDocument();
    expect(screen.queryByTestId("submission")).not.toBeInTheDocument();
  });
});

describe("ChildHomeworkDetailView", () => {
  it("shows a parent the homework read only", async () => {
    childDetail.mockResolvedValue(homework({ canSubmit: false }));
    renderAs(PARENT, <ChildHomeworkDetailView studentId="st1" id="hw1" />, ["PARENT"]);

    expect(await screen.findByRole("heading", { name: "Tables 12 to 15" })).toBeInTheDocument();
    expect(childDetail).toHaveBeenCalledWith("st1", "hw1");
    expect(screen.getByText("Arjun Sharma · Mathematics")).toBeInTheDocument();
    expect(screen.getByTestId("parent-not-submitted")).toHaveTextContent("Not handed in yet.");
    expect(screen.queryByTestId("submission-send")).not.toBeInTheDocument();
    expect(screen.queryByRole("textbox")).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: /Back to homework/ })).toHaveAttribute("href", "/app/homework?child=st1");
  });
});
