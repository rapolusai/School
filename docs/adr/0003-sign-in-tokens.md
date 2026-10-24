# 3. Short access tokens and rotating refresh cookies

Status: accepted (Phase 0)

## Decision
- Passwords are hashed with Argon2id.
- Sign-in returns a 15-minute signed access token (HS256, key from Secrets Manager) that the web
  app keeps in memory only.
- A refresh token lives in an `httpOnly`, `Secure`, `SameSite=Strict` cookie scoped to `/api/auth`.
  Only its SHA-256 hash is stored. Each refresh replaces it; presenting an already-replaced token
  (outside a 30-second grace for two tabs refreshing together) revokes the whole chain and is
  written to the audit trail.
- Refresh and sign-out also reject requests whose `Origin` is not the web app.
- Failed sign-ins are throttled per address, school and email in PostgreSQL (`platform.rate_limit`, one atomic
  upsert per failure), so the limit holds when the API runs as several tasks without adding Redis; AWS WAF also
  rate-limits the sign-in and sign-up endpoints.
- Super Admins get the same kind of rotating cookie (`platform_refresh_token`, scoped to `/api/platform/auth`,
  stored in `platform.platform_refresh_token`); school and platform cookies and tokens are refused by each other's
  endpoints.
- Every failure gives the same message, so it does not reveal whether a school or email exists.
