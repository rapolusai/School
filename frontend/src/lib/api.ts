import type {
  AuditEvent,
  AuthResponse,
  CreateTenantRequest,
  CreateUserRequest,
  ProblemDetails,
  Role,
  SignupRequest,
  SignupResponse,
  TenantSummary,
  UserSummary,
  Me,
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

let refreshInFlight: Promise<AuthResponse | null> | null = null;

/**
 * POST /api/auth/refresh using the httpOnly cookie. Concurrent callers share one request:
 * the refresh token rotates, so two parallel refreshes would look like token reuse.
 * Resolves to null when there is no valid session.
 */
export function refreshSession(): Promise<AuthResponse | null> {
  if (!refreshInFlight) {
    const tokenAtStart = accessToken;
    refreshInFlight = (async () => {
      try {
        const res = await fetch("/api/auth/refresh", {
          method: "POST",
          credentials: "include",
          headers: { Accept: "application/json" },
        });
        // A sign-in or sign-out happened while this was in flight: its result is stale.
        if (accessToken !== tokenAtStart) return null;
        if (!res.ok) {
          accessToken = null;
          emitSession(null);
          return null;
        }
        const session = (await res.json()) as AuthResponse;
        accessToken = session.accessToken;
        emitSession(session);
        return session;
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

type RequestOptions = {
  method?: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  body?: unknown;
  /** Send the bearer token and retry once through /api/auth/refresh on 401. Default true. */
  auth?: boolean;
  signal?: AbortSignal;
};

/** Low-level fetch wrapper used by every endpoint helper. */
export async function apiFetch<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method = "GET", body, auth = true, signal } = options;

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

  if (!res.ok) throw await parseErrorResponse(res);
  if (res.status === 204 || res.headers.get("content-length") === "0") return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/* ------------------------------------------------------------------ */
/* Endpoint helpers (docs/api/phase-0.md)                              */
/* ------------------------------------------------------------------ */

export const api = {
  signup: (body: SignupRequest) =>
    apiFetch<SignupResponse>("/api/public/signup", { method: "POST", body, auth: false }),
  login: (body: { schoolCode: string; email: string; password: string }) =>
    apiFetch<AuthResponse>("/api/auth/login", { method: "POST", body, auth: false }),
  platformLogin: (body: { email: string; password: string }) =>
    apiFetch<AuthResponse>("/api/platform/auth/login", { method: "POST", body, auth: false }),
  logout: () => apiFetch<void>("/api/auth/logout", { method: "POST", auth: false }),

  me: () => apiFetch<Me>("/api/me"),
  listUsers: () => apiFetch<UserSummary[]>("/api/users"),
  getUser: (id: string) => apiFetch<UserSummary>(`/api/users/${encodeURIComponent(id)}`),
  createUser: (body: CreateUserRequest) =>
    apiFetch<UserSummary>("/api/users", { method: "POST", body }),
  listRoles: () => apiFetch<Role[]>("/api/roles"),
  listAuditEvents: (limit = 50) => apiFetch<AuditEvent[]>(`/api/audit-events?limit=${limit}`),

  listTenants: () => apiFetch<TenantSummary[]>("/api/platform/tenants"),
  createTenant: (body: CreateTenantRequest) =>
    apiFetch<TenantSummary>("/api/platform/tenants", { method: "POST", body }),
};
