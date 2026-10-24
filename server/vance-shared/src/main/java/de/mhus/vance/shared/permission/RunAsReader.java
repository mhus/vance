package de.mhus.vance.shared.permission;

import org.jspecify.annotations.Nullable;

/**
 * Knows one kind of document the server <em>executes</em> under an identity —
 * schedulers, hooks, events — and can read which identity a document of that
 * kind asks to run as.
 *
 * <p>The document layer uses it to apply the rule that nobody acts as someone
 * with a stronger tenant role ({@link PermissionService#mayActAs}): writing
 * such a document is checked against the identity it will run under — its
 * {@code runAs} when the document is privileged, otherwise its creator. Each
 * implementation lives next to the parser of its format; the document layer
 * does not parse anything itself.
 */
public interface RunAsReader {

    /** Whether documents at {@code path} are executed by the server under an identity. */
    boolean executes(String path);

    /**
     * The {@code runAs} a document at {@code path} declares, or {@code null}
     * when it declares none or does not parse — a document that does not parse
     * is not executed either.
     */
    @Nullable
    String declaredRunAs(String path, String content);
}
