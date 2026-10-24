# Restoring the database (and disaster recovery)

What protects the data, how to get it back, and the drill that proves it works. Commands use `prod`; swap in
`staging` for drills. Run them from AWS CloudShell in the production account, as an administrator.

## What is in place (Terraform)

| Protection | Where | Recovers from |
|------------|-------|---------------|
| Multi-AZ standby (`db_multi_az = true`) | `rds.tf` | the database server or its availability zone failing: automatic failover in 1-2 minutes, no data lost |
| Automated backups + transaction logs, 30 days in prod (`db_backup_retention_days`) | `rds.tf` | a bad deployment, a wrong bulk change, deleted records: restore to any second in the window |
| Backups kept after an accidental instance deletion (`delete_automated_backups = false`), deletion protection, final snapshot | `rds.tf` | someone deleting the database |
| Copy of the backups in ap-south-2, Hyderabad (`backup_replica_region`) | `rds.tf` | losing the whole ap-south-1 region |
| Files: versioned S3 bucket, old versions kept 30 days | `storage.tf` | files deleted or overwritten by mistake (files are not copied to a second region yet) |

**Targets.** At most 5 minutes of data lost (RPO: transaction logs reach the backups about every 5 minutes) and
the school back in service within 1 hour (RTO) for anything short of a region loss. A region loss is a manual
rebuild of a few hours (section D).

## A. Something was changed or deleted by mistake (most likely)

Restore a copy next to the live database, take what is needed from it, and leave the live database running.

1. Pick the time just before the mistake (UTC). The audit log (`audit.audit_event`) usually shows when it happened.
2. Restore a copy (takes 10-30 minutes):

   ```bash
   aws rds restore-db-instance-to-point-in-time \
     --source-db-instance-identifier akshara-prod \
     --target-db-instance-identifier akshara-prod-restore-$(date +%Y%m%d%H%M) \
     --restore-time 2026-11-03T04:15:00Z \
     --db-subnet-group-name akshara-prod \
     --vpc-security-group-ids <terraform output: the rds security group id> \
     --db-parameter-group-name <the akshara-prod-pg17-… group> \
     --no-multi-az --no-publicly-accessible --no-deletion-protection
   aws rds wait db-instance-available --db-instance-identifier akshara-prod-restore-…
   ```
3. Connect from a CloudShell VPC environment (infra/terraform/README.md, "Database role bootstrap") to the
   restored copy and to the live database, and copy back the affected rows of that school with `pg_dump
   --data-only --table=…` and `psql`, or by hand for a few rows. Work as the owner role; every school table has a
   `tenant_id`, so filter by it.
4. Record what was restored in the school's audit trail (a note) and tell the school.
5. Delete the copy: `aws rds delete-db-instance --db-instance-identifier akshara-prod-restore-… --skip-final-snapshot`.

## B. The whole database must go back in time (a bad migration, widespread damage)

1. Announce maintenance; stop the services so nothing writes:
   `aws ecs update-service --cluster akshara-prod --service api --desired-count 0` (and `web`).
2. Restore a copy as in A.2, with `--multi-az` and the production instance class.
3. Swap the names, so the API's database address stays the same:

   ```bash
   aws rds modify-db-instance --db-instance-identifier akshara-prod \
     --new-db-instance-identifier akshara-prod-broken-$(date +%Y%m%d) --apply-immediately
   # wait until the rename is done, then
   aws rds modify-db-instance --db-instance-identifier akshara-prod-restore-… \
     --new-db-instance-identifier akshara-prod --apply-immediately
   ```
4. Start the services again (`--desired-count 2`); the readiness check and the smoke test of the deploy workflow
   (run it by hand with the current tag) confirm the app works.
5. Run `terraform plan` for prod. It should show at most small differences on the restored instance (tags,
   monitoring settings); apply them. Keep the broken instance for a week for investigation, then delete it.

The restored copy keeps the original's encryption key, users and roles (they are inside the database), so
`db-roles.sql` does not need to run again.

## C. One availability zone or the database server fails

Nothing to do: Multi-AZ fails over automatically and the database address stays the same. The `rds-cpu`,
`target-5xx` and `site-down` alarms may fire briefly. Check the RDS events afterwards.

## D. The ap-south-1 region is unavailable

1. Restore the database in ap-south-2 from the copied backups:

   ```bash
   aws rds describe-db-instance-automated-backups --region ap-south-2   # find the replicated backups' ARN
   aws rds restore-db-instance-to-point-in-time --region ap-south-2 \
     --source-db-instance-automated-backups-arn <arn> \
     --use-latest-restorable-time \
     --target-db-instance-identifier akshara-prod \
     --kms-key-id <the backup copy key in ap-south-2> --multi-az --no-publicly-accessible
   ```
2. Bring up the rest in ap-south-2 with Terraform: a copy of `prod.tfvars` with `aws_region = "ap-south-2"`, its
   availability zones, a certificate issued in ap-south-2, a new state key, and the restored database imported
   (`terraform import aws_db_instance.main akshara-prod`) instead of a new one.
3. Push the current images to the new region's ECR (re-run the deploy workflow with the new environment's
   variables), point the domain at the new load balancer, and check with the smoke test.
4. Uploaded files are not yet copied to a second region: until S3 replication is added (a follow-up), files
   uploaded in ap-south-1 are unavailable until that region returns. Records and data are not affected.

## The drill (every quarter, and before go-live)

On staging (or on a copy of production in its own account):

1. Note the time, then change something you can recognise (add a test school or a notice).
2. Follow section A to restore to a time before the change; check the change is absent in the copy and present in
   the live database. Time each step.
3. Follow section B on staging end to end, including the deploy workflow's smoke test.
4. Write down: the times, anything that did not match this page, and fix this page.

| Date | Environment | A: restore time | B: total downtime | Problems found | By |
|------|-------------|-----------------|-------------------|----------------|----|
|      |             |                 |                   |                |    |
