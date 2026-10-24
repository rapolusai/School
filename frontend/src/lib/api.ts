import type {
  AcademicYear,
  AuditEvent,
  AuthResponse,
  Child,
  ClassRequest,
  ClassView,
  CreateStudentRequest,
  CreateTenantRequest,
  CreateUserRequest,
  Guardian,
  GuardianFields,
  ImportResult,
  LeaveRequest,
  ProblemDetails,
  PromoteRequest,
  PromotionResult,
  Role,
  SchoolProfile,
  SchoolProfileRequest,
  SectionRequest,
  SectionView,
  SignInRequest,
  SignupRequest,
  SignupResponse,
  StudentDetail,
  StudentPage,
  StudentQuery,
  Subject,
  SubjectRef,
  SubjectRequest,
  TeacherRef,
  TenantSummary,
  UpdateStudentRequest,
  UserSummary,
  Me,
  YearRequest,
} from "./types";

/** A failed API call, parsed from an RFC 9457 problem+json body when one is present. */
export class ApiError extends Error {
  readonly status: number;
  readonly title: string;
  readonly detail?: string;
  readonly errors?: Record<string, string>;
  readonly type?: string;

  constructor(init: {
    status: number;
    title: string;
    detail?: string;
    errors?: Record<string, string>;
    type?: string;
  }) {
    super(init.detail ?? init.title);
    this.name = "ApiError";
    this.status = init.status;
    this.title = init.title;
    this.detail = init.detail;
    this.errors = init.errors;
    this.type = init.type;
  }
}

/** Status used for network failures (request never reached the API). */
export const NETWORK_ERROR_STATUS = 0;

export function isApiError(value: unknown): value is ApiError {
  return value instanceof ApiError;
}

/** Normalise anything thrown by a request into an ApiError. */
export function toApiError(value: unknown): ApiError {
  if (value instanceof ApiError) return value;
  return new ApiError({
    status: NETWORK_ERROR_STATUS,
    title: "Network error",
    detail: value instanceof Error ? value.message : undefined,
  });
}

function stringRecord(value: unknown): Record<string, string> | undefined {
  if (!value || typeof value !== "object" || Array.isArray(value)) return undefined;
  const out: Record<string, string> = {};
  for (const [key, message] of Object.entries(value as Record<string, unknown>)) {
    if (typeof message === "string") out[key] = message;
    else if (Array.isArray(message)) out[key] = message.filter((m) => typeof m === "string").join(" ");
  }
  return Object.keys(out).length ? out : undefined;
}

/** Build an ApiError from a non-2xx response. Exported for tests. */
export async function parseErrorResponse(res: Response): Promise<ApiError> {
  let problem: ProblemDetails = {};
  const contentType = res.headers.get("content-type") ?? "";
  if (contentType.includes("json")) {
    try {
      problem = (await res.json()) as ProblemDetails;
    } catch {
      problem = {};
    }
  }
  return new ApiError({
    status: typeof problem.status === "number" ? problem.status : res.status,
    title: problem.title || res.statusText || `HTTP ${res.status}`,
    detail: typeof problem.detail === "string" ? problem.detail : undefined,
    errors: stringRecord(problem.errors),
    type: typeof problem.type === "string" ? problem.type : undefined,
  });
}

/* ------------------------------------------------------------------ */
/* Access token: memory only. Never written to localStorage/cookies.   */
/* ------------------------------------------------------------------ */

let accessToken: string | null = null;

export function getAccessToken(): string | null {
  return accessToken;
}

export function setAccessToken(token: string | null): void {
  accessToken = token;
}

type SessionListener = (session: AuthResponse | null) => void;
const sessionListeners = new Set<SessionListener>();

/**
 * Subscribe to session changes caused by the API layer itself: a silent refresh after a 401
 * (new token + fresh `user`) or a failed refresh (session is gone).
 */
export function onSessionChange(listener: SessionListener): () => void {
  sessionListeners.add(listener);
  return () => {
    sessionListeners.delete(listener);
  };
}

function emitSession(session: AuthResponse | null) {
  for (const listener of sessionListeners) listener(session);
}

/**
 * Which refresh cookie the session lives on: a school user's (`refresh_token`, sent to /api/auth) or a Super
 * Admin's (`platform_refresh_token`, sent to /api/platform/auth). Null until a sign-in or a restored session tells.
 */
