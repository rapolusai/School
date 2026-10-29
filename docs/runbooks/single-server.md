# Production on a single server

**Owner decision, 2026-10-27:** production starts on **one AWS server** (4 GB RAM, 30 GB disk, about $30-40 a
month) instead of the managed setup in `infra/terraform` (ECS, RDS, ALB, WAF: $450-600 a month for prod). The
Terraform setup stays in the repository, unapplied, for when the business needs it (see "Moving to the managed
setup" at the end).

Everything for the server is in `infra/single-server/`:

| File | What it is |
|------|------------|
| `docker-compose.prod.yml` | PostgreSQL 17, the API, the web app and Caddy (automatic HTTPS). Only ports 80 and 443 are published. Memory limits, health checks, restart policies, log rotation. |
| `caddy/Caddyfile` | HTTPS for `APP_DOMAIN`; `/api/*` to the API, `/actuator*` refused, everything else to the web app; HSTS; 30 MB request limit. |
| `.env.prod.example` | Every setting, commented. Copied to `.env` (never committed). |
| `generate-secrets.sh` | Creates `.env` with strong random secrets, mode 600. |
| `compose.sh` | `docker compose` with the right files: use it for everything (`./compose.sh ps`, `logs -f api`, ...). |
| `deploy.sh` | Build and roll out a git ref, keep the previous images, automatic rollback when the new version is unhealthy. |
| `backup/backup.sh`, `backup/restore.sh` | Daily `pg_dump` to S3; restore drill and live restore. |
| `systemd/akshara-backup.{service,timer}`, `backup/akshara-backup.cron` | Runs the backup daily at 03:00 India time (use the timer or the cron file). |
| `postgres/init-roles.sh` | Creates the `akshara_owner` and `akshara_app` roles on first start (with `infra/terraform/bootstrap/db-roles.sql`). |
| `monitoring/cloudwatch-agent.json` | Optional CloudWatch agent config: disk, memory and swap metrics for alarms. |

```
Internet ──80/443──▶ Caddy ──/api/*──▶ api (Spring Boot, :8080) ──▶ db (PostgreSQL 17, volume db-data)
                       └────other────▶ web (Next.js, :3000)
                     (one private Docker network; db, api and web publish no ports)
```

Host names: the app runs on **`app.rapolu.in`** (`APP_DOMAIN`); the root `rapolu.in` stays free for a landing
site. To serve the app on the root later, set `APP_DOMAIN=rapolu.in`, add its A record and restart Caddy.

## Memory budget (4 GB)

| Container | Limit | Tuning | Measured idle / peak under the e2e suite (local test, end of page) |
|-----------|-------|--------|------------------|
| db | 768 MB | `shared_buffers=192MB`, `work_mem=4MB`, `maintenance_work_mem=64MB`, `max_connections=40`, `effective_cache_size=1GB` | 74 / 187 MB |
| api | 1024 MB | JVM heap 60% of the limit (~616 MB), metaspace ≤ 256 MB, code cache ≤ 96 MB, Serial GC, Hikari pool 10 | 574 / 755 MB |
| web | 384 MB | `NODE_OPTIONS=--max-old-space-size=256` | 62 / 153 MB |
| caddy | 128 MB | | 14 / 56 MB |
| **Total** | **2.3 GB** | | **0.7 / 1.15 GB** (sum of per-container peaks) |

That leaves about 1.5 GB for Ubuntu, Docker, backups and the image builds of a deploy, plus a 2 GB swap file. The
image's own default (`-XX:MaxRAMPercentage=75`) would give the API a 768 MB heap inside a 1 GB limit and leave
only ~256 MB for everything else in the JVM, which risks the kernel killing the container; the compose file
overrides it. `max_connections=40` covers the API pool (10) plus Flyway, a second API overlapping during a deploy,
`pg_dump`, a restore drill and admin sessions.

## 1. Launch the server (AWS console, ap-south-1 Mumbai)

Nothing in this repository creates AWS resources for this setup: these are console steps for the owner. (A small
Terraform module was considered, but could not be validated offline here, so it is not included.)

1. **S3 buckets** (S3 → Create bucket, region ap-south-1, *Block all public access* on, default encryption SSE-S3):
   - `akshara-prod-backups-<account-id>`: Management → Lifecycle rule "expire-30d", whole bucket,
     *Expire current versions of objects* after **30 days** (and *Delete expired object delete markers*). No versioning needed.
   - `akshara-prod-files-<account-id>` (uploaded files, `FILES_STORE=s3`): Properties → **Versioning on**;
     lifecycle rule *Permanently delete noncurrent versions* after **30 days**.
     Bucket policy denying non-TLS access:
     ```json
     {"Version":"2012-10-17","Statement":[{"Sid":"TlsOnly","Effect":"Deny","Principal":"*","Action":"s3:*",
       "Resource":["arn:aws:s3:::akshara-prod-files-<account-id>","arn:aws:s3:::akshara-prod-files-<account-id>/*"],
       "Condition":{"Bool":{"aws:SecureTransport":"false"}}}]}
     ```
2. **SES** (Amazon SES → Identities → Create identity → Domain `rapolu.in`, Easy DKIM RSA 2048, custom MAIL FROM
   domain `mail.rapolu.in`). Add the DNS records it shows (section 2), then *Request production access* (out of the
   sandbox) for transactional email. Until it is granted, keep `EMAIL_SENDER=simulated`.
3. **IAM role** (IAM → Roles → Create role → AWS service, EC2), name `akshara-prod-server`, with this inline policy
   (replace `<account-id>`), plus the managed policies `AmazonSSMManagedInstanceCore` (Session Manager, optional)
   and `CloudWatchAgentServerPolicy` (optional, for the disk alarm):
   ```json
   {
     "Version": "2012-10-17",
     "Statement": [
       {"Sid": "BackupsWrite", "Effect": "Allow",
        "Action": ["s3:PutObject", "s3:GetObject", "s3:ListBucket"],
        "Resource": ["arn:aws:s3:::akshara-prod-backups-<account-id>", "arn:aws:s3:::akshara-prod-backups-<account-id>/*"]},
       {"Sid": "Files", "Effect": "Allow",
        "Action": ["s3:PutObject", "s3:GetObject", "s3:DeleteObject", "s3:ListBucket"],
        "Resource": ["arn:aws:s3:::akshara-prod-files-<account-id>", "arn:aws:s3:::akshara-prod-files-<account-id>/*"]},
       {"Sid": "Email", "Effect": "Allow",
        "Action": ["ses:SendEmail", "ses:SendRawEmail"],
        "Resource": "*",
        "Condition": {"StringEquals": {"ses:FromAddress": "no-reply@rapolu.in"}}}
     ]
   }
   ```
   No `s3:DeleteObject` on the backup bucket: a compromised server cannot erase the backups (old ones expire by
   the lifecycle rule).
4. **Security group** `akshara-prod-server`: inbound **22/tcp from your own IP only** (`x.x.x.x/32`; or no SSH at
   all and use Session Manager), **80/tcp** and **443/tcp** from `0.0.0.0/0` and `::/0`, **443/udp** from anywhere
   (HTTP/3, optional). Outbound: all.
5. **EC2 instance** (EC2 → Launch instance):
   - AMI **Ubuntu Server 24.04 LTS, 64-bit (Arm)**; instance type **t4g.medium** (2 vCPU Graviton, 4 GiB).
     ARM is fine: the images are built on the server, and every base image (postgres, temurin, node, caddy,
     maven) is multi-arch. Burstable: watch CPU credits (Monitoring); switch to *unlimited* or m7g.medium if
     credits run out under real load.
   - Key pair: create one (ed25519), keep the `.pem` safe.
   - Network: default VPC, a public subnet, the security group above.
   - Storage: **30 GiB gp3**, encrypted (default KMS key).
   - Advanced: IAM instance profile `akshara-prod-server`; **Termination protection on**; **Metadata version
     V2 only** and **Metadata response hop limit 2** (the API runs in a container: with the default limit of 1 it
     cannot reach the instance role, and S3 uploads and SES fail).
6. **Elastic IP**: EC2 → Elastic IPs → Allocate → Associate with the instance. Note the address.
7. **Billing alarm**: Billing → Budgets → a monthly cost budget of, say, $50 with an email alert.

Lightsail (4 GB plan) also works with the same compose file, but it has no instance roles: the backup upload, S3
files and SES would need an IAM user's access keys in `.env` (`AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY` passed to
the API, and `aws configure` for the backup). EC2 is preferred for that reason.

