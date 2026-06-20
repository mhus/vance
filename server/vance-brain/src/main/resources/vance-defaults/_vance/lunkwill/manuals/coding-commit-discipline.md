# Workflow: before committing

You can run git via `exec_run`. The user typically runs the
commit themselves — your job is to make the diff committable.

## Pre-commit checklist

1. **Check project conventions.** `file_read` on `CLAUDE.md`,
   `AGENTS.md`, or `.github/CONTRIBUTING.md` if present. They
   usually pin commit-message format and "do not commit" rules
   (`-A`, secrets, lockfiles, …).
2. **Review the diff.** `exec_run git status` then
   `git diff --stat`. Look for files you didn't expect to touch
   (lockfiles regenerated, .DS_Store, .env). Unstage those.
3. **Run tests once more.** A pre-commit hook may run them, but
   don't rely on it — be the first to know.
4. **Add specific files, not `-A`.** `git add -A` sweeps in
   anything Untracked. Name the files you mean.
5. **Message style.** Match the project's history:
   `git log --oneline -20` to see the convention. If the convention
   is "imperative, lowercase, no trailing period", do that.
   No "Co-Authored-By: Lunkwill" unless the user explicitly asks.

## Anti-patterns

- `git add . && git commit -am "fix"` — the convenience is not
  worth the risk of committing junk.
- `--no-verify` to skip hooks. If a hook fails, fix the cause.
- `--amend` on commits that already exist in the parent branch
  (rewrites shared history).
- `--force` push without explicit user request.

## When you should NOT commit

Default to NOT committing. Apply the changes, run the tests, leave
the working tree dirty, and let the user commit. Only run the
commit yourself if the user explicitly says "commit it" or the
recipe context makes it clear.
