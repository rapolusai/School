# Production launch checklist (go / no-go)

Tick every line before the first real school goes on production. "Built" means the code or configuration is in
this repository; "To do" needs someone with the accounts, or a run against AWS.

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