## 2. DNS records for rapolu.in

Add these at the DNS provider of `rapolu.in` (Route 53 or the registrar). The root `rapolu.in` A record is left
for the landing site.

| Name | Type | Value | Why |
|------|------|-------|-----|
| `app.rapolu.in` | A | the Elastic IP | the app (Caddy gets its certificate for this name) |
| `<token1>._domainkey.rapolu.in` | CNAME | `<token1>.dkim.amazonses.com` | SES DKIM (three records, tokens shown by SES) |
| `<token2>._domainkey.rapolu.in` | CNAME | `<token2>.dkim.amazonses.com` | |
| `<token3>._domainkey.rapolu.in` | CNAME | `<token3>.dkim.amazonses.com` | |
| `mail.rapolu.in` | MX | `10 feedback-smtp.ap-south-1.amazonses.com` | SES custom MAIL FROM (bounces) |
| `mail.rapolu.in` | TXT | `"v=spf1 include:amazonses.com ~all"` | SPF for the MAIL FROM domain |
| `_dmarc.rapolu.in` | TXT | `"v=DMARC1; p=none; rua=mailto:dmarc@rapolu.in"` | DMARC: start with `p=none`, move to `p=quarantine` after a few weeks of clean reports |
| `rapolu.in` | CAA | `0 issue "letsencrypt.org"` (optional) | only if you want to restrict certificate issuers; add the landing site's CA too |

