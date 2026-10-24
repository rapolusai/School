// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  ApiError,
  api,
  apiFetch,
  getAccessToken,
  isUnreachablePath,
  onSessionChange,
  parseErrorResponse,
  pathSegment,
  CONSENT_REQUIRED_TYPE,
  onConsentRequired,
  refreshSession,
  setAccessToken,
  setSessionKind,
} from "./api";
import type { AuthResponse } from "./types";

const ME = {
  id: "u1",
  name: "Asha Rao",
  email: "asha@school.edu",
  roles: ["SCHOOL_ADMIN"],
  permissions: ["dashboard.view"],
  platformAdmin: false,
  tenant: null,
};

const auth = (token: string): AuthResponse => ({ accessToken: token, expiresIn: 900, user: ME });

const json = (status: number, body: unknown, type = "application/json") =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": type } });

const problem = (status: number, body: Record<string, unknown>) =>
  json(status, { status, ...body }, "application/problem+json");

const fetchMock = vi.fn<typeof fetch>();

function authHeader(call: number): string | undefined {
  const init = fetchMock.mock.calls[call][1];
  return (init?.headers as Record<string, string> | undefined)?.Authorization;
}

beforeEach(() => {
  setAccessToken(null);
  setSessionKind(null);
  fetchMock.mockReset();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("apiFetch", () => {
  it("sends the in-memory bearer token and parses JSON", async () => {
    setAccessToken("t1");
    fetchMock.mockResolvedValueOnce(json(200, [{ id: "1" }]));
    await expect(apiFetch("/api/users")).resolves.toEqual([{ id: "1" }]);
    expect(authHeader(0)).toBe("Bearer t1");
    expect(fetchMock.mock.calls[0][1]?.credentials).toBe("include");
  });

  it("on 401 refreshes once, retries with the new token and announces the session", async () => {
    setAccessToken("expired");
    const seen: (AuthResponse | null)[] = [];
    const off = onSessionChange((s) => seen.push(s));
    fetchMock
      .mockResolvedValueOnce(problem(401, { title: "Unauthorized" }))
      .mockResolvedValueOnce(json(200, auth("fresh")))
      .mockResolvedValueOnce(json(200, { ok: true }));

    await expect(apiFetch("/api/me")).resolves.toEqual({ ok: true });
    off();

    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(fetchMock.mock.calls[1][0]).toBe("/api/auth/refresh");
    expect(fetchMock.mock.calls[1][1]).toMatchObject({ method: "POST", credentials: "include" });
    expect(authHeader(2)).toBe("Bearer fresh");
    expect(getAccessToken()).toBe("fresh");
    expect(seen).toEqual([auth("fresh")]);
  });

  it("gives up after one refresh attempt and reports the session as gone", async () => {
    setAccessToken("expired");
    const seen: (AuthResponse | null)[] = [];
    const off = onSessionChange((s) => seen.push(s));
    fetchMock
      .mockResolvedValueOnce(problem(401, { title: "Unauthorized" }))
      .mockResolvedValueOnce(problem(401, { title: "Unauthorized" }));

    const error = await apiFetch("/api/me").catch((e: unknown) => e);
    off();

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(401);
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(getAccessToken()).toBeNull();
    expect(seen).toEqual([null]);
  });

  it("shares one refresh between concurrent 401s (refresh tokens rotate)", async () => {
    setAccessToken("expired");
    fetchMock.mockImplementation(async (input, init) => {
      const url = String(input);
      if (url === "/api/auth/refresh") return json(200, auth("fresh"));
      const header = (init?.headers as Record<string, string>).Authorization;
      return header === "Bearer fresh" ? json(200, { url }) : problem(401, { title: "Unauthorized" });
    });

    const results = await Promise.all([apiFetch("/api/users"), apiFetch("/api/roles")]);
    expect(results).toEqual([{ url: "/api/users" }, { url: "/api/roles" }]);
    const refreshCalls = fetchMock.mock.calls.filter(([url]) => url === "/api/auth/refresh");
    expect(refreshCalls).toHaveLength(1);
  });

  it("refreshes a Super Admin's session through the platform endpoint", async () => {
    setAccessToken("expired");
    setSessionKind("platform");
    fetchMock
      .mockResolvedValueOnce(problem(401, { title: "Unauthorized" }))
      .mockResolvedValueOnce(json(200, { ...auth("fresh"), user: { ...ME, platformAdmin: true } }))
      .mockResolvedValueOnce(json(200, []));
    await expect(apiFetch("/api/platform/tenants")).resolves.toEqual([]);
    expect(fetchMock.mock.calls[1][0]).toBe("/api/platform/auth/refresh");
    expect(fetchMock).toHaveBeenCalledTimes(3);
  });

  it("announces a consent-required answer so the privacy gate can show the consent screen", async () => {
    setAccessToken("t1");
    let told = 0;
    const off = onConsentRequired(() => told++);
    fetchMock
      .mockResolvedValueOnce(problem(403, { type: CONSENT_REQUIRED_TYPE, title: "Consent needed" }))
      .mockResolvedValueOnce(problem(403, { title: "Not allowed" }));
    const error = (await apiFetch("/api/me/children").catch((e: unknown) => e)) as ApiError;
    expect(error).toMatchObject({ status: 403, type: CONSENT_REQUIRED_TYPE });
    await apiFetch("/api/users").catch(() => undefined);
    off();
    expect(told).toBe(1);
  });

  it("does not refresh for unauthenticated endpoints such as login", async () => {
    fetchMock.mockResolvedValueOnce(problem(401, { title: "Unauthorized", detail: "Bad credentials" }));
    const error = (await api
      .login({ schoolCode: "abc", email: "a@b.co", password: "x" })
      .catch((e: unknown) => e)) as ApiError;
    expect(error.status).toBe(401);
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(authHeader(0)).toBeUndefined();
  });

  it("parses problem+json into a typed ApiError", async () => {
    setAccessToken("t1");
    fetchMock.mockResolvedValueOnce(
      problem(400, {
        type: "https://akshara.app/problems/validation",
        title: "Invalid request",
        detail: "Some fields are invalid.",
        errors: { email: "must be a well-formed email address", ignored: 42 },
      }),
    );
    const error = (await apiFetch("/api/users", { method: "POST", body: {} }).catch(
      (e: unknown) => e,
    )) as ApiError;
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({
      status: 400,
      title: "Invalid request",
      detail: "Some fields are invalid.",
      errors: { email: "must be a well-formed email address" },
      type: "https://akshara.app/problems/validation",
    });
  });

  it("falls back to the HTTP status when the body is not JSON", async () => {
    const res = new Response("<html>bad gateway</html>", {
      status: 502,
      statusText: "Bad Gateway",
      headers: { "content-type": "text/html" },
    });
    const error = await parseErrorResponse(res);
    expect(error.status).toBe(502);
    expect(error.title).toBe("Bad Gateway");
    expect(error.detail).toBeUndefined();
  });

  it("turns network failures into ApiError with status 0", async () => {
    fetchMock.mockRejectedValueOnce(new TypeError("fetch failed"));
    const error = (await apiFetch("/api/roles").catch((e: unknown) => e)) as ApiError;
    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(0);
  });

  it("returns undefined for 204 No Content", async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));
    await expect(api.logout()).resolves.toBeUndefined();
  });

  it("serialises JSON bodies", async () => {
    setAccessToken("t1");
    fetchMock.mockResolvedValueOnce(json(201, { id: "u2" }));
    await api.createUser({ name: "N", email: "n@s.edu", password: "0123456789", roles: ["TEACHER"] });
    const init = fetchMock.mock.calls[0][1]!;
    expect(init.method).toBe("POST");
    expect(JSON.parse(String(init.body))).toEqual({
      name: "N",
      email: "n@s.edu",
      password: "0123456789",
      roles: ["TEACHER"],
    });
    expect((init.headers as Record<string, string>)["Content-Type"]).toBe("application/json");
  });
});

