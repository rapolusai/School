# Akshara on AWS (Terraform)

Infrastructure for the Akshara school management SaaS in **ap-south-1 (Mumbai)**. One root module;
each environment is a `*.tfvars` file plus a `*.backend.hcl` file (its own state key). Staging is
the only environment defined so far.

> **Approval gate.** Nothing in this directory has been planned or applied. It was written and
> checked with `terraform fmt` and `terraform validate` only, without AWS credentials.
> Nobody runs `terraform plan` or `apply` against an AWS account until the owner has reviewed
> this code and the cost section below and has explicitly approved it.

## What it creates

| Area | Resources |
|------|-----------|
| Network (`network.tf`) | VPC `10.20.0.0/16` over 3 AZs: public subnets (ALB, NAT), private subnets (ECS tasks), isolated database subnets (no internet route). One NAT gateway (staging) or one per AZ (`one_nat_per_az`). S3 gateway endpoint. VPC flow logs to CloudWatch. Default security group emptied. |
| Entry point (`alb.tf`, `waf.tf`) | Internet-facing ALB: HTTP→HTTPS redirect, TLS 1.2/1.3 policy, invalid headers dropped, access logs to an S3 bucket (SSE-S3, private, 90-day expiry). `/api/*` → API, everything else → web, `/actuator*` → 404. WAF: AWS IP reputation, common, known-bad-inputs and SQLi rule sets, a per-IP rate limit (2000 per 5 min) and a stricter one (100 per 5 min) on login and signup. |
| Containers (`ecr.tf`, `ecs.tf`) | ECR `akshara-api` and `akshara-web` (immutable tags, scan on push, keep last 30). ECS cluster with Container Insights. Fargate services `api` (8080) and `web` (3000) in private subnets with rollback on failed deployments and CPU autoscaling (target 60%). One-off task definition `api-migrate`. |
| Database (`rds.tf`) | RDS PostgreSQL 17, gp3 with storage autoscaling, encrypted with a customer-managed KMS key, TLS enforced, Multi-AZ switch, 14-day backups, deletion protection, final snapshot, Performance Insights, enhanced monitoring, logs to CloudWatch. The master password is generated and held by RDS in Secrets Manager. |
| Secrets (`secrets.tf`, `kms.tf`) | `akshara/<env>/db-app`, `db-owner`, `jwt`, `platform-admin` and (when demo is enabled) `demo` in Secrets Manager, encrypted with a second KMS key, 7-day recovery window. |
| Access (`iam.tf`) | ECS execution roles (one for api/web, a separate one for api-migrate, the only role that can read `db-owner`), empty task roles, GitHub Actions OIDC provider (optional) and a deploy role. |
| Monitoring (`monitoring.tf`) | SNS topic (no subscribers yet) and alarms: ALB 5xx, target 5xx, RDS CPU > 80%, RDS free storage < 5 GiB, fewer API tasks running than desired. |

## Monthly cost drivers (staging)