If `rapolu.in` itself already has an SPF record for another mail service, leave it; SES's SPF lives on
`mail.rapolu.in`. Use a short TTL (300 s) on `app.rapolu.in` so a later move is quick. The A record must resolve
before the first start, or Caddy cannot get a certificate (it retries on its own).

## 3. Prepare the server (once)

SSH in (`ssh -i key.pem ubuntu@<elastic-ip>`), then:

```bash
sudo timedatectl set-timezone UTC
sudo apt-get update && sudo apt-get -y upgrade && sudo apt-get -y install unattended-upgrades git curl unzip

# 2 GB swap: headroom for image builds and spikes (the containers have hard limits; swap keeps the host alive)
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile && sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-swappiness.conf && sudo sysctl --system

# Docker Engine + compose + buildx from Docker's repository (https://docs.docker.com/engine/install/ubuntu/)
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu        # log out and back in

# AWS CLI v2 (ARM build); it uses the instance role, no keys
curl -fsSL https://awscli.amazonaws.com/awscli-exe-linux-aarch64.zip -o /tmp/awscli.zip
unzip -q /tmp/awscli.zip -d /tmp && sudo /tmp/aws/install && aws sts get-caller-identity   # shows the role

# The code
sudo mkdir -p /opt/akshara && sudo chown ubuntu: /opt/akshara
git clone https://github.com/rapolusai/School.git /opt/akshara    # a deploy key or token if the repo is private
```

## 4. Configure

```bash
cd /opt/akshara/infra/single-server
./generate-secrets.sh          # creates .env (mode 600) with random database, JWT, sandbox and admin secrets
nano .env                      # fill in every REQUIRED value
```

Check at least: `APP_DOMAIN=app.rapolu.in`, `ACME_EMAIL`, `PLATFORM_ADMIN_EMAIL`, `FILES_S3_BUCKET` (or
`FILES_STORE=database`), `BACKUP_S3_BUCKET`, `EMAIL_FROM=no-reply@rapolu.in`, the `BILLING_SELLER_*` values.
Production keeps `COOKIE_SECURE=true` and `DEMO_ENABLED=false` (fixed in the compose file) and starts with the
sandbox gateways (`PAYMENTS_GATEWAY=sandbox`, `BILLING_PAYMENTS_GATEWAY=sandbox`). Keep a copy of `.env` in a
password manager: without it a backup cannot be restored with the same passwords.

## 5. First start

```bash
cd /opt/akshara
infra/single-server/deploy.sh main      # builds both images on the server (10-20 min the first time), starts all
```

The API creates the schema (Flyway) and the Super Admin on its first start. Check:

```bash
infra/single-server/compose.sh ps                     # all four "healthy"
curl -sI https://app.rapolu.in/login | head -1        # HTTP/2 200, a Let's Encrypt certificate
infra/single-server/compose.sh logs caddy | grep -i certificate
```

