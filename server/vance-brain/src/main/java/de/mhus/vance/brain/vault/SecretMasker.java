package de.mhus.vance.brain.vault;

import java.util.Collection;
import org.jspecify.annotations.Nullable;

/**
 * Replaces known secret values with {@code ***} in output before it reaches the
 * caller/LLM or a persisted document. Best-effort, exact-substring masking (the
 * GitHub-Actions model): a script/command can't be stopped from emitting a value
 * it holds, so an accidental {@code echo $TOKEN} / {@code console.log(secret)} is
 * redacted after the fact.
 *
 * <p>Shared by the compose-secrets path ({@code DamogranTaskSupport}) and the
 * script vault-access path ({@code ScriptSecretApi}).
 *
 * <p>v1 limitation: only exact raw values are masked. Transformed forms (base64,
 * url-encoded, split, or embedded in a structured object) slip through — same
 * caveat CI secret-masking has. Very short values ({@literal <} {@link #MIN_LEN}
 * chars) are skipped so a one-character secret can't blank out unrelated output.
 */
public final class SecretMasker {

    public static final String MASK = "***";
    /** Below this length a value is too generic to safely substring-replace. */
    public static final int MIN_LEN = 4;

    private SecretMasker() {}

    public static @Nullable String mask(@Nullable String text, Collection<String> secretValues) {
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
