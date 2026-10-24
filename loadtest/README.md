# Load tests (k6)

- `seed.js` makes `SCHOOLS` test schools named `load-<RUN_ID>-<n>` through the public API.
- `scenarios.js` runs the morning register rush, the evening parent rush and office work against them
  (`PROFILE=smoke` or `peak`, rates scaled by `SCALE`).
- `lib/common.js` holds the account naming, helpers and pass marks.

How to run them, what the numbers mean and the capacity report: [docs/runbooks/load-testing.md](../docs/runbooks/load-testing.md).

```bash
k6 run -e BASE_URL=http://localhost:3000 -e RUN_ID=dev1 -e SCHOOLS=2 -e STUDENTS_PER_SECTION=10 loadtest/seed.js
k6 run -e BASE_URL=http://localhost:3000 -e RUN_ID=dev1 -e SCHOOLS=2 -e PROFILE=smoke loadtest/scenarios.js
```

Never point them at production.