**Super Admin:** open `https://app.rapolu.in/login`, switch to the Super Admin sign-in, use
`PLATFORM_ADMIN_EMAIL` and `PLATFORM_ADMIN_PASSWORD` from `.env`, and **change the password** right away. You may
then empty `PLATFORM_ADMIN_PASSWORD` in `.env` (it is only used when that admin does not exist).

## 6. Deploy, update and roll back

```bash
cd /opt/akshara
infra/single-server/deploy.sh v1.4.0       # a tag (recommended), a branch or a commit
infra/single-server/deploy.sh --status     # running version and history
infra/single-server/deploy.sh --rollback   # back to the previous version (its images are still on the server)
```

`deploy.sh` checks out the ref, builds `akshara-api:<sha>` and `akshara-web:<sha>` while the current version
keeps serving, takes a local backup, switches `release.env` to the new tag and recreates the API and web
containers (the API runs new migrations as it starts). Expect **one to two minutes of 502 errors** while the API
starts: deploy outside school hours. It then smoke-tests `/login` through Caddy and the API's readiness, and if
that fails it rolls back to the previous tag by itself. The last 3 versions' images are kept; older ones and the
build cache beyond 3 GB are pruned.

As with the AWS pipeline, a rollback changes the code, never the database: migrations must work with the
previous version too (`release-and-rollback.md`). If a release damaged data, restore (section 7).

Settings only (`.env` changed, same code): `infra/single-server/compose.sh up -d` recreates what changed.

Building on the server is the simplest option (no registry, native ARM). If builds ever get too slow or
memory-hungry for the 4 GB server, build in GitHub Actions for `linux/arm64`, push to GHCR or ECR, and replace
the `build:` sections with the pushed `image:` names.

## 7. Backups and restore

**Install the daily backup** (03:00 India time):

```bash
sudo mkdir -p /var/backups/akshara && sudo chown ubuntu: /var/backups/akshara
sudo cp /opt/akshara/infra/single-server/systemd/akshara-backup.* /etc/systemd/system/   # runs as "ubuntu"
sudo systemctl daemon-reload && sudo systemctl enable --now akshara-backup.timer
sudo systemctl start akshara-backup.service && journalctl -u akshara-backup -n 20    # first run now
systemctl list-timers akshara-backup.timer
```

(Or the cron file `backup/akshara-backup.cron` in `/etc/cron.d/`, not both.)

What it does: `pg_dump -Fc -Z6` as the `postgres` superuser (so row-level security hides nothing), checks the
dump reads back (`pg_restore --list`), writes a `.sha256`, uploads both to
`s3://$BACKUP_S3_BUCKET/db/YYYY/MM/` with the instance role, and deletes local copies older than 7 days
(`BACKUP_KEEP_DAYS`). The bucket's lifecycle rule deletes S3 copies after 30 days. Set `BACKUP_PING_URL` to a
healthchecks.io (or similar) check URL to be alerted when a night's backup did not happen.

**Uploaded files:** with `FILES_STORE=s3` (recommended) they live in the versioned files bucket: deleted or
overwritten files can be recovered for 30 days from their old versions; the dump does not contain them. With
`FILES_STORE=database` the files are rows in PostgreSQL and every dump includes them (bigger dumps, bigger
database). There is no local-disk files store. To move from `database` to `s3` later, set the bucket, start once
with `FILES_COPY_TO_S3=true`, then set it back to `false`.

**Restore drill (monthly, takes minutes, touches nothing live):**

```bash
cd /opt/akshara/infra/single-server
aws s3 ls s3://akshara-prod-backups-<account-id>/db/$(date -u +%Y/%m)/
backup/restore.sh --drill s3://akshara-prod-backups-<account-id>/db/2026/11/akshara-20261103T213012Z.dump
```

It restores into a throwaway PostgreSQL container (no network, 512 MB), counts the rows of every table, compares
them with the live database (tables written since the dump differ, which is expected) and removes the container.
Write the date and the restore time in the launch checklist.

**Live restore (the database must go back to a dump):**

```bash
backup/restore.sh --live /var/backups/akshara/akshara-20261103T213012Z.dump      # or an s3:// URL
```

