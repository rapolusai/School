import { fireEvent, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import type { StudentDetail } from "@/lib/types";
import { ADMIN_PERMISSIONS, renderAs, TEACHER_PERMISSIONS } from "@/test/render";
import { validateLeave, validateSignIn } from "./student-dialogs";
import { StudentDetailView } from "./student-detail-view";

const { getStudent, guardianSignIn, leaveStudent } = vi.hoisted(() => ({
  getStudent: vi.fn(),
  guardianSignIn: vi.fn(),
  leaveStudent: vi.fn(),
}));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, getStudent, guardianSignIn, leaveStudent } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/students/st1",
}));

const ENROLLMENT = {
  id: "e1",
  academicYearId: "y1",
  academicYearName: "2026-27",
  currentYear: true,
  classId: "c5",
  className: "Class 5",
  sectionId: "s5a",
  sectionName: "A",
  rollNo: 1,
};

const ARJUN: StudentDetail = {
  id: "st1",
  admissionNo: "AKS/2026/001",
  firstName: "Arjun",
  lastName: "Sharma",
  fullName: "Arjun Sharma",
  dateOfBirth: "2016-04-12",
  gender: "MALE",
  admissionDate: "2026-06-02",
  status: "ACTIVE",
  bloodGroup: null,
  address: null,
  previousSchool: null,
  apaarId: null,
  leftOn: null,
  leavingReason: null,
  hasSignIn: true,
  signInEmail: "student@demo.akshara.school",
  currentEnrollment: ENROLLMENT,
  guardians: [
    {
      id: "g1",
      name: "Anitha Sharma",
      relation: "MOTHER",
      phone: "9876500001",
      email: "anitha@example.com",
      occupation: null,
      primary: true,
      hasSignIn: false,
      signInEmail: null,
    },
  ],
  siblings: [
    { id: "st2", fullName: "Diya Sharma", admissionNo: "AKS/2026/002", status: "ACTIVE", className: "Class 2", sectionName: "A" },
  ],
  enrollments: [ENROLLMENT],
};

beforeEach(() => {
  getStudent.mockResolvedValue(ARJUN);
  guardianSignIn.mockReset();
  leaveStudent.mockReset();
});

describe("student dialog checks (mirror the API)", () => {
  it("checks sign-ins and leaving", () => {
    expect(validateSignIn({ mode: "LINK", email: "parent@example.com", password: "" })).toEqual({});
    expect(validateSignIn({ mode: "CREATE", email: "bad", password: "short" })).toEqual({
      email: "validation.email",
      password: "validation.password",
    });
    expect(validateLeave({ status: "TRANSFERRED", leftOn: "2026-10-01", reason: "Moved" }, "2026-06-02")).toEqual({});
    expect(validateLeave({ status: "WITHDRAWN", leftOn: "2026-05-01", reason: " " }, "2026-06-02")).toEqual({
      leftOn: "student.leave.beforeAdmission",
      reason: "validation.required",
    });
  });
});

describe("StudentDetailView", () => {
  it("shows the profile, parents, class history and siblings", async () => {
    renderAs(ADMIN_PERMISSIONS, <StudentDetailView id="st1" />);
    expect(await screen.findByRole("heading", { level: 1, name: /Arjun Sharma/ })).toBeInTheDocument();
    expect(screen.getByText("Class 5 A · Roll 1 · AKS/2026/001")).toBeInTheDocument();
    expect(screen.getByText("12 Apr 2016")).toBeInTheDocument();
    expect(screen.getByText("student@demo.akshara.school")).toBeInTheDocument();

    const guardians = screen.getByTestId("guardians-list");
    expect(within(guardians).getByText("Mother")).toBeInTheDocument();
    expect(within(guardians).getByText("Primary")).toBeInTheDocument();
    expect(within(guardians).getByRole("link", { name: "98765 00001" })).toHaveAttribute("href", "tel:+919876500001");

    expect(screen.getByRole("link", { name: /Diya Sharma/ })).toHaveAttribute("href", "/app/students/st2");
    expect(getStudent).toHaveBeenCalledWith("st1");
  });

  it("hides every change for a teacher", async () => {
    renderAs(TEACHER_PERMISSIONS, <StudentDetailView id="st1" />, ["TEACHER"]);
    await screen.findByRole("heading", { level: 1, name: /Arjun Sharma/ });
    expect(screen.queryByRole("button", { name: "Edit" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Transfer or withdraw" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Give Anitha Sharma a sign-in" })).not.toBeInTheDocument();
  });

  it("says when the student does not exist (or belongs to another school)", async () => {
    getStudent.mockRejectedValue(new ApiError({ status: 404, title: "Not found" }));
    renderAs(ADMIN_PERMISSIONS, <StudentDetailView id="other" />);
    expect(await screen.findByText("This student could not be found.")).toBeInTheDocument();
  });

  it("gives a parent a sign-in", async () => {
    const user = userEvent.setup();
    guardianSignIn.mockResolvedValue(undefined);
    renderAs(ADMIN_PERMISSIONS, <StudentDetailView id="st1" />);
    await user.click(await screen.findByRole("button", { name: "Give Anitha Sharma a sign-in" }));
    const dialog = screen.getByRole("dialog", { name: "Sign-in for Anitha Sharma" });
    expect(within(dialog).getByLabelText("Email")).toHaveValue("anitha@example.com");

    await user.click(within(dialog).getByRole("button", { name: "Give sign-in" }));
    expect(guardianSignIn).not.toHaveBeenCalled();

    await user.type(within(dialog).getByLabelText("Temporary password"), "parent-pass-123");
    await user.click(within(dialog).getByRole("button", { name: "Give sign-in" }));
    expect(guardianSignIn).toHaveBeenCalledWith("st1", "g1", {
      mode: "CREATE",
      email: "anitha@example.com",
      password: "parent-pass-123",
    });
    expect(await screen.findByText("anitha@example.com can now sign in.")).toBeInTheDocument();
  });

  it("records a transfer", async () => {
    const user = userEvent.setup();
    leaveStudent.mockResolvedValue({ ...ARJUN, status: "TRANSFERRED" });
    renderAs(ADMIN_PERMISSIONS, <StudentDetailView id="st1" />);
    await user.click(await screen.findByRole("button", { name: "Transfer or withdraw" }));
    const dialog = screen.getByRole("dialog", { name: "Arjun Sharma leaves the school" });
    fireEvent.change(within(dialog).getByLabelText("Last day"), { target: { value: "2026-10-01" } });
    await user.type(within(dialog).getByLabelText("Note"), "Family moved to Pune");
    await user.click(within(dialog).getByRole("button", { name: "Save" }));
    expect(leaveStudent).toHaveBeenCalledWith(
      "st1",
      expect.objectContaining({ leftOn: "2026-10-01", reason: "Family moved to Pune" }),
    );
  });
});
