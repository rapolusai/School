# 2. Schools share one database, isolated by row-level security

Status: accepted (Phase 0)

## Context
Every school's data must stay private to that school. A database per school is costly to run and
migrate at hundreds of schools; relying only on application code to add `where tenant_id = ?`
risks a single missed filter leaking data.

## Decision
- Every school-owned table has a `tenant_id` column and a PostgreSQL row-level security policy
  comparing it with `app.tenant_id`, a setting the API stamps on each pooled connection
  (`TenantAwareDataSource`) from the signed-in user's token.
- The API connects as `akshara_app`, which owns no tables and has no `BYPASSRLS`, so the policies
  always apply. `DatabaseRoleGuard` refuses to start otherwise. Migrations run as `akshara_owner`.
- Hibernate's `@TenantId` adds the same filter in the application as a second layer.
- With no school selected, school tables return no rows at all.
- The audit table only allows `insert` and `select` for the API role.

## Consequences
- A bug in a query can return too little, never another school's rows. Tests in
  `TenantIsolationIT` check this through the API and directly in the database.
- Switching school inside one request needs a new transaction (`TenantContext.runAs`), as sign-in
  and school provisioning do.
- Very large schools can later move to their own database without changing the code.
