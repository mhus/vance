package de.mhus.vance.toolpack.core;

import de.mhus.vance.toolpack.ToolInvocationContext;
import org.jspecify.annotations.Nullable;

/**
 * Resolves {@code {{secret:<key>}}} references in tool-pack
 * configuration to plain-text values. Server-side implementation
 * reads PASSWORD-typed settings via the cascade; future client-side
 * implementations may resolve from local config or interactive
 * prompts.
 *
 * <p>Reference syntax: {@code {{secret:my.api.token}}}. Anything not
 * matching the pattern is returned unchanged ({@link #resolve}
 * accepts plain literals as a passthrough convenience).
 *
 * <p>Pure Java — lives in the {@code toolpack} package so the future
 * extracted module can ship it without a Spring/Vance dependency.
 * The Spring-bound implementation lives in
 * {@code de.mhus.vance.brain.tools.SettingsSecretResolver}.
 */
public interface SecretResolver {

    /** Empty resolver — returns input as-is, never substitutes. */
    SecretResolver NOOP = (input, ctx) -> input;

    /**
     * Returns {@code input} with every {@code {{secret:<key>}}}
     * substitution replaced. Unknown references resolve to the empty
     * string (logged at warn level by the implementation). Non-template
     * input is returned unchanged.
     *
     * @param input  raw configuration string (URL, header value, …)
     * @param ctx    invocation scope — implementations use it to walk
     *               the tenant/project/process settings cascade
     */
    @Nullable String resolve(@Nullable String input, ToolInvocationContext ctx);

    /**
     * Same substitution, but for <b>connector configuration</b> — an SMTP/IMAP
     * tool document, a REST or MCP tool pack. Those are operator-authored config,
     * not dynamic elements, so they may resolve a real secret
     * ({@code SettingType.PASSWORD}) as well.
     *
     * <p>{@link #resolve} is the restricted path and stays the default on purpose:
     * an implementation that does not distinguish the two only ever ends up
     * <em>narrower</em> here, never wider. The distinction is what makes PASSWORD
     * worth having — a credential a connector uses is unreadable and unwritable
     * for agents and scripts, yet still usable.
     */
    default @Nullable String resolveForConnector(
            @Nullable String input, ToolInvocationContext ctx) {
        return resolve(input, ctx);
    }
}
