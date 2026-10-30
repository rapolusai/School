# Phase 0 API

All endpoints live under `/api` and speak JSON. Errors use RFC 9457 problem details:
`{ "type", "title", "status", "detail", "errors"?: { field: message } }`.

Common client mistakes, on any endpoint:

- `400` — a missing required query parameter (`errors.<param>: "This is required."`), a query or path value of the
  wrong type (`errors.<param>`; a malformed record id in the path is `404`, below), or a body that is not JSON or
  does not fit the form. A JSON number with a fraction sent to a whole-number field (amounts in paise, quantities,
  counts) is refused, never truncated: `errors.<field>: "Enter a whole number."` (nested fields as `lines[0].quantity`).
- `404` — a record id in the path that is not a UUID (it can name no record, like an id that does not exist).
- `400` `Bad request` — a path the request firewall refuses (a `;`, an encoded `/`, `\` or `%`, and the like); it is
  never answered as `401`, so the session stays valid.
- `405` — the path exists but not for this method; the `Allow` header lists the methods it takes.
- `415` — the body's `Content-Type` is not one the endpoint accepts (JSON endpoints take `application/json`, uploads
  `multipart/form-data`); the `Accept` header lists them.
- `409` with title `Changed by someone else` — another request changed the same record at the same moment (a stale
  version, a lock or serialization failure). Nothing was saved; reload and try again.

Authenticated calls send `Authorization: Bearer <accessToken>`. Access tokens last 15 minutes.
The refresh token is an httpOnly, `SameSite=Strict` cookie named `refresh_token`, scoped to `/api/auth`.
A Super Admin's refresh token is a separate httpOnly, `SameSite=Strict` cookie named
`platform_refresh_token`, scoped to `/api/platform/auth` (see [Super Admin sessions](#super-admin-sessions)).

## Public

| Method | Path | Body | Success |
| --- | --- | --- | --- |
| POST | `/api/public/signup` | `{ schoolName, schoolCode, board, city, adminName, adminEmail, password, plan? }` (`plan`: `STARTER` when left out) | `201 { tenantId, schoolCode, trialEndsAt }` |
| GET | `/api/public/plans` | — | `200 PlanView[]` (the plan catalogue; `schools` is null) |
| POST | `/api/auth/login` | `{ schoolCode, email, password }` | `200 AuthResponse` + refresh cookie |
| POST | `/api/auth/refresh` | (refresh cookie) | `200 AuthResponse` + rotated refresh cookie |
| POST | `/api/auth/logout` | (refresh cookie) | `204`, cookie cleared, token family revoked |
| POST | `/api/platform/auth/login` | `{ email, password }` | `200 AuthResponse` (platform admin) + platform refresh cookie |
| POST | `/api/platform/auth/refresh` | (platform refresh cookie) | `200 AuthResponse` + rotated platform refresh cookie |
| POST | `/api/platform/auth/logout` | (platform refresh cookie) | `204`, cookie cleared, token family revoked |
| POST | `/api/auth/password-reset` | `{ schoolCode, email }` | `202 { message }`, the same whether or not the school and email exist |
| POST | `/api/auth/password-reset/confirm` | `{ schoolCode, email, code, newPassword }` | `204`; every session of that person ends |
| POST | `/api/auth/password` (signed in, school users) | `{ currentPassword, newPassword }` | `200 AuthResponse` + new refresh cookie; other sessions end |

**Forgot password.** A six-digit code goes to the person's sign-in email through the notifications outbox
(template `identity.password_reset`). Only a hash of the code is kept, salted with the request id; it lasts 15
minutes and 5 tries, and asking again replaces it. A wrong, used, replaced or expired code is
`400 { errors.code }` that says nothing about whether the account exists. The message log never shows the
code's text (a real delivery wipes it; `akshara.notifications.show-simulated-secrets=true` shows simulated
codes on a development machine only), and quiet hours do not hold it back. Requests are rate-limited per IP and
per school and email; in AWS the WAF's strict login limit also covers `/api/auth/password-reset*`. Staff
imported without a password set their first one the same way.

**Change password.** The current password must be right (`400 { errors.currentPassword }`; wrong guesses count
towards a rate limit), and the new one must differ (`400 { errors.newPassword }`). Super Admins get `403`.

`schoolCode`: 3–40 characters, lowercase letters, digits and hyphens, starting with a letter. A few
codes are reserved (`admin`, `api`, `app`, `www`, `platform`, …) and answer `409` like a taken code.
Self-service sign-up starts the school on a 14-day trial of the `STARTER` plan.
`password`: at least 10 characters.
`board`: one of `CBSE`, `ICSE`, `STATE`, `IB`, `CAMBRIDGE`.

```ts
type AuthResponse = { accessToken: string; expiresIn: number; user: Me };

type Me = {
  id: string;
  name: string;
  email: string;
  roles: string[];          // role codes, e.g. ["SCHOOL_ADMIN"]
  permissions: string[];    // e.g. ["users.read", "users.manage"]
  platformAdmin: boolean;
  tenant: null | {
    id: string; name: string; code: string;
    status: "TRIAL" | "ACTIVE" | "PAST_DUE" | "SUSPENDED";
    plan: "STARTER" | "GROWTH" | "ENTERPRISE";
    board: string; city: string | null;
    trialEndsAt: string | null;   // ISO-8601 instant
  };
};
```

Login failures return `401` with a generic message (no hint whether the school, email or password was wrong).
Repeated failures return `429`: after `akshara.auth.max-failed-logins` (10) failures for one IP address, school
code and email (IP address and email for the Super Admin) the key is refused until the
`akshara.auth.failed-login-window` (5 minutes) that started with its first failure is over; a successful sign-in
clears it. Each attempt is counted before its password is checked, so parallel guesses sent at once cannot all get
past the limit (at most 10 are checked; the rest get `429`). The counts are kept in PostgreSQL (`platform.rate_limit`, migration `V29`), so the limit holds across every
API instance: each failure is one atomic `insert ... on conflict do update` on the SHA-256 of the key (no email or IP
address is stored in clear), and rows whose window is over are deleted every 500 hits. The public limits (enquiry
form, certificate verification, privacy notice, GPS devices) use the same table.

Refresh rotates the cookie. A refresh with a cookie that was already replaced in the last 30 seconds (two
tabs refreshing together, or a response lost because the page was left or reloaded mid-refresh) succeeds
with another new cookie in the same chain and leaves the earlier new cookie valid too, unless the chain has
been signed out; an older replaced cookie is treated as stolen: every session in that chain ends and the
event is audited. Refresh and logout reject requests whose `Origin` header is not the web app, however the path is spelled (`/api/auth/refres%68` too).

### Super Admin sessions

A Super Admin's sign-in sets `platform_refresh_token` (httpOnly, `SameSite=Strict`, `Secure` outside
local development, path `/api/platform/auth`, 12 hours by default: `akshara.auth.platform-refresh-token-ttl`), so a
reload of the web app keeps them signed in. It rotates exactly like a school user's cookie (same 30-second grace; an
older replaced cookie ends the whole chain and is logged), and is stored hashed in `platform.platform_refresh_token`
(no school, so no `tenant_id`). The two sessions never cross:

- each cookie is sent only to its own path, and the values differ in shape (`<schoolId>.<random>` for a school,
  `p.<random>` for the platform), so a platform cookie is refused (`401`) by `/api/auth/refresh` and a school cookie
  by `/api/platform/auth/refresh`, whatever name it is sent under;
- a platform access token has no school and only `platform.admin`, so school endpoints answer `403`; a school access
  token gets `403` from `/api/platform/**`;
- signing in as one clears the other cookie in the same response, so the next reload restores the session that
  signed in last.

The web app restores a session on load by trying `/api/auth/refresh` and then `/api/platform/auth/refresh` (the
other way round on `/app/platform` pages); once it knows which one it holds it refreshes and signs out only there.

Audit actions written in Phase 0: `school.created`, `user.created`, `auth.login`, `auth.login_failed`,
`auth.logout`, `auth.refresh_reused`; and for passwords `auth.password_reset_requested`,
`auth.password_reset_wrong_code` and `user.password_set` (with `via`: `password_reset` or `self`).

## School (tenant) endpoints

| Method | Path | Permission | Success |
| --- | --- | --- | --- |
| GET | `/api/me` | any signed-in user | `200 Me` |
| GET | `/api/users` | `users.read` | `200 UserSummary[]` |
| GET | `/api/users/{id}` | `users.read` | `200 UserSummary`, `404` if not in the caller's school |
| POST | `/api/users` | `users.manage` | `201 UserSummary`; body `{ name, email, password, roles: string[] }` |
| GET | `/api/roles` | `roles.read` | `200 Role[]` |
| GET | `/api/audit-events?limit=50` | `audit.read` | `200 AuditEvent[]`, newest first |

```ts
type UserSummary = { id: string; name: string; email: string; roles: string[];
  status: "ACTIVE" | "DISABLED"; lastLoginAt: string | null; createdAt: string };
type Role = { code: string; name: string; permissions: string[] };
type AuditEvent = { id: string; at: string; actorName: string | null; action: string;
  entityType: string | null; entityId: string | null; details: Record<string, unknown> };
```

## Platform (Super Admin) endpoints

| Method | Path | Success |
| --- | --- | --- |
| GET | `/api/platform/tenants` | `200 TenantSummary[]` |
| POST | `/api/platform/tenants` | `201 TenantSummary`; body `{ schoolName, schoolCode, board, city, plan, adminName, adminEmail, password }`. The school starts `ACTIVE` with no trial. |

```ts
type TenantSummary = { id: string; name: string; code: string; status: string; plan: string;
  board: string; city: string | null; createdAt: string; trialEndsAt: string | null; userCount: number };
```

## Roles and permissions seeded for every school

| Role code | Name | Permissions |
| --- | --- | --- |
| `SCHOOL_ADMIN` | School Admin | all school permissions |
| `PRINCIPAL` | Principal | `dashboard.view users.read roles.read audit.read students.read attendance.read fees.read exams.manage notices.send` |
| `TEACHER` | Teacher | `dashboard.view students.read attendance.mark attendance.read exams.manage notices.send` |
| `ACCOUNTANT` | Accountant | `dashboard.view students.read fees.read fees.collect` |
| `FRONT_OFFICE` | Front office | `dashboard.view students.read` |
| `PARENT` | Parent | `dashboard.view child.view` |
| `STUDENT` | Student | `dashboard.view` |

All school permissions: `dashboard.view users.read users.manage roles.read audit.read settings.manage students.read students.manage attendance.mark attendance.read fees.read fees.collect exams.manage notices.send child.view`.
Platform admins carry the single permission `platform.admin`.
