import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { StaffDepartment, StaffDetail, StaffPage, StaffRow } from "@/lib/types";
import { renderAs } from "@/test/render";
import { EMPTY_STAFF, validateStaff, validateLeaving } from "./staff-form";
import { DEFAULT_STAFF_FILTERS, StaffView, toStaffQuery } from "./staff-view";

const { list, designations, roles, departments, create } = vi.hoisted(() => ({
  list: vi.fn(),
  designations: vi.fn(),
  roles: vi.fn(),
  departments: vi.fn(),
  create: vi.fn(),
}));

vi.mock("@/lib/staff-api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/staff-api")>();
  return { ...actual, staffApi: { ...actual.staffApi, list, designations, roles, departments, create } };
});

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
  usePathname: () => "/app/staff",
}));

const READ = ["dashboard.view", "staff.read", "leave.request", "leave.approve"];
const MANAGE = [...READ, "staff.manage", "staff_attendance.manage"];

const SCIENCE: StaffDepartment = { id: "d1", name: "Science", head: { id: "u2", name: "Anjali Deshmukh" }, staffCount: 2 };

function row(overrides: Partial<StaffRow>): StaffRow {
  return {
    userId: "u1",
    name: "Ravi Kumar",
    email: "teacher@demo.akshara.test",
    roles: ["TEACHER"],
    employeeCode: "AKS-101",
    designation: "Primary teacher",
    department: { id: "d0", name: "Primary" },
    employmentType: "PERMANENT",
    dateOfJoining: "2020-06-01",
    dateOfLeaving: null,
    mobile: "98480•••02",
    status: "ACTIVE",
    profileComplete: true,
    today: "PRESENT",
    ...overrides,
  };
}

function page(items: StaffRow[], overrides: Partial<StaffPage> = {}): StaffPage {
  return { items, page: 0, size: 25, total: items.length, incompleteProfiles: 0, ...overrides };
}

beforeEach(() => {
  list.mockResolvedValue(
    page(
      [
        row({}),
        row({
          userId: "u3",
          name: "Meena Iyer",
          email: "principal@demo.akshara.test",
          roles: ["PRINCIPAL"],
          employeeCode: null,
          designation: null,
          department: null,
          mobile: null,
          profileComplete: false,
          today: null,
        }),
      ],
      { incompleteProfiles: 1 },
    ),
  );
  designations.mockResolvedValue(["Primary teacher", "Principal"]);
  roles.mockResolvedValue([
    { code: "TEACHER", name: "Teacher" },
    { code: "PRINCIPAL", name: "Principal" },
  ]);
  departments.mockResolvedValue([SCIENCE]);
});

describe("toStaffQuery", () => {
  it("sends only the filters in use, active staff by default", () => {
    expect(toStaffQuery(DEFAULT_STAFF_FILTERS, "  ", 0)).toEqual({
      departmentId: undefined,
      designation: undefined,
      role: undefined,
      status: "ACTIVE",
      incomplete: undefined,
      q: undefined,
      page: 0,
      size: 25,
    });
    expect(
      toStaffQuery({ ...DEFAULT_STAFF_FILTERS, departmentId: "d1", role: "TEACHER", status: "", incomplete: true }, " ravi ", 2),
    ).toMatchObject({ departmentId: "d1", role: "TEACHER", status: undefined, incomplete: true, q: "ravi", page: 2 });
  });
});

