# Phase 0 API

All endpoints live under `/api` and speak JSON. Errors use RFC 9457 problem details:
`{ "type", "title", "status", "detail", "errors"?: { field: message } }`.

Authenticated calls send `Authorization: Bearer <accessToken>`. Access tokens last 15 minutes.
The refresh token is an httpOnly, `SameSite=Strict` cookie named `refresh_token`, scoped to `/api/auth`.

## Public

| Method | Path | Body | Success |
| --- | --- | --- | --- |
| POST | `/api/public/signup` | `{ schoolName, schoolCode, board, city, adminName, adminEmail, password }` | `201 { tenantId, schoolCode, trialEndsAt }` |
| POST | `/api/auth/login` | `{ schoolCode, email, password }` | `200 AuthResponse` + refresh cookie |
| POST | `/api/auth/refresh` | (refresh cookie) | `200 AuthResponse` + rotated refresh cookie |
| POST | `/api/auth/logout` | (refresh cookie) | `204`, cookie cleared, token family revoked |
| POST | `/api/platform/auth/login` | `{ email, password }` | `200 AuthResponse` (platform admin, no refresh cookie) |

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
Repeated failures return `429`.

Refresh rotates the cookie. A refresh with a cookie that was already replaced in the last 30 seconds (two
tabs refreshing together) returns `401` and leaves the newer cookie alone; an older replaced cookie is
treated as stolen: every session in that chain ends and the event is audited. Refresh and logout reject
requests whose `Origin` header is not the web app.

Audit actions written in Phase 0: `school.created`, `user.created`, `auth.login`, `auth.login_failed`,
`auth.logout`, `auth.refresh_reused`.

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
