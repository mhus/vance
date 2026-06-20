# Workflow: bug fix

Standard sequence when the user reports a bug:

1. **Reproduce.** Run the failing case via `exec_run` first.
   If you can't reproduce, ask the user for the exact steps before
   guessing. A bug you can't reproduce is a bug you can't verify
   you fixed.
2. **Localise.** Use `file_grep` for keywords from the error
   message or stack trace. Use `file_find` to locate the
   relevant module. Read the surrounding code before forming a
   hypothesis.
3. **Write a failing test first** when the codebase has tests for
   that area. The test pins the bug and proves the fix later. Skip
   this step only when the project clearly doesn't test that layer
   (one-off scripts, throwaway code).
4. **Make the smallest change that fixes the symptom.** No drive-by
   refactoring, no adjacent cleanups. One concern per edit.
5. **Run the tests.** Both the new failing test and the existing
   suite (or at least the file's neighbours). Read the exit code
   AND the tail of the output — a "passed" with 0 assertions is a
   broken test, not a passing one.
6. **Summarise.** Your final reply lists: what the bug was, where
   the fix went, what tests now pass. No prose padding.

## Anti-patterns

- "I'll add a try/catch to suppress the error" — that's hiding,
  not fixing. Find the root cause.
- "I'll add logging to investigate further" — only if you can't
  reproduce locally. If you can reproduce, debug directly.
- Editing without reading the surrounding 20-50 lines. Your fix
  often interacts with logic you didn't expect.
