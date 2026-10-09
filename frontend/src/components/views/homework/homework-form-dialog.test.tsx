import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import { fileProblem, formatBytes, MAX_FILE_BYTES } from "@/lib/files-api";
import type { HomeworkDetail, HomeworkOptions, HomeworkRequest } from "@/lib/types";
import { renderAs } from "@/test/render";
import { HomeworkFormDialog, subjectsFor, validateHomework, type HomeworkValues } from "./homework-form-dialog";

const { create, addAttachment } = vi.hoisted(() => ({ create: vi.fn(), addAttachment: vi.fn() }));

vi.mock("@/lib/homework-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/homework-api")>();
  return { ...actual, homeworkApi: { ...actual.homeworkApi, create, addAttachment } };
});

const TEACHER = ["dashboard.view", "timetable.read", "homework.manage"];

const OPTIONS: HomeworkOptions = {
  academicYear: { id: "y1", name: "2026-27", startsOn: "2026-06-01", endsOn: "2027-03-31" },
  today: "2026-10-09",
  maxAttachments: 5,
  maxFileBytes: MAX_FILE_BYTES,
  sections: [
    {
      id: "s5a",
      label: "Class 5 A",
      classId: "c5",
      subjects: [
        { id: "maths", name: "Mathematics" },
        { id: "english", name: "English" },
      ],
    },
    { id: "s5b", label: "Class 5 B", classId: "c5", subjects: [{ id: "maths", name: "Mathematics" }] },
  ],
};

function detail(body: HomeworkRequest): HomeworkDetail {
  return {
    id: "hw1",
    title: body.title,
    instructions: body.instructions ?? null,
    subjectId: body.subjectId,
    subjectName: "Mathematics",
    sections: body.sectionIds.map((id) => ({ id, label: id === "s5a" ? "Class 5 A" : "Class 5 B" })),
    assignedOn: body.assignedOn ?? "2026-10-09",
    dueOn: body.dueOn,
    onlineSubmission: body.onlineSubmission,
    attachments: [],
    createdByName: "Ravi Kumar",
    createdAt: "2026-10-09T04:00:00Z",
    updatedAt: "2026-10-09T04:00:00Z",
    counts: { students: 30, submitted: 0, late: 0, missing: 30, reviewed: 0, needsRedo: 0, waiting: 0 },
    canEdit: true,
    canDelete: true,
    today: "2026-10-09",
  };
}

function pdf(name: string, size?: number): File {
  const file = new File(["%PDF-1.4 test"], name, { type: "application/pdf" });
  if (size !== undefined) Object.defineProperty(file, "size", { value: size });
  return file;
}

function values(overrides: Partial<HomeworkValues> = {}): HomeworkValues {
  return {
    sectionIds: ["s5a"],
    subjectId: "maths",
    title: "Tables 12 to 15",
    instructions: "",
    assignedOn: "2026-10-09",
    dueOn: "2026-10-12",
    onlineSubmission: true,
    ...overrides,
  };
}

beforeEach(() => {
  create.mockImplementation((body: HomeworkRequest) => Promise.resolve(detail(body)));
  addAttachment.mockImplementation((_id: string, file: File) =>
    Promise.resolve({ id: `f-${file.name}`, name: file.name, contentType: file.type, size: file.size }),
  );
});

describe("homework form rules", () => {
  it("offers only the subjects common to every chosen section", () => {
    expect(subjectsFor(OPTIONS, ["s5a", "s5b"]).map((s) => s.id)).toEqual(["maths"]);
    expect(subjectsFor(OPTIONS, []).map((s) => s.name)).toEqual(["English", "Mathematics"]);
  });

  it("checks sections, subject, title and dates as the API does", () => {
    expect(validateHomework(values(), OPTIONS, false)).toEqual({});
    expect(validateHomework(values({ sectionIds: [], subjectId: "", title: " " }), OPTIONS, false)).toEqual({
      sectionIds: "homework.v.sections",
      subjectId: "validation.choose",
      title: "validation.required",
    });
    expect(validateHomework(values({ sectionIds: ["s5b"], subjectId: "english" }), OPTIONS, false).subjectId).toBe(
      "homework.v.subject",
    );
    expect(validateHomework(values({ dueOn: "2026-10-08" }), OPTIONS, false).dueOn).toBe("homework.v.duePast");
    expect(validateHomework(values({ dueOn: "2027-04-01" }), OPTIONS, false).dueOn).toBe("homework.v.dueAfterYear");
    expect(validateHomework(values({ assignedOn: "2026-10-10", dueOn: "2026-10-12" }), OPTIONS, false).assignedOn).toBe(
      "homework.v.assignedFuture",
    );
    // The set date cannot change once the homework exists.
    expect(validateHomework(values({ assignedOn: "2026-10-10" }), OPTIONS, true)).toEqual({});
  });

  it("judges files as the API will: type, size and empty", () => {
    expect(fileProblem({ name: "Notes.PDF", size: 10 })).toBeNull();
    expect(fileProblem({ name: "setup.exe", size: 10 })).toBe("type");
    expect(fileProblem({ name: "noextension", size: 10 })).toBe("type");
    expect(fileProblem({ name: "big.png", size: MAX_FILE_BYTES + 1 })).toBe("size");
    expect(fileProblem({ name: "blank.txt", size: 0 })).toBe("empty");
    expect(formatBytes(758)).toBe("758 B");
    expect(formatBytes(1536)).toBe("1.5 KB");
    expect(formatBytes(MAX_FILE_BYTES)).toBe("5 MB");
  });
});

