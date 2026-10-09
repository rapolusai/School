# Akshara frontend

Next.js 16 (App Router, TypeScript strict, Tailwind CSS 4) for the Phase 0 school admin app.
The API contract lives in [`../docs/api/phase-0.md`](../docs/api/phase-0.md).

Browser calls go to `/api/*` on this origin and are proxied to the Spring Boot API
(`API_URL`, default `http://localhost:8080`). That keeps the httpOnly refresh cookie
same-origin. The access token is held in memory only.

## Develop

```bash
npm install
API_URL=http://localhost:8080 npm run dev   # http://localhost:3000
```

## Check

```bash
npm run lint        # ESLint (next core-web-vitals + typescript), zero warnings allowed
npm run typecheck   # next typegen && tsc --noEmit
npm test            # Vitest + Testing Library (jsdom)
```

## Build

```bash
API_URL=http://backend:8080 npm run build   # .next/standalone
```

`next.config.ts` is evaluated at build time and baked into the standalone server, so the
`/api` rewrite target comes from `API_URL` **when you build**, not when you start.

### Docker

```bash
docker build --build-arg API_URL=http://backend:8080 -t akshara-frontend .
docker run -p 3000:3000 akshara-frontend
```

The image runs `node server.js` as a non-root user on `PORT=3000`.

## Layout

- `src/app` – routes: `/login`, `/signup`, `/app/*` (signed-in shell), 404
- `src/components` – shell (sidebar ≥1024px, icon rail 640–1023px, bottom bar <640px), views, UI
- `src/lib` – `api.ts` (fetch + refresh-once + problem+json), `auth/`, `permissions.ts`
  (navigation model, `navFor(me)`), `i18n/` (en, hi), `format.ts` (₹ and en-IN dates)
