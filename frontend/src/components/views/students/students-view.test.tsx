import { fireEvent, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import type { AcademicYear, ClassView, StudentDetail, StudentPage, StudentRow } from "@/lib/types";
import { ADMIN_PERMISSIONS, renderAs, TEACHER_PERMISSIONS } from "@/test/render";
import { nextYearOf, validatePromotion } from "./promote-dialog";
import { StudentsView, toStudentQuery } from "./students-view";

const { listStudents, listYears, listClasses, createStudent, promoteStudents } = vi.hoisted(() => ({
  listStudents: vi.fn(),
  listYears: vi.fn(),
  listClasses: vi.fn(),
  createStudent: vi.fn(),
  promoteStudents: vi.fn(),
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, listStudents, listYears, listClasses, createStudent, promoteStudents } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/students",
}));

const YEAR: AcademicYear = { id: "y1", name: "2026-27", startsOn: "2026-06-01", endsOn: "2027-03-31", current: true };

const CLASSES: ClassView[] = [
  {
    id: "c5",
    name: "Class 5",
    displayOrder: 5,
    subjects: [],
    sections: [
      {
        id: "s5a",
        classId: "c5",
        className: "Class 5",
        name: "A",
        capacity: 40,
        classTeacher: { id: "t1", name: "Ravi Kumar" },
        studentCount: 2,
      },
    ],
  },
];

function row(overrides: Partial<StudentRow>): StudentRow {
  return {
    id: "st1",
    admissionNo: "AKS/2026/001",
    firstName: "Arjun",
    lastName: "Sharma",
    fullName: "Arjun Sharma",
    gender: "MALE",
    dateOfBirth: "2016-04-12",
    status: "ACTIVE",
    classId: "c5",
    className: "Class 5",
    sectionId: "s5a",
    sectionName: "A",
    rollNo: 1,
    guardianName: "Anitha Sharma",
    guardianPhone: "9876500001",
    ...overrides,
  };
}

const PAGE: StudentPage = {
  items: [
    row({}),
    row({
      id: "st2",
      admissionNo: "AKS/2026/002",
      firstName: "Kavya",
      lastName: "Reddy",
      fullName: "Kavya Reddy",
      gender: "FEMALE",
      rollNo: 2,
      status: "TRANSFERRED",
      guardianName: null,
      guardianPhone: null,
    }),
  ],
  page: 0,
  size: 25,
  total: 2,
  academicYearId: "y1",
};

beforeEach(() => {
  listStudents.mockResolvedValue(PAGE);
  listYears.mockResolvedValue([YEAR]);
  listClasses.mockResolvedValue(CLASSES);
  createStudent.mockReset();
  promoteStudents.mockReset();
});

describe("toStudentQuery", () => {
  it("leaves out empty filters and trims the search", () => {
    expect(toStudentQuery({ yearId: "", classId: "c5", sectionId: "", status: "" }, "  aru ", 2)).toEqual({
      yearId: undefined,
      classId: "c5",
      sectionId: undefined,
      status: undefined,
      q: "aru",
      page: 2,
      size: 25,
    });
  });
});