describe("HomeworkFormDialog", () => {
  it("refuses unsuitable files before anything is uploaded", async () => {
    const user = userEvent.setup({ applyAccept: false });
    renderAs(TEACHER, <HomeworkFormDialog open options={{ ...OPTIONS, maxAttachments: 2 }} onClose={vi.fn()} onSaved={vi.fn()} />, [
      "TEACHER",
    ]);
    const dialog = screen.getByRole("dialog", { name: "Set homework" });
    const input = dialog.querySelector<HTMLInputElement>('input[name="attachments"]');
    expect(input).not.toBeNull();

    await user.upload(input!, [
      new File(["MZ"], "setup.exe", { type: "application/octet-stream" }),
      pdf("huge.pdf", MAX_FILE_BYTES + 1),
      pdf("blank.pdf", 0),
      pdf("sheet.pdf"),
      pdf("notes.pdf"),
      pdf("extra.pdf"),
    ]);

    const refused = within(dialog).getByTestId("file-refused");
    expect(refused).toHaveTextContent("setup.exe: this kind of file is not accepted.");
    expect(refused).toHaveTextContent("huge.pdf: the file is larger than 5 MB.");
    expect(refused).toHaveTextContent("blank.pdf: the file is empty.");
    expect(refused).toHaveTextContent("extra.pdf: at most 2 more files can be added.");
    expect(within(dialog).getByRole("button", { name: "Remove sheet.pdf" })).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Remove notes.pdf" })).toBeInTheDocument();
    expect(within(dialog).queryByRole("button", { name: "Remove setup.exe" })).not.toBeInTheDocument();
    expect(input).toBeDisabled();
  });

  it("shows what is missing and sends nothing", async () => {
    const user = userEvent.setup();
    renderAs(TEACHER, <HomeworkFormDialog open options={OPTIONS} onClose={vi.fn()} onSaved={vi.fn()} />, ["TEACHER"]);
    const dialog = screen.getByRole("dialog", { name: "Set homework" });

    await user.click(within(dialog).getByTestId("homework-save"));

    expect(within(dialog).getByText("Choose at least one section.")).toBeInTheDocument();
    expect(within(dialog).getByText("Fill in this field.")).toBeInTheDocument();
    expect(within(dialog).getByText("Enter a valid date.")).toBeInTheDocument();
    expect(create).not.toHaveBeenCalled();
  });

  it("sets homework for a section and then uploads its file", async () => {
    const user = userEvent.setup();
    const onSaved = vi.fn();
    renderAs(TEACHER, <HomeworkFormDialog open options={OPTIONS} onClose={vi.fn()} onSaved={onSaved} />, ["TEACHER"]);
    const dialog = screen.getByRole("dialog", { name: "Set homework" });

    await user.click(within(dialog).getByRole("checkbox", { name: "Class 5 A" }));
    await user.selectOptions(within(dialog).getByLabelText("Subject"), "Mathematics");
    await user.type(within(dialog).getByLabelText(/Title/), "Tables 12 to 15");
    await user.type(within(dialog).getByLabelText("Instructions"), "Write them out twice.");
    fireEvent.change(within(dialog).getByLabelText(/Due on/), { target: { value: "2026-10-12" } });
    const sheet = pdf("tables.pdf");
    await user.upload(dialog.querySelector<HTMLInputElement>('input[name="attachments"]')!, sheet);
    await user.click(within(dialog).getByTestId("homework-save"));

    await waitFor(() => expect(onSaved).toHaveBeenCalled());
    expect(create).toHaveBeenCalledWith({
      sectionIds: ["s5a"],
      subjectId: "maths",
      title: "Tables 12 to 15",
      instructions: "Write them out twice.",
      assignedOn: "2026-10-09",
      dueOn: "2026-10-12",
      onlineSubmission: true,
    });
    expect(addAttachment).toHaveBeenCalledWith("hw1", sheet);
    expect(onSaved.mock.calls[0][0].attachments).toEqual([
      { id: "f-tables.pdf", name: "tables.pdf", contentType: "application/pdf", size: sheet.size },
    ]);
  });

  it("keeps the dialog open on the saved homework when the API refuses a file", async () => {
    const user = userEvent.setup();
    addAttachment.mockRejectedValue(
      new ApiError({
        status: 400,
        title: "Invalid file",
        errors: { file: "The file's contents do not match its type." },
      }),
    );
    const onSaved = vi.fn();
    renderAs(
      TEACHER,
      <HomeworkFormDialog open options={{ ...OPTIONS, sections: [OPTIONS.sections[1]] }} onClose={vi.fn()} onSaved={onSaved} />,
      ["TEACHER"],
    );
    const dialog = screen.getByRole("dialog", { name: "Set homework" });

    // With one section and one subject, both are chosen already.
    expect(within(dialog).getByRole("checkbox", { name: "Class 5 B" })).toBeChecked();
    expect(within(dialog).getByLabelText("Subject")).toHaveValue("maths");
    await user.type(within(dialog).getByLabelText(/Title/), "Tables");
    fireEvent.change(within(dialog).getByLabelText(/Due on/), { target: { value: "2026-10-12" } });
    await user.upload(dialog.querySelector<HTMLInputElement>('input[name="attachments"]')!, pdf("fake.pdf"));
    await user.click(within(dialog).getByTestId("homework-save"));

    expect(
      await within(dialog).findByText(
        "The homework is saved, but fake.pdf could not be uploaded: The file's contents do not match its type.",
      ),
    ).toBeInTheDocument();
    expect(create).toHaveBeenCalledTimes(1);
    expect(onSaved).not.toHaveBeenCalled();
    expect(screen.getByRole("dialog", { name: "Change homework" })).toBeInTheDocument();
    expect(within(dialog).getByTestId("homework-save")).toHaveTextContent("Save");
  });
});
