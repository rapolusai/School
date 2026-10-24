# Releasing and rolling back

## How a change reaches production

1. A pull request into `main`. CI (`.github/workflows/ci.yml`) must pass: API tests with a real PostgreSQL, web
   lint, types, tests and build, Terraform validation, the Playwright end-to-end suite, and both images building.
2. Merging to `main` deploys to **staging** automatically (`.github/workflows/deploy.yml`), after CI passes on
   `main`: images tagged with the commit SHA, migrations, API then web, smoke test, automatic rollback on failure.
3. Check staging: the parts of the app the change touched, the dashboard, and the alarms.
4. Tag the commit: `git tag v1.4.0 <sha> && git push origin v1.4.0`.
5. **Production**: Actions → Deploy → Run workflow → environment `prod`, ref `v1.4.0`. A reviewer of the `prod`
   environment approves it in GitHub; the same steps run against production.

Database migrations must work with the previous version of the API too (add columns and tables first, remove
old ones in a later release), because a rollback only changes the code, never the database.

## When something goes wrong

| What happened | What to do |
|---------------|------------|
| The deploy's smoke test failed | Nothing: the workflow already put the previous task definitions back. Read the job log and the `api` log group. |
| Migrations failed | Nothing was rolled out. Read the `api-migrate` log group; fix the migration in a new commit. |
| A problem found after a successful deploy | Run the Deploy workflow with the previous tag (`v1.3.2`). Same images, so it takes minutes. |
| Need to go back faster than a deploy | `aws ecs update-service --cluster akshara-prod --service api --task-definition <previous revision>` (and `web`); the revisions are kept. |
| Data was damaged | `docs/runbooks/restore-database.md`. |
| The site is down for everyone | Check the dashboard's running tasks and 5xx, the ECS service events, the RDS status, and whether the certificate or DNS changed. |

## Hotfix

Branch from the tag that is in production, fix, PR into `main`, let staging deploy it, tag `vX.Y.Z+1`, deploy
to prod. Never deploy to production a commit that has not been on staging.