describe("StudentsView", () => {
  it("lists students as a table and as phone cards", async () => {
    renderAs(ADMIN_PERMISSIONS, <StudentsView />);
    const table = await screen.findByTestId("students-table");
    expect(within(table).getByText("Arjun Sharma")).toBeInTheDocument();
    expect(within(table).getByText("AKS/2026/001")).toBeInTheDocument();
    expect(within(table).getAllByText("Class 5 A")).toHaveLength(2);
    expect(within(table).getByText("98765 00001")).toBeInTheDocument();
    expect(within(table).getByText("Transferred")).toBeInTheDocument();
    expect(within(table).getByRole("link", { name: /Arjun Sharma/ })).toHaveAttribute("href", "/app/students/st1");

    const cards = screen.getByTestId("students-cards");
    expect(within(cards).getAllByRole("link")).toHaveLength(2);
    expect(within(cards).getByText("Class 5 A · Roll 1")).toBeInTheDocument();
    expect(screen.getByText("Showing 1–2 of 2")).toBeInTheDocument();
    expect(screen.getByText("2 students · 2026-27")).toBeInTheDocument();
  });

  it("asks for setup when the school has no current year", async () => {
    listStudents.mockResolvedValue({ items: [], page: 0, size: 25, total: 0, academicYearId: null });
    listYears.mockResolvedValue([]);
    renderAs(ADMIN_PERMISSIONS, <StudentsView />);
    const empty = await screen.findByTestId("students-no-year");
    expect(within(empty).getByText(/Set up the current academic year/)).toBeInTheDocument();
    expect(within(empty).getByRole("link", { name: "Open School setup" })).toHaveAttribute("href", "/app/setup");
  });

  it("is read-only for a teacher", async () => {
    renderAs(TEACHER_PERMISSIONS, <StudentsView />, ["TEACHER"]);
    await screen.findByTestId("students-table");
    expect(screen.queryByRole("button", { name: "Add student" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Promote" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Import" })).not.toBeInTheDocument();
  });

  it("sends the class filter and the search to the API", async () => {
    const user = userEvent.setup();
    renderAs(ADMIN_PERMISSIONS, <StudentsView />);
    await screen.findByTestId("students-table");
    expect(listStudents).toHaveBeenLastCalledWith({ page: 0, size: 25 });

    await user.selectOptions(screen.getByRole("combobox", { name: "Class" }), "c5");
    await waitFor(() => expect(listStudents).toHaveBeenLastCalledWith(expect.objectContaining({ classId: "c5" })));

    await user.type(screen.getByRole("searchbox"), "kav");
    await waitFor(() =>
      expect(listStudents).toHaveBeenLastCalledWith(expect.objectContaining({ classId: "c5", q: "kav", page: 0 })),
    );
  });

  it("explains an empty search result", async () => {
    const user = userEvent.setup();
    renderAs(ADMIN_PERMISSIONS, <StudentsView />);
    await screen.findByTestId("students-table");
    listStudents.mockResolvedValue({ ...PAGE, items: [], total: 0 });
    await user.type(screen.getByRole("searchbox"), "zzz");
    expect(await screen.findByText("No students match these filters.")).toBeInTheDocument();
  });
});

