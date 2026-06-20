# Workflow: understand a codebase

When the user asks "how does X work?" or "explain Y":

1. **Find the entry point** with `file_grep`. Look for `main`,
   `Application`, `index`, `app`, or whatever idiom the language
   uses. For services: the HTTP route registration. For CLIs: the
   command dispatcher.
2. **Sketch the layout** with `file_list` / `file_find`.
   You want package/module structure before diving into one file.
3. **Read the README / docs first.** `file_read` on
   `README*`, `docs/`, `CLAUDE.md`, `AGENTS.md`. Project authors
   write the high-level model there; reading code first means
   re-deriving things they already wrote down.
4. **Follow data, not control flow.** If the question is "how does
   X get from A to B", trace the data structure through grep. If
   it's "what runs when Y happens", trace the call chain.
5. **Don't try to read everything.** A 200-file repo has maybe
   5-10 files that matter for any given question. Find those, read
   them thoroughly, ignore the rest.

## What to say back

Your reply should explain the architecture in the user's terms —
the model they likely have, not the implementation's vocabulary.
Cite specific files (`path/to/file.ext:line_number`) so they can
read along. Prefer concrete examples over abstract description.

If you genuinely couldn't find the answer, say so — don't fabricate.
