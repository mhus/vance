---
triggers: tests, testing, unit tests, run tests, pytest, junit, mvn test, npm test
summary: Detect the project's test command from marker files and run it.
requires-tools: exec_run, file_read
---

# Workflow: run the project's tests

You don't always know the test command. Detect it.

## Detection priority

1. **`CLAUDE.md` / `AGENTS.md` / `README*`** — usually says
   "run tests via X". Read first.
2. **Project marker files**:
   - `pom.xml` → `mvn test` (often scoped: `mvn -pl <module> test`)
   - `build.gradle{,.kts}` → `./gradlew test`
   - `package.json` → check `scripts.test`; commonly `npm test`,
     `pnpm test`, or `yarn test`. Use the matching package manager
     (look for `pnpm-lock.yaml` / `yarn.lock`).
   - `pyproject.toml` / `pytest.ini` / `tox.ini` → `pytest` or
     `pytest -k <name>`
   - `Cargo.toml` → `cargo test`
   - `go.mod` → `go test ./...`
3. **Scoped runs.** Don't run the whole suite if a smaller scope
   makes sense:
   - `mvn -pl <module> -am test` for a single Maven module
   - `pytest path/to/test_file.py::test_name`
   - `npm test -- <pattern>`

## Reading the output

- Exit code first. 0 = pass.
- Tail of the output second. A "BUILD SUCCESS" with 0 tests run
  means your filter excluded everything — that's not a pass.
- For flaky tests: re-run the failing test in isolation before
  blaming the change.

## When tests take long

- Per-test timing is in the framework's own output (Surefire,
  pytest `-v --durations=10`, …). If a test takes >5 minutes,
  consider whether you should run it now or note "would need to
  run X" in your reply.
- Container-based test suites (Testcontainers, Docker Compose)
  cost more to spin up — run a smaller suite when iterating.

## When you can't run the tests

Sometimes the env lacks the dependencies (Mongo not running,
Node not installed, …). Say so plainly in your reply and list
the commands the user should run after your change.
