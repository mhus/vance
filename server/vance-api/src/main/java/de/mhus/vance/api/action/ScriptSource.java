package de.mhus.vance.api.action;

/**
 * Where a {@link TriggerAction.Script} reads its code from.
 * See {@code planning/trigger-actions.md} §3.
 */
public enum ScriptSource {

    /**
     * Script source lives in the document layer, addressed via the
     * normal project → _vance → resource cascade. Path is interpreted
     * as a document path.
     */
    DOCUMENT,

    /**
     * Script source lives in a workspace RootDir of the same project.
     * The RootDir must exist (no auto-create); a missing RootDir fails
     * the trigger with {@code SKIPPED:rootdir-missing}.
     */
    WORKSPACE
}
