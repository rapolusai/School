# Production launch checklist (go / no-go)

Tick every line before the first real school goes on production. "Built" means the code or configuration is in
this repository; "To do" needs someone with the accounts, or a run against AWS.

> **Starting setup (owner decision, 2026-10-27): one server.** Production launches on a single EC2 server
> (t4g.medium, 4 GB, 30 GB gp3, ap-south-1, about $30-40 a month) with Docker Compose, Caddy for HTTPS on
> `app.rapolu.in`, daily `pg_dump` backups to S3: `infra/single-server/` and
> [`single-server.md`](single-server.md). The managed setup in `infra/terraform` (ECS, RDS, ALB, WAF) stays for
> later; the tables further down describe it. For the single server, tick this list instead of the
> "Infrastructure and delivery" table:
>
> | | Item | Status |
> |-|------|--------|
> | ☐ | EC2 instance, Elastic IP, security group (22 from the owner's IP only), IAM role, IMDSv2 hop limit 2 | To do (console) |
> | ☐ | Backup bucket (30-day expiry) and files bucket (versioned) | To do (console) |
> | ☐ | DNS: `app.rapolu.in` A record; SES DKIM, MAIL FROM and DMARC records for `rapolu.in` | To do |
> | ☐ | `.env` generated, REQUIRED values filled, a copy in the password manager | To do |
> | ☐ | First deploy healthy; Let's Encrypt certificate; Super Admin password changed | Built (`deploy.sh`); to do |
> | ☐ | Backup timer enabled; first dump in S3; restore drill done and timed (`restore.sh --drill`) | Built; to do |
> | ☐ | Uptime check, disk alarm, instance recovery alarm, billing budget | To do |
> | ☐ | A deploy and a `deploy.sh --rollback` rehearsed outside school hours | Built; to do |
> | ☑ | Compose stack tested locally: HTTPS, sign-in, Secure cookies, DB not exposed, backup and restore, memory under the e2e suite | Done 2026-10-27 |

## Accounts and decisions (owner)

| | Item | Status |
|-|------|--------|
| ☐ | AWS accounts for staging and production, billing alerts set | To do |
| ☐ | Domain; DNS in Route 53; ACM certificates for `staging.` and `app.` | To do |
| ☐ | Terraform state buckets created; every `REPLACE` value in `staging.tfvars` and `prod.tfvars` filled | To do |
| ☐ | GitHub: protected `main` branch; `staging` and `prod` environments (prod with required reviewers) and their variables | To do |
| ☐ | Razorpay account with KYC; test keys on staging, live keys on prod, webhook configured | To do |
| ☐ | SES: DKIM records in DNS, production access granted | To do |
| ☐ | SMS / WhatsApp provider chosen, DLT entity, sender id and templates approved | To do (no code yet) |
| ☐ | Terms of service, privacy policy, data processing agreement for schools, GST registration | To do |

## Infrastructure and delivery

| | Item | Status |
|-|------|--------|
| ☐ | Terraform plan reviewed and applied for staging, then prod | Built, not applied |
| ☐ | Deploy workflow: a merge to `main` reaches staging; a deliberately broken release rolls itself back | Built, not run |
| ☐ | Alarm emails confirmed; uptime check green; dashboard shows traffic | Built, not applied |
| ☐ | Firewall: managed rules, rate limits, uploads allowed; ZAP baseline scan clean | Built; scan to run |
| ☐ | Backups: 30 days, copy in ap-south-2; restore drill done and timed (`restore-database.md`) | Built; drill to do |
| ☐ | Load test at production size; capacity report written; prod sized from it (`load-testing.md`) | Scripts built; run to do |
| ☐ | Penetration test by an outside firm, findings fixed | To do |

## Application

| | Item | Status |
|-|------|--------|
| ☑ | All modules of phases 1-3, tested (unit, integration with PostgreSQL, browser end-to-end) | Built |
| ☑ | Forgot password (emailed code) and change password | Built |
| ☑ | Uploaded files in S3; email through SES; Razorpay payments | Built (switched on per environment) |
| ☑ | Request size limits; double payments from two tabs prevented | Built |
| ☐ | SMS and WhatsApp senders | Waits for the provider |
| ☑ | Sign-in rate limits shared across API tasks (counted in PostgreSQL) and Super Admin sessions | Built |
| ☐ | S3 copy of files in a second region; removing a deleted school's files | Follow-up |
| ☐ | `demo_enabled = false` on prod; Super Admin password read from Secrets Manager and changed after first sign-in | At go-live |

## Pilot

| | Item | Status |
|-|------|--------|
| ☐ | 2-3 pilot schools onboarded with the Excel school import | To do |
| ☐ | Two weeks of daily checks: errors, payments matched to Razorpay settlements, messages delivered | To do |
| ☐ | Support contact and escalation path published to the schools | To do |
| ☐ | Go / no-go for opening public sign-up | To do |
