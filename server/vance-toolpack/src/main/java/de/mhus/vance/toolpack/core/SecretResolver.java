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
 * <p>Literal escape: a value starting with {@link #LITERAL_PREFIX} is taken
 * verbatim, prefix removed, no substitution attempted — see
 * {@link #isLiteral(String)}.
 *
 * <p>Pure Java — lives in the {@code toolpack} package so the future
 * extracted module can ship it without a Spring/Vance dependency.
 * The Spring-bound implementation lives in
 * {@code de.mhus.vance.brain.tools.SettingsSecretResolver}.
 */
public interface SecretResolver {

    /**
     * Resolver that returns its input as-is and never substitutes.
     *
     * <p>Named {@code PASSTHROUGH} rather than {@code NOOP} because
     * {@link #LITERAL_PREFIX} spells {@code {noop}} and means something else
     * entirely — one is "this resolver does nothing", the other is "this one
     * value is not a reference".
     */
    SecretResolver PASSTHROUGH = (input, ctx) -> input;

    /**
     * Marks the rest of a value as a verbatim literal: {@code {noop}sk-abc123}
     * resolves to {@code sk-abc123} with no reference lookup at all.
     *
     * <p>Spelling borrowed from Spring Security's {@code DelegatingPasswordEncoder},
     * where it means the same thing.
     *
     * <p>Whether a credential is stored as a reference or in the clear is the
     * decision of whoever configures it, not of this layer. The prefix exists
     * so that decision is <em>visible</em> in the configuration: a bare literal
     * already passes through unchanged, so {@code {noop}} is a declaration
     * first and a mechanism second. It becomes strictly necessary for a literal
     * that itself contains {@code {{…}}}. A literal that has to start with
     * {@code {noop}} is written {@code {noop}{noop}…}.
     */
    String LITERAL_PREFIX = "{noop}";

    /** True when {@code input} is an explicit literal rather than a template. */
    static boolean isLiteral(@Nullable String input) {
        return input != null && input.startsWith(LITERAL_PREFIX);
    }

    /**
     * The verbatim value behind {@link #LITERAL_PREFIX}. Only meaningful when
     * {@link #isLiteral(String)} said yes; anything else is returned unchanged.
     */
    static @Nullable String literalValue(@Nullable String input) {
        return isLiteral(input) ? input.substring(LITERAL_PREFIX.length()) : input;
    }

    /**
     * Returns {@code input} with every {@code {{secret:<key>}}} substitution
     * replaced. Unknown references resolve to the empty string (logged at warn
     * level by the implementation). Non-template input is returned unchanged.
     *
     * <p>Implement this; call {@link #resolve}. The literal escape is applied
     * by the caller-facing method rather than here, so an implementation cannot
     * forget it — the failure it prevents is a {@code {noop}} prefix travelling
     * into an {@code Authorization} header.
     *
     * @param input  raw configuration string (URL, header value, …)
     * @param ctx    invocation scope — implementations use it to walk
     *               the tenant/project/process settings cascade
     */
    @Nullable String substitute(@Nullable String input, ToolInvocationContext ctx);

    /**
     * Same substitution, but for <b>connector configuration</b> — an SMTP/IMAP
     * tool document, a REST or MCP tool pack. Those are operator-authored config,
     * not dynamic elements, so they may resolve a real secret
     * ({@code SettingType.PASSWORD}) as well.
     *
     * <p>{@link #substitute} is the restricted path and stays the default on
     * purpose: an implementation that does not distinguish the two only ever
     * ends up <em>narrower</em> here, never wider. The distinction is what
     * makes PASSWORD worth having — a credential a connector uses is unreadable
     * and unwritable for agents and scripts, yet still usable.
     */
    default @Nullable String substituteForConnector(
            @Nullable String input, ToolInvocationContext ctx) {
        return substitute(input, ctx);
    }

    /**
     * Resolve one configuration value: a {@link #LITERAL_PREFIX} value verbatim
     * without its prefix, anything else through {@link #substitute}.
     */
    default @Nullable String resolve(@Nullable String input, ToolInvocationContext ctx) {
        return isLiteral(input) ? literalValue(input) : substitute(input, ctx);
    }

    /** {@link #resolve} on the connector path — see {@link #substituteForConnector}. */
    default @Nullable String resolveForConnector(
            @Nullable String input, ToolInvocationContext ctx) {
        return isLiteral(input) ? literalValue(input) : substituteForConnector(input, ctx);
    }
}