Rough on-demand figures for ap-south-1, before tax and data transfer. Check them in the
[AWS Pricing Calculator](https://calculator.aws/) before approving; prices change.

- **Database** is the biggest item: a `db.t4g.medium` running all month is roughly $50-60.
  Multi-AZ doubles it (staging has it off; prod should have it on). Storage is small (20 GiB gp3,
  a few dollars) and grows automatically up to 100 GiB.
- **NAT gateway**: roughly $40 a month per gateway just for existing, plus a charge for every GB
  the tasks download through it (image pulls from ECR go through it; S3 layer downloads use the
  free S3 endpoint). Staging has one; prod with `one_nat_per_az = true` has three.
- **Load balancer**: roughly $17-20 a month plus a small usage charge.
- **Fargate**: you pay per vCPU and GB of memory per hour while tasks run. One API task
  (0.5 vCPU, 1 GB) is roughly $18 a month and one web task (0.25 vCPU, 0.5 GB) roughly $9;
  autoscaling adds tasks under load.
- **Public IPv4 addresses**: AWS charges about $3.65 a month for each (the ALB uses one per AZ and
  each NAT gateway one), so about $15 for staging.
- **WAF**: $5 for the web ACL plus $1 for each of the 6 rules, plus $0.60 per million requests.
- **Small items**: CloudWatch logs and Container Insights metrics (grow with traffic and the
  retention settings), 2 KMS keys ($1 each), about 6 secrets ($0.40 each), ECR storage, alarms.

Ballpark: **$170-220 a month for staging** as configured. A prod setup with Multi-AZ, three NAT
gateways and two or more tasks per service is roughly double.

## One-time prerequisites (owner)

1. **State bucket.** Create the bucket named in `environments/staging.backend.hcl` (replace
   `<account-id>`), in ap-south-1, with versioning on, default encryption on and all public access
   blocked. Restrict who can read it: the state contains every generated secret (see below).
   Locking uses S3 lock files (`use_lockfile`), so no DynamoDB table is needed.
2. **Certificate.** Request an ACM certificate in ap-south-1 for the staging host name, validate it
   via DNS, and put its ARN in `acm_certificate_arn`.
3. **Fill in** every `REPLACE` value in `environments/staging.tfvars` (domain, certificate ARN,
   platform admin email; image tags are set during first bring-up).
4. **GitHub.** Create the `staging` environment in the repository and limit its deployment
   branches to `main`. The deploy role trusts jobs on `main` and jobs that use the `staging`
   environment. `github_repository` must match GitHub's spelling exactly (`rapolusai/School`),
   because IAM compares the token's claim case-sensitively.
5. **Credentials.** Run Terraform with an administrator profile for the target account.

## Running Terraform (after approval)

```bash
cd infra/terraform
terraform init -backend-config=environments/staging.backend.hcl
terraform plan  -var-file=environments/staging.tfvars -out=staging.tfplan
terraform apply staging.tfplan
```

Always review the plan output before applying. `.terraform.lock.hcl` pins the provider versions
and checksums for all platforms; commit it.

## First-time bring-up (once per environment)

The services need images that do not exist yet and database roles that Terraform does not create,
so the first rollout is done in this order:

1. **Create the image repositories only:**
   `terraform apply -var-file=environments/staging.tfvars -target='aws_ecr_repository.app'`
2. **Build and push** both images tagged with the git SHA (tags are immutable). Build the web image
   with `API_URL=https://<domain_name>` if its Dockerfile takes that build argument. Set
   `api_image_tag` and `web_image_tag` in `staging.tfvars` to that SHA.
3. **Apply everything:** plan and apply as above. The API service will not become healthy yet;
   that is expected until steps 4 and 5 are done.
4. **Create the database roles** (see next section).
5. **Run migrations** with the `api-migrate` task (see "Deploying a new version", step 3).
6. **Start the API cleanly:**
   `aws ecs update-service --cluster akshara-staging --service api --force-new-deployment`
7. **DNS:** point the domain at `terraform output alb_dns_name` (CNAME, or a Route 53 alias).
8. **Alarms:** subscribe an email address or chat webhook to `terraform output alarm_topic_arn`.
9. **Pipeline:** store the outputs the deploy job needs as GitHub `staging` environment variables
   (`github_deploy_role_arn`, `ecr_repository_urls`, `ecs_cluster_name`,
   `task_definition_families`, `migrate_network_configuration`).

## Database role bootstrap

Terraform has no database connection, so the two application roles are created once by hand with
`bootstrap/db-roles.sql`:

- `akshara_owner`: can log in, owns the `akshara` database and (through the migrations) every
  schema and table. Only the `api-migrate` task uses it.
- `akshara_app`: can log in, no superuser, `NOBYPASSRLS`, owns nothing, so row-level security
  always applies. The API connects as this role.

The database only accepts connections from the `api` security group and has no public address.
Without a bastion host, use an **AWS CloudShell VPC environment**:

1. CloudShell → Actions → *Create VPC environment*: VPC `akshara-staging`, one subnet from
   `terraform output private_subnet_ids`, security group `terraform output api_security_group_id`.
2. Upload `bootstrap/db-roles.sql` (Actions → Upload file) and install a PostgreSQL client
   (for example `sudo dnf install -y postgresql16`).
3. Run, filling in the two `terraform output` values:

```bash
get() { aws secretsmanager get-secret-value --secret-id "$1" --query SecretString --output text; }
MASTER=$(get '<rds_master_secret_arn>')
export PGPASSWORD=$(jq -r .password <<<"$MASTER")
psql "host=<rds_endpoint> port=5432 dbname=akshara user=$(jq -r .username <<<"$MASTER") sslmode=require" \
  -v owner_password="$(get akshara/staging/db-owner | jq -r .password)" \
  -v app_password="$(get akshara/staging/db-app | jq -r .password)" \
  -f db-roles.sql
unset PGPASSWORD MASTER
```

4. Delete the CloudShell VPC environment.

The script is safe to re-run. Re-run it whenever the `db-owner` or `db-app` password changes.

## Deploying a new version (the pipeline's job)

Terraform owns what each task definition *contains* (environment, secrets, sizes, roles). The
pipeline owns *which image runs*: the ECS services ignore `task_definition` changes made outside
Terraform, and old revisions are kept for rollback. The GitHub deploy role allows exactly these
steps:

1. **Build and push** `akshara-api:<sha>` and `akshara-web:<sha>`.
2. **Register new revisions** of the three families: take the latest revision
   (`aws ecs describe-task-definition`), swap the image, `aws ecs register-task-definition`.
3. **Migrate before rolling the API:**

   ```bash
   TASK=$(aws ecs run-task --cluster akshara-staging --launch-type FARGATE \
     --task-definition akshara-staging-api-migrate \
     --network-configuration "$MIGRATE_NETWORK_CONFIGURATION" \
     --query 'tasks[0].taskArn' --output text)
   aws ecs wait tasks-stopped --cluster akshara-staging --tasks "$TASK"
   aws ecs describe-tasks --cluster akshara-staging --tasks "$TASK" \
     --query 'tasks[0].containers[0].exitCode' --output text   # must print 0, otherwise stop
   ```

4. **Roll the services:** `aws ecs update-service --cluster akshara-staging --service api
   --task-definition <new api revision>` (then `web`), and `aws ecs wait services-stable`. A
   deployment whose tasks fail health checks is rolled back automatically.

When a Terraform change alters a task definition, Terraform registers a new revision using
`api_image_tag`/`web_image_tag`. Keep those set to the currently deployed SHA; the change
reaches the running services with the next pipeline deploy (or a `--force-new-deployment`).

## How requests are routed

- The ALB sends `/api/*` straight to the API target group and everything else to the web target
  group, so in AWS the Next.js server never proxies API calls. The browser sees a single origin,
  which keeps the refresh cookie first-party.
- The web container gets `API_URL=https://<domain_name>` (not `http://localhost:8080`, which would
  point at itself on Fargate). `next.config.ts` reads `API_URL` at build time for its `/api`
  rewrite; that rewrite is never reached in AWS because the ALB takes `/api/*` first.
- `/actuator/*` is not reachable from the internet (the ALB answers 404). The target group checks
  `/actuator/health/readiness` on the container port directly; the container's own health check
  uses `/actuator/health/liveness`.

## Secrets

- Generated with the `random` provider and written to Secrets Manager. **The values are also
  stored in the Terraform state**, which is why the state bucket must stay encrypted, versioned and
  readable only by administrators. Outputs expose ARNs only.
- Containers receive them as environment variables injected by ECS at start-up. The `api` service
  never receives the owner password; only `api-migrate` does.
- Read one: `aws secretsmanager get-secret-value --secret-id akshara/staging/platform-admin`.
- Rotate one: `terraform apply -replace=random_password.<name>` (e.g. `db_app`), then for the
  database passwords re-run `bootstrap/db-roles.sql`, then force a new deployment.
- The RDS master password is managed and rotated by RDS itself.

## Adding prod

Copy `staging.tfvars` and `staging.backend.hcl` to `prod.*`, change `environment`, the state `key`
and the domain/certificate, and set `one_nat_per_az = true`, `db_multi_az = true`,
`demo_enabled = false`, and min/desired counts of 2 or more. Create a `prod` GitHub environment
with required reviewers.

Some names are account-wide: the ECR repositories (`akshara-api`, `akshara-web`) and the GitHub
OIDC provider. Prod in its **own AWS account** (recommended) needs no change. Prod in the
**same account** needs `create_github_oidc_provider = false`, and the ECR repositories would have to
move to a shared stack first (both environments would otherwise try to create them).

## Known limits and follow-ups

- WAF `AWSManagedRulesCommonRuleSet` blocks request bodies over 8 KB. If file or bulk uploads are
  added, override `SizeRestrictions_BODY` for those paths.
- The per-IP rate limit counts all requests from the NAT gateway's address as one client. If the
  web server ever calls the public API URL server-side, exempt the NAT IPs or call the API
  internally.
- No interface VPC endpoints (ECR API, Secrets Manager, CloudWatch Logs): that traffic uses the NAT
  gateway. Endpoints cost about $8 a month each per AZ and pay off only at higher traffic.
- Containers run on x86. Graviton (ARM64) Fargate is about 20% cheaper if both images are built
  for `linux/arm64`.
- CloudWatch log groups use the default AWS-owned encryption, not a customer-managed key.
