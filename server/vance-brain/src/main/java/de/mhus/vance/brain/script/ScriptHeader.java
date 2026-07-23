package de.mhus.vance.brain.script;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Parsed first-block JSDoc header of a script. Carries the v1
 * tags from {@code specification/script-engine.md} §3.5. Every
 * field is optional — a script without a header yields the
 * {@link #empty()} instance and the executor falls back to
 * caller-supplied / setting-default / code-default in order.
 *
 * <p>Construction is exclusively through
 * {@link ScriptHeaderParser#parse}. The record is immutable; the
 * {@link #allowTools} / {@link #requiresTools} sets are unmodifiable
 * copies of whatever the parser collected.
 */
public record ScriptHeader(
        @Nullable Duration timeout,
        @Nullable Long statementLimit,
        @Nullable Long maxResultNodes,
        Set<String> allowTools,
        Set<String> requiresTools,
        @Nullable String description,
        @Nullable String version,
        Set<String> requires,
        @Nullable String workspaceRoot,
        Set<String> nodeBuiltins) {

    public ScriptHeader {
        allowTools = allowTools == null
                ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(allowTools));
        requiresTools = requiresTools == null
                ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(requiresTools));
        requires = requires == null
                ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(requires));
        nodeBuiltins = nodeBuiltins == null
                ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(nodeBuiltins));
    }

    private static final ScriptHeader EMPTY = new ScriptHeader(
            null, null, null, Set.of(), Set.of(), null, null,
            Set.of(), null, Set.of());

    /** No header present — every field defaults; executor uses
     *  the full Caller / Setting / Code-default fallback chain. */
    public static ScriptHeader empty() {
        return EMPTY;
    }

    /** {@code true} when the header carries at least one tag.
     *  Used by callers that want to skip the clamping branch
     *  entirely for header-less scripts. */
    public boolean isPresent() {
        return timeout != null
                || statementLimit != null
                || maxResultNodes != null
                || !allowTools.isEmpty()
                || !requiresTools.isEmpty()
                || description != null
                || version != null
                || !requires.isEmpty()
                || workspaceRoot != null
                || !nodeBuiltins.isEmpty();
    }
}