describe("StaffView", () => {
  it("lists staff with masked mobiles, today's status and a profile-incomplete marker", async () => {
    renderAs(READ, <StaffView />);
    const table = await screen.findByTestId("staff-table");
    expect(within(table).getByText("Ravi Kumar")).toBeInTheDocument();
    expect(within(table).getByText("98480•••02")).toBeInTheDocument();
    expect(within(table).getByText("Present")).toBeInTheDocument();
    expect(within(table).getByText("Profile incomplete")).toBeInTheDocument();
    expect(within(table).getByRole("link", { name: /Ravi Kumar/ })).toHaveAttribute("href", "/app/staff/u1");
    expect(screen.getByTestId("incomplete-notice")).toHaveTextContent("1 staff member has no profile yet.");
    expect(screen.getByText("2 staff members")).toBeInTheDocument();
    // Only staff.manage adds staff.
    expect(screen.queryByRole("button", { name: "Add staff" })).not.toBeInTheDocument();
  });

  it("filters by department and shows the incomplete profiles", async () => {
    const user = userEvent.setup();
    renderAs(READ, <StaffView />);
    await screen.findByTestId("staff-table");
    await screen.findByRole("option", { name: "Science" });
    await user.selectOptions(screen.getByLabelText("Department"), "d1");
    await waitFor(() => expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ departmentId: "d1", page: 0 })));
    await user.click(screen.getByRole("button", { name: "Show them" }));
    await waitFor(() =>
      expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ incomplete: true, departmentId: undefined, status: undefined })),
    );
  });

  it("shows an empty state", async () => {
    list.mockResolvedValue(page([]));
    renderAs(MANAGE, <StaffView />);
    expect(await screen.findByTestId("staff-empty")).toHaveTextContent("No staff yet.");
  });

  it("adds a staff member with a sign-in and profile in one step", async () => {
    const created: StaffDetail = {
      userId: "u9",
      name: "Neha Gupta",
      email: "neha@school.test",
      roles: ["TEACHER"],
      status: "ACTIVE",
      accountActive: true,
      lastLoginAt: null,
      profileComplete: true,
      profile: null,
      leaveApprover: { routing: "SCHOOL", departmentHead: null },
    };
    create.mockResolvedValue(created);
    const user = userEvent.setup();
    renderAs(MANAGE, <StaffView />);
    await user.click(await screen.findByRole("button", { name: "Add staff" }));
    const dialog = await screen.findByRole("dialog", { name: "Add staff member" });
    await user.click(within(dialog).getByRole("button", { name: "Add staff member" }));
    expect(create).not.toHaveBeenCalled();
    expect(within(dialog).getAllByText("Fill in this field.").length).toBeGreaterThan(0);

    await user.type(within(dialog).getByLabelText("Full name"), "Neha Gupta");
    await user.type(within(dialog).getByLabelText("Email"), "neha@school.test");
    await user.type(within(dialog).getByLabelText("Temporary password"), "a-long-password");
    await user.click(await within(dialog).findByRole("checkbox", { name: "Teacher" }));
    await user.type(within(dialog).getByLabelText("Employee code"), "AKS-150");
    await user.type(within(dialog).getByLabelText("Designation"), "TGT English");
    await user.type(within(dialog).getByLabelText("Date of joining"), "2026-07-01");
    await user.type(within(dialog).getAllByLabelText("Mobile")[0], "+91 98480 12345");
    await user.click(within(dialog).getByRole("button", { name: "Add staff member" }));

    await waitFor(() => expect(create).toHaveBeenCalledTimes(1));
    expect(create).toHaveBeenCalledWith(
      expect.objectContaining({
        name: "Neha Gupta",
        email: "neha@school.test",
        password: "a-long-password",
        roles: ["TEACHER"],
        employeeCode: "AKS-150",
        designation: "TGT English",
        departmentId: null,
        employmentType: "PERMANENT",
        dateOfJoining: "2026-07-01",
        mobile: "9848012345",
        emergencyContactName: null,
        emergencyContactMobile: null,
      }),
    );
    expect(await screen.findByText("Neha Gupta added to the staff.")).toBeInTheDocument();
  });
});

describe("validateStaff", () => {
  const valid = {
    ...EMPTY_STAFF,
    name: "Neha",
    email: "n@s.test",
    password: "0123456789",
    roles: ["TEACHER"],
    employeeCode: "AKS-1",
    designation: "Teacher",
    dateOfJoining: "2026-06-01",
    mobile: "9848012345",
  };

  it("accepts a complete form", () => {
    expect(validateStaff(valid, true, "2026-10-09")).toEqual({});
  });

  it("mirrors the API's rules", () => {
    expect(validateStaff({ ...valid, employeeCode: "AKS 1" }, true, "2026-10-09").employeeCode).toBe("staff.v.employeeCode");
    expect(validateStaff({ ...valid, dateOfJoining: "2028-01-01" }, true, "2026-10-09").dateOfJoining).toBe(
      "staff.v.joiningFuture",
    );
    expect(validateStaff({ ...valid, mobile: "12345" }, true, "2026-10-09").mobile).toBe("staff.v.mobile");
    expect(validateStaff({ ...valid, emergencyContactName: "Asha" }, true, "2026-10-09").emergencyContactMobile).toBe(
      "staff.v.emergencyBoth",
    );
    expect(validateStaff({ ...valid, password: "short" }, true, "2026-10-09").password).toBe("validation.password");
    // Editing a profile does not check the sign-in fields.
    expect(validateStaff({ ...valid, name: "", password: "" }, false, "2026-10-09")).toEqual({});
  });

  it("checks the leaving date against today and the joining date", () => {
    expect(validateLeaving({ leftOn: "2026-10-10", reason: "Moved" }, "2020-01-01", "2026-10-09").leftOn).toBe(
      "staff.v.leftFuture",
    );
    expect(validateLeaving({ leftOn: "2019-01-01", reason: "Moved" }, "2020-01-01", "2026-10-09").leftOn).toBe(
      "staff.v.leftBeforeJoining",
    );
    expect(validateLeaving({ leftOn: "2026-10-01", reason: " " }, "2020-01-01", "2026-10-09").reason).toBe(
      "validation.required",
    );
  });
});