It asks for the domain as confirmation, takes a safety dump of the current database, restores the dump into a
side database `akshara_restoring` while the live one keeps serving, and only if that succeeds stops the API and
web, swaps the databases and starts everything again. A truncated or corrupt dump stops before anything live is
touched ("the live database was not touched"). The side copy needs free disk for a second copy of the database. Everything since the dump is lost (RPO up to 24
hours; take an extra `backup/backup.sh` before risky operations). To recover a few rows instead, restore the dump into a side
container by hand (as `--drill` does) and copy the rows over with `psql`.

**Losing the server entirely:** launch a new one (sections 1, 3), restore `.env` from the password manager,
`deploy.sh <the tag that ran>`, then `restore.sh --live s3://...latest dump`, and move the Elastic IP to it.

## 8. Monitoring basics

- **Uptime:** a Route 53 health check on `https://app.rapolu.in/login` (HTTPS, string match optional) with a
  CloudWatch alarm to your email (about $1 a month), or a free external monitor (UptimeRobot, Better Stack).
- **Server recovery:** CloudWatch alarm on `StatusCheckFailed_System` for the instance with the action *Recover
  this instance* (EC2 → instance → Monitoring → Create alarm).
- **Disk:** the single biggest risk on 30 GB (images, logs, dumps). Install the CloudWatch agent
  (`sudo snap install amazon-cloudwatch-agent` or the `.deb` for arm64), load `monitoring/cloudwatch-agent.json`
  (`sudo /opt/aws/amazon-cloudwatch-agent/bin/amazon-cloudwatch-agent-ctl -a fetch-config -m ec2 -s -c
  file:/opt/akshara/infra/single-server/monitoring/cloudwatch-agent.json`) and alarm on
  `Akshara/SingleServer disk_used_percent > 80` and `mem_used_percent > 90`. Without the agent:
  `df -h /` and `docker system df` weekly; `docker builder prune` and `docker image prune` free space.
- **CPU credits:** alarm on `CPUCreditBalance < 50` (t4g is burstable).
- **Backups:** `BACKUP_PING_URL` (above) and `journalctl -u akshara-backup`.
- **Logs:** `./compose.sh logs -f api` (`web`, `caddy`, `db`). Docker keeps 5 × 10 MB per container.
  The app's own health page for the Super Admin shows the version and the database.
- **Certificates:** Caddy renews by itself; `./compose.sh logs caddy | grep -i error` if the site shows a
  certificate warning.

## 9. Security notes (differences from the AWS setup)

- No WAF and no load-balancer rate limits. Sign-in, password reset, enquiry and certificate verification have
  the app's own per-client limits (in PostgreSQL); Caddy replaces any `X-Forwarded-For` sent by a client with the
  real address, and the API trusts it only from the private Docker network, so those limits cannot be dodged by
  spoofing the header (tested). Put CloudFront + WAF in front later if abuse appears.
- The API process holds the database owner password (it runs the migrations at start-up), as in local
  development. In AWS a separate `api-migrate` task holds it. The app still connects as `akshara_app`, so
  row-level security applies to every request. (The `migrate` profile used by the AWS pipeline's
  api-migrate step was fixed on SCH3 after e3c22fa: it now exits 0 after migrating.)
- Secrets are in `.env` (mode 600) on the encrypted disk and in your password manager; never in git.
- Keep SSH limited to your IP (or closed, with Session Manager) and keep `unattended-upgrades` on.
- `/actuator/*` answers 404 from outside; the containers check their health internally.

## 10. Moving to the managed setup (`infra/terraform`)

Move when one of these becomes true: the CPU stays above ~60% or the server swaps during school hours; downtime
during deploys or a server failure is no longer acceptable (the single server has no standby); a day of possible
data loss (daily dumps) is too much (RDS gives 5-minute point-in-time recovery); or the number of schools needs
more than one API instance.

1. Approve and apply `infra/terraform` for prod (its README), with
   `email_enabled` and `payments_gateway` as on the server. Do not start the services yet, or keep them at 0.
2. Lower the TTL of `app.rapolu.in` to 60 s a day before.
3. Maintenance window: `compose.sh stop web api` (Caddy keeps serving an error page), `backup/backup.sh`.
4. Restore into RDS from a CloudShell VPC environment (README, "Database role bootstrap"): run
   `bootstrap/db-roles.sql`, then
   `pg_restore --no-owner --role=akshara_owner -d "host=<rds> dbname=akshara user=<master> sslmode=require" akshara-….dump`
   so every object belongs to `akshara_owner` (the grants to `akshara_app` come with the dump). Compare row
   counts with the query in `backup/restore.sh`.
