---
triggers: test, schreibe test, add test, write test, junit, pytest, spec
summary: Find a sibling test file, match its style, single-behaviour AAA.
requires-tools: file_read, file_write, file_find, exec_run
---

# Workflow: add a test

1. **Find where similar tests live.** `file_find` for the
   nearest `*Test.java` / `test_*.py` / `*.spec.ts` next to the code
   under test. Match the project's convention — Vance has no global
   "tests next to source" rule; per-language norms apply.
2. **Read an adjacent test as template.** Steal the framework
   imports, the setup/teardown style, the assertion library, the
   naming convention (`feature_situation_expectedOutcome`,
   `describe/it`, `should_...`, …). Don't introduce a new style.
3. **One behaviour per test.** A test that asserts on 5 unrelated
   things hides which one fails. Split.
4. **AAA structure**: Arrange (build inputs), Act (call the unit
   under test), Assert (one observable outcome). Keep them visually
   separated.
5. **Avoid mocks for things you own.** Mock external boundaries
   (HTTP, DB, filesystem) only. Mocking internal classes makes
   tests brittle and lets implementation drift go unnoticed.
6. **Run the test** via `exec_run`. New tests must pass
   from the first run — a test that "passes" by chance has no
   value. Read the output, not just the exit code.

## When the project has a CLAUDE.md / AGENTS.md

Check it first. Many projects pin specific test conventions there
(test naming, when integration vs unit, what NOT to test). Vance
itself has explicit rules in `CLAUDE.md` § Tests — follow them.
