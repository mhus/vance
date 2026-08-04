package de.mhus.vance.brain.recipe;

import java.util.Objects;

/**
 * One completion guard: a judge query and the follow-up prompt injected
 * when the judge fires. Config-level (recipe {@code guard:} block or a
 * per-process runtime override). See {@code planning/completion-guard.md}.
 *
 * @param judge     free-form judge question — the LLM answers whether it holds
 * @param prompt    fixed follow-up prompt injected when the judge fires
 * @param trigger   which yield point this guard applies to
 * @param maxRounds hard cap on guard injections for the process (0 = disabled)
 */
public record GuardConfig(String judge, String prompt, GuardTrigger trigger, int maxRounds) {

    public GuardConfig {
        if (judge == null || judge.isBlank()) {
            throw new IllegalArgumentException("guard.judge must be non-blank");
        }
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException("guard.prompt must be non-blank");
        }
        Objects.requireNonNull(trigger, "guard.trigger");
        if (maxRounds < 0) {
            throw new IllegalArgumentException("guard.maxRounds must be >= 0");
        }
    }
}
