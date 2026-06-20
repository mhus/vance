# Workflow: small refactor

Rule of thumb: "small" = one symbol rename, one method extraction,
one file restructure, or pulling a helper out. Anything broader is
"large" and probably needs Marvin first.

1. **Read the call sites first.** Before renaming/moving anything,
   use `file_grep` to count usages. A "small" refactor with
   200 call sites becomes large — re-evaluate.
2. **Plan the edit set** mentally before touching the first file.
   List the files you'll touch. If the list grows past ~5, stop —
   consider whether you should be doing this at all.
3. **Apply edits in order.** Definition first, then usages. Don't
   touch usages before the definition is in place — your repo will
   be broken between edits, but each edit should leave a clearer
   half-state, not a more broken one.
4. **Run tests after every 2-3 edits.** Catches a misspelled new
   name early. Cheaper than digging through 5 broken files at the
   end.
5. **No drive-by changes.** If you spot an unrelated mess in a file
   you're refactoring, leave it. Open an issue or mention it in
   your final reply, but don't fold it in. Your PR diff should be
   reviewable.

## Anti-patterns

- Refactor + bug fix in the same change. Either the refactor hides
  the bug fix or the bug fix masks the refactor's behaviour
  change. Split.
- Renaming a public API without searching for it across the repo
  (and especially across `repos/*` if you're in a multi-module
  workspace).
- "While I'm here, let me also …" — that's how small refactors
  become unreviewable.
