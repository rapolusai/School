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
- Failed sign-ins are throttled per address, school and email (in memory for now, Redis once the
  API runs as several tasks), and AWS WAF rate-limits the sign-in and sign-up endpoints.
- Every failure gives the same message, so it does not reveal whether a school or email exists.
