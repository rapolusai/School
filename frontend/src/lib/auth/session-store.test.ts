// @vitest-environment node
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { getAccessToken } from "../api";
import {
  __resetSessionForTests,
  bootstrapSession,
  getSessionState,
  login,
  logout,
} from "./session-store";

const ME = {
  id: "u1",
  name: "Asha Rao",
  email: "asha@school.edu",
  roles: ["SCHOOL_ADMIN"],
  permissions: ["dashboard.view"],
  platformAdmin: false,
  tenant: null,
};

const json = (status: number, body: unknown) =>
  new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });

const fetchMock = vi.fn<typeof fetch>();

async function settle() {
  for (let i = 0; i < 5; i++) await new Promise((resolve) => setTimeout(resolve, 0));
}

beforeEach(() => {
  __resetSessionForTests();
  fetchMock.mockReset();
  vi.stubGlobal("fetch", fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("session store", () => {
  it("restores the session from the refresh cookie on load", async () => {
    fetchMock.mockResolvedValueOnce(json(200, { accessToken: "a1", expiresIn: 900, user: ME }));
    bootstrapSession();
    bootstrapSession(); // StrictMode double-invoke must not refresh twice
    await settle();
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(getSessionState()).toMatchObject({ status: "authenticated", me: ME, accessToken: "a1" });
    expect(getAccessToken()).toBe("a1");
  });

  it("becomes anonymous when there is no session", async () => {
    fetchMock.mockResolvedValueOnce(json(401, { title: "Unauthorized" }));
    bootstrapSession();
    await settle();
    expect(getSessionState().status).toBe("anonymous");
  });

  it("logs in, then logs out via /api/auth/logout and forgets the token", async () => {
    fetchMock
      .mockResolvedValueOnce(json(200, { accessToken: "a2", expiresIn: 900, user: ME }))
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    await login(" Sunrise ", "asha@school.edu ", "secret-password");
    expect(JSON.parse(String(fetchMock.mock.calls[0][1]?.body))).toEqual({
      schoolCode: "sunrise",
      email: "asha@school.edu",
      password: "secret-password",
    });
    expect(getSessionState().status).toBe("authenticated");

    await logout();
    expect(fetchMock.mock.calls[1][0]).toBe("/api/auth/logout");
    expect(fetchMock.mock.calls[1][1]).toMatchObject({ method: "POST", credentials: "include" });
    expect(getSessionState().status).toBe("anonymous");
    expect(getAccessToken()).toBeNull();
  });

  it("clears the local session even if the logout call fails", async () => {
    fetchMock
      .mockResolvedValueOnce(json(200, { accessToken: "a3", expiresIn: 900, user: ME }))
      .mockRejectedValueOnce(new TypeError("offline"));
    await login("sunrise", "asha@school.edu", "secret-password");
    await logout();
    expect(getSessionState().status).toBe("anonymous");
  });
});