describe("refreshSession", () => {
  it("ignores a refresh result that lands after a new sign-in", async () => {
    let release: (res: Response) => void = () => {};
    fetchMock.mockReturnValueOnce(new Promise<Response>((resolve) => (release = resolve)));
    const pending = refreshSession();
    setAccessToken("signed-in-meanwhile");
    release(problem(401, { title: "Unauthorized" }));
    await expect(pending).resolves.toBeNull();
    expect(getAccessToken()).toBe("signed-in-meanwhile");
  });
});

describe("ids in API paths", () => {
  it("encodes an id once, even when the route param is still percent-encoded", () => {
    expect(pathSegment("0f8c1c1e-7d0b-4b8f-9d2a-1b2c3d4e5f60")).toBe("0f8c1c1e-7d0b-4b8f-9d2a-1b2c3d4e5f60");
    expect(pathSegment("a;b")).toBe("a%3Bb");
    expect(pathSegment("a%3Bb")).toBe("a%3Bb");
    expect(pathSegment("zzz%27%22%3Cb%3E")).toBe("zzz'%22%3Cb%3E");
    expect(pathSegment("2025-26")).toBe("2025-26");
    // Not valid percent-encoding: encoded as typed.
    expect(pathSegment("%E0%A4")).toBe("%25E0%25A4");
  });

  it("answers a malformed id as not found without calling the API or touching the session", async () => {
    setAccessToken("t1");
    const listener = vi.fn();
    const off = onSessionChange(listener);
    try {
      await expect(api.getStudent("a%3Bb")).rejects.toMatchObject({ status: 404 });
      await expect(api.getStudent("%E0%A4")).rejects.toMatchObject({ status: 404 });
      expect(fetchMock).not.toHaveBeenCalled();
      expect(listener).not.toHaveBeenCalled();
      expect(getAccessToken()).toBe("t1");
    } finally {
      off();
    }
  });

  it("only looks at the path, not the query", () => {
    expect(isUnreachablePath("/api/staff/a%3Bb")).toBe(true);
    expect(isUnreachablePath("/api/staff/a%2Fb/payslips")).toBe(true);
    expect(isUnreachablePath("/api/students?q=1%2F2")).toBe(false);
    expect(isUnreachablePath("/api/staff/abc")).toBe(false);
  });
});