export type SessionKind = "school" | "platform";

const REFRESH_PATH: Record<SessionKind, string> = {
  school: "/api/auth/refresh",
  platform: "/api/platform/auth/refresh",
};

const LOGOUT_PATH: Record<SessionKind, string> = {
  school: "/api/auth/logout",
  platform: "/api/platform/auth/logout",
};

let sessionKind: SessionKind | null = null;

export function getSessionKind(): SessionKind | null {
  return sessionKind;
}

export function setSessionKind(kind: SessionKind | null): void {
  sessionKind = kind;
}

function kindOf(session: AuthResponse): SessionKind {
  return session.user.platformAdmin ? "platform" : "school";
}

/** On a page reload nothing says which cookie the browser holds: try the one the address suggests first. */
function restoreOrder(): SessionKind[] {
  const onPlatformPage = typeof window !== "undefined" && window.location?.pathname?.startsWith("/app/platform");
  return onPlatformPage ? ["platform", "school"] : ["school", "platform"];
}

let refreshInFlight: Promise<AuthResponse | null> | null = null;

/**
 * POST /api/auth/refresh (or /api/platform/auth/refresh for a Super Admin) using the httpOnly cookie. Concurrent
 * callers share one request: the refresh token rotates, so two parallel refreshes would look like token reuse.
 * With `restore` (a page load, when the kind of session is not known yet) both cookies are tried in turn.
 * Resolves to null when there is no valid session.
 */
export function refreshSession(options: { restore?: boolean } = {}): Promise<AuthResponse | null> {
  if (!refreshInFlight) {
    const tokenAtStart = accessToken;
    const kinds: SessionKind[] = sessionKind
      ? [sessionKind]
      : options.restore
        ? restoreOrder()
        : ["school"];
    refreshInFlight = (async () => {
      try {
        for (const kind of kinds) {
          const res = await fetch(REFRESH_PATH[kind], {
            method: "POST",
            credentials: "include",
            headers: { Accept: "application/json" },
          });
          // A sign-in or sign-out happened while this was in flight: its result is stale.
          if (accessToken !== tokenAtStart) return null;
          if (!res.ok) continue;
          const session = (await res.json()) as AuthResponse;
          accessToken = session.accessToken;
          sessionKind = kindOf(session);
          emitSession(session);
          return session;
        }
        accessToken = null;
        sessionKind = null;
        emitSession(null);
        return null;
      } catch {
        // Network failure: keep whatever token we had, report no new session.
        return null;
      } finally {
        refreshInFlight = null;
      }
    })();
  }
  return refreshInFlight;
}

/**
 * Problem type the API answers (403) to a parent or student whose school's privacy notice has not been accepted
 * (docs/api/phase-1-privacy.md). The privacy gate listens and shows the consent screen.
 */
export const CONSENT_REQUIRED_TYPE = "urn:akshara:problem:consent-required";

type ConsentListener = () => void;
const consentListeners = new Set<ConsentListener>();

/** Subscribe to "consent required" answers from any API call. */
export function onConsentRequired(listener: ConsentListener): () => void {
  consentListeners.add(listener);
  return () => {
    consentListeners.delete(listener);
  };
}

type RequestOptions = {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  /** Send the bearer token and retry once through /api/auth/refresh on 401. Default true. */
  auth?: boolean;
  signal?: AbortSignal;
};

/**
 * One path segment for an API address. Route params can arrive still percent-encoded (an id typed in the address
 * bar as `a%3Bb`), so the value is decoded once before it is encoded; a plain id passes through unchanged.
 */
export function pathSegment(value: string): string {
  let raw = value;
  if (/%[0-9A-Fa-f]{2}/.test(value)) {
    try {
      raw = decodeURIComponent(value);
    } catch {
      raw = value;
    }
  }
  return encodeURIComponent(raw);
}

/**
 * Encoded characters the API's request firewall refuses in a path (";", "%", "/", "\\" and control characters).
 * Such an address can name no record, so it is answered here as not found instead of being sent.
 */
const REFUSED_IN_PATH = /%(?:3B|25|2F|5C|0[0-9A-F]|1[0-9A-F]|7F)/i;

/** True when the path part of an API address holds an id the API cannot look up. Exported for tests. */
export function isUnreachablePath(path: string): boolean {
  const question = path.indexOf("?");
  return REFUSED_IN_PATH.test(question === -1 ? path : path.slice(0, question));
}

