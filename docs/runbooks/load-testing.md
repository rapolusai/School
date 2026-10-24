# Load testing

How to find out how many schools one environment carries, before production and after any big change. The
scripts are in `loadtest/` and use [k6](https://k6.io). They only use the public API, the same calls the web app
makes, so they test the whole path: firewall, load balancer, web and API tasks, database.

Never run them against production. Use staging, sized like production for the test (see "Sizing for the test").

## What is simulated

| Scenario | Who | What one user does | Peak rate (`PROFILE=peak`, `SCALE=1`) |
|----------|-----|--------------------|----------------------------------------|
| `morning` | class teachers, 9:00-9:30 | sign in, list sections, open today's register, take the roll (5-25 s), save it | ramps to 5 registers per second, holds 8 minutes |
| `parents` | parents, evening | sign in, open the child's attendance, homework, fees and the notice board | 10 parents per second for 15 minutes |
| `office` | school office | sign in, search students, open a record, today's attendance, message log, users | 2 per second for 15 minutes |

At `SCALE=1` that is roughly a busy morning for 1,000 schools with 20 sections each (20,000 registers in about
an hour), plus the evening parent rush. `SCALE=2` doubles every rate.

Pass marks (`loadtest/lib/common.js`, change with `-e`): fewer than 1% failed requests (`MAX_ERROR_RATE`), 95% of
requests under 800 ms (`P95_MS`) and 99% under 2 s (`P99_MS`), saving a register under 1.5 s at p95
(`REGISTER_P95_MS`), and more than 99% of checks passing.

## Steps

1. **Install k6** on the machine that generates the load (an EC2 instance in the same region is best:
   `c7i.xlarge` or larger for `SCALE` above 1). <https://grafana.com/docs/k6/latest/set-up/install-k6/>
2. **Let the generator past the firewall's rate limits** for the test: put its public IP in `waf_trusted_ips`
   (e.g. `["203.0.113.10/32"]`) in `environments/staging.tfvars`, plan and apply. Without this the WAF blocks the
   generator after 2,000 requests in 5 minutes.
3. **Seed schools** once per test run (a new `RUN_ID` makes new schools):

   ```bash
   export BASE_URL=https://staging.example.com RUN_ID=lt1 LOAD_PASSWORD='<a throwaway password>'
   k6 run -e SCHOOLS=200 -e STUDENTS_PER_SECTION=40 loadtest/seed.js
   ```

   Each school gets Class 5 A and B with a class teacher each, 40 students per section from the CSV import, and
   sign-ins for five parents per section.
4. **Smoke test** (one minute, a few users) to check the scripts against this environment:
   `k6 run -e SCHOOLS=200 -e PROFILE=smoke loadtest/scenarios.js`
5. **Peak test**: `k6 run -e SCHOOLS=200 -e PROFILE=peak -e SCALE=1 --summary-export=peak-1.json loadtest/scenarios.js`.
   Watch the environment's CloudWatch dashboard while it runs (`terraform output dashboard_url`).
6. **Find the limit**: repeat with `SCALE=2`, `3`, … until a pass mark fails. The last passing `SCALE` × 1,000
   schools is the environment's capacity at that size.
7. **Undo step 2**: empty `waf_trusted_ips`, plan and apply.
8. **Write the capacity report** (below) and size production from it.

## Sizing for the test

Start staging at production's sizes for the test (in `staging.tfvars`): `api_cpu = 1024`, `api_memory = 2048`,
`api_min_count = 2`, `api_max_count = 6`, `db_instance_class = "db.m7g.large"`. Autoscaling adds API tasks at 60%
CPU; note how many were running at each `SCALE`. Put staging back to its own sizes afterwards.

Things to watch on the dashboard, and what they mean:

- **API p95 rising while API CPU is low** → the database or its connections: check RDS CPU, connections, and
  Performance Insights' top SQL.
- **RDS connections near the alarm** → `DB_POOL_SIZE` (10 per task) × tasks is too high for the instance class:
  use a bigger class or lower the pool.
- **5xx from the load balancer** → tasks were replaced or not ready: check "Running tasks" and the ECS events.
- **WAF blocked requests** → the generator is not in `waf_trusted_ips`.

## Capacity report (template)

```
Date, commit, environment sizes (API cpu/memory/min/max, web, DB class, Multi-AZ)
Schools seeded, students per section
SCALE  | registers/s | parents/s | p95 | p99 | errors | API tasks | API CPU | RDS CPU | RDS connections | pass?
1      |             |           |     |     |        |           |         |         |                 |
2      |             |           |     |     |        |           |         |         |                 |
Capacity: N schools at these sizes. Bottleneck at the first failing SCALE: …
Production sizing chosen: …
```

## Notes

- The sign-in rate limit inside the API counts per IP, school and email, so the seeded accounts never trip it.
- The seed and scenarios never touch real schools: every school they make is `load-<RUN_ID>-<n>`. Delete a run's
  schools from the Super Admin console when done (or drop the staging database if it was seeded only for tests).
- The scripts were run on a developer PC against the local stack (2 schools, smoke profile: 0 failed requests,
  p95 85 ms) to prove they work; that says nothing about AWS capacity.
