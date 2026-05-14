package de.mhus.vance.shared.hactar;

import org.jspecify.annotations.Nullable;

/**
 * One entry in a {@code condition_task}'s {@code transitions:} list
 * (plan §4.6). Evaluated in order; the first matching {@link #condition}
 * wins. The {@code else:} fallback is encoded as a row with
 * {@code condition == null} that must appear last after parse-time
 * validation.
 *
 * @param condition SpEL expression text — null for the {@code else:} branch.
 * @param target State name to enter on match.
 */
public record HactarTransition(
        @Nullable String condition,
        String target) {

    public boolean isElse() {
        return condition == null;
    }
}