describe("Admitting a student", () => {
  async function openDialog() {
    const user = userEvent.setup();
    renderAs(ADMIN_PERMISSIONS, <StudentsView />);
    await screen.findByTestId("students-table");
    await user.click(screen.getByRole("button", { name: "Add student" }));
    const dialog = await screen.findByRole("dialog", { name: "Admit a student" });
    await within(dialog).findByRole("button", { name: "Admit student" });
    return { user, dialog };
  }

  async function fillValidForm(user: ReturnType<typeof userEvent.setup>, dialog: HTMLElement) {
    await user.type(within(dialog).getByLabelText("First name"), "Kabir");
    await user.type(within(dialog).getByLabelText("Admission number"), "AKS/2026/201");
    fireEvent.change(within(dialog).getByLabelText("Date of birth"), { target: { value: "2016-04-12" } });
    await user.selectOptions(within(dialog).getByLabelText("Gender"), "MALE");
    await user.selectOptions(within(dialog).getByLabelText("Class and section"), "s5a");
    await user.type(within(dialog).getByLabelText("Name"), "Meena Rao");
    await user.selectOptions(within(dialog).getByLabelText("Relation"), "MOTHER");
    await user.type(within(dialog).getByLabelText("Mobile number"), "+91 98765 00011");
  }

  it("checks the form the same way the API does before sending it", async () => {
    const { user, dialog } = await openDialog();
    expect(within(dialog).getByText("The student joins a section of 2026-27.")).toBeInTheDocument();
    expect(within(dialog).getByRole("option", { name: "Class 5 A · 2 of 40" })).toBeInTheDocument();

    await user.type(within(dialog).getByLabelText("Mobile number"), "12345");
    await user.type(within(dialog).getByLabelText("APAAR ID"), "1234");
    await user.click(within(dialog).getByRole("button", { name: "Admit student" }));

    expect(createStudent).not.toHaveBeenCalled();
    expect(within(dialog).getAllByText("Fill in this field.").length).toBeGreaterThanOrEqual(3);
    expect(within(dialog).getByText("Pick a section.")).toBeInTheDocument();
    expect(within(dialog).getByText("Enter a 10-digit Indian mobile number.")).toBeInTheDocument();
    expect(within(dialog).getByText("An APAAR ID has 12 digits.")).toBeInTheDocument();
    expect(within(dialog).getByLabelText("First name")).toHaveFocus();
  });

  it("sends the student with their primary contact", async () => {
    createStudent.mockResolvedValue({ id: "st9", fullName: "Kabir" } as StudentDetail);
    const { user, dialog } = await openDialog();
    await fillValidForm(user, dialog);
    await user.click(within(dialog).getByRole("button", { name: "Admit student" }));

    expect(createStudent).toHaveBeenCalledWith(
      expect.objectContaining({
        admissionNo: "AKS/2026/201",
        firstName: "Kabir",
        lastName: null,
        dateOfBirth: "2016-04-12",
        gender: "MALE",
        sectionId: "s5a",
        rollNo: null,
        apaarId: null,
        guardians: [
          { name: "Meena Rao", relation: "MOTHER", phone: "+91 98765 00011", email: null, occupation: null, primary: true },
        ],
      }),
    );
    expect(await screen.findByText("Kabir has been admitted.")).toBeInTheDocument();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("shows the API's field errors next to the guardian's fields", async () => {
    createStudent.mockRejectedValue(
      new ApiError({
        status: 400,
        title: "Invalid request",
        errors: { "guardians[0].phone": "This number belongs to a different parent name." },
      }),
    );
    const { user, dialog } = await openDialog();
    await fillValidForm(user, dialog);
    await user.click(within(dialog).getByRole("button", { name: "Admit student" }));

    expect(await within(dialog).findByText("This number belongs to a different parent name.")).toBeInTheDocument();
    expect(within(dialog).getByText("Check the highlighted fields.")).toBeInTheDocument();
    expect(within(dialog).getByLabelText("Mobile number")).toHaveAttribute("aria-invalid", "true");
  });

  it("allows up to four parents or guardians with one primary contact", async () => {
    const { user, dialog } = await openDialog();
    const add = () => within(dialog).getByRole("button", { name: "Add another parent or guardian" });
    await user.click(add());
    await user.click(add());
    await user.click(add());
    expect(within(dialog).queryByRole("button", { name: "Add another parent or guardian" })).not.toBeInTheDocument();
    expect(within(dialog).getAllByRole("radio", { name: "Primary contact" })).toHaveLength(4);
    expect(within(dialog).getAllByRole("radio", { name: "Primary contact" })[0]).toBeChecked();
  });
});

describe("Promoting a section", () => {
  const NEXT: AcademicYear = { id: "y2", name: "2027-28", startsOn: "2027-06-01", endsOn: "2028-03-31", current: false };
  const CLASS_6: ClassView = {
    id: "c6",
    name: "Class 6",
    displayOrder: 6,
    subjects: [],
    sections: [{ ...CLASSES[0].sections[0], id: "s6a", classId: "c6", className: "Class 6", studentCount: 0 }],
  };

  it("picks the year after the source year and refuses an earlier target", () => {
    const years = [NEXT, YEAR];
    expect(nextYearOf(years, "y1")?.id).toBe("y2");
    expect(nextYearOf(years, "y2")).toBeUndefined();
    const base = { fromYearId: "y1", fromSectionId: "s5a", toSectionId: "s6a" };
    expect(validatePromotion({ ...base, action: "promote", toYearId: "y2" }, years)).toEqual({});
    expect(validatePromotion({ ...base, action: "promote", toYearId: "y1" }, years)).toEqual({
      toYearId: "students.promote.laterYear",
    });
    expect(validatePromotion({ ...base, action: "graduate", toYearId: "", toSectionId: "" }, years)).toEqual({});
  });

  it("moves a section into the next year and reports who was skipped", async () => {
    const user = userEvent.setup();
    listYears.mockResolvedValue([YEAR, NEXT]);
    listClasses.mockResolvedValue([...CLASSES, CLASS_6]);
    promoteStudents.mockResolvedValue({
      promoted: 1,
      graduated: 0,
      skipped: [{ studentId: "st2", fullName: "Kavya Reddy", reason: "Already placed in 2027-28." }],
    });
    renderAs(ADMIN_PERMISSIONS, <StudentsView />);
    await screen.findByTestId("students-table");
    await user.click(screen.getByRole("button", { name: "Promote" }));
    const dialog = await screen.findByRole("dialog", { name: "Promote a section" });
    await within(dialog).findByRole("button", { name: "Promote students" });
    expect(within(dialog).getByLabelText("From year")).toHaveValue("y1");
    expect(within(dialog).getByLabelText("To year")).toHaveValue("y2");

    await user.click(within(dialog).getByRole("button", { name: "Promote students" }));
    expect(promoteStudents).not.toHaveBeenCalled();
    expect(within(dialog).getAllByText("Choose one.")).toHaveLength(2);

    await user.selectOptions(within(dialog).getByLabelText("From section"), "s5a");
    await user.selectOptions(within(dialog).getByLabelText("To section"), "s6a");
    await user.click(within(dialog).getByRole("button", { name: "Promote students" }));
    expect(promoteStudents).toHaveBeenCalledWith({
      fromSectionId: "s5a",
      fromYearId: "y1",
      toSectionId: "s6a",
      toYearId: "y2",
      graduate: false,
    });
    const result = await within(dialog).findByTestId("promotion-result");
    expect(within(result).getByText(/1 student promoted/)).toBeInTheDocument();
    expect(within(result).getByText("Kavya Reddy: Already placed in 2027-28.")).toBeInTheDocument();
  });
});
