/**
 * Bundled reusable completion-guard script — "LLM judge + fixed
 * follow-up prompt". Configure it from a recipe `guard:` entry without
 * writing JS:
 *
 *   guard:
 *     - script: _vance/guards/llm-judge.js
 *       params: { judge: "Is the dev task done?", prompt: "Build + update the spec?" }
 *       maxRounds: 2
 *
 * Params:
 *   judge  — the judge question (the guard condition)
 *   prompt — the fixed follow-up prompt injected when the judge fires
 *
 * See planning/completion-guard.md v2.
 */
const judge = vance.params.judge;
const prompt = vance.params.prompt;
if (!judge || !prompt) {
  // Misconfigured legacy guard — nothing to evaluate. Fail-open.
  return;
}

const res = vance.llm.callForJson("completion-guard", "Evaluate the guard condition.", {
  judge: judge,
  task: vance.guard.task,
  output: vance.guard.output,
});

if (res && res.fire) {
  vance.guard.continueWith(prompt);
}