/** Low-level fetch wrapper used by every endpoint helper. */
export async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = "GET", body, auth = true, signal } = options;
  if (isUnreachablePath(path)) {
    throw new ApiError({ status: 404, title: "Not found", detail: "There is nothing at this address." });
  }

  const send = (token: string | null) => {
    const headers: Record<string, string> = { Accept: "application/json" };
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (auth && token) headers.Authorization = `Bearer ${token}`;
    return fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      credentials: "include",
      signal,
    });
  };

  let res: Response;
  try {
    const sentToken = accessToken;
    res = await send(sentToken);
    if (res.status === 401 && auth) {
      const session = await refreshSession();
      // Retry once with the refreshed token, or with a token set by a concurrent sign-in.
      const retryToken = session?.accessToken ?? (accessToken !== sentToken ? accessToken : null);
      if (retryToken) res = await send(retryToken);
    }
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError") throw error;
    throw toApiError(error);
  }

  if (!res.ok) {
    const error = await parseErrorResponse(res);
    if (error.status === 403 && error.type === CONSENT_REQUIRED_TYPE) {
      for (const listener of consentListeners) listener();
    }
    throw error;
  }
  if (res.status === 204 || res.headers.get("content-length") === "0") return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/* ------------------------------------------------------------------ */
/* Endpoint helpers (docs/api/phase-0.md, docs/api/phase-1.md)         */
/* ------------------------------------------------------------------ */

const id = pathSegment;

/** "?yearId=…&q=…" from the set filters only. Exported for tests. */
export function studentQueryString(query: StudentQuery): string {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === "") continue;
    params.set(key, String(value));
  }
  const text = params.toString();
  return text ? `?${text}` : "";
}

