/** Types mirroring docs/api/phase-0.md. Keep in sync with the contract. */

export const BOARDS = ["CBSE", "ICSE", "STATE", "IB", "CAMBRIDGE"] as const;
export type Board = (typeof BOARDS)[number];

export const PLANS = ["STARTER", "GROWTH", "ENTERPRISE"] as const;
export type Plan = (typeof PLANS)[number];

export type TenantStatus = "TRIAL" | "ACTIVE" | "PAST_DUE" | "SUSPENDED";

export type Tenant = {
  id: string;
  name: string;
  code: string;
  status: TenantStatus;
  plan: Plan;
  board: string;
  city: string | null;
  /** ISO-8601 instant */
  trialEndsAt: string | null;
};

export type Me = {
  id: string;
  name: string;
  email: string;
  /** Role codes, e.g. ["SCHOOL_ADMIN"] */
  roles: string[];
  /** Permission codes, e.g. ["users.read", "users.manage"] */
  permissions: string[];
  platformAdmin: boolean;
  tenant: Tenant | null;
};

export type AuthResponse = { accessToken: string; expiresIn: number; user: Me };

export type SignupRequest = {
  schoolName: string;
  schoolCode: string;
  board: Board;
  city: string;
  adminName: string;
  adminEmail: string;
  password: string;
};

export type SignupResponse = { tenantId: string; schoolCode: string; trialEndsAt: string };

export type UserStatus = "ACTIVE" | "DISABLED";

export type UserSummary = {
  id: string;
  name: string;
  email: string;
  roles: string[];
  status: UserStatus;
  lastLoginAt: string | null;
  createdAt: string;
};

export type CreateUserRequest = { name: string; email: string; password: string; roles: string[] };

export type Role = { code: string; name: string; permissions: string[] };

export type AuditEvent = {
  id: string;
  at: string;
  actorName: string | null;
  action: string;
  entityType: string | null;
  entityId: string | null;
  details: Record<string, unknown>;
};

export type TenantSummary = {
  id: string;
  name: string;
  code: string;
  status: string;
  plan: string;
  board: string;
  city: string | null;
  createdAt: string;
  trialEndsAt: string | null;
  userCount: number;
};

export type CreateTenantRequest = {
  schoolName: string;
  schoolCode: string;
  board: Board;
  city: string;
  plan: Plan;
  adminName: string;
  adminEmail: string;
  password: string;
};

/** RFC 9457 problem details as returned by the API. */
export type ProblemDetails = {
  type?: string;
  title?: string;
  status?: number;
  detail?: string;
  errors?: Record<string, string>;
};

export * from "./types/school";
export * from "./types/admissions";
export * from "./types/attendance";
export * from "./types/notifications";
