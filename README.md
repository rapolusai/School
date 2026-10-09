# Akshara School Cloud

A multi-tenant school management platform for Indian schools: one product that runs admissions, staff,
academics, fees, parent and student portals, transport, library, communication and analytics for
many schools, each kept strictly private from the others. "Akshara" is a working name.

This repository is being built in phases. **Phase 0 lays the foundations:** school sign-up,
sign-in, people and roles, a per-school audit trail, the Super Admin console, the design system, and
the infrastructure and CI the later modules build on. **Phase 1** adds school setup (academic years,
classes and sections, subjects, the school profile) and student records: admission with parents'
contacts, transfers, year-end promotion, CSV import of up to 2,000 students, and sign-ins that show
parents their children and students their class.

| Area | Where |
| --- | --- |
| API (Spring Boot 4, Java 21, PostgreSQL) | [`backend/`](backend) |
| Web app (Next.js 16, React 19, Tailwind 4) | [`frontend/`](frontend) |
| End-to-end tests (Playwright) | [`e2e/`](e2e) |
| AWS infrastructure (Terraform, not yet applied) | [`infra/terraform/`](infra/terraform) |
| API contract | [`docs/api/phase-0.md`](docs/api/phase-0.md), [`docs/api/phase-1.md`](docs/api/phase-1.md) |
| Architecture decisions | [`docs/adr/`](docs/adr) |
| Clickable prototype of the full product | [`docs/prototype/index.html`](docs/prototype/index.html) |

## How school data is kept apart
Every school-owned row carries the school's id and PostgreSQL row-level security only shows rows of
the school the signed-in user belongs to. The API connects with a database role that cannot switch
that off, and refuses to start if it could. See [ADR 2](docs/adr/0002-tenant-isolation.md).

## Run it locally
Needs Docker. Then:

```sh
cp .env.example .env      # fill in every value; JWT_SECRET needs 32+ characters
docker compose up --build
```

Open http://localhost:3000. Sign up a new school, or, with `DEMO_PASSWORD` set, sign in to school
code `demo` as `admin@demo.akshara.test` (also `principal@`, `teacher@`, `accounts@`, `frontoffice@`,
`parent@` and `student@` at `demo.akshara.test`) with that password. The demo school has two academic
years, classes LKG to 10 and about 60 students; the parent's dashboard shows Arjun (Class 5 A) and Diya
(Class 2 A), and the teacher is class teacher of Class 5 A.

### Working on one part
- API: `cd backend && mvn verify` runs unit and integration tests (Testcontainers starts PostgreSQL;
  Docker must be running). `mvn spring-boot:run` with the variables from `.env.example` starts it on
  port 8080.
- Web: `cd frontend && npm ci && npm run dev` (expects the API on http://localhost:8080).
  `npm run lint`, `npm run typecheck`, `npm test`, `npm run build`.
- End-to-end: see [`e2e/README.md`](e2e/README.md).

## Configuration
The API reads everything from environment variables (see `backend/src/main/resources/application.yml`).
In AWS they come from Secrets Manager; nothing secret is committed. Test accounts for shared
environments are stored in Secrets Manager, not in this repository or in chat.

## Deployment
Planned: AWS ECS Fargate in ap-south-1 (Mumbai) behind an ALB with WAF, RDS PostgreSQL Multi-AZ,
images in ECR, deployed by GitHub Actions through OIDC. The Terraform code is in
[`infra/terraform/`](infra/terraform); **nothing has been applied** and no AWS resources exist yet.
Applying it needs the owner's explicit approval.
