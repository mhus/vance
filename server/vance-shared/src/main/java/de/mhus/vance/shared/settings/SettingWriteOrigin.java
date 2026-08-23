package de.mhus.vance.shared.settings;

/**
 * Who triggered a setting write — the human at a REST/UI surface, or an agent
 * through an LLM tool.
 *
 * <p>Needed because the two are <em>indistinguishable by identity</em>: an agent
 * acts with the human's own {@code SecurityContext} (a tool passes
 * {@code ctx.userId()} on), so no permission check and no
 * {@code SubjectType} tells them apart. The existing {@code actor} parameter on
 * the kit surfaces is a user id for the audit trail, not an origin. This enum is
 * therefore passed explicitly, in the same spirit as
 * {@code DocumentService.WriterIdentity} / {@code TOOL_IDENTITY} on the document
 * path.
 *
 * <p>Consumers: {@code KitService.importKit} / {@code applyTemplate} and their
 * downstream setting writes, which apply the agent-write rules W1 and W3 (no
 * overwrite of a setting an agent may not read back, deny-listed keys refused)
 * only for {@link #AGENT}. W2 — "a value that passed through the model context
 * becomes {@link de.mhus.vance.api.settings.SettingType#HIDDEN}" — was withdrawn:
 * the type follows the <em>use</em> the caller declares, not the provenance of
 * the value. See {@code planning/setting-type-hidden.md} §6.
 */
public enum SettingWriteOrigin {

    /**
     * A human at an authenticated REST/UI surface, or an internal
     * system-triggered flow (project creation, bootstrap). Declared types are
     * honoured and no agent-write restriction applies — the surface's own
     * authorization is the gate.
     */
    USER,

    /**
     * An LLM tool call. The agent chose the parameters, so the agent-write rules
     * apply.
     */
    AGENT
}