5. Files: if `FILES_STORE=s3`, copy the files bucket to the Terraform bucket
   (`aws s3 sync s3://akshara-prod-files-<account-id> s3://<terraform files bucket>`); if `database`, start the
   ECS API once with `FILES_COPY_TO_S3=true`.
6. Start the ECS services, smoke-test via the ALB name, point `app.rapolu.in` at the ALB (CNAME/alias), and move
   the Razorpay webhooks if the host name changes.
7. Keep the server **stopped, not terminated**, for two weeks as a fallback; then take a final snapshot and
   terminate it, release the Elastic IP, and keep the backup bucket until its copies expire.

## Local test results (2026-10-27, before launch)

Run in a sandbox (Docker 29, Compose v5.6, x86_64, 4 CPUs) with `APP_DOMAIN=localhost`, `CADDY_TLS=internal`
(Caddy's own CA), ports 18080/18443, `FILES_STORE=database`, no AWS. The images were the runtime stages of
`backend/Dockerfile` and `frontend/Dockerfile` fed with a host-built jar (`mvn package` of e3c22fa) and Next.js
standalone output (`npm run build` with `API_URL=http://api:8080`): the sandbox could not run the Maven stage of
`docker build` (no proxy or CA inside the build) and had too little disk for the full multi-stage builds. So
`docker compose build` itself was **not** exercised; on the server it runs the repository's Dockerfiles unchanged.

| Check | Result |
|-------|--------|
| `up -d --wait` from an empty volume | all four containers healthy; roles created by `init-roles.sh`; Flyway ran as `akshara_owner` |
| API health (inside the container) | `{"status":"UP"}` (liveness and readiness) |
| `https://localhost:18443/login` | 200; certificate from "Caddy Local Authority"; HTTP on 18080 → 308 to HTTPS; HSTS header present |
| `/actuator/health` through Caddy | 404 |
| Super Admin sign-in (`/api/platform/auth/login`) | 200; `platform_refresh_token` cookie `Secure; HttpOnly; SameSite=Strict` |
| Refresh with the cookie | 200 with the site's Origin, 403 with another Origin (`ALLOWED_ORIGINS`) |
| Spoofed `X-Forwarded-For` (11 wrong passwords, a different fake address each) | 10 × 401, then 429: Caddy overwrites the header, the limit still applies |
| DB, API and web ports from the host | not published (`docker port` empty; connecting to 5432 refused) |
| JVM inside a 1 GB limit | heap 616 MiB with the compose setting (60%); the image default (75%) gives 768 MiB |
| Playwright suite (`e2e/`, 51 tests, 2 workers, through Caddy over HTTPS, Super Admin from `.env`, demo off) | **51 passed in 5.2 min**; no OOM kill, no restart |
| Memory, idle → peak during the suite (`docker stats`, every ~4 s) | db 74 → 187 MiB, api 574 → 755 MiB, web 62 → 153 MiB, caddy 14 → 56 MiB; peak CPU api 120%, web 47% |
| `backup.sh` with the upload path, against a stub `aws` CLI | 1.5 MB dump (after the suite), checked, `.sha256`, "uploaded" to `db/2026/10/` |
| `restore.sh --drill s3://…` (from the stub) | checksum ok, restored in 5 s, **243 tables, 5,406 rows, counts equal to the live database for all 243** |
| `restore.sh --live --yes <dump>` | safety dump, database recreated, restored in 6 s, stack healthy again 49 s later; afterwards auth, students and isolation specs passed (5 tests) |
| `restore.sh --live --yes` with a truncated dump, then random bytes (negative sweep 2026-10-29) | before the fix: live database dropped, 0 tables, Caddy 502 on every page; after: pg_restore fails into the side database, the stack keeps serving (244 tables, login 200) |
| `deploy.sh --rollback` twice (two tags of the same images) | switched and smoke-tested in ~35 s each way; `--status` shows the history |
| Shell scripts | `sh -n`, `dash -n` and `shellcheck -s sh` clean |

Not tested here: anything on AWS (instance role, S3, SES, Let's Encrypt), `deploy.sh <ref>` (it builds and checks
out git refs), ARM64 images, swap, and the server's 2 vCPUs (the sandbox had 4).
