package de.mhus.vance.brain.arthur;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Constants describing Arthur's structured-action vocabulary. Kept
 * in a dedicated class so the engine itself stays focused on
 * lifecycle + dispatch, and so future Arthur-style engines can
 * reuse / fork the schema without copy-pasting it inline.
 *
 * <h2>Action types</h2>
 *
 * <ul>
 *   <li>{@link #TYPE_ANSWER} — direct reply ready, send it.</li>
 *   <li>{@link #TYPE_ASK_USER} — need clarification from the user
 *       before doing more work.</li>
 *   <li>{@link #TYPE_DELEGATE} — spawn a worker via a recipe and
 *       (silently or with a brief pre-text) hand off the task.</li>
 *   <li>{@link #TYPE_WAIT} — async work is in flight; nothing to
 *       say right now. Engine goes IDLE and auto-wakes on the
 *       worker's ProcessEvent.</li>
 *   <li>{@link #TYPE_REJECT} — the request is out of scope or
 *       impossible; explain and stop.</li>
 * </ul>
 *
 * <p>Every action carries a non-blank {@code reason} (validated by
 * {@code StructuredActionEngine}) — both for audit and to force the
 * model to think about why it picked this branch.
 */
public final class ArthurActionSchema {

    private ArthurActionSchema() {}

    public static final String TOOL_NAME = "arthur_action";

    public static final String TOOL_DESCRIPTION =
            "Final structured action for this turn. Call this exactly once "
                    + "per turn (after any read-tool calls like recipe_list / "
                    + "manual_read you may need). The 'type' picks the branch, "
                    + "'reason' explains the choice, and per-type fields carry "
                    + "the content. Replaces the legacy free-form combination "
                    + "of process_create / process_steer / respond.";

    public static final String TYPE_ANSWER     = "ANSWER";
    public static final String TYPE_ASK_USER   = "ASK_USER";
    public static final String TYPE_DELEGATE   = "DELEGATE";
    public static final String TYPE_WAIT       = "WAIT";
    public static final String TYPE_REJECT     = "REJECT";

    public static final Set<String> SUPPORTED_TYPES = Set.of(
            TYPE_ANSWER, TYPE_ASK_USER, TYPE_DELEGATE, TYPE_WAIT, TYPE_REJECT);

    public static final String PARAM_MESSAGE = "message";
    public static final String PARAM_PRESET  = "preset";
    public static final String PARAM_PROMPT  = "prompt";

    /**
     * JSON schema (flat) covering all action types. Per-type
     * required-field validation lives in
     * {@code ArthurEngine.handleAction} where the type is known —
     * keeping the schema flat avoids the {@code anyOf} / discriminator
     * complexity that some LLMs handle poorly. The {@code type} enum
     * + {@code reason} required-flag give the model the right shape
     * out of the box; the engine layer enforces the rest.
     */
    public static Map<String, Object> schema() {
        Map<String, Object> typeProp = new LinkedHashMap<>();
        typeProp.put("type", "string");
        typeProp.put("enum", List.of(
                TYPE_ANSWER, TYPE_ASK_USER, TYPE_DELEGATE, TYPE_WAIT, TYPE_REJECT));
        typeProp.put("description",
                "Which branch this turn takes. ANSWER = direct reply. "
                        + "ASK_USER = clarification question. DELEGATE = spawn "
                        + "a worker. WAIT = async work running, no message "
                        + "needed. REJECT = out of scope, explain and stop.");

        Map<String, Object> reasonProp = new LinkedHashMap<>();
        reasonProp.put("type", "string");
        reasonProp.put("description",
                "One short sentence explaining why this action was chosen. "
                        + "Required for audit and to force deliberate decisions. "
                        + "Never empty.");

        Map<String, Object> messageProp = new LinkedHashMap<>();
        messageProp.put("type", "string");
        messageProp.put("description",
                "User-facing text. Required for ANSWER, ASK_USER, REJECT. "
                        + "Optional for DELEGATE (a brief pre-announcement) "
                        + "and WAIT (only set if you have something genuine to "
                        + "say — leave empty for silent waiting). Markdown "
                        + "allowed.");

        Map<String, Object> presetProp = new LinkedHashMap<>();
        presetProp.put("type", "string");
        presetProp.put("description",
                "Recipe name to spawn the worker from (e.g. 'web-research', "
                        + "'analyze', 'code-read'). Required for DELEGATE. "
                        + "Use recipe_list at runtime if unsure.");

        Map<String, Object> promptProp = new LinkedHashMap<>();
        promptProp.put("type", "string");
        promptProp.put("description",
                "Concrete instruction the worker should execute on spawn. "
                        + "Required for DELEGATE. Self-contained — the worker "
                        + "doesn't see the parent's chat history by default.");

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("type", typeProp);
        properties.put("reason", reasonProp);
        properties.put(PARAM_MESSAGE, messageProp);
        properties.put(PARAM_PRESET, presetProp);
        properties.put(PARAM_PROMPT, promptProp);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", properties);
        root.put("required", List.of("type", "reason"));
        return root;
    }
}
