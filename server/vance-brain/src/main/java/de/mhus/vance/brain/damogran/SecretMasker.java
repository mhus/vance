package de.mhus.vance.brain.damogran;

import java.util.Collection;
import org.jspecify.annotations.Nullable;

/**
 * Replaces known injected secret values with {@code ***} in task output before
 * it reaches the caller/LLM or a persisted document. Best-effort, exact-substring
 * masking (the GitHub-Actions model): a script can't be stopped from emitting a
 * value it holds, so an accidental {@code echo $TOKEN} is redacted after the fact.
 *
 * <p>v1 limitation: only exact raw values are masked. Transformed forms
 * (base64, url-encoded, split) slip through — same caveat CI secret-masking has.
 * Very short values ({@literal <} {@link #MIN_LEN} chars) are skipped so a
 * one-character secret can't blank out unrelated output.
 *
 * <p>Scope: the result envelope the caller/LLM sees. The live exec tail and the
 * server-local exec log files (shared {@code ExecManager} infra) are not masked
 * in v1.
 */
final class SecretMasker {

    static final String MASK = "***";
    /** Below this length a value is too generic to safely substring-replace. */
    static final int MIN_LEN = 4;

    private SecretMasker() {}

    static @Nullable String mask(@Nullable String text, Collection<String> secretValues) {
        if (text == null || text.isEmpty() || secretValues.isEmpty()) {
            return text;
        }
        String out = text;
        for (String value : secretValues) {
            if (value != null && value.length() >= MIN_LEN) {
                out = out.replace(value, MASK);
            }
        }
        return out;
    }
}