export const api = {
  signup: (body: SignupRequest) =>
    apiFetch<SignupResponse>("/api/public/signup", { method: "POST", body, auth: false }),
  login: (body: { schoolCode: string; email: string; password: string }) =>
    apiFetch<AuthResponse>("/api/auth/login", { method: "POST", body, auth: false }),
  platformLogin: (body: { email: string; password: string }) =>
    apiFetch<AuthResponse>("/api/platform/auth/login", { method: "POST", body, auth: false }),
  logout: () => apiFetch<void>(LOGOUT_PATH[sessionKind ?? "school"], { method: "POST", auth: false }),

  me: () => apiFetch<Me>("/api/me"),
  myPreferences: () => apiFetch<{ language: string | null }>("/api/me/preferences"),
  saveMyPreferences: (body: { language: string }) =>
    apiFetch<{ language: string | null }>("/api/me/preferences", { method: "PUT", body }),
  listUsers: () => apiFetch<UserSummary[]>("/api/users"),
  getUser: (userId: string) => apiFetch<UserSummary>(`/api/users/${id(userId)}`),
  createUser: (body: CreateUserRequest) =>
    apiFetch<UserSummary>("/api/users", { method: "POST", body }),
  listRoles: () => apiFetch<Role[]>("/api/roles"),
  listAuditEvents: (limit = 50) => apiFetch<AuditEvent[]>(`/api/audit-events?limit=${limit}`),

  listTenants: () => apiFetch<TenantSummary[]>("/api/platform/tenants"),
  createTenant: (body: CreateTenantRequest) =>
    apiFetch<TenantSummary>("/api/platform/tenants", { method: "POST", body }),

  /* School setup */
  listYears: () => apiFetch<AcademicYear[]>("/api/academics/years"),
  createYear: (body: YearRequest) =>
    apiFetch<AcademicYear>("/api/academics/years", { method: "POST", body }),
  updateYear: (yearId: string, body: YearRequest) =>
    apiFetch<AcademicYear>(`/api/academics/years/${id(yearId)}`, { method: "PUT", body }),
  setCurrentYear: (yearId: string) =>
    apiFetch<AcademicYear>(`/api/academics/years/${id(yearId)}/set-current`, { method: "POST" }),
  deleteYear: (yearId: string) =>
    apiFetch<void>(`/api/academics/years/${id(yearId)}`, { method: "DELETE" }),

  listClasses: () => apiFetch<ClassView[]>("/api/academics/classes"),
  createClass: (body: ClassRequest) =>
    apiFetch<ClassView>("/api/academics/classes", { method: "POST", body }),
  updateClass: (classId: string, body: ClassRequest) =>
    apiFetch<ClassView>(`/api/academics/classes/${id(classId)}`, { method: "PUT", body }),
  deleteClass: (classId: string) =>
    apiFetch<void>(`/api/academics/classes/${id(classId)}`, { method: "DELETE" }),
  createSection: (classId: string, body: SectionRequest) =>
    apiFetch<SectionView>(`/api/academics/classes/${id(classId)}/sections`, { method: "POST", body }),
  updateSection: (sectionId: string, body: SectionRequest) =>
    apiFetch<SectionView>(`/api/academics/sections/${id(sectionId)}`, { method: "PUT", body }),
  deleteSection: (sectionId: string) =>
    apiFetch<void>(`/api/academics/sections/${id(sectionId)}`, { method: "DELETE" }),
  listTeachers: () => apiFetch<TeacherRef[]>("/api/academics/teachers"),

  listSubjects: () => apiFetch<Subject[]>("/api/academics/subjects"),
  createSubject: (body: SubjectRequest) =>
    apiFetch<Subject>("/api/academics/subjects", { method: "POST", body }),
  updateSubject: (subjectId: string, body: SubjectRequest) =>
    apiFetch<Subject>(`/api/academics/subjects/${id(subjectId)}`, { method: "PUT", body }),
  deleteSubject: (subjectId: string) =>
    apiFetch<void>(`/api/academics/subjects/${id(subjectId)}`, { method: "DELETE" }),
  setClassSubjects: (classId: string, subjectIds: string[]) =>
    apiFetch<SubjectRef[]>(`/api/academics/classes/${id(classId)}/subjects`, {
      method: "PUT",
      body: { subjectIds },
    }),

  getSchoolProfile: () => apiFetch<SchoolProfile>("/api/school/profile"),
  updateSchoolProfile: (body: SchoolProfileRequest) =>
    apiFetch<SchoolProfile>("/api/school/profile", { method: "PUT", body }),

  /* Students */
  listStudents: (query: StudentQuery = {}) =>
    apiFetch<StudentPage>(`/api/students${studentQueryString(query)}`),
  getStudent: (studentId: string) => apiFetch<StudentDetail>(`/api/students/${id(studentId)}`),
  createStudent: (body: CreateStudentRequest) =>
    apiFetch<StudentDetail>("/api/students", { method: "POST", body }),
  updateStudent: (studentId: string, body: UpdateStudentRequest) =>
    apiFetch<StudentDetail>(`/api/students/${id(studentId)}`, { method: "PUT", body }),
  leaveStudent: (studentId: string, body: LeaveRequest) =>
    apiFetch<StudentDetail>(`/api/students/${id(studentId)}/leave`, { method: "POST", body }),
  addGuardian: (studentId: string, body: GuardianFields) =>
    apiFetch<Guardian>(`/api/students/${id(studentId)}/guardians`, { method: "POST", body }),
  updateGuardian: (studentId: string, guardianId: string, body: GuardianFields) =>
    apiFetch<Guardian>(`/api/students/${id(studentId)}/guardians/${id(guardianId)}`, {
      method: "PUT",
      body,
    }),
  removeGuardian: (studentId: string, guardianId: string) =>
    apiFetch<void>(`/api/students/${id(studentId)}/guardians/${id(guardianId)}`, { method: "DELETE" }),
  guardianSignIn: (studentId: string, guardianId: string, body: SignInRequest) =>
    apiFetch<Guardian>(`/api/students/${id(studentId)}/guardians/${id(guardianId)}/sign-in`, {
      method: "POST",
      body,
    }),
  studentSignIn: (studentId: string, body: SignInRequest) =>
    apiFetch<StudentDetail>(`/api/students/${id(studentId)}/sign-in`, { method: "POST", body }),
  promoteStudents: (body: PromoteRequest) =>
    apiFetch<PromotionResult>("/api/students/promote", { method: "POST", body }),
  importStudents: (csv: string, dryRun: boolean) =>
    apiFetch<ImportResult>(`/api/students/import?dryRun=${dryRun}`, { method: "POST", body: { csv } }),

  myChildren: () => apiFetch<Child[]>("/api/me/children"),
  myStudentRecord: () => apiFetch<Child>("/api/me/student"),
};
